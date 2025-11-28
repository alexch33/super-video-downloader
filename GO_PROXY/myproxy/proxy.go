package myproxy

import (
	"bufio"
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
	"github.com/miekg/dns"
)

/* ============================================================
   Configuration & Limits
   ============================================================ */

const (
	DNS_MODE_OFF         = 0
	DNS_MODE_SYSTEM_ONLY = 1
	DNS_MODE_PROXY_ONLY  = 2
	DNS_MODE_ALWAYS      = 3

	DNS_TRANSPORT_SYSTEM = "system"
	DNS_TRANSPORT_DOH    = "doh"
	DNS_TRANSPORT_DOT    = "dot"

	defaultDialTimeout     = 10 * time.Second
	defaultRequestTimeout  = 30 * time.Second
	defaultDoHTimeout      = 5 * time.Second
	defaultDoTTimeout      = 5 * time.Second
	defaultIdleConnTimeout = 30 * time.Second
	defaultTLSHandshakeTO  = 10 * time.Second
	defaultDNSCacheTTL     = 5 * time.Minute
	maxRequestBodyBytes    = 10 << 20
	maxConcurrentConns     = 200
)

var (
	dnsModeLock   sync.RWMutex
	dnsMode       = DNS_MODE_PROXY_ONLY
	dnsTransportLock sync.RWMutex
	dnsTransport     = DNS_TRANSPORT_DOH

	upstreams     []string
	upstreamsLock sync.RWMutex
	rr            uint32

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

	activeConns = make(chan struct{}, maxConcurrentConns)

	customDialer = &net.Dialer{
		Timeout:   defaultDialTimeout,
		KeepAlive: 30 * time.Second,
	}

	dnsHTTPClient     *http.Client
	dnsHTTPClientLock sync.RWMutex
)

/* ============================================================
   Adblock rules
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
   Public API
   ============================================================ */

func SetDNSMode(mode int) {
	if mode < DNS_MODE_OFF || mode > DNS_MODE_ALWAYS {
		return
	}
	dnsModeLock.Lock()
	dnsMode = mode
	dnsModeLock.Unlock()

	clearDNSCacheAndClient()
	log.Println("[proxy] SetDNSMode:", mode)
}

func SetDNSTransport(transport string) {
	if transport != DNS_TRANSPORT_SYSTEM && transport != DNS_TRANSPORT_DOH && transport != DNS_TRANSPORT_DOT {
		return
	}
	dnsTransportLock.Lock()
	dnsTransport = transport
	dnsTransportLock.Unlock()

	clearDNSCacheAndClient()
	log.Println("[proxy] SetDNSTransport:", transport)
}

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
	clearDNSCacheAndClient()
	log.Println("[proxy] Upstreams:", upstreams)
}

func SetDoHServers(list string) {
	parts := splitClean(list)
	if len(parts) == 0 {
		return
	}
	dohServersLock.Lock()
	dohServers = parts
	dohServersLock.Unlock()
	clearDNSCacheAndClient()
	log.Println("[proxy] DoH servers:", parts)
}

func SetDoTServers(list string) {
	parts := splitClean(list)
	if len(parts) == 0 {
		return
	}
	dotServersLock.Lock()
	dotServers = parts
	dotServersLock.Unlock()
	clearDNSCacheAndClient()
	log.Println("[proxy] DoT servers:", parts)
}

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

/* ============================================================
   Start server
   ============================================================ */

func Start(addr string) {
    go func() {
        srv := &http.Server{
            Addr:    addr,
            Handler: http.HandlerFunc(proxyHandler),
        }
        _ = srv.ListenAndServe()
    }()
}

/* ============================================================
   Helpers
   ============================================================ */

func clearDNSCacheAndClient() {
	dnsCacheLock.Lock()
	dnsCache = make(map[string]dnsCacheItem)
	dnsCacheLock.Unlock()

	dnsHTTPClientLock.Lock()
	dnsHTTPClient = nil
	dnsHTTPClientLock.Unlock()
}

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
   DNS resolution
   ============================================================ */

