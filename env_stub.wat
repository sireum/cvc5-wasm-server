(module
  (func (export "emscripten_notify_memory_growth") (param i32) (nop))
  (func (export "__syscall_faccessat") (param i32 i32 i32 i32) (result i32) (i32.const -1))
  (func (export "__syscall_pipe") (param i32) (result i32) (i32.const -1))
)
