package quic

import (
	"time"

	"github.com/apernet/quic-go/internal/protocol"
	"github.com/apernet/quic-go/internal/wire"
)

// Values a Chrome-parroting client pins. Advertising Chrome's transport
// parameter shape while carrying quic-go's own numbers would defeat the point.
const (
	chromeMaxIdleTimeout        = 30 * time.Second
	chromeInitialMaxStreamData  = 6291456
	chromeInitialMaxData        = 15728640
	chromeMaxIncomingStreams    = 100
	chromeMaxIncomingUniStreams = 103
	// UDP payload size for the packets we send.
	chromeInitialPacketSize = 1250
	// max_udp_payload_size: what we are willing to receive, which differs from
	// what we send.
	chromeMaxUDPPayloadSize = 1472
	// DATAGRAM support is always advertised, so ChromeParrot forces datagrams on
	// rather than leave the fingerprint a parameter short.
	chromeMaxDatagramFrameSize = 65536
)

// chromeParrotTransportParameters adjusts the transport parameters advertised by
// a Chrome-parroting client. The omitted ones are reset to their protocol
// defaults, so what we imply by omission matches how we actually behave.
func chromeParrotTransportParameters(params *wire.TransportParameters) {
	params.MaxUDPPayloadSize = chromeMaxUDPPayloadSize
	params.MaxDatagramFrameSize = chromeMaxDatagramFrameSize
	params.MaxAckDelay = protocol.DefaultMaxAckDelay
	params.AckDelayExponent = protocol.DefaultAckDelayExponent
	params.DisableActiveMigration = false
	params.ActiveConnectionIDLimit = protocol.DefaultActiveConnectionIDLimit
	params.ChromeFingerprint = true
}

// ZeroLengthConnectionIDGenerator generates zero-length connection IDs, as a
// Chrome-parroting client needs. Set it on a Transport:
//
//	tr := &quic.Transport{Conn: conn, ConnectionIDGenerator: quic.ZeroLengthConnectionIDGenerator{}}
//
// It must be chosen at the Transport level, not per-dial: the Transport parses
// every incoming packet's destination connection ID at one fixed length, so
// overriding it for a single connection breaks routing.
//
// Handlers are keyed by source connection ID, so a Transport using this carries
// one connection at a time. That suits a client with one connection per socket,
// not a server or a multiplexing dialer.
type ZeroLengthConnectionIDGenerator struct{}

func (ZeroLengthConnectionIDGenerator) GenerateConnectionID() (ConnectionID, error) {
	return ConnectionID{}, nil
}

func (ZeroLengthConnectionIDGenerator) ConnectionIDLen() int { return 0 }