func getDNSHTTPClient() *http.Client {
	dnsHTTPClientLock.RLock()
	if dnsHTTPClient != nil {
		defer dnsHTTPClientLock.RUnlock()
		return dnsHTTPClient
	}
	dnsHTTPClientLock.RUnlock()

	dnsHTTPClientLock.Lock()
	defer dnsHTTPClientLock.Unlock()
	if dnsHTTPClient != nil {
		return dnsHTTPClient
	}

	tr := &http.Transport{
		DialContext:         customDialer.DialContext,
		TLSHandshakeTimeout: defaultTLSHandshakeTO,
		IdleConnTimeout:     defaultIdleConnTimeout,
	}

	upstreamsLock.RLock()
	hasUp := len(upstreams) > 0
	upstreamsLock.RUnlock()
	if hasUp {
		up := pickUpstream()
		if up != nil {
			switch strings.ToLower(up.Scheme) {
			case "http", "https":
				tr.Proxy = http.ProxyURL(up)
				log.Printf("[DNS] Configured HTTP proxy for DNS queries: %s", up.Host)
			case "socks5":
				dialer, err := socks5.SOCKS5("tcp", up.Host, nil, customDialer)
				if err == nil {
					tr.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
						return dialer.Dial(network, addr)
					}
					log.Printf("[DNS] Configured SOCKS5 proxy for DNS queries: %s", up.Host)
				}
			}
		}
	}

	dnsHTTPClient = &http.Client{
		Transport: tr,
		Timeout:   defaultDoHTimeout,
	}
	return dnsHTTPClient
}

func resolveHost(host string) []net.IP {
	host = strings.ToLower(host)
	if strings.Contains(host, "..") || strings.Contains(host, "@") {
		return nil
	}
	if ip := net.ParseIP(host); ip != nil {
		return []net.IP{ip}
	}

	dnsCacheLock.RLock()
	if item, ok := dnsCache[host]; ok && time.Now().Before(item.TTL) {
		dnsCacheLock.RUnlock()
		return item.IPs
	}
	dnsCacheLock.RUnlock()

	var ips []net.IP

	dnsModeLock.RLock()
	mode := dnsMode
	dnsModeLock.RUnlock()

	dnsTransportLock.RLock()
	transport := dnsTransport
	dnsTransportLock.RUnlock()

	switch mode {
	case DNS_MODE_OFF:
		return nil
	case DNS_MODE_SYSTEM_ONLY:
		ips = resolveSystem(host)
	case DNS_MODE_PROXY_ONLY:
		upstreamsLock.RLock()
		hasUp := len(upstreams) > 0
		upstreamsLock.RUnlock()
		if hasUp {
			ips, _ = resolveWithConfig(host, transport, true)
			if len(ips) == 0 {
				ips = resolveSystem(host)
			}
		} else {
			ips = resolveSystem(host)
		}
	case DNS_MODE_ALWAYS:
		ips, _ = resolveWithConfig(host, transport, true)
		if len(ips) == 0 {
			ips, _ = resolveWithConfig(host, transport, false)
		}
		if len(ips) == 0 {
			ips = resolveSystem(host)
		}
	default:
		ips = resolveSystem(host)
	}

	if len(ips) > 0 {
		dnsCacheLock.Lock()
		dnsCache[host] = dnsCacheItem{IPs: ips, TTL: time.Now().Add(defaultDNSCacheTTL)}
		dnsCacheLock.Unlock()
	}
	return ips
}

func resolveWithConfig(host, transport string, proxyAware bool) ([]net.IP, error) {
	if transport == DNS_TRANSPORT_SYSTEM {
		return resolveSystem(host), nil
	} else if transport == DNS_TRANSPORT_DOH {
		return resolveDoH(host, proxyAware)
	} else if transport == DNS_TRANSPORT_DOT {
		return resolveDoT(host, proxyAware)
	}
	return nil, fmt.Errorf("unsupported dnsTransport: %s", transport)
}

