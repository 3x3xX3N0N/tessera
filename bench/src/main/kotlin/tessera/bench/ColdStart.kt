package tessera.bench

import tessera.core.Handshake
import tessera.core.ZeroRtt
import tessera.transport.ConnConfig
import tessera.transport.TesseraClient
import tessera.transport.TesseraServer
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Locale

/**
 * Cold-start attribution: where the *first* connect in a fresh JVM spends its time.
 *
 * The number this replaces ("128 ms cold vs 8.4 ms warm", TEST-PLAN coverage table) was a single wall-clock
 * measurement with a written-down guess attached — class loading plus "the first ML-KEM operation". This mode
 * measures it instead, and the only way to do that honestly is one *fresh JVM per sample*: a second connect in
 * the same process has already paid for every class, every JIT decision and every one-time library init, which
 * is precisely the cost under study.
 *
 * So the parent process re-executes itself. `bench coldstart` spawns `--jvms` children, each of which runs one
 * of two child scripts and prints `cs <stage> <us>` lines that the parent aggregates (median across children,
 * which is the right summary for a per-process one-shot — a mean would follow whichever child lost the CPU):
 *
 *   `--child total`   nothing but the connect. Server and client both cold, both in this JVM, no preparation
 *                     of any kind before the timer starts. This is the honest end-to-end cold number, and it
 *                     is the one the docs quote. Then a second connect in the same JVM: the warm baseline.
 *
 *   `--child stages`  the same work decomposed. Each stage runs in order and pays only the class loading and
 *                     one-time init that the stages before it did not already pay, so the stage times sum to
 *                     roughly the total and each one names a subsystem. Every crypto stage is measured twice:
 *                     first-use (cold, includes BouncyCastle's class init for that primitive) and then a warm
 *                     median over [WARM_REPS] repeats. cold-minus-warm is the amortisable part; the warm
 *                     figure is the CPU floor that every connect pays forever.
 *
 * Stages, in the order a cold connect actually touches them:
 *   securerandom   first `SecureRandom()` + first nextBytes — the seeding cost, ahead of any protocol work
 *   x25519         first X25519 keypair + agreement (the classical half of the hybrid)
 *   mlkem-keygen   `Handshake.generate()`: ML-KEM-768 keypair. A *server* cost; a client never does this.
 *   mlkem-encap    `Handshake.initiate()`: the client's encapsulation, on the cold-connect critical path
 *   mlkem-decap    `Handshake.respond()`: the server's extraction, also on it
 *   zerortt-build  `ZeroRtt.Client.initial()`: frame codec + packet crypto, first use
 *   zerortt-accept `ZeroRtt.Server.accept()`: replay window, AddressValidator table, first use
 *   nativelib      loading tessera_native and building its Panama downcall handles (nothing when native is off)
 *   endpoints      TesseraServer + TesseraClient construction: sockets, BufferPool, rx and timer threads
 *   connect        the wire round trip itself, with everything above already warm — the residue
 *   connect-warm   a second connect in the same JVM (the 8.4 ms figure's origin)
 *
 * usage: bench coldstart [--jvms 12] [--native auto|on|off]
 *        bench coldstart --child <total|stages>        (internal; run by the parent, one per fresh JVM)
 */
private const val WARM_REPS = 40

