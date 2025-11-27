package myproxy

import (
	"bufio"
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
)

// ===================== GLOBALS ==========================

// upstream proxy list (modifiable from Kotlin)
var upstream []string
var upstreamLock sync.RWMutex
var rr uint32 = 0

// adblock list (modifiable from Kotlin)
var blockedHosts = make(map[string]bool)
var blockedLock sync.RWMutex

// =========================================================
//                    EXPORTED API
// =========================================================

// SetUpstreams accepts comma or newline separated upstream URLs
func SetUpstreams(list string) {
	upstreamLock.Lock()
	defer upstreamLock.Unlock()

	splitter := func(c rune) bool { return c == ',' || c == '\n' }
	rawEntries := strings.FieldsFunc(list, splitter)

	filteredUpstream := make([]string, 0, len(rawEntries))
	for _, entry := range rawEntries {
		trimmed := strings.TrimSpace(entry)
		if trimmed != "" {
			if _, err := url.Parse(trimmed); err == nil {
				filteredUpstream = append(filteredUpstream, trimmed)
			} else {
				log.Printf("Invalid proxy URL format, skipping: %s", trimmed)
			}
		}
	}
	upstream = filteredUpstream
	log.Printf("[SetUpstreams] Updated upstream proxies. Count: %d, List: %v", len(upstream), upstream)
}

// SetBlockedHosts accepts comma or newline separated hosts/patterns
func SetBlockedHosts(list string) {
	blockedLock.Lock()
	defer blockedLock.Unlock()

	splitter := func(c rune) bool { return c == ',' || c == '\n' }
	rawEntries := strings.FieldsFunc(list, splitter)

	blockedHosts = make(map[string]bool)
	for _, h := range rawEntries {
		trimmed := strings.ToLower(strings.TrimSpace(h))
		if trimmed != "" {
			blockedHosts[trimmed] = true
		}
	}

	log.Printf("[SetBlockedHosts] Updated blocked hosts. Count: %d, List: %v", len(blockedHosts), blockedHosts)
}

// Start the proxy server
func Start(addr string) {
	go func() {
		log.Println("[Proxy] Listening on:", addr)
		err := http.ListenAndServe(addr, http.HandlerFunc(handler))
		if err != nil {
			log.Println("[Proxy] Error:", err)
		}
	}()
}

// =========================================================
//                    MAIN HANDLER
// =========================================================

func handler(w http.ResponseWriter, r *http.Request) {
	// Remove Chrome/WebView X-Requested-With
	r.Header.Del("X-Requested-With")

	// Adblock check
	if isBlocked(r.Host) {
		http.Error(w, "Blocked by adblock", 403)
		return
	}

	// HTTPS CONNECT
	if r.Method == http.MethodConnect {
		handleConnect(w, r)
		return
	}

	// HTTP request
	err := forwardViaChainOrDirect(w, r)
	if err != nil {
		http.Error(w, err.Error(), 502)
	}
}

// =========================================================
//                         ADBLOCK
// =========================================================

func isBlocked(host string) bool {
	host = strings.ToLower(host)
	parts := strings.Split(host, ":")
	host = parts[0]

	blockedLock.RLock()
	defer blockedLock.RUnlock()

	if blockedHosts[host] {
		return true
	}

	for b := range blockedHosts {
		if strings.HasSuffix(host, "."+b) {
			return true
		}
	}

	return false
}

// =========================================================
//                     CONNECT (HTTPS)
// =========================================================

func handleConnect(w http.ResponseWriter, r *http.Request) {
	upstreamURL, err := pickUpstreamURL()
	directMode := err != nil // if no upstreams, we go direct

	var conn net.Conn
	if directMode {
		conn, err = net.DialTimeout("tcp", r.Host, 10*time.Second)
		if err != nil {
			http.Error(w, "Direct CONNECT failed: "+err.Error(), 502)
			return
		}
	} else {
		conn, err = net.DialTimeout("tcp", upstreamURL.Host, 10*time.Second)
		if err != nil {
			http.Error(w, "Upstream CONNECT failed: "+err.Error(), 502)
			return
		}
		_, err = conn.Write([]byte("CONNECT " + r.Host + " HTTP/1.1\r\nHost: " + r.Host + "\r\n\r\n"))
		if err != nil {
			http.Error(w, "Upstream write failed", 502)
			return
		}
		br := bufio.NewReader(conn)
		resp, err := http.ReadResponse(br, r)
		if err != nil || resp.StatusCode != 200 {
			http.Error(w, "Upstream CONNECT rejected", 502)
			return
		}
	}

	hijacker, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "Hijacking not supported", 500)
		return
	}

	clientConn, _, err := hijacker.Hijack()
	if err != nil {
		http.Error(w, "Hijack failed", 500)
		return
	}

	clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))

	go io.Copy(conn, clientConn)
	go io.Copy(clientConn, conn)
}

// =========================================================
//                   HTTP FORWARDING (CHAIN OR DIRECT)
// =========================================================

func forwardViaChainOrDirect(w http.ResponseWriter, r *http.Request) error {
	upstreamURL, err := pickUpstreamURL()
	directMode := err != nil // no upstreams, direct connection

	transport := &http.Transport{
		DisableCompression: false,
		DialContext: (&net.Dialer{
			Timeout:   30 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
	}

	if !directMode {
		transport.Proxy = http.ProxyURL(upstreamURL)
	}

	r.RequestURI = ""
	r.Host = r.URL.Host

	resp, err := transport.RoundTrip(r)
	if err != nil && !directMode {
		return failover(w, r, err)
	} else if err != nil && directMode {
		return err
	}
	defer resp.Body.Close()

	for k, v := range resp.Header {
		w.Header()[k] = v
	}

	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
	return nil
}

// =========================================================
//                        FAILOVER
// =========================================================

func failover(w http.ResponseWriter, r *http.Request, originalErr error) error {
	upstreamLock.RLock()
	count := len(upstream)
	upstreamLock.RUnlock()

	for i := 0; i < count; i++ {
		next, err := pickUpstreamURL()
		if err != nil {
			continue
		}
		transport := &http.Transport{Proxy: http.ProxyURL(next)}
		resp, err := transport.RoundTrip(r)
		if err != nil {
			continue
		}
		defer resp.Body.Close()
		for k, v := range resp.Header {
			w.Header()[k] = v
		}
		w.WriteHeader(resp.StatusCode)
		io.Copy(w, resp.Body)
		return nil
	}

	return errors.New("all upstream proxies failed: " + originalErr.Error())
}

// =========================================================
//                    ROUND ROBIN PICKER
// =========================================================

func pickUpstreamURL() (*url.URL, error) {
	upstreamLock.RLock()
	defer upstreamLock.RUnlock()

	if len(upstream) == 0 {
		return nil, errors.New("no upstream proxies")
	}

	i := atomic.AddUint32(&rr, 1)
	raw := upstream[i%uint32(len(upstream))]
	return url.Parse(raw)
}
