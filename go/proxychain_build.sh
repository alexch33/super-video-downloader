#!/usr/bin/env bash
set -e

echo "Build script started."
echo "Go version: $(go version)"

# --- Configuration ---
# Update with your NDK path if different
NDK_PATH="$HOME/Android/Sdk/ndk/29.0.14206865"
API_LEVEL=24

# --- Get Absolute Path of the Script's Directory ---
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
GO_SOURCE_FILE="$SCRIPT_DIR/proxychain.go"
OUTPUT_BASE_DIR="$SCRIPT_DIR/../app/src/main/jniLibs"

# --- Pre-flight Checks ---
if [ ! -f "$GO_SOURCE_FILE" ]; then
    echo "Error: Go source file not found at '$GO_SOURCE_FILE'"
    exit 1
fi
echo "Go source file found: $GO_SOURCE_FILE"
echo "Output directory base: $OUTPUT_BASE_DIR"

# --- Toolchain Setup ---
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64"

# --- Build Loop ---
TARGETS=(
    "arm64;arm64-v8a;aarch64-linux-android"
    "arm;armeabi-v7a;armv7a-linux-androideabi"
    "amd64;x86_64;x86_64-linux-android"
    "386;x86;i686-linux-android"
)

LDFLAGS="-s -w -buildid="

for target in "${TARGETS[@]}"; do
    # Split the tuple into individual variables
    IFS=';' read -r GOARCH ABI_DIR COMPILER_PREFIX <<< "$target"

    echo "============================================================"
    echo "Building for GOARCH=$GOARCH (ABI: $ABI_DIR)"
    echo "============================================================"

    # Define the C compiler for the target
    C_COMPILER="${TOOLCHAIN}/bin/${COMPILER_PREFIX}${API_LEVEL}-clang"
    C_FLAGS="--sysroot=${TOOLCHAIN}/sysroot"

    # Check if the required C compiler exists
    if [ ! -x "$C_COMPILER" ]; then
        echo "Error: C Compiler not found or not executable at '$C_COMPILER'"
        exit 1
    fi

    OUTPUT_DIR="${OUTPUT_BASE_DIR}/${ABI_DIR}"
    mkdir -p "${OUTPUT_DIR}"

    # Execute the build command with all variables inlined for robustness
    CGO_ENABLED=1 \
    GOOS=android \
    GOARCH="$GOARCH" \
    CC="$C_COMPILER" \
    CGO_CFLAGS="$C_FLAGS" \
    go build -v -buildmode=c-shared -tags netgo -ldflags="$LDFLAGS" -o "${OUTPUT_DIR}/libproxychain.so" "$GO_SOURCE_FILE"
done

echo "============================================================"
echo "All architectures built successfully!"
echo "============================================================"
