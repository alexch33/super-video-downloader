// +build all

package main

/*
#cgo LDFLAGS: -llog
#include <android/log.h>
#include <stdint.h>
#include <stdlib.h>

static inline void android_log(const char* msg) {
    __android_log_print(ANDROID_LOG_DEBUG, "GoProxy", "%s", msg);
}
*/
import "C"

import (
	"bufio"
	"bytes"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"text/template"
	"time"
	"unsafe"

	"github.com/xtls/xray-core/core"
)

type logcatWriter struct{}

func (w logcatWriter) Write(p []byte) (n int, err error) {
	lines := strings.Split(strings.TrimSpace(string(p)), "\n")
	for _, line := range lines {
		if line == "" {
			continue
		}
		cmsg := C.CString(line)
		defer C.free(unsafe.Pointer(cmsg))
		C.android_log(cmsg)
	}
	return len(p), nil
}

var (
	xrayInstance     atomic.Value
	instanceMu       sync.Mutex
	localProxyConfig atomic.Value
	logger           *log.Logger
	originalStdout   *os.File
	stdoutReader     *os.File
)

func init() {
	logger = log.New(logcatWriter{}, "proxychain-go: ", log.Ltime|log.Lshortfile)
	logger.Println("Logger initialized to use logcat.")
	if err := captureStdout(); err != nil {
		logger.Printf("Warning: Failed to start stdout capture: %v", err)
	}
}

func captureStdout() error {
	originalStdout = os.Stdout
	r, w, err := os.Pipe()
	if err != nil {
		return fmt.Errorf("failed to create pipe: %w", err)
	}

	stdoutReader = r
	os.Stdout = w

	go func() {
		scanner := bufio.NewScanner(stdoutReader)
		for scanner.Scan() {
			logger.Println("[stdout]", scanner.Text())
		}
		if err := scanner.Err(); err != nil {
			logger.Printf("Error reading from stdout pipe: %v", err)
		}
	}()

	logger.Println("Stdout capture started.")
	return nil
}

type localProxyInfo struct {
	Port int
	User string
	Pass string
}

const (
	localProxyTag = "local_proxy_in"
	directTag     = "direct"
)

type Hop struct {
	Tag      string
	Address  string
	Port     int
	Type     string
	Username string
	Password string
}

type XrayConfigTemplateData struct {
	LocalPort     int
	LocalUser     string
	LocalPass     string
	HasAuth       bool
	DohURL        string
	Hops          []Hop
	LastTag       string
	LocalProxyTag string
	DirectTag     string
}

const xrayJSONTemplate = `{
  "log": {
    "loglevel": "debug",
    "access": "/dev/stdout",
    "error": "/dev/stderr"
  },
  "inbounds": [
    {
      "tag": "{{.LocalProxyTag}}",
      "port": {{.LocalPort}},
      "listen": "127.0.0.1",
      "protocol": "http",
      "settings": {
        {{if .HasAuth}}
        "accounts": [
          {
            "user": "{{.LocalUser}}",
            "pass": "{{.LocalPass}}"
          }
        ],
        {{end}}
        "allowTransparent": false,
        "timeout": 300
      },
      "sniffing": {
        "enabled": true,
        "destOverride": ["http", "tls"]
      }
    }
  ],
  "outbounds": [
    {{range .Hops}}
    {
      "tag": "{{.Tag}}",
      "protocol": "{{.Type}}",
      "settings": {
        "servers": [
          {
            "address": "{{.Address}}",
            "port": {{.Port}}{{if .Username}},
            "users": [
              {
                "user": "{{.Username}}",
                "pass": "{{.Password}}"
              }
            ]{{end}}
          }
        ]
      }{{if eq .Type "http"}},
      "streamSettings": {
        "network": "tcp"
      }{{end}}
    },
    {{end}}
    {
      "tag": "{{.DirectTag}}",
      "protocol": "freedom",
      "settings": {
        "domainStrategy": "AsIs"
      }
    }
  ],
  "routing": {
    "domainStrategy": "AsIs",
    "rules": [
      {
        "type": "field",
        "inboundTag": ["{{.LocalProxyTag}}"],
        "outboundTag": "{{.LastTag}}"
      }
    ]
  }{{if .DohURL}},
  "dns": {
    "servers": [
      {
        "address": "{{.DohURL}}",
        "port": 443,
        "protocol": "https"
      }
    ]
  }{{end}}
}`

