# cvc5 WASM Server

A WebAssembly build of cvc5 SMT solver with a binary stdin/stdout protocol for in-process use via GraalWasm or wasmtime.

## Protocol

Query loop (options are provided before each query):

1. `[4-byte big-endian length][CLI options string]` (length = 0 means shutdown)
   - e.g., `--produce-models --full-saturate-quant`
   - Options are parsed: strip `--`, split on `=`, set as solver options
   - If no `=`, value defaults to `"true"`; `--lang=*` is skipped
2. `[4-byte big-endian length][SMT-LIB query bytes]`
   - Response: `[4-byte big-endian length][result bytes]`

Each query gets a fresh solver instance with the provided options applied.

## Prerequisites

- [Emscripten SDK](https://emscripten.org/docs/getting_started/downloads.html)
- [Binaryen](https://github.com/WebAssembly/binaryen) (for `wasm-opt`, `wasm-merge`, `wat2wasm`)

## Build

```bash
git submodule update --init --recursive
./build.sh
```

Produces:
- `cvc5_server_graal.wasm` — For GraalWasm (exception handling stripped)
- `cvc5_server_wasmtime.wasm` — For wasmtime (exnref exceptions)

## Test

```bash
# With wasmtime
scala-cli run test_server.sc -- cvc5_server_wasmtime.wasm

# With wasmtime (graal variant, EH stripped)
scala-cli run test_server.sc -- cvc5_server_graal.wasm

# With GraalVM Polyglot (in-process)
scala-cli run test_server.sc -- cvc5_server_graal.wasm --graalvm
```

## Deployment

Copy `cvc5_server_graal.wasm` to `$SIREUM_HOME/lib/cvc5_server.wasm` for use with Sireum Logika's `cvc5.wasm` solver.
# cvc5-wasm-server
