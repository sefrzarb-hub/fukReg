package app.fukreg.link

import android.content.Context
import android.util.Base64
import app.fukreg.crypto.Crypto
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** Persists the host long-term key, pairId and the paired client's key. */
class PairStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("pair", Context.MODE_PRIVATE)
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    private fun unb64(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING)

    val hostKey: KeyPair by lazy {
        val pub = p.getString("pub", null); val priv = p.getString("priv", null)
        if (pub != null && priv != null) {
            val kf = KeyFactory.getInstance("X25519")
            KeyPair(kf.generatePublic(X509EncodedKeySpec(unb64(pub))), kf.generatePrivate(PKCS8EncodedKeySpec(unb64(priv))))
        } else Crypto.newKeyPair().also {
            p.edit().putString("pub", b64(it.public.encoded)).putString("priv", b64(it.private.encoded)).apply()
        }
    }

    val pairId: ByteArray get() = p.getString("id", null)?.let(::unb64)
        ?: Crypto.random(16).also { p.edit().putString("id", b64(it)).apply() }

    var clientPub: ByteArray?
        get() = p.getString("client", null)?.let(::unb64)
        set(v) { p.edit().putString("client", v?.let(::b64)).apply() }

    var pairKey: ByteArray?
        get() = p.getString("pk", null)?.let(::unb64)
        set(v) { p.edit().putString("pk", v?.let(::b64)).apply() }

    fun qrUri() = "fukreg://pair?v=1&id=${b64(pairId)}&pk=${b64(Crypto.rawPublic(hostKey))}&svc=${Link.SERVICE}"

    fun reset() = p.edit().clear().apply()
}