func testPortBinding(port int, user, pass string) {
	logger.Printf("Starting port binding test for port %d...", port)

	time.Sleep(800 * time.Millisecond)

	addr := fmt.Sprintf("127.0.0.1:%d", port)

	logger.Printf("Test 1: Testing TCP connection to %s...", addr)
	conn, err := net.DialTimeout("tcp", addr, 3*time.Second)
	if err != nil {
		logger.Printf("❌ TCP connection failed: %v", err)
		return
	}
	conn.Close()
	logger.Printf("✅ TCP connection successful")

	if user != "" && pass != "" {
		logger.Printf("Test 2: Testing HTTP proxy with auth...")
		testHTTPProxy(port, user, pass)
	} else {
		logger.Printf("Test 2: Testing HTTP proxy without auth...")
		testHTTPProxy(port, "", "")
	}
}

func testHTTPProxy(port int, user, pass string) {
	var proxyURL *url.URL
	var err error

	if user != "" && pass != "" {
		proxyURL, err = url.Parse(fmt.Sprintf("http://%s:%s@127.0.0.1:%d", user, pass, port))
	} else {
		proxyURL, err = url.Parse(fmt.Sprintf("http://127.0.0.1:%d", port))
	}

	if err != nil {
		logger.Printf("❌ Failed to parse proxy URL: %v", err)
		return
	}

	transport := &http.Transport{
		Proxy: http.ProxyURL(proxyURL),
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true,
		},
	}

	client := &http.Client{
		Transport: transport,
		Timeout:   10 * time.Second,
	}

	req, err := http.NewRequest("GET", "http://httpbin.org/ip", nil)
	if err != nil {
		logger.Printf("❌ Failed to create request: %v", err)
		return
	}
	req.Header.Set("User-Agent", "ProxyTester/1.0")

	logger.Printf("Sending test request through proxy...")
	resp, err := client.Do(req)
	if err != nil {
		logger.Printf("❌ HTTP request failed: %v", err)
		return
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		logger.Printf("❌ Failed to read response body: %v", err)
		return
	}

	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		logger.Printf("✅ HTTP test successful! Status: %d", resp.StatusCode)
		if len(body) > 0 {
			logger.Printf("Response body (first 200 chars): %s", string(body[:min(200, len(body))]))
		}
	} else {
		logger.Printf("❌ HTTP test failed with status: %d", resp.StatusCode)
		if len(body) > 0 {
			logger.Printf("Error body: %s", string(body))
		}
	}
}

//export go_init_chain
func go_init_chain() C.long {
	logger.Println("go_init_chain called.")
	return 1
}

//export go_destroy_chain
func go_destroy_chain(ptr C.long) {
	logger.Println("go_destroy_chain called")
	stopXray()
}

//export go_update_chain
func go_update_chain(configB64 *C.char) C.int {
	logger.Println("go_update_chain called")

	if err := startXrayFromEncodedConfig(C.GoString(configB64)); err != nil {
		logger.Printf("CRITICAL: go_update_chain failed: %v", err)
		return -1
	}

	logger.Println("go_update_chain completed successfully.")
	return 0
}

//export go_create_socket
func go_create_socket(uri *C.char) C.int {
	logger.Println("go_create_socket is DEPRECATED.")
	return -1
}

//export go_start_local_proxy
func go_start_local_proxy(port C.int) C.int {
	logger.Printf("go_start_local_proxy called for port %d", port)
	return go_start_local_proxy_auth(port, C.CString(""), C.CString(""))
}

