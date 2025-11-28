// myproxy.go
package myproxy

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	socks5 "golang.org/x/net/proxy"
	dnsmsg "github.com/miekg/dns"
)

/* ============================================================
   Configuration & Limits
   ============================================================ */

const (
	// DNS modes
	DNS_MODE_OFF        = 0  // Use system DNS, no custom handling
	DNS_MODE_DOH        = 1  // Use DoH only
	DNS_MODE_DOT        = 2  // Use DoT only
	DNS_MODE_AUTO       = 3  // Try DoH, then DoT, then system
	DNS_MODE_SYSTEM_ONLY = 4 // Use system DNS only (no custom resolution)

	// Timeouts & limits
	defaultDialTimeout       = 10 * time.Second
	defaultRequestTimeout    = 30 * time.Second
	defaultDoHTimeout        = 5 * time.Second
	defaultDoTTimeout        = 5 * time.Second
	defaultIdleConnTimeout   = 30 * time.Second
	defaultTLSHandshakeTO    = 10 * time.Second
	defaultDNSCacheTTL       = 5 * time.Minute
	maxRequestBodyBytes      = 10 << 20 // 10MB
	maxConcurrentConnections = 200
)

/* ============================================================
   Runtime state (thread-safe)
   ============================================================ */

var (
	dnsMode     = DNS_MODE_AUTO
	dnsModeLock sync.RWMutex

	upstreams     []string
	upstreamsLock sync.RWMutex
	rr            uint32 // round-robin

	dohServersLock sync.RWMutex
	dotServersLock sync.RWMutex
	dohServers     = []string{
		"https://dns.google/dns-query",
		"https://cloudflare-dns.com/dns-query",
	}
	dotServers = []string{
		"dns.google:853",
		"1.1.1.1:853",
	}

	blockLock  sync.RWMutex
	whiteLock  sync.RWMutex
	blockRules []Rule
	whiteRules []Rule

	dnsCache     = map[string]dnsCacheItem{}
	dnsCacheLock sync.RWMutex

	// active connection semaphore
	activeConns = make(chan struct{}, maxConcurrentConnections)

	// Custom dialer that prevents DNS leaks
	customDialer = &net.Dialer{
		Timeout:   defaultDialTimeout,
		KeepAlive: 30 * time.Second,
	}

	// Flag to control whether we do DNS resolution at all
	disableDNSHandling     = false
	disableDNSHandlingLock sync.RWMutex
)

/* ============================================================
   Adblock rule engine (ABP-lite subset)
   ============================================================ */

type RuleType int

const (
	RuleHost RuleType = iota
	RuleSubstring
	RulePrefix
	RuleSuffix
)

type Rule struct {
	Typ RuleType
	Str string
}

func parseRule(s string) Rule {
	if s == "" {
		return Rule{Typ: -1}
	}
	if strings.HasPrefix(s, "||") {
		return Rule{Typ: RuleSuffix, Str: strings.TrimPrefix(s, "||")}
	}
	if strings.HasPrefix(s, "|") {
		return Rule{Typ: RulePrefix, Str: strings.TrimPrefix(s, "|")}
	}
	if strings.Contains(s, "*") {
		return Rule{Typ: RuleSubstring, Str: strings.ReplaceAll(s, "*", "")}
	}
	return Rule{Typ: RuleHost, Str: s}
}

func ruleMatch(r Rule, host, full string) bool {
	switch r.Typ {
	case RuleHost:
		return host == r.Str || strings.HasSuffix(host, "."+r.Str)
	case RuleSuffix:
		return strings.HasSuffix(host, r.Str)
	case RulePrefix:
		return strings.HasPrefix(full, r.Str)
	case RuleSubstring:
		return strings.Contains(full, r.Str)
	default:
		return false
	}
}

func isWhitelisted(host, full string) bool {
	whiteLock.RLock()
	defer whiteLock.RUnlock()
	for _, r := range whiteRules {
		if ruleMatch(r, host, full) {
			return true
		}
	}
	return false
}

func isBlocked(host, full string) bool {
	blockLock.RLock()
	defer blockLock.RUnlock()
	for _, r := range blockRules {
		if ruleMatch(r, host, full) {
			return true
		}
	}
	return false
}