func resolveSystem(host string) []net.IP {
	ips, err := net.LookupIP(host)
	if err != nil {
		log.Printf("[DNS] System lookup failed for %s: %v", host, err)
		return nil
	}
	var ipv4s []net.IP
	for _, ip := range ips {
		if ip.To4() != nil {
			ipv4s = append(ipv4s, ip)
		}
	}
	log.Printf("[DNS] System resolution for %s: %d IPs", host, len(ipv4s))
	return ipv4s
}

func resolveDoH(host string, proxyAware bool) ([]net.IP, error) {
	body := map[string]any{"name": host, "type": "A"}
	b, _ := json.Marshal(body)

	dohServersLock.RLock()
	servers := append([]string{}, dohServers...)
	dohServersLock.RUnlock()

	client := getDNSHTTPClient()
	for _, s := range servers {
		ctx, cancel := context.WithTimeout(context.Background(), defaultDoHTimeout)
		defer cancel()
		req, err := http.NewRequestWithContext(ctx, "POST", s, strings.NewReader(string(b)))
		if err != nil {
			continue
		}
		req.Header.Set("Content-Type", "application/dns-json")
		req.Header.Set("Accept", "application/dns-json")

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
			if a.Type == 1 {
				if ip := net.ParseIP(a.Data); ip != nil && ip.To4() != nil {
					ips = append(ips, ip)
				}
			}
		}
		if len(ips) > 0 {
			log.Printf("[DoH] Resolved %s via %s: %d IPs", host, s, len(ips))
			return ips, nil
		}
	}
	return nil, errors.New("DoH failed")
}

func resolveDoT(host string, proxyAware bool) ([]net.IP, error) {
	dotServersLock.RLock()
	servers := append([]string{}, dotServers...)
	dotServersLock.RUnlock()

	for _, s := range servers {
		addr := s
		if !strings.Contains(s, ":") {
			addr = net.JoinHostPort(s, "853")
		}
		m := new(dns.Msg)
		m.SetQuestion(dns.Fqdn(host), dns.TypeA)
		m.RecursionDesired = true

		client := dns.Client{
			Net:     "tcp-tls",
			Timeout: defaultDoTTimeout,
			TLSConfig: &tls.Config{
				InsecureSkipVerify: false,
			},
		}

		resp, _, err := client.Exchange(m, addr)
		if err != nil {
			log.Printf("[DoT] %s failed: %v", addr, err)
			continue
		}
		if resp.Rcode != dns.RcodeSuccess {
			log.Printf("[DoT] %s: invalid response code %d", addr, resp.Rcode)
			continue
		}
		var ips []net.IP
		for _, ans := range resp.Answer {
			if a, ok := ans.(*dns.A); ok {
				ips = append(ips, a.A)
			}
		}
		if len(ips) > 0 {
			log.Printf("[DoT] Resolved %s via %s: %d IPs", host, addr, len(ips))
			return ips, nil
		}
	}
	return nil, errors.New("DoT failed")
}

/* ============================================================
   DNS-aware Dialer
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

	up := pickUpstream()
	var target string
	if up != nil {
		target = addr
	} else {
		ips := resolveHost(host)
		if len(ips) == 0 {
			return nil, fmt.Errorf("cannot resolve host: %s", host)
		}
		target = net.JoinHostPort(ips[0].String(), port)
	}

	return customDialer.DialContext(ctx, network, target)
}

/* ============================================================
   HTTP(S) proxy handler
   ============================================================ */

func proxyHandler(w http.ResponseWriter, r *http.Request) {
	select {
	case activeConns <- struct{}{}:
	default:
		http.Error(w, "Server busy", http.StatusServiceUnavailable)
		return
	}
	defer func() { <-activeConns }()

	host := safeHostname(r.Host)
	if host == "" {
		http.Error(w, "Invalid host", http.StatusBadRequest)
		return
	}
	fullURL := r.URL.String()

	if !isWhitelisted(host, fullURL) && isBlocked(host, fullURL) {
		http.Error(w, "Blocked", http.StatusForbidden)
		return
	}

	if r.Method == http.MethodConnect {
		handleConnect(w, r)
	} else {
		handleHTTP(w, r)
	}
}