fun coldStartMain(args: Array<String>) {
    fun opt(k: String, d: String) = args.indexOf("--$k").let { if (it >= 0) args[it + 1] else d }
    when (opt("child", "")) {
        "total" -> { childTotal(); return }
        "stages" -> { childStages(); return }
        "order" -> { childOrder(); return }
    }
    val jvms = opt("jvms", "12").toInt()
    val native = opt("native", "auto")
    println("coldstart driving $jvms fresh JVMs per script, -Dtessera.native=$native (median across JVMs; a fresh JVM is the only way to see a cold connect)")

    val total = runChildren("total", jvms, native)
    val stages = runChildren("stages", jvms, native)
    val order = runChildren("order", jvms, native)

    fun show(script: Map<String, List<Long>>, key: String, note: String) {
        val v = script[key] ?: return
        println(String.format(Locale.ROOT, "coldstart %-16s p50=%7.1fms  min=%7.1fms  max=%7.1fms  n=%d   %s",
            key, med(v) / 1000.0, (v.min()) / 1000.0, (v.max()) / 1000.0, v.size, note))
    }
    println("-- end to end, nothing warmed --")
    show(total, "jvm-startup", "JVM start to the first line of main(), before any tessera class exists")
    show(total, "total-cold", "first connect: everything cold, server and client both in this JVM")
    show(total, "total-warm", "second connect, same JVM: the warm baseline")
    println("-- decomposed (each stage pays only what the stages above it did not) --")
    for (k in listOf("securerandom", "x25519", "mlkem-keygen", "mlkem-encap", "mlkem-decap",
                     "zerortt-build", "zerortt-accept", "nativelib", "endpoints", "connect", "connect-warm")) {
        val cold = stages[k] ?: continue
        val warm = stages["$k.warm"]
        val w = if (warm == null) "" else String.format(Locale.ROOT, "  warm=%.3fms -> amortisable %.1fms", med(warm) / 1000.0, (med(cold) - med(warm)) / 1000.0)
        println(String.format(Locale.ROOT, "coldstart %-16s cold p50=%7.1fms  min=%7.1fms  max=%7.1fms%s", k, med(cold) / 1000.0, cold.min() / 1000.0, cold.max() / 1000.0, w))
    }
    val summed = listOf("securerandom", "x25519", "mlkem-keygen", "mlkem-encap", "mlkem-decap",
                        "zerortt-build", "zerortt-accept", "nativelib", "endpoints", "connect").sumOf { med(stages[it] ?: listOf(0L)) }
    // The ordering control. `stages` charges X25519 with whatever BouncyCastle's very first class touch costs,
    // because X25519 is the first BC primitive it uses. This script touches a trivial BC digest first, then runs
    // ML-KEM before X25519, so the first-touch cost lands somewhere else and each primitive is charged only for
    // its own classes.
    println("-- ordering control: which of these costs is BouncyCastle's first touch rather than the primitive? --")
    for (k in listOf("securerandom", "bc-first-touch", "mlkem-keygen", "mlkem-encap", "x25519")) {
        val v = order[k] ?: continue
        println(String.format(Locale.ROOT, "coldstart %-16s cold p50=%7.1fms  min=%7.1fms  max=%7.1fms   (in `stages` order: %s)",
            k, med(v) / 1000.0, v.min() / 1000.0, v.max() / 1000.0,
            stages[k]?.let { String.format(Locale.ROOT, "%.1fms", med(it) / 1000.0) } ?: "n/a"))
    }
    println(String.format(Locale.ROOT, "coldstart stages sum p50=%.1fms vs total-cold p50=%.1fms (the gap is work the decomposition does not name, e.g. JIT the staged run happens to have done already)",
        summed / 1000.0, med(total["total-cold"] ?: listOf(0L)) / 1000.0))
}

/** Runs `jvms` fresh JVMs of one child script and collects their `cs <stage> <us>` lines by stage. */
private fun runChildren(script: String, jvms: Int, native: String): Map<String, MutableList<Long>> {
    val out = LinkedHashMap<String, MutableList<Long>>()
    val java = ProcessHandle.current().info().command().orElse(System.getProperty("java.home") + "/bin/java")
    val cp = System.getProperty("java.class.path")
    repeat(jvms) { i ->
        val cmd = listOf(java, "-cp", cp, "-Dtessera.native=$native", "--enable-native-access=ALL-UNNAMED",
                         "tessera.bench.MainKt", "coldstart", "--child", script)
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        val rc = p.waitFor()
        if (rc != 0) { println("coldstart child $script #$i exited $rc:\n${text.take(2000)}"); return@repeat }
        for (line in text.lineSequence()) {
            val f = line.trim().split(" ")
            if (f.size == 3 && f[0] == "cs") f[2].toLongOrNull()?.let { out.getOrPut(f[1]) { ArrayList() } += it }
        }
    }
    return out
}

