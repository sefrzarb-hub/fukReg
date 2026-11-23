package app.fukreg.crypto

import java.nio.ByteBuffer
import java.security.*
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** X25519 + HKDF-SHA256 + AES-256-GCM, per docs/PROTOCOL.md */
object Crypto {
    // Raw 32-byte X25519 public keys on the wire; this is the X.509 prefix.
    private val X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()

    fun rawPublic(kp: KeyPair): ByteArray = kp.public.encoded.takeLast(32).toByteArray()

    fun publicFromRaw(raw: ByteArray): PublicKey =
        KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(X509_PREFIX + raw))

    fun dh(priv: PrivateKey, peerRaw: ByteArray): ByteArray =
        KeyAgreement.getInstance("X25519").run { init(priv); doPhase(publicFromRaw(peerRaw), true); generateSecret() }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, len: Int = 32): ByteArray {
        val prk = hmac(salt, ikm)
        var t = ByteArray(0); val out = ByteBuffer.allocate(len); var i = 1
        while (out.position() < len) {
            t = hmac(prk, t + info.toByteArray() + byteArrayOf(i++.toByte()))
            out.put(t, 0, minOf(t.size, len - out.position()))
        }
        return out.array()
    }

    fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
}

/** One direction of an encrypted channel with replay protection. */
class Sealer(private val key: ByteArray) {
    private var sendCtr = 0L
    private var recvCtr = -1L

    private fun nonce(c: Long) = ByteBuffer.allocate(12).putInt(0).putLong(c).array()

    fun seal(plain: ByteArray): ByteArray {
        val c = sendCtr++
        val ct = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(c))); doFinal(plain)
        }
        return ByteBuffer.allocate(8 + ct.size).putLong(c).put(ct).array()
    }

    fun open(frame: ByteArray): ByteArray {
        val bb = ByteBuffer.wrap(frame); val c = bb.long
        require(c > recvCtr) { "replay" }
        val ct = ByteArray(bb.remaining()).also { bb.get(it) }
        val p = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(c))); doFinal(ct)
        }
        recvCtr = c
        return p
    }
}
