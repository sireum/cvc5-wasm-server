#!/usr/bin/env -S scala-cli shebang
//> using scala 2.13
//> using jvm graalvm-community:25.0.2
//> using javaOpt --enable-native-access=ALL-UNNAMED
//> using dep org.graalvm.polyglot:polyglot:25.0.2
//> using dep org.graalvm.wasm:wasm-language:25.0.2
//> using dep org.graalvm.truffle:truffle-runtime:25.0.2
// Test cvc5_server via length-prefixed stdin/stdout protocol with per-query opts.
//
// Usage:
//     scala-cli test_server.sc -- [binary] [--graalvm]
//
// binary defaults to cvc5_server_wasmtime.wasm.
// Without --graalvm: runs via wasmtime subprocess (EH flags auto-detected from filename).
// With --graalvm: runs in-process via GraalVM Polyglot/GraalWasm (interpreter mode).

import java.io.{ByteArrayOutputStream, InputStream, OutputStream, PipedInputStream, PipedOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.locks.ReentrantLock

// --- Protocol helpers ---

def writeU32(os: OutputStream, v: Int): Unit = {
  os.write((v >> 24) & 0xFF)
  os.write((v >> 16) & 0xFF)
  os.write((v >> 8) & 0xFF)
  os.write(v & 0xFF)
  os.flush()
}

def readU32(is: InputStream): Int = {
  val buf = new Array[Byte](4)
  var n = 0
  while (n < 4) {
    val r = is.read(buf, n, 4 - n)
    if (r < 0) return -1
    n += r
  }
  ((buf(0) & 0xFF) << 24) | ((buf(1) & 0xFF) << 16) |
    ((buf(2) & 0xFF) << 8) | (buf(3) & 0xFF)
}

def sendMsg(os: OutputStream, data: String): Unit = {
  val bytes = data.getBytes(StandardCharsets.UTF_8)
  writeU32(os, bytes.length)
  os.write(bytes)
  os.flush()
}

def recvResponse(is: InputStream): Option[String] = {
  val len = readU32(is)
  if (len < 0) return None
  if (len == 0) return Some("")
  val buf = new Array[Byte](len)
  var n = 0
  while (n < len) {
    val r = is.read(buf, n, len - n)
    if (r < 0) return Some(new String(buf, 0, n, StandardCharsets.UTF_8))
    n += r
  }
  Some(new String(buf, StandardCharsets.UTF_8))
}

def sendQuery(os: OutputStream, opts: String, query: String): Unit = {
  sendMsg(os, opts)
  sendMsg(os, query)
}

// --- Server abstraction ---

trait Server {
  def outputStream: OutputStream
  def inputStream: InputStream
  def shutdown(): Unit
}

class WasmtimeServer(binary: String) extends Server {
  private val wasmtimeFlags =
    if (binary.contains("graal")) Seq.empty[String]
    else Seq("-W", "exceptions,function-references,gc")
  private val cmd = Seq("wasmtime", "run") ++ wasmtimeFlags ++ Seq(binary)
  private val proc = new ProcessBuilder(cmd: _*)
    .redirectError(ProcessBuilder.Redirect.PIPE)
    .start()

  def outputStream: OutputStream = proc.getOutputStream
  def inputStream: InputStream = proc.getInputStream

  def shutdown(): Unit = {
    writeU32(proc.getOutputStream, 0)
    proc.getOutputStream.flush()
    proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
    val baos = new ByteArrayOutputStream()
    val buf = new Array[Byte](1024)
    val es = proc.getErrorStream
    var n = es.read(buf)
    while (n >= 0) { baos.write(buf, 0, n); n = es.read(buf) }
    val stderr = new String(baos.toByteArray, StandardCharsets.UTF_8).trim
    if (stderr.nonEmpty) println(s"  Stderr: ${stderr.take(500)}")
  }
}

/** Ring buffer with ReentrantLock + Condition (avoids PipedInputStream 1-second polling). */
class FastPipe(capacity: Int = 1024 * 1024) {
  private val buf = new Array[Byte](capacity)
  private var readPos = 0
  private var writePos = 0
  private var count = 0
  private var closed = false
  private val lock = new ReentrantLock()
  private val notEmpty = lock.newCondition()
  private val notFull = lock.newCondition()

  val inputStream: InputStream = new InputStream {
    override def read(): Int = {
      lock.lock()
      try {
        while (count == 0 && !closed) notEmpty.await()
        if (count == 0) return -1
        val b = buf(readPos) & 0xFF
        readPos = (readPos + 1) % capacity
        count -= 1
        notFull.signal()
        b
      } finally lock.unlock()
    }
    override def read(b: Array[Byte], off: Int, len: Int): Int = {
      if (len == 0) return 0
      lock.lock()
      try {
        while (count == 0 && !closed) notEmpty.await()
        if (count == 0) return -1
        val n = math.min(len, count)
        var i = 0
        while (i < n) {
          b(off + i) = buf(readPos)
          readPos = (readPos + 1) % capacity
          i += 1
        }
        count -= n
        notFull.signal()
        n
      } finally lock.unlock()
    }
  }

  val outputStream: OutputStream = new OutputStream {
    override def write(b: Int): Unit = {
      lock.lock()
      try {
        while (count == capacity && !closed) notFull.await()
        if (closed) throw new java.io.IOException("Pipe closed")
        buf(writePos) = b.toByte
        writePos = (writePos + 1) % capacity
        count += 1
        notEmpty.signal()
      } finally lock.unlock()
    }
    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      if (len == 0) return
      lock.lock()
      try {
        var written = 0
        while (written < len) {
          while (count == capacity && !closed) notFull.await()
          if (closed) throw new java.io.IOException("Pipe closed")
          val space = capacity - count
          val n = math.min(len - written, space)
          var i = 0
          while (i < n) {
            buf(writePos) = b(off + written + i)
            writePos = (writePos + 1) % capacity
            i += 1
          }
          count += n
          written += n
          notEmpty.signal()
        }
      } finally lock.unlock()
    }
    override def flush(): Unit = {}
  }

  def close(): Unit = {
    lock.lock()
    try {
      closed = true
      notEmpty.signalAll()
      notFull.signalAll()
    } finally lock.unlock()
  }
}