private fun med(a: List<Long>): Double = if (a.isEmpty()) Double.NaN else a.sorted()[a.size / 2].toDouble()
private fun emit(stage: String, nanos: Long) = println("cs $stage ${nanos / 1000}")

/** Child: the end-to-end cold connect, with nothing warmed first, then a warm one in the same JVM. */
private fun childTotal() {
    emit("jvm-startup", (System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime) * 1_000_000)
    val t0 = System.nanoTime()
    val keys = Handshake.generate()
    TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { (it * 7).toByte() }, ConnConfig()).use { s ->
        TesseraClient(cfg = ConnConfig()).use { c ->
            val payload = ByteArray(128) { 0x42 }
            val st = Thread { repeat(2) { val sc = s.accept(5_000) ?: return@repeat; sc.receive(3_000); sc.send("hi".toByteArray()) } }
                .apply { isDaemon = true; start() }
            val conn = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, payload, 10_000)
            conn.receive(5_000)
            emit("total-cold", System.nanoTime() - t0)
            conn.close()
            val t1 = System.nanoTime()
            val c2 = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, payload, 10_000)
            c2.receive(5_000)
            emit("total-warm", System.nanoTime() - t1)
            c2.close(); st.join(1_000)
        }
    }
}

/** Child: the same work, stage by stage, each stage cold on entry because nothing before it touched that subsystem. */
private fun childStages() {
    // 1. SecureRandom seeding, before any tessera or BouncyCastle class is loaded.
    var t = System.nanoTime()
    val rng = SecureRandom(); val seed = ByteArray(32); rng.nextBytes(seed)
    emit("securerandom", System.nanoTime() - t)
    emit("securerandom.warm", timeWarm { val r = ByteArray(32); rng.nextBytes(r) })

    // 2. X25519 — the classical half. Loads the BouncyCastle lightweight API for the first time.
    t = System.nanoTime()
    val xa = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(rng)
    val dh = ByteArray(32)
    org.bouncycastle.crypto.agreement.X25519Agreement().apply { init(xa) }.calculateAgreement(xa.generatePublicKey(), dh, 0)
    emit("x25519", System.nanoTime() - t)
    emit("x25519.warm", timeWarm {
        val e = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(rng)
        org.bouncycastle.crypto.agreement.X25519Agreement().apply { init(e) }.calculateAgreement(e.generatePublicKey(), dh, 0)
    })

    // 3. ML-KEM-768 keygen. A server-side cost only — a client that connects to a pinned key never runs it.
    t = System.nanoTime(); val keys = Handshake.generate(); emit("mlkem-keygen", System.nanoTime() - t)
    emit("mlkem-keygen.warm", timeWarm { Handshake.generate() })

    // 4/5. Encapsulation (client) and extraction (server): both on the cold-connect critical path.
    val xpub = keys.x25519Pub; val kpub = keys.kemPub
    t = System.nanoTime(); val init = Handshake.initiate(xpub, kpub); emit("mlkem-encap", System.nanoTime() - t)
    emit("mlkem-encap.warm", timeWarm { Handshake.initiate(xpub, kpub) })
    t = System.nanoTime(); Handshake.respond(keys, init.ePub, init.kemCt); emit("mlkem-decap", System.nanoTime() - t)
    emit("mlkem-decap.warm", timeWarm { Handshake.respond(keys, init.ePub, init.kemCt) })

    // 6/7. Frame codec + packet crypto + the accept path (replay window, AddressValidator's fixed table).
    val data = ByteArray(128)
    t = System.nanoTime(); val body = ZeroRtt.Client(Handshake.initiate(xpub, kpub)).initial(data, 1L, 1L); emit("zerortt-build", System.nanoTime() - t)
    val srv = ZeroRtt.Server(keys)
    t = System.nanoTime(); requireNotNull(srv.accept(body, 1L)); emit("zerortt-accept", System.nanoTime() - t)
    emit("zerortt-build.warm", timeWarm { ZeroRtt.Client(Handshake.initiate(xpub, kpub)).initial(data, 2L, 2L) })
    var n = 100L
    emit("zerortt-accept.warm", timeWarm { srv.accept(ZeroRtt.Client(Handshake.initiate(xpub, kpub)).initial(data, ++n, n), n) })

    // 8. The native datapath's one-time cost, separated from the sockets: touching `nativeAvailable` loads
    //    tessera_native (dlopen, plus a copy into the tmpdir on the very first run of a given build) and builds
    //    every Panama downcall handle. `nativeSelected()` is what `openUdpIo` itself calls, and under
    //    `-Dtessera.native=off` it short-circuits on the property without loading anything — so this stage costs
    //    what the real connect would pay, and nothing more. (`Datapath.nativeAvailable` would *not* do: it loads
    //    the library to answer, which charged the JDK-only configuration ~49 ms it never actually spends.)
    t = System.nanoTime(); tessera.transport.Datapath.nativeSelected(); emit("nativelib", System.nanoTime() - t)

    // 9. Endpoint construction: datapath selection (NativeLib + Panama handles when native is on), sockets,
    //    BufferPool, rx and timer threads. Once per process for the library load, once per endpoint for the rest.
    t = System.nanoTime()
    val s = TesseraServer(InetSocketAddress("127.0.0.1", 0), keys, ByteArray(32) { (it * 7).toByte() }, ConnConfig())
    val c = TesseraClient(cfg = ConnConfig())
    emit("endpoints", System.nanoTime() - t)
    s.use { c.use {
        val payload = ByteArray(128) { 0x42 }
        val st = Thread { repeat(2) { val sc = s.accept(5_000) ?: return@repeat; sc.receive(3_000); sc.send("hi".toByteArray()) } }
            .apply { isDaemon = true; start() }
        // 10. The wire round trip with every subsystem above already warm: what is left of the cold connect.
        t = System.nanoTime()
        val conn = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, payload, 10_000); conn.receive(5_000)
        emit("connect", System.nanoTime() - t); conn.close()
        t = System.nanoTime()
        val c2 = c.connect(s.localAddress, keys.x25519Pub, keys.kemPub, payload, 10_000); c2.receive(5_000)
        emit("connect-warm", System.nanoTime() - t); c2.close()
        st.join(1_000)
    } }
}

