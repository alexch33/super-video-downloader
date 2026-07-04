package libv2ray

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"strconv"
	"time"

	quic "github.com/apernet/quic-go"
)

type certSha256Request struct {
	Address    string `json:"address"`
	Port       int    `json:"port"`
	ServerName string `json:"serverName"`
	TimeoutMs  int64  `json:"timeoutMs"`
}

type certSha256Result struct {
	Sha256 string `json:"sha256"`
	Error  string `json:"error"`
}

func FetchTlsCertSha256(requestJSON string) string {
	return fetchCertSha256(requestJSON, fetchTLSCertSha256)
}

func FetchQuicCertSha256(requestJSON string) string {
	return fetchCertSha256(requestJSON, fetchQUICCertSha256)
}

func fetchCertSha256(
	requestJSON string,
	fetcher func(certSha256Request) (string, error),
) string {
	var request certSha256Request
	if err := json.Unmarshal([]byte(requestJSON), &request); err != nil {
		return marshalCertSha256Result(certSha256Result{Error: err.Error()})
	}

	sha256Value, err := fetcher(request)
	if err != nil {
		return marshalCertSha256Result(certSha256Result{Error: err.Error()})
	}

	return marshalCertSha256Result(certSha256Result{Sha256: sha256Value})
}

func fetchTLSCertSha256(request certSha256Request) (string, error) {
	address, serverName, timeout, err := normalizeCertRequest(request)
	if err != nil {
		return "", err
	}

	conn, err := tls.DialWithDialer(
		&net.Dialer{Timeout: timeout},
		"tcp",
		address,
		&tls.Config{
			ServerName:         serverName,
			InsecureSkipVerify: true,
			MinVersion:         tls.VersionTLS12,
		},
	)
	if err != nil {
		return "", err
	}
	defer conn.Close()

	state := conn.ConnectionState()
	if len(state.PeerCertificates) == 0 {
		return "", errors.New("peer certificate is empty")
	}

	return rawCertSHA256Hex(state.PeerCertificates[0].Raw), nil
}

func fetchQUICCertSha256(request certSha256Request) (string, error) {
	address, serverName, timeout, err := normalizeCertRequest(request)
	if err != nil {
		return "", err
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	conn, err := quic.DialAddr(
		ctx,
		address,
		&tls.Config{
			ServerName:         serverName,
			InsecureSkipVerify: true,
			MinVersion:         tls.VersionTLS12,
			NextProtos:         []string{"h3"},
		},
		&quic.Config{
			HandshakeIdleTimeout: timeout,
			MaxIdleTimeout:       timeout,
		},
	)
	if err != nil {
		return "", err
	}
	defer conn.CloseWithError(0, "")

	state := conn.ConnectionState()
	if len(state.TLS.PeerCertificates) == 0 {
		return "", errors.New("peer certificate is empty")
	}

	return rawCertSHA256Hex(state.TLS.PeerCertificates[0].Raw), nil
}

func normalizeCertRequest(req certSha256Request) (string, string, time.Duration, error) {
	if req.Address == "" {
		return "", "", 0, errors.New("address is empty")
	}

	port := req.Port
	if port <= 0 {
		port = 443
	}

	timeout := time.Duration(req.TimeoutMs) * time.Millisecond

	return net.JoinHostPort(req.Address, strconv.Itoa(port)), req.ServerName, timeout, nil
}

func rawCertSHA256Hex(raw []byte) string {
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}

func marshalCertSha256Result(result certSha256Result) string {
	data, err := json.Marshal(result)
	if err != nil {
		return `{"error":"failed to marshal result"}`
	}
	return string(data)
}
