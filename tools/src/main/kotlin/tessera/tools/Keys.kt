package tessera.tools

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import tessera.core.Handshake
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Persisted responder keys, so a headless listener can use a keypair that was generated somewhere else and
 * the probe can be handed the matching `--peer-key` in advance — no console access needed to read one off
 * a cloud instance's stdout.
 *
 * Format is a single base64 line: version(1) | len(x25519Priv):2 | x25519Priv | len(kemPriv):2 | kemPriv.
 * These are secrets: the file guards a listener, so treat it like an SSH private key.
 */
object Keys {
    private const val VERSION = 1

    fun write(keys: Handshake.StaticKeys, file: File) {
        val x = keys.x25519Priv.encoded
        val k = keys.kemPriv.encoded
        val buf = ByteBuffer.allocate(1 + 2 + x.size + 2 + k.size)
        buf.put(VERSION.toByte()).putShort(x.size.toShort()).put(x).putShort(k.size.toShort()).put(k)
        file.parentFile?.mkdirs()
        file.writeText(Base64.getEncoder().encodeToString(buf.array()) + "\n")
    }

    fun read(file: File): Handshake.StaticKeys {
        val raw = Base64.getDecoder().decode(file.readText().trim())
        val buf = ByteBuffer.wrap(raw)
        require(buf.get().toInt() == VERSION) { "${file.name}: unsupported key file version" }
        val x = ByteArray(buf.short.toInt() and 0xFFFF).also { buf.get(it) }
        val k = ByteArray(buf.short.toInt() and 0xFFFF).also { buf.get(it) }
        return Handshake.StaticKeys(
            X25519PrivateKeyParameters(x, 0),
            MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, k),
        )
    }

    /** The string the probe needs: the two public keys concatenated, base64. */
    fun peerKey(keys: Handshake.StaticKeys): String =
        Base64.getEncoder().encodeToString(keys.x25519Pub.encoded + keys.kemPub.encoded)
}

/** `tessera keygen --out server.key` — generate a responder keypair and print the peer key to hand the probe. */
fun keygenMain(a: Args) {
    val out = File(a.req("out"))
    val keys = Handshake.generate()
    Keys.write(keys, out)
    // Round-trip immediately: a key file that cannot be read back is worse than no key file.
    val reloaded = Keys.read(out)
    check(Keys.peerKey(reloaded) == Keys.peerKey(keys)) { "key file did not round-trip" }
    println("wrote ${out.path} (private — treat like an ssh key)")
    println()
    println("peer key for the probe:")
    println(Keys.peerKey(keys))
}
