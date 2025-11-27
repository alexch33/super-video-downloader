package myproxy

import (
	"bufio"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"sync/atomic"
	"time"
)

// ----------- CONFIG -------------

// List of upstream proxies (HTTP or SOCKS5)
var upstream = []string{
	"http://10.0.2.2:2080",
}

// round‑robin pointer
var rr uint32 = 0

// ---------------------------------

func Start(addr string) {
	go func() {
		log.Println("Chained proxy listening on:", addr)
		err := http.ListenAndServe(addr, http.HandlerFunc(handler))
		if err != nil {
			log.Println("Proxy error:", err)
		}
	}()
}

//
// ================== MAIN PROXY HANDLER ==================
//
func handler(w http.ResponseWriter, r *http.Request) {

	// Remove problematic headers
	r.Header.Del("X-Requested-With")

	// HTTPS CONNECT tunneling
	if r.Method == http.MethodConnect {
		handleConnect(w, r)
		return
	}

	// Standard HTTP proxying through chain
	err := forwardViaChain(w, r)
	if err != nil {
		http.Error(w, err.Error(), 502)
	}
}

//
// ================== CONNECT (HTTPS) ==================
//
func handleConnect(w http.ResponseWriter, r *http.Request) {

	upstreamURL, err := pickUpstreamURL()
	if err != nil {
		http.Error(w, "No upstream proxies", 500)
		return
	}

	// Connect to upstream proxy
	conn, err := net.DialTimeout("tcp", upstreamURL.Host, 10*time.Second)
	if err != nil {
		http.Error(w, "Upstream CONNECT failed: "+err.Error(), 502)
		return
	}

	// Send CONNECT request to upstream proxy
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

	// Upgrade local client connection
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

	// Tell WebView CONNECT OK
	clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))

	// Tunnel both ways
	go io.Copy(conn, clientConn)
	go io.Copy(clientConn, conn)
}

//
// ================== HTTP FORWARD THROUGH CHAIN ==================
//
func forwardViaChain(w http.ResponseWriter, r *http.Request) error {

	upstreamURL, err := pickUpstreamURL()
	if err != nil {
		return err
	}

	transport := &http.Transport{
		Proxy: http.ProxyURL(upstreamURL),
		DisableCompression: false,
		DialContext: (&net.Dialer{
			Timeout:   30 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
	}

	r.RequestURI = "" // Required for client-mode
	r.Host = r.URL.Host

	resp, err := transport.RoundTrip(r)
	if err != nil {
		return failover(w, r, err)
	}
	defer resp.Body.Close()

	// Copy headers
	for k, v := range resp.Header {
		w.Header()[k] = v
	}

	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
	return nil
}

//
// ================== FAILOVER ==================
//
func failover(w http.ResponseWriter, r *http.Request, originalErr error) error {

	for i := 0; i < len(upstream); i++ {
		// Try next upstream
		next, err := pickUpstreamURL()
		if err != nil {
			continue
		}

		transport := &http.Transport{
			Proxy: http.ProxyURL(next),
		}

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

//
// ================== ROUND-ROBIN UPSTREAM PICKER ==================
//
func pickUpstreamURL() (*url.URL, error) {
	if len(upstream) == 0 {
		return nil, errors.New("no upstream proxies provided")
	}

	i := atomic.AddUint32(&rr, 1)
	raw := upstream[i%uint32(len(upstream))]
	return url.Parse(raw)
}