//export go_start_local_proxy_auth
func go_start_local_proxy_auth(port C.int, userC *C.char, passC *C.char) C.int {
	user := C.GoString(userC)
	pass := C.GoString(passC)
	logger.Printf("go_start_local_proxy_auth called for port %d, user '%s'", port, user)

	localProxyConfig.Store(&localProxyInfo{
		Port: int(port),
		User: user,
		Pass: pass,
	})

	if err := startXrayFromEncodedConfig(""); err != nil {
		logger.Printf("CRITICAL: go_start_local_proxy_auth failed: %v", err)
		return -1
	}

	logger.Println("go_start_local_proxy_auth completed successfully.")
	return 0
}

//export go_stop_local_proxy
func go_stop_local_proxy() {
	logger.Println("go_stop_local_proxy called")
	stopXray()
	localProxyConfig.Store((*localProxyInfo)(nil))
}

func startXrayFromEncodedConfig(encodedConfig string) error {
	logger.Println("=== STARTING XRAY INSTANCE ===")
	instanceMu.Lock()
	defer instanceMu.Unlock()

	if prev, ok := xrayInstance.Load().(*core.Instance); ok && prev != nil {
		logger.Println("Closing previous Xray instance...")
		if err := prev.Close(); err != nil {
			logger.Printf("Warning: Failed to close previous Xray instance: %v", err)
		}
		xrayInstance.Store((*core.Instance)(nil))
	}

	cfg, _ := localProxyConfig.Load().(*localProxyInfo)
	if cfg == nil {
		return errors.New("cannot start Xray: no local proxy configuration found")
	}

	var hops []string
	var dohURL string
	if encodedConfig != "" {
		data, err := base64.StdEncoding.DecodeString(encodedConfig)
		if err != nil {
			return fmt.Errorf("base64 decode error: %w", err)
		}

		configStr := string(data)
		logger.Printf("Decoded config (%d bytes): %s", len(configStr), configStr)

		for _, line := range strings.Split(configStr, "\n") {
			line = strings.TrimSpace(line)
			if line == "" {
				continue
			}
			if strings.HasPrefix(line, "socks5://") ||
			   strings.HasPrefix(line, "http://") ||
			   strings.HasPrefix(line, "https://") {
				hops = append(hops, line)
				logger.Printf("Added hop: %s", line)
			} else if strings.HasPrefix(line, "doh=") {
				dohURL = strings.TrimPrefix(line, "doh=")
				logger.Printf("Found DoH URL: %s", dohURL)
			}
		}
	}

	logger.Printf("Configuration: %d hops, DoH: %s", len(hops), dohURL)

	configJSON, err := buildXrayJSON(cfg, hops, dohURL)
	if err != nil {
		logger.Printf("Failed to build Xray config: %v", err)
		return fmt.Errorf("failed to build xray config: %w", err)
	}

	configProto := &core.Config{}
	if err := json.Unmarshal(configJSON, configProto); err != nil {
		logger.Printf("Failed to unmarshal Xray config: %v", err)
		logger.Printf("Config JSON (first 500 chars): %s",
			string(configJSON[:min(500, len(configJSON))]))
		return fmt.Errorf("failed to unmarshal xray config: %w", err)
	}

	logger.Println("Creating new Xray instance...")
	instance, err := core.New(configProto)
	if err != nil {
		logger.Printf("CRITICAL: core.New(config) failed: %v", err)
		return fmt.Errorf("core.New(config) failed: %w", err)
	}

	logger.Println("Starting Xray instance...")
	startResult := make(chan error, 1)
	go func() {
		startResult <- instance.Start()
	}()

	select {
	case err := <-startResult:
		if err != nil {
			logger.Printf("CRITICAL: Xray instance.Start() failed: %v", err)
			instance.Close()
			return fmt.Errorf("instance.Start() failed: %w", err)
		}
	case <-time.After(5 * time.Second):
		logger.Println("WARNING: Xray instance.Start() timed out after 5 seconds.")
		logger.Println("This might be normal if Xray is still initializing.")
	}

	xrayInstance.Store(instance)
	logger.Printf("✅ Xray instance started on port %d", cfg.Port)

	go func() {
		time.Sleep(1 * time.Second)
		testPortBinding(cfg.Port, cfg.User, cfg.Pass)
	}()

	return nil
}

