package aether.core

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom

/**
 * Noise-IK-shaped 1-RTT handshake with hybrid X25519 + ML-KEM-768.
 * Initiator already knows the responder's static keys (pinned / out-of-band / delegated credential).
 * First flight: e_pub (32B) + KEM ciphertext (1088B) + 0-RTT payload. Fits one 1350B initial. No X.509 in-band.
 */
object Handshake {
    private val rng = SecureRandom()

    class StaticKeys(val x25519Priv: X25519PrivateKeyParameters, val kemPriv: MLKEMPrivateKeyParameters) {
        val x25519Pub: X25519PublicKeyParameters get() = x25519Priv.generatePublicKey()
        val kemPub: MLKEMPublicKeyParameters get() = kemPriv.publicKeyParameters
    }

    fun generate(): StaticKeys {
        val x = X25519PrivateKeyParameters(rng)
        val gen = MLKEMKeyPairGenerator().apply { init(MLKEMKeyGenerationParameters(rng, MLKEMParameters.ml_kem_768)) }
        return StaticKeys(x, gen.generateKeyPair().private as MLKEMPrivateKeyParameters)
    }

    class InitiatorResult(val ePub: ByteArray, val kemCt: ByteArray, val key: ByteArray)

    fun initiate(rsPub: X25519PublicKeyParameters, rkemPub: MLKEMPublicKeyParameters): InitiatorResult {
        val e = X25519PrivateKeyParameters(rng)
        val dh = ByteArray(32)
        X25519Agreement().apply { init(e) }.calculateAgreement(rsPub, dh, 0)
        val enc = MLKEMGenerator(rng).generateEncapsulated(rkemPub)
        val ePub = e.generatePublicKey().encoded
        return InitiatorResult(ePub, enc.encapsulation, hkdf(dh + enc.secret, ePub + enc.encapsulation))
    }

    fun respond(keys: StaticKeys, ePub: ByteArray, kemCt: ByteArray): ByteArray {
        val dh = ByteArray(32)
        X25519Agreement().apply { init(keys.x25519Priv) }.calculateAgreement(X25519PublicKeyParameters(ePub, 0), dh, 0)
        val ss = MLKEMExtractor(keys.kemPriv).extractSecret(kemCt)
        return hkdf(dh + ss, ePub + kemCt)
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray): ByteArray {
        val out = ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(ikm, salt, "aether-v0".toByteArray())) }
            .generateBytes(out, 0, 32)
        return out
    }
}
