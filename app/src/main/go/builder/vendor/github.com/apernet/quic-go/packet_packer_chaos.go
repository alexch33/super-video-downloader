package quic

import (
	"fmt"

	"github.com/apernet/quic-go/quicvarint"

	"github.com/apernet/quic-go/internal/ackhandler"
	"github.com/apernet/quic-go/internal/protocol"
	"github.com/apernet/quic-go/internal/wire"
)

// Bounds for the chaos protection applied to Initial packets.
const (
	// How many extra CRYPTO frames to attempt. An attempt that lands on a frame
	// that cannot be split is spent doing nothing, so the resulting frame count
	// is a distribution rather than a fixed target.
	chaosMinAddedCryptoFrames = 2
	chaosMaxAddedCryptoFrames = 10

	chaosMinPingFrames = 2
	chaosMaxPingFrames = 10

	// Worst-case encoded size of a CRYPTO frame header: type byte plus a varint
	// offset and a varint length. Used to bound per-packet capacity.
	maxCryptoFrameHeaderLen = 8
)

// Length of the leading CRYPTO frame when a ClientHello is spread over several
// Initials. It covers the fixed part of the ClientHello plus the length of its
// first extension, so a receiver must reassemble to parse past it, with enough
// randomness that the cut point is not a constant.
const (
	chaosMinFirstFrameLen    = 55
	chaosFirstFrameLenRandom = 32
)

// chromeCryptoSplit decides how a ClientHello too large for one Initial is laid
// out. It returns the length of the leading frame and of the trailing frame,
// which the first packet carries together: the head of the ClientHello and its
// very end, with the middle left for the packets that follow.
//
// Carrying the two ends first means a receiver cannot parse the ClientHello
// from the first packet alone, nor assume CRYPTO offsets arrive in order.
// Zero means the data fits in one packet and no split is needed.
//
// pending is the number of bytes queued, offset the crypto stream offset they
// start at, and available the payload space of a packet.
func chromeCryptoSplit(pending, offset, available protocol.ByteCount, rand func(int) int) (first, last protocol.ByteCount) {
	if pending <= 0 || available <= 0 {
		return 0, 0
	}
	// Ceiling on one frame header over the whole range, used to reserve space.
	minFrame := cryptoFrameHeaderLen(offset+pending-1, pending)
	if available < 2*minFrame {
		return 0, 0
	}
	// The first packet holds two frames, later packets one, so the first has one
	// header less to spend on data.
	maxFirst := available - 2*minFrame
	maxOther := available - minFrame
	if pending <= maxFirst {
		return 0, 0 // fits in a single packet
	}
	occupied := maxOther - maxFirst
	numPackets := (pending + occupied + maxOther - 1) / maxOther // ceil
	dataOther := (pending + occupied + numPackets - 1) / numPackets
	if dataOther < occupied {
		return 0, 0
	}
	dataFirst := dataOther - occupied

	first = protocol.ByteCount(chaosMinFirstFrameLen + rand(chaosFirstFrameLenRandom))
	if pending <= first || dataFirst <= first {
		return 0, 0
	}
	return first, dataFirst - first
}

// cryptoFrameHeaderLen is the encoded size of a CRYPTO frame header carrying the
// given offset and data length: type byte plus two varints.
func cryptoFrameHeaderLen(offset, dataLen protocol.ByteCount) protocol.ByteCount {
	return 1 + protocol.ByteCount(quicvarint.Len(uint64(offset))) +
		protocol.ByteCount(quicvarint.Len(uint64(dataLen)))
}

// chaosItem is one element of the shuffled packet payload: either a frame to
// encode, or a run of padding bytes.
type chaosItem struct {
	frame      wire.Frame
	paddingLen protocol.ByteCount
}

// appendChaosProtectedPayload writes the payload the way Chromium's
// QuicChaosProtector does: the CRYPTO data is cut into several frames, PING
// frames are mixed in, the padding is broken into runs, and the lot is shuffled.
//
// This denies a DPI box the ability to parse a ClientHello out of a single
// contiguous CRYPTO frame at a predictable offset, and is a fingerprint in its
// own right, since stock quic-go never does it.
//
// The encoded size is preserved exactly: every byte spent on extra frame headers
// and PINGs comes out of the padding budget. If the budget can't cover a step we
// stop splitting rather than overrun.
func (p *packetPacker) appendChaosProtectedPayload(raw []byte, pl payload, paddingLen protocol.ByteCount, v protocol.Version) ([]byte, error) {
	startLen := len(raw)

	// The ACK, if any, is written first and left out of the shuffle. Packets that
	// carry an ACK and no CRYPTO data never get here, see worthChaosProtecting.
	if pl.ack != nil {
		var err error
		if raw, err = pl.ack.Append(raw, v); err != nil {
			return nil, err
		}
	}

	crypto, others := splitCryptoFrames(pl.frames)
	budget := paddingLen

	crypto, budget = p.shredCryptoFrames(crypto, len(others), budget)

	items := make([]chaosItem, 0, len(crypto)+len(others)+2*chaosMaxPingFrames)
	for _, cf := range crypto {
		items = append(items, chaosItem{frame: cf})
	}
	for _, f := range others {
		items = append(items, chaosItem{frame: f.Frame})
	}

	// PING frames are one byte each and indistinguishable from padding to anything
	// not decrypting the packet.
	numPings := min(
		protocol.ByteCount(chaosMinPingFrames+p.rand.IntN(chaosMaxPingFrames-chaosMinPingFrames+1)),
		budget,
	)
	for range numPings {
		items = append(items, chaosItem{frame: &wire.PingFrame{}})
	}
	budget -= numPings

	// Padding is handed out across the frames accumulated so far, before they
	// are shuffled, so the run count tracks the frame count.
	for _, run := range p.spreadPadding(len(items), budget) {
		items = append(items, chaosItem{paddingLen: run})
	}

	p.rand.Shuffle(len(items), func(i, j int) { items[i], items[j] = items[j], items[i] })

	for _, item := range items {
		if item.frame != nil {
			var err error
			if raw, err = item.frame.Append(raw, v); err != nil {
				return nil, err
			}
			continue
		}
		raw = append(raw, make([]byte, item.paddingLen)...)
	}

	// The whole point is that the packet size is unchanged, so verify it.
	if written := protocol.ByteCount(len(raw) - startLen); written != pl.length+paddingLen {
		return nil, fmt.Errorf("packetPacker BUG: chaos-protected payload size inconsistent (expected %d, got %d bytes)", pl.length+paddingLen, written)
	}
	return raw, nil
}