func stopXray() {
	logger.Println("Stopping Xray instance...")
	instanceMu.Lock()
	defer instanceMu.Unlock()

	if inst, ok := xrayInstance.Load().(*core.Instance); ok && inst != nil {
		if err := inst.Close(); err != nil {
			logger.Printf("Error closing Xray instance: %v", err)
		}
		xrayInstance.Store((*core.Instance)(nil))
		logger.Println("Xray instance stopped.")
	} else {
		logger.Println("No active Xray instance to stop.")
	}
}

func buildXrayJSON(cfg *localProxyInfo, hops []string, dohURL string) ([]byte, error) {
	logger.Printf("Building Xray JSON with %d hops", len(hops))

	tmpl, err := template.New("xray-config").Parse(xrayJSONTemplate)
	if err != nil {
		return nil, fmt.Errorf("failed to parse template: %w", err)
	}

	var templateHops []Hop
	var lastTag string

	for i, hop := range hops {
		hopURL, err := url.Parse(hop)
		if err != nil {
			return nil, fmt.Errorf("invalid hop URL '%s': %w", hop, err)
		}

		portStr := hopURL.Port()
		if portStr == "" {
			switch hopURL.Scheme {
			case "socks5":
				portStr = "1080"
			case "http", "https":
				portStr = "8080"
			default:
				portStr = "1080"
			}
			logger.Printf("Using default port %s for %s", portStr, hop)
		}

		port, _ := strconv.Atoi(portStr)

		var username, password string
		if hopURL.User != nil {
			username = hopURL.User.Username()
			password, _ = hopURL.User.Password()
			if username != "" {
				logger.Printf("Hop %d requires authentication: %s", i, username)
			}
		}

		protocolType := hopURL.Scheme
		if protocolType == "https" {
			protocolType = "http"
			logger.Printf("Converted https to http for hop %d", i)
		}

		tag := fmt.Sprintf("proxy-%d", i)
		templateHops = append(templateHops, Hop{
			Tag:      tag,
			Address:  hopURL.Hostname(),
			Port:     port,
			Type:     protocolType,
			Username: username,
			Password: password,
		})
		lastTag = tag
	}

	finalTag := directTag
	if len(hops) > 0 {
		finalTag = lastTag
	}

	logger.Printf("Final routing: inbound '%s' -> outbound '%s'", localProxyTag, finalTag)

	data := XrayConfigTemplateData{
		LocalPort:     cfg.Port,
		LocalUser:     cfg.User,
		LocalPass:     cfg.Pass,
		HasAuth:       cfg.User != "" && cfg.Pass != "",
		DohURL:        dohURL,
		Hops:          templateHops,
		LastTag:       finalTag,
		LocalProxyTag: localProxyTag,
		DirectTag:     directTag,
	}

	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, data); err != nil {
		return nil, fmt.Errorf("failed to execute template: %w", err)
	}

	var prettyJSON bytes.Buffer
	if err := json.Indent(&prettyJSON, buf.Bytes(), "", "  "); err != nil {
		logger.Printf("Warning: could not pretty-print JSON: %v", err)
		return buf.Bytes(), nil
	}

	logger.Printf("Generated Xray config (%d bytes)", prettyJSON.Len())
	logger.Printf("Config preview: %s",
		string(prettyJSON.Bytes()[:min(300, prettyJSON.Len())]))

	return prettyJSON.Bytes(), nil
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func main() {
	logger.Println("ProxyChains Go module loaded and ready")
}