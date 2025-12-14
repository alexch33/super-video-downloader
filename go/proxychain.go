package main

/*
#include <stdint.h>
*/
import "C"

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256" // For pinning
	"crypto/tls"     // For pinning
	"crypto/x509"    // For pinning
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/miekg/dns"
)

// ======================================================
// GLOBAL STATE
// ======================================================

var (
	activeChain atomic.Value // *ProxyChain
	buildMu     sync.Mutex
	localProxy  atomic.Value // *LocalProxyServer

	// A map of known DoH provider hosts to their public key pins.
	dohPins = map[string]string{
		"dns.quad9.net":         "i2kObfz0qIKCGNWt7MjBUeSrh0Dyjb0/zWINImZES+I=", // Quad9
		"cloudflare-dns.com":    "SPfg6FluPIlUc6a5h313BDCxQYNGX+THTy7ig5X3+VA=", // Cloudflare
		"dns.adguard-dns.com":   "Gl68Pn7oCtbC1It1MQA46A8n/t8R8bhnH57M6EGTBXE=", // AdGuard DNS
	}
)

// ======================================================
// JNI API
// ======================================================

//export go_init_chain
func go_init_chain() C.long {
	builder := NewChainBuilder()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	activeChain.Store(builder.Build(ctx))
	return 1
}

//export go_destroy_chain
func go_destroy_chain(ptr C.long) {
	activeChain.Store((*ProxyChain)(nil))
	stopLocalProxy()
}

//export go_update_chain
func go_update_chain(configB64 *C.char) C.int {
	data, err := base64.StdEncoding.DecodeString(C.GoString(configB64))
	if err != nil {
		return -1
	}
	builder := NewChainBuilder()
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		switch {
		case strings.HasPrefix(line, "socks5://"):
			builder.AddSOCKS5(line)
		case strings.HasPrefix(line, "http://"), strings.HasPrefix(line, "https://"):
			builder.AddHTTP(line)
		case strings.HasPrefix(line, "doh="):
			dohValue := strings.TrimPrefix(line, "doh=")
			if strings.HasPrefix(dohValue, "strict:") {
				builder.SetDoH(strings.TrimPrefix(dohValue, "strict:"), true)
			} else {
				builder.SetDoH(dohValue, false)
			}
		}
	}
	buildMu.Lock()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	activeChain.Store(builder.Build(ctx))
	buildMu.Unlock()
	return 0
}