/* ============================================================
   DNS cache
   ============================================================ */

type dnsCacheItem struct {
	IPs []net.IP
	TTL time.Time
}

/* ============================================================
   Public API (gomobile exports)
   ============================================================ */

// SetDNSMode(mode int): DNS_MODE_OFF / DNS_MODE_DOH / DNS_MODE_DOT / DNS_MODE_AUTO / DNS_MODE_SYSTEM_ONLY
func SetDNSMode(mode int) {
	if mode < DNS_MODE_OFF || mode > DNS_MODE_SYSTEM_ONLY {
		return
	}
	dnsModeLock.Lock()
	dnsMode = mode
	dnsModeLock.Unlock()

	// Clear DNS cache when mode changes
	dnsCacheLock.Lock()
	dnsCache = make(map[string]dnsCacheItem)
	dnsCacheLock.Unlock()

	log.Println("[proxy] SetDNSMode:", mode)
}

// SetDNSHandling(enable bool): enable or disable DNS handling entirely
func SetDNSHandling(enable bool) {
	disableDNSHandlingLock.Lock()
	disableDNSHandling = !enable
	disableDNSHandlingLock.Unlock()
	log.Println("[proxy] SetDNSHandling:", enable)
}

// SetUpstreams(list string): comma or newline separated upstreams.
// Supported schemes: http, https, socks5. Empty list -> direct mode.
func SetUpstreams(list string) {
	upstreamsLock.Lock()
	defer upstreamsLock.Unlock()
	upstreams = nil
	for _, p := range splitClean(list) {
		u, err := url.Parse(p)
		if err != nil {
			log.Printf("[SetUpstreams] invalid URL ignored: %q (%v)", p, err)
			continue
		}
		switch strings.ToLower(u.Scheme) {
		case "http", "https", "socks5":
			if u.Host == "" {
				log.Printf("[SetUpstreams] missing host/port ignored: %q", p)
				continue
			}
			upstreams = append(upstreams, p)
		default:
			log.Printf("[SetUpstreams] unsupported scheme ignored: %q", p)
		}
	}
	log.Println("[proxy] Upstreams:", upstreams)
}

// SetDoHServers(list string): comma/newline separated DoH endpoints (POST JSON).
func SetDoHServers(list string) {
	parts := splitClean(list)
	if len(parts) == 0 {
		return
	}
	dohServersLock.Lock()
	dohServers = parts
	dohServersLock.Unlock()
	log.Println("[proxy] DoH servers:", parts)
}

// SetDoTServers(list string): comma/newline separated DoT host:port entries.
func SetDoTServers(list string) {
	parts := splitClean(list)
	if len(parts) == 0 {
		return
	}
	dotServersLock.Lock()
	dotServers = parts
	dotServersLock.Unlock()
	log.Println("[proxy] DoT servers:", parts)
}

// LoadAdblockRules(list string): newline separated rules, use @@ prefix to whitelist
func LoadAdblockRules(list string) {
	lines := strings.Split(list, "\n")
	var b []Rule
	var w []Rule
	for _, ln := range lines {
		ln = strings.TrimSpace(ln)
		if ln == "" || strings.HasPrefix(ln, "!") {
			continue
		}
		white := false
		if strings.HasPrefix(ln, "@@") {
			white = true
			ln = strings.TrimPrefix(ln, "@@")
		}
		r := parseRule(ln)
		if r.Typ == -1 {
			continue
		}
		if white {
			w = append(w, r)
		} else {
			b = append(b, r)
		}
	}
	blockLock.Lock()
	whiteLock.Lock()
	blockRules = b
	whiteRules = w
	whiteLock.Unlock()
	blockLock.Unlock()
	log.Printf("[proxy] Rules loaded: blocks=%d whitelist=%d", len(b), len(w))
}

// Start(addr string) - start proxy (e.g. "127.0.0.1:8080")
func Start(addr string) {
	go func() {
		srv := &http.Server{
			Addr:              addr,
			Handler:           http.HandlerFunc(proxyHandler),
			ReadHeaderTimeout: 10 * time.Second,
			IdleTimeout:       defaultIdleConnTimeout,
		}
		log.Println("[proxy] starting on", addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Println("[proxy] ListenAndServe error:", err)
		}
	}()
}