class GraalVmServer(binary: String) extends Server {
  private val stdinPipe = new FastPipe()
  private val stdoutPipe = new FastPipe()
  @volatile private var alive = false
  @volatile private var error: String = _

  private val thread = new Thread(new Runnable {
    def run(): Unit = {
      var context: org.graalvm.polyglot.Context = null
      try {
        val wasmBytes = Files.readAllBytes(Paths.get(binary))
        val source = org.graalvm.polyglot.Source.newBuilder(
          "wasm",
          org.graalvm.polyglot.io.ByteSequence.create(wasmBytes),
          "cvc5_server"
        ).build()
        context = org.graalvm.polyglot.Context.newBuilder("wasm")
          .option("wasm.Builtins", "wasi_snapshot_preview1")
          .option("wasm.WasiConstantRandomGet", "true")
          .option("engine.WarnInterpreterOnly", "false")
          .arguments("wasm", Array("cvc5_server"))
          .in(stdinPipe.inputStream)
          .out(stdoutPipe.outputStream)
          .err(System.err)
          .allowAllAccess(true)
          .build()
        val module = context.eval(source)
        val instance = module.newInstance()
        val exports = instance.getMember("exports")
        val startFn = exports.getMember("_start")
        alive = true
        startFn.executeVoid()
      } catch {
        case e: org.graalvm.polyglot.PolyglotException if e.isExit && e.getExitStatus == 0 => // normal
        case e: org.graalvm.polyglot.PolyglotException if e.isExit =>
          error = s"cvc5 exited with code ${e.getExitStatus}"
        case e: Throwable =>
          error = s"Error: ${e.getMessage}"
      } finally {
        alive = false
        try { stdoutPipe.close() } catch { case _: Throwable => }
        if (context != null) {
          try { context.close() } catch { case _: Throwable => }
        }
      }
    }
  })
  thread.setDaemon(true)
  thread.setName("cvc5-graalwasm")
  thread.start()