func handleConnect(w http.ResponseWriter, r *http.Request) {
	up := pickUpstream()
	host, port, err := net.SplitHostPort(r.Host)
	if err != nil {
		http.Error(w, "Invalid host:port", http.StatusBadRequest)
		return
	}

	var target string
	if up != nil {
		target = r.Host
	} else {
		ips := resolveHost(host)
		if len(ips) == 0 {
			http.Error(w, "DNS resolution failed", http.StatusBadGateway)
			return
		}
		target = net.JoinHostPort(ips[0].String(), port)
	}

	var conn net.Conn
	ctx, cancel := context.WithTimeout(context.Background(), defaultRequestTimeout)
	defer cancel()

	if up == nil {
		conn, err = customDialer.DialContext(ctx, "tcp", target)
	} else {
		switch strings.ToLower(up.Scheme) {
		case "socks5":
			dialer, derr := socks5.SOCKS5("tcp", up.Host, nil, &dnsAwareDialer{})
			if derr != nil {
				err = derr
			} else {
				conn, err = dialer.Dial("tcp", r.Host)
			}
		case "http", "https":
            conn, err = dialViaHTTPProxy(up, r.Host, defaultRequestTimeout)
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

	clientConn.SetDeadline(time.Now().Add(defaultRequestTimeout))
	conn.SetDeadline(time.Now().Add(defaultRequestTimeout))

	go proxyCopy(conn, clientConn)
	go proxyCopy(clientConn, conn)
}

// dialViaHTTPProxy establishes a TCP connection via an HTTP/HTTPS upstream proxy using CONNECT
func dialViaHTTPProxy(upstream *url.URL, target string, timeout time.Duration) (net.Conn, error) {
	d := &net.Dialer{Timeout: timeout}
	conn, err := d.Dial("tcp", upstream.Host)
	if err != nil {
		return nil, err
	}

	// Send CONNECT request
	req := fmt.Sprintf("CONNECT %s HTTP/1.1\r\nHost: %s\r\n\r\n", target, target)
	_, err = conn.Write([]byte(req))
	if err != nil {
		conn.Close()
		return nil, err
	}

	// Read response
	br := bufio.NewReader(conn)
	resp, err := http.ReadResponse(br, &http.Request{Method: http.MethodConnect})
	if err != nil {
		conn.Close()
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		conn.Close()
		return nil, fmt.Errorf("HTTP CONNECT failed: %s", resp.Status)
	}

	return conn, nil
}

func handleHTTP(w http.ResponseWriter, r *http.Request) {
	up := pickUpstream()
	req := sanitizeRequestForUpstream(r)

	tr := &http.Transport{
		DisableCompression:  false,
		IdleConnTimeout:     defaultIdleConnTimeout,
		TLSHandshakeTimeout: defaultTLSHandshakeTO,
		DialContext:         (&dnsAwareDialer{}).DialContext,
	}

	if up != nil {
		switch strings.ToLower(up.Scheme) {
		case "socks5":
			dialer, derr := socks5.SOCKS5("tcp", up.Host, nil, &dnsAwareDialer{})
			if derr != nil {
				http.Error(w, "Upstream socks5 error", http.StatusBadGateway)
				return
			}
			tr.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
				return dialer.Dial(network, addr)
			}
		case "http", "https":
			tr.Proxy = http.ProxyURL(up)
		default:
			http.Error(w, "Unsupported upstream", http.StatusBadGateway)
			return
		}
	}

	client := &http.Client{
		Transport: tr,
		Timeout:   defaultRequestTimeout,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
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

	for _, h := range hopByHopHeaders {
		resp.Header.Del(h)
	}
	for k, vals := range resp.Header {
		for _, v := range vals {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
}

func proxyCopy(dst, src net.Conn) {
	defer dst.Close()
	defer src.Close()
	_ = dst.SetDeadline(time.Time{})
	_ = src.SetDeadline(time.Time{})
	io.Copy(dst, src)
}