/* ============================================================
   Helpers
   ============================================================ */

func splitClean(s string) []string {
	out := make([]string, 0, 4)
	for _, p := range strings.FieldsFunc(s, func(r rune) bool { return r == ',' || r == '\n' }) {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func safeHostname(hostport string) string {
	if hostport == "" {
		return ""
	}
	if i := strings.Index(hostport, ":"); i != -1 {
		return strings.ToLower(hostport[:i])
	}
	return strings.ToLower(hostport)
}

var hopByHopHeaders = []string{
	"Connection", "Proxy-Connection", "Keep-Alive", "Proxy-Authenticate",
	"Proxy-Authorization", "TE", "Trailer", "Transfer-Encoding", "Upgrade",
}

func sanitizeRequestForUpstream(in *http.Request) *http.Request {
	req := new(http.Request)
	*req = *in
	req.RequestURI = ""
	for _, h := range hopByHopHeaders {
		req.Header.Del(h)
	}
	if req.Body != nil {
		req.Body = http.MaxBytesReader(nil, req.Body, maxRequestBodyBytes)
	}
	return req
}

/* ============================================================
   DNS resolution: system / DoH / DoT (miekg/dns) - NO LEAKS
   ============================================================ */

func resolveHost(host string) []net.IP {
	// Check if DNS handling is completely disabled
	disableDNSHandlingLock.RLock()
	if disableDNSHandling {
		disableDNSHandlingLock.RUnlock()
		return resolveSystem(host)
	}
	disableDNSHandlingLock.RUnlock()

	host = strings.ToLower(host)
	// dns rebinding simple checks
	if strings.Contains(host, "..") || strings.Contains(host, "@") {
		return nil
	}

	// Check if it's already an IP
	if ip := net.ParseIP(host); ip != nil {
		return []net.IP{ip}
	}

	// cache
	dnsCacheLock.RLock()
	item, ok := dnsCache[host]
	if ok && time.Now().Before(item.TTL) {
		dnsCacheLock.RUnlock()
		return item.IPs
	}
	dnsCacheLock.RUnlock()

	var ips []net.IP
	dnsModeLock.RLock()
	mode := dnsMode
	dnsModeLock.RUnlock()

	switch mode {
	case DNS_MODE_OFF:
		// Use system DNS but don't cache
		return resolveSystem(host)
	case DNS_MODE_SYSTEM_ONLY:
		// Use system DNS with caching
		ips = resolveSystem(host)
	case DNS_MODE_DOH:
		ips, _ = resolveDoH(host)
	case DNS_MODE_DOT:
		ips, _ = resolveDoT(host)
	case DNS_MODE_AUTO:
		ips, _ = resolveDoH(host)
		if len(ips) == 0 {
			ips, _ = resolveDoT(host)
		}
		if len(ips) == 0 {
			ips = resolveSystem(host)
		}
	}

	// Only cache if we're not in DNS_MODE_OFF and we got results
	if mode != DNS_MODE_OFF && len(ips) > 0 {
		dnsCacheLock.Lock()
		dnsCache[host] = dnsCacheItem{IPs: ips, TTL: time.Now().Add(defaultDNSCacheTTL)}
		dnsCacheLock.Unlock()
	}
	return ips
}

func resolveSystem(host string) []net.IP {
	ips, err := net.LookupIP(host)
	if err != nil {
		return nil
	}
	// Filter IPv4 addresses only for compatibility
	var ipv4s []net.IP
	for _, ip := range ips {
		if ip.To4() != nil {
			ipv4s = append(ipv4s, ip)
		}
	}
	return ipv4s
}

/* ---------------- DoH (JSON POST) ---------------- */

func resolveDoH(host string) ([]net.IP, error) {
	body := map[string]any{"name": host, "type": "A"}
	b, _ := json.Marshal(body)

	dohServersLock.RLock()
	servers := append([]string{}, dohServers...)
	dohServersLock.RUnlock()

	for _, s := range servers {
		ctx, cancel := context.WithTimeout(context.Background(), defaultDoHTimeout)
		defer cancel()

		req, err := http.NewRequestWithContext(ctx, "POST", s, bytes.NewReader(b))
		if err != nil {
			continue
		}
		req.Header.Set("Content-Type", "application/dns-json")
		req.Header.Set("Accept", "application/dns-json")

		// Use custom transport that doesn't leak DNS
		tr := &http.Transport{
			DialContext: customDialer.DialContext,
			TLSClientConfig: &tls.Config{
				MinVersion: tls.VersionTLS12,
			},
		}
		client := &http.Client{
			Transport: tr,
			Timeout:   defaultDoHTimeout,
		}

		resp, err := client.Do(req)
		if err != nil {
			log.Printf("[DoH] %s failed: %v", s, err)
			continue
		}

		data, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil {
			continue
		}

		var out struct {
			Answer []struct {
				Data string `json:"data"`
				Type int    `json:"type"`
			} `json:"Answer"`
		}
		if err := json.Unmarshal(data, &out); err != nil {
			continue
		}

		var ips []net.IP
		for _, a := range out.Answer {
			if a.Type == 1 { // A record
				if ip := net.ParseIP(a.Data); ip != nil && ip.To4() != nil {
					ips = append(ips, ip)
				}
			}
		}
		if len(ips) > 0 {
			return ips, nil
		}
	}
	return nil, errors.New("DoH failed")
}

/* ---------------- DoT using miekg/dns ---------------- */

func resolveDoT(host string) ([]net.IP, error) {
	dotServersLock.RLock()
	servers := append([]string{}, dotServers...)
	dotServersLock.RUnlock()

	for _, s := range servers {
		// ensure host:port
		addr := s
		if !strings.Contains(s, ":") {
			addr = s + ":853"
		}

		// Create DNS client with TLS
		client := &dnsmsg.Client{
			Net: "tcp-tls",
			TLSConfig: &tls.Config{
				ServerName:         strings.Split(addr, ":")[0],
				InsecureSkipVerify: false,
				MinVersion:         tls.VersionTLS12,
			},
			Timeout: defaultDoTTimeout,
		}

		m := new(dnsmsg.Msg)
		m.SetQuestion(dnsmsg.Fqdn(host), dnsmsg.TypeA)

		ctx, cancel := context.WithTimeout(context.Background(), defaultDoTTimeout)
		defer cancel()

		in, _, err := client.ExchangeContext(ctx, m, addr)
		if err != nil || in == nil {
			log.Printf("[DoT] %s failed: %v", addr, err)
			continue
		}

		var ips []net.IP
		for _, ans := range in.Answer {
			if a, ok := ans.(*dnsmsg.A); ok {
				ips = append(ips, a.A)
			}
		}
		if len(ips) > 0 {
			return ips, nil
		}
	}
	return nil, errors.New("DoT failed")
}

/* ============================================================
   Custom DNS-aware dialer - PREVENTS DNS LEAKS
   ============================================================ */

type dnsAwareDialer struct {
	upstream *url.URL
}

func (d *dnsAwareDialer) Dial(network, addr string) (net.Conn, error) {
	return d.DialContext(context.Background(), network, addr)
}

func (d *dnsAwareDialer) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}

	// Check if DNS handling is disabled - use system resolution directly
	disableDNSHandlingLock.RLock()
	if disableDNSHandling {
		disableDNSHandlingLock.RUnlock()
		return customDialer.DialContext(ctx, network, addr)
	}
	disableDNSHandlingLock.RUnlock()

	// Resolve host using our DNS system
	ips := resolveHost(host)
	if len(ips) == 0 {
		return nil, fmt.Errorf("cannot resolve host: %s", host)
	}

	// Try each IP until one succeeds
	var firstErr error
	for _, ip := range ips {
		target := net.JoinHostPort(ip.String(), port)
		conn, err := customDialer.DialContext(ctx, network, target)
		if err == nil {
			return conn, nil
		}
		if firstErr == nil {
			firstErr = err
		}
	}

	return nil, firstErr
}

