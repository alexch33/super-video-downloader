.PHONY: init
init:
	git submodule update --init
	go install golang.org/x/perf/cmd/benchstat@latest
	go install github.com/campoy/embedmd@latest
	$(MAKE) lib/libbrotli_cref.a

lib/libbrotli_cref.a: testdata/build_libbrotli_cref.sh \
	$(wildcard brotli-ref/c/common/*.c) \
	$(wildcard brotli-ref/c/dec/*.c) \
	$(wildcard brotli-ref/c/enc/*.c) \
	$(wildcard brotli-ref/c/common/*.h) \
	$(wildcard brotli-ref/c/dec/*.h) \
	$(wildcard brotli-ref/c/enc/*.h) \
	$(wildcard brotli-ref/c/include/brotli/*.h)
	testdata/build_libbrotli_cref.sh