//export go_create_socket
func go_create_socket(uri *C.char) C.int {
	chain, _ := activeChain.Load().(*ProxyChain)
	if chain == nil {
		return -1
	}
	u, err := url.Parse(C.GoString(uri))
	if err != nil {
		return -1
	}
	host := u.Host
	if !strings.Contains(host, ":") {
		if u.Scheme == "https" {
			host += ":443"
		} else {
			host += ":80"
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	conn, err := chain.DialContext(ctx, "tcp", host)
	if err != nil {
		return -1
	}
	tcp, ok := conn.(*net.TCPConn)
	if !ok {
		conn.Close()
		return -1
	}
	file, err := tcp.File()
	if err != nil {
		conn.Close()
		return -1
	}
	fd := int(file.Fd())
	syscall.SetNonblock(fd, true)
	return C.int(fd)
}

// ======================================================
// LOCAL PROXY JNI
// ======================================================

//export go_start_local_proxy
func go_start_local_proxy(port C.int) C.int {
	stopLocalProxy()
	lp := &LocalProxyServer{Port: int(port)}
	if err := lp.Start(); err != nil {
		return -1
	}
	localProxy.Store(lp)
	return 0
}

//export go_start_local_proxy_auth
func go_start_local_proxy_auth(port C.int, userC *C.char, passC *C.char) C.int {
	stopLocalProxy()
	lp := &LocalProxyServer{
		Port: int(port),
		Auth: &ProxyAuth{User: C.GoString(userC), Pass: C.GoString(passC)},
	}
	if err := lp.Start(); err != nil {
		return -1
	}
	localProxy.Store(lp)
	return 0
}

//export go_stop_local_proxy
func go_stop_local_proxy() {
	stopLocalProxy()
}

// ======================================================
// DNS (SAFE) - HARDENED TLS CONFIG
// ======================================================

type DoHResolver struct {
	Endpoint string
	Client   *http.Client
}

func NewDoHResolver(endpoint, pin string) *DoHResolver {
	tlsConfig := &tls.Config{
		// Enforce modern, secure TLS version.
		MinVersion: tls.VersionTLS12,
		// Disable insecure renegotiation.
		Renegotiation: tls.RenegotiateNever,
		// We will do our own verification in VerifyPeerCertificate.
		InsecureSkipVerify: true,
		// Custom verification function to perform public key pinning.
		VerifyPeerCertificate: func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
			// If no pin is provided, we can't verify. For this to be secure,
			// this should only happen for user-defined DoH servers.
			if pin == "" {
				// To be safe, we can add a check to still verify the cert against system roots
				// if we don't have a pin. This is a good middle ground.
				if len(rawCerts) == 0 {
					return errors.New("no certificates presented by server")
				}
				cert, err := x509.ParseCertificate(rawCerts[0])
				if err != nil {
					return err
				}
				_, err = cert.Verify(x509.VerifyOptions{})
				return err
			}

			if len(rawCerts) == 0 {
				return errors.New("DoH pinning: no peer certificates found")
			}

			// Parse the server's leaf certificate.
			cert, err := x509.ParseCertificate(rawCerts[0])
			if err != nil {
				return fmt.Errorf("DoH pinning: failed to parse certificate: %w", err)
			}

			// Hash the public key.
			spki, err := x509.MarshalPKIXPublicKey(cert.PublicKey)
			if err != nil {
				return fmt.Errorf("DoH pinning: failed to marshal public key: %w", err)
			}
			hash := sha256.Sum256(spki)
			b64Hash := base64.StdEncoding.EncodeToString(hash[:])

			// Compare with our hard-coded pin.
			if b64Hash != pin {
				return fmt.Errorf("DoH public key pin mismatch: expected %s, got %s", pin, b64Hash)
			}

			// If we're here, the pin is valid.
			return nil
		},
	}

	client := &http.Client{
		Timeout: 10 * time.Second,
		Transport: &http.Transport{
			// The DialContext remains the same.
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, network, addr)
			},
			// The DialTLSContext is now simplified, as the custom verification is in the tls.Config.
			DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				// Set the ServerName for SNI.
				tlsConfig.ServerName = strings.Split(addr, ":")[0]
				return tls.Dial(network, addr, tlsConfig)
			},
		},
	}
	return &DoHResolver{
		Endpoint: endpoint,
		Client:   client,
	}
}