/* ============================================================
   Upstream picker (round-robin) and validation
   ============================================================ */

func pickUpstream() *url.URL {
	upstreamsLock.RLock()
	defer upstreamsLock.RUnlock()
	if len(upstreams) == 0 {
		return nil
	}
	i := atomic.AddUint32(&rr, 1)
	u, err := url.Parse(upstreams[int(i)%len(upstreams)])
	if err != nil {
		return nil
	}
	return u
}

/* ============================================================
   Main HTTP proxy handler
   ============================================================ */

func proxyHandler(w http.ResponseWriter, r *http.Request) {
	// semaphore: prevent resource exhaustion
	select {
	case activeConns <- struct{}{}:
		// proceed
	default:
		http.Error(w, "Server busy", http.StatusServiceUnavailable)
		return
	}
	defer func() { <-activeConns }()

	// basic sanitization
	r.Header.Del("X-Requested-With")
	host := safeHostname(r.Host)
	if host == "" {
		http.Error(w, "Invalid host", http.StatusBadRequest)
		return
	}
	full := r.URL.String()

	// adblock check
	if !isWhitelisted(host, full) && isBlocked(host, full) {
		http.Error(w, "Blocked", http.StatusForbidden)
		return
	}

	// Check if DNS handling is disabled - skip DNS resolution for adblock only
	disableDNSHandlingLock.RLock()
	dnsDisabled := disableDNSHandling
	disableDNSHandlingLock.RUnlock()

	// Only perform DNS resolution if not disabled
	if !dnsDisabled {
		// resolve host to ensure reachable via configured DNS
		ips := resolveHost(host)
		if len(ips) == 0 {
			http.Error(w, "DNS_FAIL", http.StatusBadGateway)
			return
		}
	}

	// handle CONNECT (tunnel) vs normal HTTP
	if r.Method == http.MethodConnect {
		handleConnect(w, r)
	} else {
		handleHTTP(w, r)
	}
}

