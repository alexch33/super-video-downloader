![go brrr](assets/go-brrr-logo.jpg)

# go-brrr - Brotli compression for Go

[![CI](https://github.com/molecule-man/go-brrr/actions/workflows/ci.yml/badge.svg)](https://github.com/molecule-man/go-brrr/actions/workflows/ci.yml)
[![Go Reference](https://pkg.go.dev/badge/github.com/molecule-man/go-brrr.svg)](https://pkg.go.dev/github.com/molecule-man/go-brrr)
[![Go Report Card](https://goreportcard.com/badge/github.com/molecule-man/go-brrr)](https://goreportcard.com/report/github.com/molecule-man/go-brrr)
[![Version](https://img.shields.io/github/v/tag/molecule-man/go-brrr?sort=semver)](https://github.com/molecule-man/go-brrr/tags)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Brotli compression library for Go (RFC 7932), with encoder and decoder support.

## Highlights

- **No C toolchain.** Builds with standard Go tooling.
- **Faster than other pure-Go brotli libraries** at every quality level we measure (see [Benchmarks](#benchmarks)).
- **Faster than CGO brotli** (cbrotli) in our benchmarks, for compression at q0-q11 and for one-shot decompression.
- **Compound dictionaries.**
- **Encoder tuning.** `LGWin` (window size) and `SizeHint` (expected total input size) are exposed via `WriterOptions`. `SizeHint` lets the encoder pick context modeling and hasher parameters tuned for the actual payload size.

## Status

The encoder and decoder are covered by compatibility tests and fuzzing. The public API is stable and follows semantic versioning.

## Compatibility

go-brrr implements Brotli RFC 7932 and is tested against the Brotli reference corpus. Encoded output is byte-compatible with the C reference implementation.

## Compared to other Go brotli libraries

| | go-brrr | [andybalholm](https://github.com/andybalholm/brotli) | [google/brotli/go/brotli](https://github.com/google/brotli/tree/master/go/brotli) | [cbrotli](https://github.com/google/brotli/tree/master/go/cbrotli) |
|---|---|---|---|---|
| Pure Go (no cgo) | ✓ | ✓ | ✓ | ✗ |
| Encoder | ✓ | ✓ | ✗ | ✓ |
| Decoder | ✓ | ✓ | ✓ | ✓ |
| Compound dictionaries (encode) | ✓ | ✗ | n/a | ✓ |
| Compound dictionaries (decode) | ✓ | ✗ | ✓ | ✓ |
| `LGWin` tuning | ✓ | ✓ | n/a | ✓ |
| `SizeHint` | ✓ | ✗ | n/a | ✗ |
| Writer `Reset` | ✓ | ✓ | n/a | ✗ |
| Reader `Reset` | ✓ | ✓ | ✗ | ✗ |

If you're using `andybalholm/brotli`, go-brrr is a near drop-in upgrade with higher throughput on both compression and decompression, plus compound-dictionary and `SizeHint` support. If you're using `cbrotli`, go-brrr gives up cgo, adds multi-chunk compound dictionaries, and exposes poolable `Writer`/`Reader` instances. `cbrotli` has no `Reset`, so each stream allocates a fresh encoder/decoder state, which is noticeable on many-small-file workloads.

## Install

```sh
go get github.com/molecule-man/go-brrr
```

```go
import "github.com/molecule-man/go-brrr"
```

The import path is `github.com/molecule-man/go-brrr`; the package name is `brrr`.

## Examples

### Compression

[embedmd]:# (example_test.go go /func Example_compress/ /^}/)
```go
func Example_compress() {
	input := []byte("Hello, brotli!")

	var compressed bytes.Buffer
	w, err := brrr.NewWriter(&compressed, 6)
	if err != nil {
		log.Fatal(err)
	}
	if _, err := w.Write(input); err != nil {
		log.Fatal(err)
	}
	if err := w.Close(); err != nil {
		log.Fatal(err)
	}
}
```

More examples are available in [example_test.go](example_test.go) and the [Go package docs](https://pkg.go.dev/github.com/molecule-man/go-brrr#pkg-examples): round-trip compression and decompression, one-shot decompression, reusing writers and readers, pooling, and compound dictionaries.

## When to use go-brrr

The best use case for brotli is **static asset compression** - CSS, JS, HTML, fonts, WASM - where you compress once at build time and serve the result millions of times. Use **quality 11** for this: speed doesn't matter because you pay the cost once, and brotli q11 delivers ratios that neither gzip nor zstd can match. Every browser shipped since 2016 supports `Content-Encoding: br`.

For on-the-fly compression, brotli q5–6 is a strong choice if you're already using zstd at its highest level: q5 is often **faster** with a **better ratio**, and q6 is only slightly slower with an even better ratio. At lower compression levels, zstd is significantly faster - if throughput is your priority and you don't need the best ratio, zstd is the better tool for the job.

If you compress or decompress repeatedly (e.g. per request in a webserver), keep `*brrr.Writer` and `*brrr.Reader` instances in `sync.Pool`s and `Reset` each one into the next stream rather than allocating new instances each time. See the [compiled examples](example_test.go).

## Implementation notes

go-brrr is optimized for throughput. Some hot paths intentionally use larger functions, duplicated loops, and specialized code where benchmarks showed measurable wins. These choices stay local to performance-sensitive encoder and decoder internals; public APIs stay small and conventional.

## Contributing

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before you start.

The most important rules:

- **One pull request is one logical change.** Independent optimizations go into separate pull requests, so each one can be benchmarked, reverted, and bisected on its own.
- **Explain the expected effect and its cause.**

The maintainer measures every performance change against a file corpus on dedicated hardware.

For a large optimization, open an issue first and agree on the scope.

## Acknowledgments

This library is a port of the [Brotli reference implementation](https://github.com/google/brotli) by the Brotli Authors, licensed under the MIT License.

## Compression Speed vs Ratio

All benchmarks were taken on the following setup with turboboost, etc, being
disabled via [denoise-amd.sh](scripts/denoise-amd.sh):

```
goos: linux
goarch: amd64
cpu: AMD Ryzen 5 7535HS with Radeon Graphics
```

Compared against [klauspost/compress](https://github.com/klauspost/compress) zstd (pure Go) and stdlib gzip. Single CPU, no parallelism. These plots measure reused streaming encoders: the timed loop resets a warmed writer and discards compressed output, while ratio is measured from a warmup buffer.

| Compression | Decompression |
|---|---|
| ![HTML 522KB](assets/gh_522KB_html.png) | ![HTML 522KB](assets/gh_522KB_html_decompress.png) |
| ![JS 187KB](assets/reactcore_187KB_js.png) | ![JS 187KB](assets/reactcore_187KB_js_decompress.png) |
| ![JSON 58KB](assets/github_events_58KB_json.png) | ![JSON 58KB](assets/github_events_58KB_json_decompress.png) |

## Benchmarks

Compared against other Go brotli libraries. **go-brrr** is the base in all comparisons. The smaller the number the better.

- **andybalholm** - [github.com/andybalholm/brotli](https://github.com/andybalholm/brotli), pure Go encoder and decoder.
- **google-brotli** - [github.com/google/brotli/go/brotli](https://github.com/google/brotli/tree/master/go/brotli), Google's official pure Go decoder, transpiled from the Java reference. Decompression only, no encoder.
- **cbrotli** - [github.com/google/brotli/go/cbrotli](https://github.com/google/brotli/tree/master/go/cbrotli), Google's official cgo bindings to the C reference implementation. Including a cgo library in a pure Go comparison isn't apples-to-apples, but it is a useful comparison against Google's C implementation as exposed through its Go bindings.

### One-shot Compression

The table below measures end-to-end throughput through each package's public Go API for many independent brotli streams, not only the inner compression loop. Each payload is written as a complete stream with a fresh public writer instance so `cbrotli`, which has no resettable writer API, can be included.

`go-brrr` still benefits from internal reuse in that shape: encoder arenas, hashers, hash tables, and scratch buffers are kept reusable through reset paths and internal `sync.Pool`s. That avoids repeated large allocations and zeroing, which matters for small and mid-size payloads. `cbrotli` uses the C reference encoder underneath, but each payload creates a new `BrotliEncoderState` through `cbrotli.NewWriter` and destroys it on `Close`, paying setup, teardown, cgo, and allocation costs for every stream.

Read these rows as repeated complete-stream compression through the Go APIs. They are not a claim that every pure-Go hot path is faster than the C implementation. Part of the gap comes from per-stream setup and cgo cost in `cbrotli`.

<!-- bench:compress -->
| | go-brrr (sec/op) | andybalholm (sec/op) | cbrotli (sec/op) |
| --- | --- | --- | --- |
| CompressOneshot/q=0/payload=VariedPayloads | 6.342m ± 0% | 12.447m ± 1%   +96.25% (p=0.000) | 6.868m ± 0%    +8.29% (p=0.000) |
| CompressOneshot/q=1/payload=VariedPayloads | 9.530m ± 0% | 20.163m ± 0%  +111.57% (p=0.000) | 10.824m ± 0%   +13.58% (p=0.000) |
| CompressOneshot/q=2/payload=VariedPayloads | 14.74m ± 0% | 39.29m ± 5%  +166.60% (p=0.000) | 18.09m ± 0%   +22.76% (p=0.000) |
| CompressOneshot/q=3/payload=VariedPayloads | 15.89m ± 0% | 43.29m ± 5%  +172.39% (p=0.000) | 20.94m ± 0%   +31.77% (p=0.000) |
| CompressOneshot/q=4/payload=VariedPayloads | 25.07m ± 0% | 62.03m ± 2%  +147.45% (p=0.000) | 30.13m ± 0%   +20.19% (p=0.000) |
| CompressOneshot/q=5/payload=VariedPayloads | 35.77m ± 0% | 80.57m ± 1%  +125.28% (p=0.000) | 47.34m ± 0%   +32.37% (p=0.000) |
| CompressOneshot/q=6/payload=VariedPayloads | 43.44m ± 0% | 90.94m ± 1%  +109.32% (p=0.000) | 54.76m ± 0%   +26.05% (p=0.000) |
| CompressOneshot/q=7/payload=VariedPayloads | 50.07m ± 0% | 125.95m ± 1%  +151.52% (p=0.000) | 107.23m ± 0%  +114.15% (p=0.000) |
| CompressOneshot/q=8/payload=VariedPayloads | 58.44m ± 1% | 146.65m ± 1%  +150.97% (p=0.000) | 82.10m ± 0%   +40.49% (p=0.000) |
| CompressOneshot/q=9/payload=VariedPayloads | 73.59m ± 0% | 209.19m ± 1%  +184.26% (p=0.000) | 232.63m ± 0%  +216.11% (p=0.000) |
| CompressOneshot/q=10/payload=VariedPayloads | 814.9m ± 0% | 1346.7m ± 2%   +65.27% (p=0.000) | 856.2m ± 1%    +5.08% (p=0.000) |
| CompressOneshot/q=11/payload=VariedPayloads | 1.587 ± 0% | 3.365 ± 1%  +112.05% (p=0.000) | 2.266 ± 0%   +42.77% (p=0.000) |
| **geomean** | 48.10m | 110.7m       +130.18% | 67.37m        +40.05% |
<!-- /bench:compress -->

*Streaming* uses `brrr.NewReader` + `io.ReadAll`; *one-shot* uses `brrr.Decompress` on a complete in-memory blob.

### Streaming Decompression

As cbrotli doesn't have the "resettable" API it's not included here.

<!-- bench:decompress -->
| | go-brrr (sec/op) | andybalholm (sec/op) | diff | p |
| --- | --- | --- | --- | --- |
| Decompress/q=4/payload=VariedPayloads | 4.373m ± 0% | 9.593m ± 0% | +119.38% | 0.000 |
| Decompress/q=5/payload=VariedPayloads | 4.181m ± 0% | 9.183m ± 0% | +119.62% | 0.000 |
| Decompress/q=6/payload=VariedPayloads | 4.052m ± 0% | 8.914m ± 0% | +119.97% | 0.000 |
| Decompress/q=11/payload=VariedPayloads | 4.645m ± 0% | 8.925m ± 0% | +92.16% | 0.000 |
| **geomean** | 4.307m | 9.150m | +112.43% |  |
<!-- /bench:decompress -->

### One-shot Decompression

<!-- bench:decompresso -->
| | go-brrr (sec/op) | andybalholm (sec/op) | cbrotli (sec/op) | google-brotli (sec/op) |
| --- | --- | --- | --- | --- |
| DecompressOneshot/q=4/payload=VariedPayloads | 4.549m ± 0% | 10.082m ± 0%  +121.63% (p=0.000) | 5.286m ± 2%  +16.21% (p=0.000) | 10.470m ± 0%  +130.16% (p=0.000) |
| DecompressOneshot/q=5/payload=VariedPayloads | 4.433m ± 0% | 9.611m ± 0%  +116.82% (p=0.000) | 5.023m ± 1%  +13.32% (p=0.000) | 10.549m ± 0%  +137.98% (p=0.000) |
| DecompressOneshot/q=6/payload=VariedPayloads | 4.318m ± 0% | 9.415m ± 0%  +118.05% (p=0.000) | 4.936m ± 1%  +14.31% (p=0.000) | 10.176m ± 0%  +135.69% (p=0.000) |
| DecompressOneshot/q=11/payload=VariedPayloads | 4.904m ± 0% | 9.377m ± 0%   +91.22% (p=0.000) | 6.534m ± 0%  +33.25% (p=0.000 | **crashed** |
| **geomean** | 4.546m | 9.617m       +111.57% | 5.410m       +19.01% | 10.40m       +134.59%                 |
<!-- /bench:decompresso -->


The `VariedPayloads` benchmark rotates through a heterogeneous mix of files, guarding against benchmark-shaped optimizations - wins that only show up when the same input is fed back-to-back should not move these rows. Payloads span small JSON API responses, mid-size HTML and JS bundles, and larger English prose, drawn from the [Brotli reference test corpus](https://github.com/google/brotli/tree/master/tests/testdata) and the local [testdata/](testdata/) directory.

| File                  | Size   | Source     |
|-----------------------|-------:|------------|
| github_events_2k.json | 2.2 KB | testdata   |
| github_events_5k.json | 5.2 KB | testdata   |
| github_events_8k.json | 8.3 KB | testdata   |
| asyoulik.txt          | 122 KB | brotli-ref |
| alice29.txt           | 149 KB | brotli-ref |
| gh_172KB.html         | 167 KB | testdata   |
| reactcore_187KB.js    | 182 KB | testdata   |
| lcet10.txt            | 417 KB | brotli-ref |
| plrabn12.txt          | 471 KB | brotli-ref |
