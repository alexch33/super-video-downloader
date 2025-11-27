package myproxy

import (
	"bufio"
	"bytes"
	"crypto/tls"
	"encoding/json"
	"errors"
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
)

/* ============================================================
   GLOBAL SETTINGS
   ============================================================ */

const (
	DNS_MODE_OFF  = 0
	DNS_MODE_DOH  = 1
	DNS_MODE_DOT  = 2
	DNS_MODE_AUTO = 3
)

var dnsMode = DNS_MODE_AUTO

/* -------- Upstreams ---------- */
var (
	upstreams     []string
	upstreamsLock sync.RWMutex
	rr            uint32
)

/* -------- Adblock engine ---------- */
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

var blockRules []Rule
var whiteRules []Rule
var blockLock sync.RWMutex
var whiteLock sync.RWMutex

/* -------- DNS cache ---------- */
type dnsCacheItem struct {
	IPs []string
	TTL time.Time
}

var dnsCache = map[string]dnsCacheItem{}
var dnsCacheLock sync.RWMutex

/* -------- DoH & DoT servers (mutable) ---------- */
var dohServers = []string{
	"https://dns.google/dns-query",
	"https://cloudflare-dns.com/dns-query",
}

var dotServers = []string{
	"dns.google:853",
	"1.1.1.1:853",
}

/* ============================================================
   PUBLIC API FOR KOTLIN / GOMOBILE
   ============================================================ */

func SetDNSMode(mode int) {
	if mode >= DNS_MODE_OFF && mode <= DNS_MODE_AUTO {
		dnsMode = mode
		log.Println("[proxy] DNS mode=", mode)
	}
}

func SetUpstreams(list string) {
	upstreamsLock.Lock()
	defer upstreamsLock.Unlock()

	upstreams = nil
	for _, p := range splitClean(list) {
		if _, err := url.Parse(p); err == nil {
			upstreams = append(upstreams, p)
		}
	}
	log.Println("[proxy] upstreams =", upstreams)
}

func SetDoHServers(list string) {
	if s := splitClean(list); len(s) > 0 {
		dohServers = s
		log.Println("[proxy] DoH servers =", dohServers)
	}
}

func SetDoTServers(list string) {
	if s := splitClean(list); len(s) > 0 {
		dotServers = s
		log.Println("[proxy] DoT servers =", dotServers)
	}
}

func LoadAdblockRules(list string) {
	lines := strings.Split(list, "\n")
	b := []Rule{}
	w := []Rule{}

	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "!") {
			continue
		}
		isWhite := false
		if strings.HasPrefix(line, "@@") {
			isWhite = true
			line = strings.TrimPrefix(line, "@@")
		}

		r := parseRule(line)
		if r.Typ == -1 {
			continue
		}
		if isWhite {
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

	log.Printf("[proxy] rules=%d whitelist=%d", len(b), len(w))
}

func Start(addr string) {
	go func() {
		srv := &http.Server{
			Addr:              addr,
			Handler:           http.HandlerFunc(proxyHandler),
			ReadHeaderTimeout: 10 * time.Second,
		}
		log.Println("[proxy] listening on", addr)
		log.Println(srv.ListenAndServe())
	}()
}

/* ============================================================
   HELPERS
   ============================================================ */