/** Median of [WARM_REPS] repeats of `f`, in nanoseconds — the steady-state cost once the class is loaded. */
private inline fun timeWarm(f: () -> Unit): Long {
    val a = LongArray(WARM_REPS)
    for (i in 0 until WARM_REPS) { val t = System.nanoTime(); f(); a[i] = System.nanoTime() - t }
    a.sort(); return a[WARM_REPS / 2]
}

/**
 * Child: the ordering control for the crypto stages. Whichever BouncyCastle primitive runs first is charged with
 * the whole of BC's first-touch cost, so running them in a different order says how much of a stage's cold time
 * is the primitive and how much is "you had not loaded BouncyCastle yet".
 */
private fun childOrder() {
    var t = System.nanoTime()
    val rng = SecureRandom(); val seed = ByteArray(32); rng.nextBytes(seed)
    emit("securerandom", System.nanoTime() - t)

    // A digest is the cheapest BouncyCastle primitive there is; anything it costs is class loading, not maths.
    t = System.nanoTime()
    val d = org.bouncycastle.crypto.digests.SHA256Digest()
    d.update(seed, 0, seed.size); d.doFinal(ByteArray(32), 0)
    emit("bc-first-touch", System.nanoTime() - t)

    t = System.nanoTime(); val keys = Handshake.generate(); emit("mlkem-keygen", System.nanoTime() - t)
    t = System.nanoTime(); Handshake.initiate(keys.x25519Pub, keys.kemPub); emit("mlkem-encap", System.nanoTime() - t)
    // X25519 last: by now BouncyCastle, SecureRandom and the KEM are all warm.
    t = System.nanoTime()
    val xa = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(rng)
    val dh = ByteArray(32)
    org.bouncycastle.crypto.agreement.X25519Agreement().apply { init(xa) }.calculateAgreement(xa.generatePublicKey(), dh, 0)
    emit("x25519", System.nanoTime() - t)
}
