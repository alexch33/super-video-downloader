// SPDX-FileCopyrightText: 2026 The Pion community <https://pion.ly>
// SPDX-License-Identifier: MIT

// Package replaydetector provides packet replay detection algorithm.
package replaydetector

// ReplayDetector is the interface of sequence replay detector.
type ReplayDetector interface {
	// Check returns true if given sequence number is not replayed.
	// Call accept() to mark the packet is received properly.
	// The return value of accept() indicates whether the accepted packet is
	// has the latest observed sequence number.
	Check(seq uint64) (accept func() bool, ok bool)
}

// Token records the outcome of a replay check for one sequence number.
// The zero value is inert: accepting it does nothing.
type Token struct {
	seq    uint64
	passed bool
}

// Passed returns a Token recording that seq passed the replay check.
func Passed(seq uint64) Token {
	return Token{seq: seq, passed: true}
}

// Rejected returns an inert Token recording that seq failed the replay check.
// Accepting the returned token is a no-op.
func Rejected(seq uint64) Token {
	return Token{seq: seq}
}

// Seq returns the sequence number the Token was created for.
func (t Token) Seq() uint64 {
	return t.seq
}

// Passed returns whether the sequence number passed the replay check.
func (t Token) Passed() bool {
	return t.passed
}

// CheckAccepter is a two-phase sequence replay detector: CheckSeq reports
// whether a sequence number is acceptable, and Accept records it as seen.
//
// In a future major release, CheckAccepter will replace ReplayDetector.
// ReplayDetectors returned by New and WithWrap also implement
// CheckAccepter.
type CheckAccepter interface {
	// CheckSeq checks whether the given sequence number is not replayed and
	// returns a Token recording the outcome. The token can be passed to
	// Accept regardless of what the outcome of CheckSeq is.
	CheckSeq(seq uint64) Token
	// Accept marks a passed Token's sequence number as received properly.
	// Accepting a rejected or zero-value Token does nothing and reports
	// false. The return value indicates whether the packet was accepted and
	// has the latest observed sequence number.
	Accept(tok Token) (latest bool)
}

// nop is a no-op func that is returned in the case that Check() fails.
func nop() bool {
	return false
}

type slidingWindowDetector struct {
	latestSeq  uint64
	maxSeq     uint64
	windowSize uint
	mask       *fixedBigInt
}

// New creates ReplayDetector.
// Created ReplayDetector doesn't allow wrapping.
// It can handle monotonically increasing sequence number up to
// full 64bit number. It is suitable for DTLS replay protection.
func New(windowSize uint, maxSeq uint64) ReplayDetector {
	return &slidingWindowDetector{
		maxSeq:     maxSeq,
		windowSize: windowSize,
		mask:       newFixedBigInt(windowSize),
	}
}

func (d *slidingWindowDetector) Check(seq uint64) (func() bool, bool) {
	if !d.checkSeq(seq) {
		return nop, false
	}

	return func() bool {
		return d.acceptSeq(seq)
	}, true
}

func (d *slidingWindowDetector) CheckSeq(seq uint64) Token {
	if !d.checkSeq(seq) {
		return Rejected(seq)
	}

	return Passed(seq)
}

func (d *slidingWindowDetector) Accept(tok Token) bool {
	if !tok.Passed() {
		return false
	}

	return d.acceptSeq(tok.Seq())
}

func (d *slidingWindowDetector) checkSeq(seq uint64) bool {
	if seq > d.maxSeq {
		// Exceeded upper limit.
		return false
	}

	if seq <= d.latestSeq {
		if d.latestSeq >= uint64(d.windowSize)+seq {
			return false
		}
		if d.mask.Bit(uint(d.latestSeq-seq)) != 0 {
			// The sequence number is duplicated.
			return false
		}
	}

	return true
}

func (d *slidingWindowDetector) acceptSeq(seq uint64) bool {
	latest := seq == 0
	if seq > d.latestSeq {
		// Update the head of the window.
		d.mask.Lsh(uint(seq - d.latestSeq))
		d.latestSeq = seq
		latest = true
	}
	diff := (d.latestSeq - seq) % d.maxSeq
	d.mask.SetBit(uint(diff))

	return latest
}

// WithWrap creates ReplayDetector allowing sequence wrapping.
// This is suitable for short bit width counter like SRTP and SRTCP.
func WithWrap(windowSize uint, maxSeq uint64) ReplayDetector {
	return &wrappedSlidingWindowDetector{
		maxSeq:     maxSeq,
		windowSize: windowSize,
		mask:       newFixedBigInt(windowSize),
	}
}

type wrappedSlidingWindowDetector struct {
	latestSeq  uint64
	maxSeq     uint64
	windowSize uint
	mask       *fixedBigInt
	init       bool
}

func (d *wrappedSlidingWindowDetector) Check(seq uint64) (func() bool, bool) {
	diff, ok := d.checkSeq(seq)
	if !ok {
		return nop, false
	}

	return func() bool {
		// The closure reuses the check-time diff so that accepts invoked out of
		// order behave exactly as Check's callers have historically observed,
		// quirks included. Accept recomputes the diff at accept time instead.
		return d.acceptDiff(seq, diff)
	}, true
}

func (d *wrappedSlidingWindowDetector) CheckSeq(seq uint64) Token {
	if _, ok := d.checkSeq(seq); !ok {
		return Rejected(seq)
	}

	return Passed(seq)
}

func (d *wrappedSlidingWindowDetector) Accept(tok Token) bool {
	if !tok.Passed() {
		return false
	}

	// NOTE: Recompute diff at accept time (rather than storing on the token)
	// so that it's computed from the correct latestSeq in case accepts are
	// called out of order.
	return d.acceptDiff(tok.Seq(), d.diff(tok.Seq()))
}

func (d *wrappedSlidingWindowDetector) checkSeq(seq uint64) (int64, bool) {
	if seq > d.maxSeq {
		// Exceeded upper limit.
		return 0, false
	}
	if !d.init {
		if seq != 0 {
			d.latestSeq = seq - 1
		} else {
			d.latestSeq = d.maxSeq
		}
		d.init = true
	}

	diff := d.diff(seq)

	if diff >= int64(d.windowSize) { //nolint:gosec // GG115 TODO check
		// Too old.
		return diff, false
	}
	if diff >= 0 {
		if d.mask.Bit(uint(diff)) != 0 {
			// The sequence number is duplicated.
			return diff, false
		}
	}

	return diff, true
}

func (d *wrappedSlidingWindowDetector) acceptDiff(seq uint64, diff int64) bool {
	if diff < 0 {
		// Update the head of the window.
		d.mask.Lsh(uint(-diff))
		d.latestSeq = seq
		d.mask.SetBit(0)

		return true
	}
	d.mask.SetBit(uint(diff))

	return false
}

// diff returns the distance of seq behind the latest observed sequence
// number, wrapped around maxSeq to the shorter direction (negative means
// seq is ahead of the window head).
func (d *wrappedSlidingWindowDetector) diff(seq uint64) int64 {
	diff := int64(d.latestSeq) - int64(seq) //nolint:gosec // GG115 TODO check
	// Wrap the number.
	if diff > int64(d.maxSeq)/2 { //nolint:gosec // GG115 TODO check
		diff -= int64(d.maxSeq + 1) //nolint:gosec // GG115 TODO check
	} else if diff <= -int64(d.maxSeq)/2 { //nolint:gosec // GG115 TODO check
		diff += int64(d.maxSeq + 1) //nolint:gosec // GG115 TODO check
	}

	return diff
}