func (r *DoHResolver) LookupIP(ctx context.Context, host string) ([]net.IP, error) {
	if ip := net.ParseIP(host); ip != nil {
		return []net.IP{ip}, nil
	}
	m := new(dns.Msg)
	m.SetQuestion(dns.Fqdn(host), dns.TypeA)
	m.RecursionDesired = true
	packedMsg, err := m.Pack()
	if err != nil {
		return nil, fmt.Errorf("failed to pack DNS message: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, r.Endpoint, bytes.NewReader(packedMsg))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	resp, err := r.Client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return nil, fmt.Errorf("DoH HTTP error: %s %s", resp.Status, string(body))
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	respMsg := new(dns.Msg)
	if err := respMsg.Unpack(body); err != nil {
		return nil, fmt.Errorf("failed to unpack DNS response: %w", err)
	}
	var ips []net.IP
	for _, rr := range respMsg.Answer {
		if a, ok := rr.(*dns.A); ok {
			ips = append(ips, a.A)
		}
	}
	if len(ips) == 0 {
		return nil, fmt.Errorf("DoH: no A records found for host %s", host)
	}
	return ips, nil
}

func systemResolver() *net.Resolver {
	return net.DefaultResolver
}

// ======================================================
// PROXY CHAIN
// ======================================================

type ChainBuilder struct {
	hops      []ProxyHop
	dohEp     string
	dohStrict bool
}

func NewChainBuilder() *ChainBuilder              { return &ChainBuilder{} }
func (b *ChainBuilder) AddSOCKS5(raw string)       { b.hops = append(b.hops, parseProxy(raw, ProxySOCKS5)) }
func (b *ChainBuilder) AddHTTP(raw string)         { b.hops = append(b.hops, parseProxy(raw, ProxyHTTP)) }
func (b *ChainBuilder) SetDoH(ep string, strict bool) {
	b.dohEp = ep
	b.dohStrict = strict
}

// Build function now resolves proxy hostnames to IPs using the DoH resolver.
func (b *ChainBuilder) Build(ctx context.Context) *ProxyChain {
	var resolver *net.Resolver
	var doh *DoHResolver

	// Step 1: Create the DoH resolver first, if one is configured.
	if b.dohEp != "" {
		dohURL, err := url.Parse(b.dohEp)
		if err == nil {
			pin := dohPins[dohURL.Hostname()]
			doh = NewDoHResolver(b.dohEp, pin)
		} else {
			doh = NewDoHResolver(b.dohEp, "") // Fallback for invalid URL
		}

		resolver = &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
				host, port, err := net.SplitHostPort(address)
				if err != nil {
					return nil, err
				}
				ips, err := doh.LookupIP(ctx, host)
				if err != nil { // In strict mode, this error propagates and fails the connection.
					return nil, err
				}
				var lastErr error
				for _, ip := range ips {
					conn, err := (&net.Dialer{}).DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
					if err == nil {
						return conn, nil
					}
					lastErr = err
				}
				return nil, fmt.Errorf("could not connect to any resolved IP for %s: %w", host, lastErr)
			},
		}
	} else {
		resolver = systemResolver()
	}

	// Step 2: Resolve proxy hostnames to IPs using the created resolver.
	for i := range b.hops {
		host, port, err := net.SplitHostPort(b.hops[i].Address)
		if err != nil {
			host = b.hops[i].Address
		}

		if net.ParseIP(host) != nil {
			b.hops[i].ResolvedAddress = b.hops[i].Address
			continue
		}

		var ips []net.IP
		if doh != nil {
			ips, _ = doh.LookupIP(ctx, host)
		}

		if len(ips) == 0 && !b.dohStrict {
			ipAddrs, err := net.DefaultResolver.LookupIPAddr(ctx, host)
			if err != nil {
				b.hops[i].ResolvedAddress = b.hops[i].Address
				continue
			}
			for _, ipAddr := range ipAddrs {
				ips = append(ips, ipAddr.IP)
			}
		}

		if len(ips) > 0 {
			b.hops[i].ResolvedAddress = net.JoinHostPort(ips[0].String(), port)
		} else {
			// If resolution fails (especially in strict mode), this will be the original hostname.
			// The connection will then fail in DialContext, which is correct for strict mode.
			b.hops[i].ResolvedAddress = b.hops[i].Address
		}
	}

	return &ProxyChain{hops: b.hops, resolver: resolver}
}

type ProxyChain struct {
	hops     []ProxyHop
	resolver *net.Resolver
}

// DialContext now uses the pre-resolved IP for the first hop.
func (c *ProxyChain) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	if len(c.hops) == 0 {
		if c.resolver != nil && c.resolver.Dial != nil {
			return c.resolver.Dial(ctx, network, addr)
		}
		return (&net.Dialer{}).DialContext(ctx, network, addr)
	}

	var conn net.Conn
	var err error

	firstHopDialer := &net.Dialer{}
	conn, err = firstHopDialer.DialContext(ctx, network, c.hops[0].ResolvedAddress)

	if err != nil {
		return nil, fmt.Errorf("chain dial to first hop %s (%s) failed: %w", c.hops[0].Address, c.hops[0].ResolvedAddress, err)
	}

	for i, hop := range c.hops {
		var target string
		if i+1 < len(c.hops) {
			target = c.hops[i+1].ResolvedAddress // Use resolved address for intermediate hops too.
		} else {
			target = addr
		}

		err = hop.DialThrough(ctx, conn, target)
		if err != nil {
			conn.Close()
			return nil, fmt.Errorf("chain dial from %s to %s failed: %w", hop.ResolvedAddress, target, err)
		}
	}

	return conn, nil
}