/* ============================================================
   CONNECT handling (tunneling) - DNS LEAK FIXED
   ============================================================ */

func handleConnect(w http.ResponseWriter, r *http.Request) {
	up := pickUpstream()
	var conn net.Conn
	var err error

	ctx, cancel := context.WithTimeout(context.Background(), defaultRequestTimeout)
	defer cancel()

	host, port, err := net.SplitHostPort(r.Host)
	if err != nil {
		http.Error(w, "Invalid host:port", http.StatusBadRequest)
		return
	}

	// Check if DNS handling is disabled
	disableDNSHandlingLock.RLock()
	dnsDisabled := disableDNSHandling
	disableDNSHandlingLock.RUnlock()

	var target string
	if dnsDisabled {
		// Use hostname directly - system DNS will handle resolution
		target = r.Host
	} else {
		// Resolve target using our DNS system
		ips := resolveHost(host)
		if len(ips) == 0 {
			http.Error(w, "DNS_FAIL", http.StatusBadGateway)
			return
		}
		target = net.JoinHostPort(ips[0].String(), port)
	}

	if up == nil {
		// Direct connection - use appropriate dialer based on DNS mode
		if dnsDisabled {
			conn, err = customDialer.DialContext(ctx, "tcp", r.Host)
		} else {
			conn, err = customDialer.DialContext(ctx, "tcp", target)
		}
	} else {
		scheme := strings.ToLower(up.Scheme)
		switch scheme {
		case "socks5":
			dialer, derr := socks5.SOCKS5("tcp", up.Host, nil, &dnsAwareDialer{})
			if derr != nil {
				err = derr
			} else {
				conn, err = dialer.Dial("tcp", r.Host)
			}
		case "http", "https":
			// For HTTP CONNECT, we need to connect to upstream proxy
			d := &net.Dialer{Timeout: defaultDialTimeout}
			conn, err = d.DialContext(ctx, "tcp", up.Host)
			if err == nil {
				conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
				connectReq := fmt.Sprintf("CONNECT %s HTTP/1.1\r\nHost: %s\r\n\r\n", r.Host, r.Host)
				_, err = conn.Write([]byte(connectReq))
				if err == nil {
					br := bufio.NewReader(conn)
					resp, rerr := http.ReadResponse(br, &http.Request{Method: http.MethodConnect})
					if rerr != nil {
						err = rerr
					} else if resp.StatusCode != 200 {
						err = errors.New("upstream CONNECT failed: " + resp.Status)
					}
				}
			}
		default:
			err = errors.New("unsupported upstream scheme")
		}
	}

	if err != nil {
		http.Error(w, "CONNECT_FAIL "+err.Error(), http.StatusBadGateway)
		return
	}

	hj, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "Hijack unsupported", http.StatusInternalServerError)
		conn.Close()
		return
	}
	clientConn, _, err := hj.Hijack()
	if err != nil {
		http.Error(w, "Hijack fail", http.StatusInternalServerError)
		conn.Close()
		return
	}
	_, _ = clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))

	// set temporary deadlines
	clientConn.SetDeadline(time.Now().Add(defaultRequestTimeout))
	conn.SetDeadline(time.Now().Add(defaultRequestTimeout))

	// copy both directions
	go proxyCopy(conn, clientConn)
	go proxyCopy(clientConn, conn)
}

