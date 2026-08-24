package tessera.tools

import java.net.InetSocketAddress

/**
 * Standalone endpoints for testing Tessera over a real network — two machines, two processes.
 *
 *   tessera echo  --token <shared> [--port 51820] [--bind ::] [--also-udp]
 *   tessera probe --connect <host:port> --peer-key <base64> --token <shared> [options]
 *
 * The echo side prints the `--peer-key` string to paste into the probe command: the handshake pins the
 * responder's static keys out of band, so there is no certificate chain and no PKI involved.
 *
 * `--token` is a shared secret carried in the 0-RTT payload. A connection whose token does not match is
 * dropped without a reply, so a public listener cannot be used as an amplifier or probed by scanners.
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull()
    val rest = args.drop(1).toTypedArray()
    when (mode) {
        "echo" -> echoMain(Args(rest))
        "probe" -> probeMain(Args(rest))
        "keygen" -> keygenMain(Args(rest))
        else -> {
            System.err.println(
                """
                usage: tessera <echo|probe|keygen> [options]

                  echo   --token <s>            shared secret; connections without it are dropped
                         --port <n>             UDP port to listen on (default 51820)
                         --bind <addr>          address to bind (default ::  — dual-stack v6+v4)
                         --also-udp             additionally run a plain-UDP echo on port+1 (the A/B floor)
                         --key-in <file>        use keys from `keygen` instead of fresh ones
                         --key-out <file>       save the freshly generated keys

                  keygen --out <file>          generate responder keys; prints the --peer-key

                  probe  --connect <host:port>  IPv6 hosts as [2600:...]:51820
                         --peer-key <base64>    printed by the echo side at startup
                         --token <s>            must match the echo side
                         --transport <t>        tessera (default) | udp — udp measures the same path with
                                                plain datagrams, i.e. the floor to compare against
                         --rate <n>             messages per second (default 50)
                         --size <n>             message bytes (default 1200)
                         --count <n>            messages to measure (default 2000)
                         --warmup <n>           unmeasured messages first (default 200)
                         --bind <addr>          local address to bind (default: matches the target family)
                         --connect-warmup <n>   discard n connects first (JVM warm-up; default 0)
                         --out <file.csv>       per-message rows: seq,rtt_us
                         --no-resume            skip the resumed-connect measurement
                """.trimIndent()
            )
            kotlin.system.exitProcess(2)
        }
    }
}

/** Minimal `--flag value` parser; no dependencies, fails loudly on a missing required option. */
class Args(private val argv: Array<String>) {
    fun opt(name: String): String? {
        val i = argv.indexOf("--$name")
        return if (i >= 0 && i + 1 < argv.size) argv[i + 1] else null
    }
    fun req(name: String): String = opt(name) ?: fail("missing required option --$name")
    fun flag(name: String): Boolean = argv.contains("--$name")
    fun int(name: String, default: Int): Int = opt(name)?.toInt() ?: default
    fun long(name: String, default: Long): Long = opt(name)?.toLong() ?: default
    private fun fail(msg: String): Nothing { System.err.println("tessera: $msg"); kotlin.system.exitProcess(2) }
}

/** Parses `host:port`, `1.2.3.4:port` and `[2600:db8::1]:port`. */
fun parseAddr(s: String): InetSocketAddress {
    if (s.startsWith("[")) {
        val close = s.indexOf(']')
        require(close > 0 && close + 2 < s.length && s[close + 1] == ':') { "bad address: $s (want [v6]:port)" }
        return InetSocketAddress(s.substring(1, close), s.substring(close + 2).toInt())
    }
    val colon = s.lastIndexOf(':')
    require(colon > 0) { "bad address: $s (want host:port)" }
    return InetSocketAddress(s.substring(0, colon), s.substring(colon + 1).toInt())
}

fun percentile(sorted: LongArray, q: Double): Long =
    if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * q).toInt()]