  // Wait for WASM module to load and start
  private val deadline = System.currentTimeMillis() + 60000
  while (!alive && error == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
  if (!alive && error != null) {
    System.err.println(s"GraalVM server failed to start: $error")
    sys.exit(1)
  }
  if (!alive) {
    System.err.println("GraalVM server timed out during startup (60s)")
    sys.exit(1)
  }

  def outputStream: OutputStream = stdinPipe.outputStream
  def inputStream: InputStream = stdoutPipe.inputStream

  def shutdown(): Unit = {
    writeU32(stdinPipe.outputStream, 0)
    stdinPipe.outputStream.flush()
    thread.join(10000)
  }
}

// --- Test runner ---

case class Query(label: String, expected: String, query: String)

def runQueries(server: Server, opts: String, queries: Seq[Query]): Boolean = {
  var allPass = true
  for (q <- queries) {
    val t0 = System.nanoTime()
    sendQuery(server.outputStream, opts, q.query)
    val result = recvResponse(server.inputStream)
    val elapsed = (System.nanoTime() - t0) / 1000000L
    result match {
      case None =>
        println(s"  FAIL  ${q.label}: no response")
        return false
      case Some(r) =>
        val ok = r.contains(q.expected)
        if (!ok) allPass = false
        val display = r.replace('\n', '|').take(120)
        val status = if (ok) "PASS" else "FAIL"
        println(s"  $status  ${q.label}  (${elapsed}ms)  ->  $display")
    }
  }
  allPass
}

// --- Test data ---

val coreQueries = Seq(
  Query("Simple SAT", "sat",
    "(set-logic ALL) (declare-const x Int) (assert (> x 0)) (check-sat)"),
  Query("Simple UNSAT", "unsat",
    "(set-logic ALL) (assert false) (check-sat)"),
  Query("QF_LIA model", "sat",
    "(set-logic QF_LIA)" +
      " (declare-const x Int) (declare-const y Int)" +
      " (assert (= (+ x y) 10)) (assert (= (- x y) 4))" +
      " (check-sat) (get-model)"),
  Query("QF_BV 32-bit", "sat",
    "(set-logic QF_BV)" +
      " (declare-const x (_ BitVec 32))" +
      " (assert (= (bvadd x #x00000001) #x00000000))" +
      " (check-sat) (get-value (x))"),
  Query("Quantified", "sat",
    "(set-logic ALL)" +
      " (assert (forall ((x Int)) (>= (* x x) 0)))" +
      " (check-sat)"),
  Query("QF_LIA 20 vars", "sat",
    "(set-logic QF_LIA) " +
      (1 to 20).map(i => s"(declare-const x$i Int)").mkString(" ") + " " +
      (1 to 20).map(i => s"(assert (>= x$i 0)) (assert (<= x$i 100))").mkString(" ") + " " +
      (1 to 19).map(i => s"(assert (< x$i x${i + 1}))").mkString(" ") + " " +
      "(assert (= (+ x1 x20) 50)) (check-sat)"),
  Query("QF_BV 64-bit multiply", "sat",
    "(set-logic QF_BV)" +
      " (declare-const a (_ BitVec 64)) (declare-const b (_ BitVec 64))" +
      " (assert (= (bvmul a b) #x00000000DEADBEEF))" +
      " (assert (bvugt a #x0000000000000001))" +
      " (assert (bvugt b #x0000000000000001))" +
      " (check-sat)"),
  Query("Arrays + BV", "sat",
    "(set-logic QF_ABV)" +
      " (declare-const a (Array (_ BitVec 32) (_ BitVec 8)))" +
      " (declare-const i (_ BitVec 32)) (declare-const j (_ BitVec 32))" +
      " (assert (not (= i j)))" +
      " (assert (= (select a i) #x41))" +
      " (assert (= (select a j) #x42))" +
      " (assert (= (select (store a i #x43) j) #x42))" +
      " (check-sat)")
)

val rlimitQueries = Seq(
  Query("rlimit respected", "unknown",
    "(set-logic ALL)" +
      " (set-option :rlimit 1)" +
      " (declare-const x Int) (declare-const y Int)" +
      " (assert (forall ((z Int)) (=> (> z 0) (> (* z z) z))))" +
      " (check-sat)")
)

// --- Main ---

val useGraalVm = args.contains("--graalvm")
val positionalArgs = args.filterNot(_.startsWith("--"))
val binary = if (positionalArgs.nonEmpty) positionalArgs(0) else "cvc5_server_wasmtime.wasm"
val defaultOpts = "--produce-models --full-saturate-quant"
val engine = if (useGraalVm) "GraalVM Polyglot" else "wasmtime"
var overallPass = true

def newServer(): Server =
  if (useGraalVm) new GraalVmServer(binary) else new WasmtimeServer(binary)

// Test 1: Core queries with default options
println("=== Test suite: core queries ===")
println(s"Binary: $binary")
println(s"Engine: $engine")
println(s"Options: $defaultOpts")
locally {
  val server = newServer()
  if (!runQueries(server, defaultOpts, coreQueries)) overallPass = false
  server.shutdown()
}

// Test 2: Empty options (regression)
println("\n=== Test suite: empty options ===")
locally {
  val server = newServer()
  val queries = Seq(
    Query("SAT (no opts)", "sat",
      "(set-logic ALL) (set-option :produce-models true)" +
        " (declare-const x Int) (assert (> x 0)) (check-sat)"),
    Query("UNSAT (no opts)", "unsat",
      "(set-logic ALL) (assert false) (check-sat)")
  )
  // Empty string has length 0 which would be shutdown; use a space instead
  if (!runQueries(server, " ", queries)) overallPass = false
  server.shutdown()
}

// Test 3: rlimit via SMT-LIB set-option (per-query limits)
println("\n=== Test suite: per-query rlimit ===")
locally {
  val server = newServer()
  if (!runQueries(server, defaultOpts, rlimitQueries)) overallPass = false
  server.shutdown()
}

// Test 4: Multiple queries with different options (per-query opts)
println("\n=== Test suite: per-query options ===")
locally {
  val server = newServer()
  var allPass = true
  for ((label, opts, expected, query) <- Seq(
    ("With produce-models", "--produce-models", "sat",
      "(set-logic ALL) (declare-const x Int) (assert (= x 42)) (check-sat)"),
    ("With finite-model-find", "--finite-model-find", "sat",
      "(set-logic ALL) (declare-const x Int) (assert (> x 0)) (check-sat)"),
    ("Different logic", "--produce-models", "unsat",
      "(set-logic QF_LIA) (declare-const a Int) (assert (> a 0)) (assert (< a 0)) (check-sat)")
  )) {
    val t0 = System.nanoTime()
    sendQuery(server.outputStream, opts, query)
    val result = recvResponse(server.inputStream)
    val elapsed = (System.nanoTime() - t0) / 1000000L
    result match {
      case None =>
        println(s"  FAIL  $label: no response")
        allPass = false
      case Some(r) =>
        val ok = r.contains(expected)
        if (!ok) allPass = false
        val display = r.replace('\n', '|').take(120)
        val status = if (ok) "PASS" else "FAIL"
        println(s"  $status  $label  (${elapsed}ms)  ->  $display")
    }
  }
  server.shutdown()
  if (!allPass) overallPass = false
}

// Test 5: Solver isolation (multiple queries, same options)
println("\n=== Test suite: solver isolation ===")
locally {
  val server = newServer()
  val queries = Seq(
    Query("Query 1: declare x", "sat",
      "(set-logic ALL) (declare-const x Int) (assert (= x 42)) (check-sat)"),
    Query("Query 2: x not visible", "sat",
      "(set-logic ALL) (declare-const x Int) (assert (= x 99)) (check-sat)"),
    Query("Query 3: different logic", "unsat",
      "(set-logic QF_LIA) (declare-const a Int) (assert (> a 0)) (assert (< a 0)) (check-sat)")
  )
  if (!runQueries(server, "--produce-models", queries)) overallPass = false
  server.shutdown()
}

println(if (overallPass) "\nAll tests passed!" else "\nSOME TESTS FAILED")
sys.exit(if (overallPass) 0 else 1)