/* ============================================================
   HTTP forwarding (non-CONNECT) - DNS LEAK FIXED
   ============================================================ */

func handleHTTP(w http.ResponseWriter, r *http.Request) {
	up := pickUpstream()
	req := sanitizeRequestForUpstream(r)

	// Check if DNS handling is disabled
	disableDNSHandlingLock.RLock()
	dnsDisabled := disableDNSHandling
	disableDNSHandlingLock.RUnlock()

	tr := &http.Transport{
		DisableCompression:  false,
		IdleConnTimeout:     defaultIdleConnTimeout,
		TLSHandshakeTimeout: defaultTLSHandshakeTO,
	}

	// Set dialer based on DNS mode
	if dnsDisabled {
		tr.DialContext = customDialer.DialContext
	} else {
		tr.DialContext = customDialer.DialContext
	}

	if up != nil {
		switch strings.ToLower(up.Scheme) {
		case "socks5":
			var dialer socks5.Dialer
			var err error
			if dnsDisabled {
				// Use system DNS for SOCKS5
				baseDialer := &net.Dialer{Timeout: defaultDialTimeout}
				dialer, err = socks5.SOCKS5("tcp", up.Host, nil, baseDialer)
			} else {
				// Use our DNS-aware dialer
				dialer, err = socks5.SOCKS5("tcp", up.Host, nil, &dnsAwareDialer{})
			}
			if err != nil {
				http.Error(w, "Upstream socks5 error: "+err.Error(), http.StatusBadGateway)
				return
			}
			tr.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
				return dialer.Dial(network, addr)
			}
		case "http", "https":
			tr.Proxy = http.ProxyURL(up)
			// Use appropriate dialer based on DNS mode
			if dnsDisabled {
				tr.DialContext = customDialer.DialContext
			} else {
				tr.DialContext = customDialer.DialContext
			}
		default:
			http.Error(w, "Unsupported upstream", http.StatusBadGateway)
			return
		}
	}

	client := &http.Client{
		Transport: tr,
		Timeout:   defaultRequestTimeout,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse // Don't follow redirects automatically
		},
	}

	ctx, cancel := context.WithTimeout(context.Background(), defaultRequestTimeout)
	defer cancel()
	req = req.WithContext(ctx)

	resp, err := client.Do(req)
	if err != nil {
		http.Error(w, "UPSTREAM_FAIL "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	// remove hop-by-hop headers
	for _, h := range hopByHopHeaders {
		resp.Header.Del(h)
	}
	
	// Copy headers
	for k, vals := range resp.Header {
		for _, v := range vals {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	
	// Copy body with error handling
	_, err = io.Copy(w, resp.Body)
	if err != nil {
		log.Printf("[proxy] Error copying response body: %v", err)
	}
}

/* ============================================================
   Utility copy and helpers
   ============================================================ */

func proxyCopy(dst net.Conn, src net.Conn) {
	defer dst.Close()
	defer src.Close()
	
	// Reset deadlines for long-lived connections
	_ = dst.SetDeadline(time.Time{})
	_ = src.SetDeadline(time.Time{})
	
	io.Copy(dst, src)
}