// Unsafe fast path for little-endian platforms: load primitives and bit
// writer hot loops that rely on unaligned uint32/uint64 reads and writes.

//go:build !purego && (amd64 || 386 || arm64 || loong64 || ppc64le || wasm)

package encoder

import "unsafe"

//go:nosplit
func loadByte(b []byte, i uint) byte {
	return *(*byte)(unsafe.Add(unsafe.Pointer(unsafe.SliceData(b)), i))
}

//go:nosplit
func loadU32LE(b []byte, i uint) uint32 {
	return *(*uint32)(unsafe.Add(unsafe.Pointer(unsafe.SliceData(b)), i))
}

//go:nosplit
func loadU64LE(b []byte, i uint) uint64 {
	return *(*uint64)(unsafe.Add(unsafe.Pointer(unsafe.SliceData(b)), i))
}

// writeBits packs value into the bitstream and advances the bit position.
// Up to 56 bits may be written at a time.
func (b *bitWriter) writeBits(nbits uint, value uint64) {
	bytePos := b.bitOffset >> 3
	bitOff := b.bitOffset & 7
	p := (*uint64)(unsafe.Add(unsafe.Pointer(unsafe.SliceData(b.buf)), bytePos))
	*p = uint64(*(*byte)(unsafe.Pointer(p))) | value<<bitOff
	b.bitOffset += nbits
}

// writeBitsAt is the variant of writeBits used by hot loops (huffmanBlock.
// writeData) that keep both the output buffer and the bit position in
// locals across many calls. It takes the buffer and current bitOffset and
// returns the updated bitOffset, so the caller can hold the slice header and
// the offset in registers instead of round-tripping them through the
// bitWriter on every writeBits.
//
//go:nosplit
func writeBitsAt(buf []byte, bitOffset, nbits uint, value uint64) uint {
	bytePos := bitOffset >> 3
	bitOff := bitOffset & 7
	p := (*uint64)(unsafe.Add(unsafe.Pointer(unsafe.SliceData(buf)), bytePos))
	*p = uint64(*(*byte)(unsafe.Pointer(p))) | value<<bitOff
	return bitOffset + nbits
}

func (b *bitWriter) writeLiteralBits(input []byte, depths *[256]byte, bits *[256]uint16) {
	bufBase := unsafe.Pointer(unsafe.SliceData(b.buf))
	bitOffset := b.bitOffset
	for _, lit := range input {
		bytePos := bitOffset >> 3
		bitOff := bitOffset & 7
		p := (*uint64)(unsafe.Add(bufBase, bytePos))
		*p = uint64(*(*byte)(unsafe.Pointer(p))) | uint64(bits[lit])<<bitOff
		bitOffset += uint(depths[lit])
	}
	b.bitOffset = bitOffset
}
