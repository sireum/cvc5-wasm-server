#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

CVC5_V=1.3.3
NPROC=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)

echo "=== Step 1: Clone and build cvc5 for WASM ==="
if [ ! -d cvc5 ]; then
  git clone --recursive https://github.com/cvc5/cvc5
fi
cd cvc5
git checkout "cvc5-$CVC5_V"
git submodule update --init --recursive

if [ ! -d wasm-standalone ]; then
  ./configure.sh \
    --static --static-binary --auto-download \
    --wasm=WASM \
    --wasm-flags='-fwasm-exceptions -s STANDALONE_WASM -s ALLOW_MEMORY_GROWTH=1' \
    --name=wasm-standalone
fi
cd wasm-standalone
make -j"$NPROC"
cmake --install . --prefix "$SCRIPT_DIR/cvc5/wasm-standalone/install"
cd "$SCRIPT_DIR"

echo "=== Step 2: Compile cvc5_server.cpp ==="
CVC5_INSTALL="$SCRIPT_DIR/cvc5/wasm-standalone/install"
CVC5_BUILD="$SCRIPT_DIR/cvc5/wasm-standalone"
CVC5_DEPS="$CVC5_BUILD/deps"

em++ -O2 -std=c++17 -fwasm-exceptions \
  cvc5_server.cpp \
  -I"$CVC5_INSTALL/include" \
  -I"$CVC5_DEPS/include" \
  -L"$CVC5_INSTALL/lib" \
  -L"$CVC5_BUILD/lib" \
  -L"$CVC5_DEPS/lib" \
  -lcvc5 -lcvc5parser -lcadical -lpicpoly -lpicpolyxx -lgmp \
  -s STANDALONE_WASM \
  -s ALLOW_MEMORY_GROWTH=1 \
  -o cvc5_server.wasm

echo "=== Step 3: Build env_stub.wasm ==="
wat2wasm --enable-all env_stub.wat -o env_stub.wasm

echo "=== Step 4: GraalWasm variant (strip EH) ==="
wasm-opt --strip-eh --all-features -O2 \
  cvc5_server.wasm -o cvc5_server_noeh.wasm
wasm-merge --all-features \
  cvc5_server_noeh.wasm cvc5 \
  env_stub.wasm env \
  -o cvc5_server_graal.wasm

echo "=== Step 5: wasmtime variant (exnref) ==="
wasm-opt --translate-to-exnref --emit-exnref --all-features -O2 \
  cvc5_server.wasm -o cvc5_server_exnref.wasm
wasm-merge --all-features \
  cvc5_server_exnref.wasm cvc5 \
  env_stub.wasm env \
  -o cvc5_server_wasmtime.wasm

echo "=== Build complete ==="
ls -lh cvc5_server_graal.wasm cvc5_server_wasmtime.wasm