func splitClean(s string) []string {
	out := []string{}
	for _, p := range strings.FieldsFunc(s, func(r rune) bool { return r == ',' || r == '\n' }) {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func parseRule(s string) Rule {
	if strings.HasPrefix(s, "||") {
		return Rule{Typ: RuleSuffix, Str: strings.TrimPrefix(s, "||")}
	}
	if strings.HasPrefix(s, "|") {
		return Rule{Typ: RulePrefix, Str: strings.TrimPrefix(s, "|")}
	}
	if strings.Contains(s, "*") {
		return Rule{Typ: RuleSubstring, Str: strings.ReplaceAll(s, "*", "")}
	}
	if s != "" {
		return Rule{Typ: RuleHost, Str: s}
	}
	return Rule{Typ: -1}
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
	}
	return false
}

/* ============================================================
   DNS RESOLUTION
   ============================================================ */
func resolveHost(host string) []string {
	host = strings.ToLower(host)

	dnsCacheLock.RLock()
	item, ok := dnsCache[host]
	if ok && time.Now().Before(item.TTL) {
		dnsCacheLock.RUnlock()
		return item.IPs
	}
	dnsCacheLock.RUnlock()

	var ips []string
	switch dnsMode {
	case DNS_MODE_OFF:
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

	if len(ips) > 0 {
		dnsCacheLock.Lock()
		dnsCache[host] = dnsCacheItem{IPs: ips, TTL: time.Now().Add(5 * time.Minute)}
		dnsCacheLock.Unlock()
	}
	return ips
}

func resolveSystem(host string) []string {
	ips, _ := net.LookupHost(host)
	return ips
}

func resolveDoH(host string) ([]string, error) {
	body := map[string]any{"name": host, "type": "A"}
	b, _ := json.Marshal(body)
	client := &http.Client{Timeout: 5 * time.Second}
	for _, s := range dohServers {
		req, _ := http.NewRequest("POST", s, bytes.NewReader(b))
		req.Header.Set("Content-Type", "application/dns-json")
		resp, err := client.Do(req)
		if err != nil {
			continue
		}
		data, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		var out struct {
			Answer []struct {
				Data string `json:"data"`
			} `json:"Answer"`
		}
		json.Unmarshal(data, &out)
		var ips []string
		for _, a := range out.Answer {
			if net.ParseIP(a.Data) != nil {
				ips = append(ips, a.Data)
			}
		}
		if len(ips) > 0 {
			return ips, nil
		}
	}
	return nil, errors.New("DoH failed")
}

func resolveDoT(host string) ([]string, error) {
	for _, s := range dotServers {
		d := &net.Dialer{Timeout: 5 * time.Second}
		conn, err := tls.DialWithDialer(d, "tcp", s, &tls.Config{
			ServerName: strings.Split(s, ":")[0],
		})
		if err != nil {
			continue
		}
		q := buildDNSQuery(host)
		_, err = conn.Write(q)
		if err != nil {
			conn.Close()
			continue
		}
		buf := make([]byte, 4096)
		n, _ := conn.Read(buf)
		conn.Close()
		if n <= 2 {
			continue
		}
		if ips := parseDNSResponse(buf[2:n]); len(ips) > 0 {
			return ips, nil
		}
	}
	return nil, errors.New("DoT failed")
}

func buildDNSQuery(host string) []byte {
	parts := strings.Split(host, ".")
	var q []byte
	header := []byte{0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	q = append(q, header...)
	for _, p := range parts {
		q = append(q, byte(len(p)))
		q = append(q, []byte(p)...)
	}
	q = append(q, 0x00, 0x00, 0x01, 0x00, 0x01)
	l := len(q)
	return append([]byte{byte(l >> 8), byte(l)}, q...)
}

func parseDNSResponse(b []byte) []string {
	var out []string
	for i := 0; i < len(b)-4; i++ {
		if b[i] == 0x00 && b[i+1] == 0x01 && b[i+2] == 0x00 && b[i+3] == 0x01 {
			if i+10 < len(b) {
				ip := net.IPv4(b[i+10], b[i+11], b[i+12], b[i+13])
				out = append(out, ip.String())
			}
		}
	}
	return out
}

/* ============================================================
   UPSTREAM PICKER
   ============================================================ */
func pickUpstream() *url.URL {
	upstreamsLock.RLock()
	defer upstreamsLock.RUnlock()
	if len(upstreams) == 0 {
		return nil
	}
	i := atomic.AddUint32(&rr, 1)
	u, _ := url.Parse(upstreams[int(i)%len(upstreams)])
	return u
}

/* ============================================================
   PROXY HANDLER
   ============================================================ */
func proxyHandler(w http.ResponseWriter, r *http.Request) {
	host := r.URL.Hostname()
	full := r.URL.String()

	if !isWhitelisted(host, full) && isBlocked(host, full) {
		http.Error(w, "BLOCKED", 403)
		return
	}

	ips := resolveHost(host)
	if len(ips) == 0 {
		http.Error(w, "DNS_FAIL", 502)
		return
	}

	if r.Method == http.MethodConnect {
		handleConnect(w, r)
	} else {
		handleHTTP(w, r)
	}
}

/* -------- HTTPS CONNECT -------- */
func handleConnect(w http.ResponseWriter, r *http.Request) {
	up := pickUpstream()
	var conn net.Conn
	var err error

	if up == nil {
		conn, err = net.DialTimeout("tcp", r.Host, 10*time.Second)
	} else {
		switch up.Scheme {
		case "socks5":
			dialer, _ := socks5.SOCKS5("tcp", up.Host, nil, socks5.Direct)
			conn, err = dialer.Dial("tcp", r.Host)
		default:
			conn, err = net.DialTimeout("tcp", up.Host, 10*time.Second)
			if err == nil {
				_, err = conn.Write([]byte("CONNECT " + r.Host + " HTTP/1.1\r\n\r\n"))
				if err == nil {
					br := bufio.NewReader(conn)
					resp, _ := http.ReadResponse(br, r)
					if resp.StatusCode != 200 {
						err = errors.New("bad upstream")
					}
				}
			}
		}
	}

	if err != nil {
		http.Error(w, "CONNECT_FAIL "+err.Error(), 502)
		return
	}

	hj, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "Hijack unsupported", 500)
		return
	}
	c, _, err := hj.Hijack()
	if err != nil {
		http.Error(w, "Hijack fail", 500)
		return
	}

	c.Write([]byte("HTTP/1.1 200 OK\r\n\r\n"))
	go io.Copy(conn, c)
	go io.Copy(c, conn)
}

/* -------- HTTP forward -------- */
func handleHTTP(w http.ResponseWriter, r *http.Request) {
	up := pickUpstream()
	r.RequestURI = ""

	tr := &http.Transport{
		DisableCompression: false,
		DialContext: (&net.Dialer{
			Timeout:   25 * time.Second,
			KeepAlive: 25 * time.Second,
		}).DialContext,
	}

	if up != nil {
		switch up.Scheme {
		case "socks5":
			dialer, _ := socks5.SOCKS5("tcp", up.Host, nil, socks5.Direct)
			tr.Dial = dialer.Dial
		default:
			tr.Proxy = http.ProxyURL(up)
		}
	}

	resp, err := tr.RoundTrip(r)
	if err != nil {
		http.Error(w, "UPSTREAM_FAIL "+err.Error(), 502)
		return
	}
	defer resp.Body.Close()

	for k, v := range resp.Header {
		w.Header()[k] = v
	}
	w.WriteHeader(resp.StatusCode)
	io.Copy(w, resp.Body)
}