// ======================================================
// PROXIES
// ======================================================

type ProxyType int

const (
	ProxySOCKS5 ProxyType = iota
	ProxyHTTP
)

type ProxyHop struct {
	Type            ProxyType
	Address         string // The original address (e.g., my-proxy.com:8080)
	ResolvedAddress string // The resolved IP address (e.g., 1.2.3.4:8080)
	User            string
	Password        string
}

func (p ProxyHop) DialThrough(ctx context.Context, conn net.Conn, target string) error {
	if p.Type == ProxySOCKS5 {
		return socks5Connect(conn, p, target)
	}
	return httpConnect(conn, p, target)
}

// ======================================================
// CONNECT IMPLEMENTATIONS
// ======================================================

func httpConnect(conn net.Conn, p ProxyHop, addr string) error {
	req := &http.Request{
		Method: http.MethodConnect,
		URL:    &url.URL{Host: addr},
		Host:   addr,
		Header: make(http.Header),
	}
	if p.User != "" {
		auth := base64.StdEncoding.EncodeToString([]byte(p.User + ":" + p.Password))
		req.Header.Set("Proxy-Authorization", "Basic "+auth)
	}
	if err := req.Write(conn); err != nil {
		return fmt.Errorf("writing HTTP CONNECT to %s failed: %w", p.ResolvedAddress, err)
	}
	br := bufio.NewReader(conn)
	resp, err := http.ReadResponse(br, req)
	if err != nil {
		return fmt.Errorf("reading HTTP CONNECT response from %s failed: %w", p.ResolvedAddress, err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("http connect to %s failed with status: %s", p.ResolvedAddress, resp.Status)
	}
	return nil
}

func socks5Connect(conn net.Conn, p ProxyHop, target string) error {
	method := byte(0x00) // No auth
	if p.User != "" {
		method = 0x02 // Username/Password
	}
	if _, err := conn.Write([]byte{0x05, 1, method}); err != nil {
		return fmt.Errorf("socks5: write method selection failed: %w", err)
	}
	buf := make([]byte, 2)
	if _, err := io.ReadFull(conn, buf); err != nil {
		return fmt.Errorf("socks5: read method selection response failed: %w", err)
	}
	if buf[0] != 0x05 || buf[1] != method {
		return errors.New("socks5: server selected unsupported auth method")
	}
	if method == 0x02 {
		u, pw := []byte(p.User), []byte(p.Password)
		req := append([]byte{0x01, byte(len(u))}, u...)
		req = append(req, byte(len(pw)))
		req = append(req, pw...)
		if _, err := conn.Write(req); err != nil {
			return fmt.Errorf("socks5: write auth request failed: %w", err)
		}
		if _, err := io.ReadFull(conn, buf); err != nil {
			return fmt.Errorf("socks5: read auth response failed: %w", err)
		}
		if buf[0] != 0x01 || buf[1] != 0x00 {
			return errors.New("socks5: authentication failed")
		}
	}
	host, portStr, err := net.SplitHostPort(target)
	if err != nil {
		return fmt.Errorf("invalid target address: %s", target)
	}
	port, _ := strconv.Atoi(portStr)
	req := []byte{0x05, 0x01, 0x00} // VER, CMD, RSV
	if ip := net.ParseIP(host); ip != nil {
		if v4 := ip.To4(); v4 != nil {
			req = append(req, 0x01)
			req = append(req, v4...)
		} else {
			req = append(req, 0x04)
			req = append(req, ip.To16()...)
		}
	} else {
		if len(host) > 255 {
			return errors.New("socks5: hostname too long")
		}
		req = append(req, 0x03, byte(len(host)))
		req = append(req, host...)
	}
	req = append(req, byte(port>>8), byte(port))
	if _, err := conn.Write(req); err != nil {
		return fmt.Errorf("socks5: write connect request failed: %w", err)
	}
	reply := make([]byte, 4)
	if _, err := io.ReadFull(conn, reply); err != nil {
		return fmt.Errorf("socks5: read connect reply failed: %w", err)
	}
	if reply[1] != 0x00 {
		return fmt.Errorf("socks5: connect failed with status: %d", reply[1])
	}
	extra := 0
	switch reply[3] {
	case 0x01:
		extra = net.IPv4len
	case 0x04:
		extra = net.IPv6len
	case 0x03:
		lenByte := make([]byte, 1)
		if _, err := io.ReadFull(conn, lenByte); err != nil {
			return fmt.Errorf("socks5: read domain len from reply failed: %w", err)
		}
		extra = int(lenByte[0])
	}
	extra += 2 // Port bytes
	if extra > 0 {
		if extra > 4096 {
			return errors.New("socks5: reply too long")
		}
		io.CopyN(io.Discard, conn, int64(extra))
	}
	return nil
}

// ======================================================
// LOCAL HTTP PROXY & HELPERS
// ======================================================

type ProxyAuth struct{ User, Pass string }
type LocalProxyServer struct {
	Port int
	Auth *ProxyAuth
	ln   net.Listener
}

func (p *LocalProxyServer) Start() error {
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", p.Port))
	if err != nil {
		return err
	}
	p.ln = ln
	go p.loop()
	return nil
}

func (p *LocalProxyServer) loop() {
	for {
		c, err := p.ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			continue
		}
		go p.handle(c)
	}
}