// splitCryptoFrames partitions a frame list into CRYPTO frames and everything
// else. Only CRYPTO frames can be shredded, since CRYPTO carries an explicit
// offset and may legally be split at any byte boundary.
// worthChaosProtecting reports whether a packet is one the imitated client
// scrambles. It needs CRYPTO data to shred and padding to spend on the result,
// so an Initial carrying only an ACK goes out as a plain ACK and PADDING.
func worthChaosProtecting(pl payload, paddingLen protocol.ByteCount) bool {
	if paddingLen <= 0 {
		return false
	}
	for _, f := range pl.frames {
		if _, ok := f.Frame.(*wire.CryptoFrame); ok {
			return true
		}
	}
	return false
}

func splitCryptoFrames(frames []ackhandler.Frame) ([]*wire.CryptoFrame, []ackhandler.Frame) {
	crypto := make([]*wire.CryptoFrame, 0, len(frames))
	others := make([]ackhandler.Frame, 0, len(frames))
	for _, f := range frames {
		if cf, ok := f.Frame.(*wire.CryptoFrame); ok {
			crypto = append(crypto, cf)
			continue
		}
		others = append(others, f)
	}
	return crypto, others
}

// shredCryptoFrames makes a bounded number of attempts to split a randomly
// chosen frame, stopping early once the padding budget can no longer cover
// another frame header. Returns the new frame set and the remaining budget.
//
// The choice ranges over every frame in the packet, not just the splittable
// ones, so an attempt that lands elsewhere is simply spent. Always splitting
// the largest frame instead would produce evenly sized pieces, which is its own
// signature.
func (p *packetPacker) shredCryptoFrames(crypto []*wire.CryptoFrame, numOther int, budget protocol.ByteCount) ([]*wire.CryptoFrame, protocol.ByteCount) {
	if len(crypto) == 0 {
		return crypto, budget
	}
	// Ceiling on what one more frame header can cost, over the whole CRYPTO
	// range and computed once, so the stop condition doesn't drift as we split.
	lo, hi := crypto[0].Offset, protocol.ByteCount(0)
	for _, cf := range crypto {
		lo = min(lo, cf.Offset)
		hi = max(hi, cf.Offset+protocol.ByteCount(len(cf.Data)))
	}
	maxOverhead := cryptoFrameHeaderLen(hi, hi-lo)

	attempts := chaosMinAddedCryptoFrames + p.rand.IntN(chaosMaxAddedCryptoFrames-chaosMinAddedCryptoFrames+1)
	for range attempts {
		if budget < maxOverhead {
			break
		}
		idx := p.rand.IntN(len(crypto) + numOther)
		if idx >= len(crypto) {
			continue // landed on a frame we can't split
		}
		cf := crypto[idx]
		if len(cf.Data) <= 1 {
			continue
		}
		cut := 1 + p.rand.IntN(len(cf.Data)-1)
		a := &wire.CryptoFrame{Offset: cf.Offset, Data: cf.Data[:cut]}
		b := &wire.CryptoFrame{Offset: cf.Offset + protocol.ByteCount(cut), Data: cf.Data[cut:]}

		budget += cryptoFrameHeaderLen(cf.Offset, protocol.ByteCount(len(cf.Data)))
		budget -= cryptoFrameHeaderLen(a.Offset, protocol.ByteCount(len(a.Data)))
		budget -= cryptoFrameHeaderLen(b.Offset, protocol.ByteCount(len(b.Data)))

		crypto[idx] = a
		crypto = append(crypto, b)
	}
	return crypto, budget
}

// spreadPadding breaks a padding budget into runs summing to exactly the
// budget, so padding appears in several places rather than one contiguous
// block.
//
// Each of the numFrames positions in turn takes a uniformly random share of
// whatever is left, so the first runs tend to be large and later ones short or
// absent; any remainder becomes a final run. Splitting the budget evenly, or at
// sorted cut points, produces a visibly different mix of run lengths.
func (p *packetPacker) spreadPadding(numFrames int, budget protocol.ByteCount) []protocol.ByteCount {
	if budget <= 0 {
		return nil
	}
	runs := make([]protocol.ByteCount, 0, numFrames+1)
	for range numFrames {
		if budget <= 0 {
			break
		}
		n := protocol.ByteCount(p.rand.IntN(int(budget) + 1))
		if n <= 0 {
			continue
		}
		runs = append(runs, n)
		budget -= n
	}
	if budget > 0 {
		runs = append(runs, budget)
	}
	return runs
}
