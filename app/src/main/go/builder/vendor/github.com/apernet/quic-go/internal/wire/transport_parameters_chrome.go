package wire

import (
	"crypto/rand"
	"encoding/binary"
	"time"

	"github.com/apernet/quic-go/internal/protocol"
	"github.com/apernet/quic-go/quicvarint"
)

// Transport parameters sent by the parroted client that aren't part of any RFC.
const (
	// versionInformationParameterID is RFC 9368 (QUIC Version Negotiation).
	versionInformationParameterID transportParameterID = 0x11
	// googleInitialRTTParameterID carries a cached RTT for a known server. Only
	// sent when such a value exists, so we never send it.
	googleInitialRTTParameterID transportParameterID = 0x3127
	// googleConnectionOptionsParameterID carries four-character option tags.
	googleConnectionOptionsParameterID transportParameterID = 0x3128
)

// chromeConnectionOptions is the sole option tag sent in
// google_connection_options.
var chromeConnectionOptions = []byte{'O', 'R', 'I', 'G'}

// chromeGREASEMaxValueLen bounds the GREASE transport parameter's value, whose
// length is drawn uniformly. Pinning it to one length would let a single
// connection exclude everything but this implementation.
const chromeGREASEMaxValueLen = 15

// marshalChrome encodes the client's transport parameters the way Chrome does.
// It differs from Marshal in four independently fingerprintable ways:
//
//  1. Parameters are written in a random order on every connection, rather than
//     a fixed order with the GREASE value pinned to the front.
//  2. version_information and google_connection_options are sent, which quic-go
//     does not send at all.
//  3. ack_delay_exponent, max_ack_delay, disable_active_migration and
//     active_connection_id_limit are omitted, relying on the protocol defaults.
//  4. The GREASE parameter uses a large reserved id rather than a short one.
//
// Values come from p rather than being hardcoded, so what we advertise stays
// truthful about what this endpoint will honor; pinning the values themselves is
// Config.ChromeParrot's job.
//
// Omitting parameters is safe: the peer falls back to the same defaults it would
// assume for the client being imitated.
func (p *TransportParameters) marshalChrome() []byte {
	// Collect each parameter as an independently encoded blob so we can shuffle
	// them before concatenating.
	params := make([][]byte, 0, 13)
	add := func(b []byte) { params = append(params, b) }

	add(p.marshalVarintParam(nil, maxIdleTimeoutParameterID, uint64(p.MaxIdleTimeout/time.Millisecond)))
	add(p.marshalVarintParam(nil, maxUDPPayloadSizeParameterID, uint64(p.MaxUDPPayloadSize)))
	add(p.marshalVarintParam(nil, initialMaxDataParameterID, uint64(p.InitialMaxData)))
	add(p.marshalVarintParam(nil, initialMaxStreamDataBidiLocalParameterID, uint64(p.InitialMaxStreamDataBidiLocal)))
	add(p.marshalVarintParam(nil, initialMaxStreamDataBidiRemoteParameterID, uint64(p.InitialMaxStreamDataBidiRemote)))
	add(p.marshalVarintParam(nil, initialMaxStreamDataUniParameterID, uint64(p.InitialMaxStreamDataUni)))
	add(p.marshalVarintParam(nil, initialMaxStreamsBidiParameterID, uint64(p.MaxBidiStreamNum)))
	add(p.marshalVarintParam(nil, initialMaxStreamsUniParameterID, uint64(p.MaxUniStreamNum)))

	// initial_source_connection_id. Normally empty, but encode whatever we have.
	b := quicvarint.Append(nil, uint64(initialSourceConnectionIDParameterID))
	b = quicvarint.Append(b, uint64(p.InitialSourceConnectionID.Len()))
	add(append(b, p.InitialSourceConnectionID.Bytes()...))

	add(marshalChromeVersionInformation())

	if p.MaxDatagramFrameSize != protocol.InvalidByteCount {
		add(p.marshalVarintParam(nil, maxDatagramFrameSizeParameterID, uint64(p.MaxDatagramFrameSize)))
	}

	b = quicvarint.Append(nil, uint64(googleConnectionOptionsParameterID))
	b = quicvarint.Append(b, uint64(len(chromeConnectionOptions)))
	add(append(b, chromeConnectionOptions...))

	add(marshalChromeGREASE())

	shuffleParams(params)

	out := make([]byte, 0, 256)
	for _, param := range params {
		out = append(out, param...)
	}
	return out
}

// marshalChromeVersionInformation encodes the version_information transport
// parameter (RFC 9368): chosen version 1, then an available-version list holding
// version 1 with one GREASE version inserted at a uniformly random index. A
// fixed order is both a statistical tell and, in one direction, a
// per-connection one.
func marshalChromeVersionInformation() []byte {
	b := quicvarint.Append(nil, uint64(versionInformationParameterID))
	b = quicvarint.Append(b, 12) // 3 versions, 4 bytes each
	b = binary.BigEndian.AppendUint32(b, uint32(protocol.Version1))

	available := [2]uint32{uint32(protocol.Version1), greaseQUICVersion()}
	if randUint64n(2) == 1 {
		available[0], available[1] = available[1], available[0]
	}
	for _, v := range available {
		b = binary.BigEndian.AppendUint32(b, v)
	}
	return b
}

// greaseQUICVersion returns a random reserved QUIC version. RFC 9000 section 15
// reserves versions matching 0x?a?a?a?a to exercise version negotiation.
func greaseQUICVersion() uint32 {
	var buf [4]byte
	rand.Read(buf[:])
	for i := range buf {
		buf[i] = buf[i]&0xf0 | 0x0a
	}
	return binary.BigEndian.Uint32(buf[:])
}

// marshalChromeGREASE encodes a GREASE transport parameter with a large reserved
// id and a random value whose length varies per connection.
//
// RFC 9000 section 18.1 reserves ids of the form 31*N+27. N must be large enough
// that the id fills a full 8-byte varint, rather than the short id stock quic-go
// produces.
func marshalChromeGREASE() []byte {
	// Keep the id in the range that encodes as a full 8-byte varint.
	const nMin = (1<<30 - 27 + 30) / 31 // ceil((2^30-27)/31)
	const nMax = (1<<62 - 1 - 27) / 31  // floor((2^62-1-27)/31)
	n := nMin + randUint64n(nMax-nMin)

	b := quicvarint.Append(nil, 31*n+27)
	valLen := randUint64n(chromeGREASEMaxValueLen + 1)
	b = quicvarint.Append(b, valLen)

	val := make([]byte, valLen)
	rand.Read(val)
	return append(b, val...)
}

// shuffleParams performs a Fisher-Yates shuffle backed by crypto/rand.
func shuffleParams(params [][]byte) {
	for i := len(params) - 1; i > 0; i-- {
		j := randUint64n(uint64(i + 1))
		params[i], params[j] = params[j], params[i]
	}
}

// randUint64n returns a random value in [0, n). The modulo bias is negligible
// at the magnitudes used here.
func randUint64n(n uint64) uint64 {
	var buf [8]byte
	rand.Read(buf[:])
	return binary.BigEndian.Uint64(buf[:]) % n
}