func (p *LocalProxyServer) handle(c net.Conn) {
	defer c.Close()
	br := bufio.NewReader(c)
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}
	chain, ok := activeChain.Load().(*ProxyChain)
	if !ok || chain == nil {
		c.Write([]byte("HTTP/1.1 500 Internal Server Error\r\n\r\nProxy chain not configured"))
		return
	}
	if p.Auth != nil {
		if !checkProxyAuth(req, p.Auth.User, p.Auth.Pass) {
			c.Write([]byte("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"Proxy\"\r\n\r\n"))
			return
		}
	}
	destConn, err := chain.DialContext(context.Background(), "tcp", req.Host)
	if err != nil {
		errorMsg := fmt.Sprintf("HTTP/1.1 503 Service Unavailable\r\n\r\nUpstream proxy chain failed: %v", err)
		c.Write([]byte(errorMsg))
		return
	}
	defer destConn.Close()
	if req.Method == http.MethodConnect {
		c.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
	} else {
		req.Header.Del("Proxy-Authorization")
		req.Header.Del("Proxy-Connection")
		if err := req.Write(destConn); err != nil {
			return
		}
	}
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); io.Copy(destConn, c) }()
	go func() { defer wg.Done(); io.Copy(c, destConn) }()
	wg.Wait()
}

func checkProxyAuth(r *http.Request, user, pass string) bool {
	authHeader := r.Header.Get("Proxy-Authorization")
	if authHeader == "" {
		return false
	}
	parts := strings.SplitN(authHeader, " ", 2)
	if len(parts) != 2 || parts[0] != "Basic" {
		return false
	}
	decoded, err := base64.StdEncoding.DecodeString(parts[1])
	if err != nil {
		return false
	}
	creds := strings.SplitN(string(decoded), ":", 2)
	return len(creds) == 2 && creds[0] == user && creds[1] == pass
}

func parseProxy(raw string, t ProxyType) ProxyHop {
	u, _ := url.Parse(raw)
	user, pass := "", ""
	if u.User != nil {
		user = u.User.Username()
		pass, _ = u.User.Password()
	}
	return ProxyHop{Type: t, Address: u.Host, User: user, Password: pass}
}

func stopLocalProxy() {
	if lp, ok := localProxy.Load().(*LocalProxyServer); ok && lp != nil {
		if lp.ln != nil {
			lp.ln.Close()
		}
	}
	localProxy.Store((*LocalProxyServer)(nil))
}

func main() {}
