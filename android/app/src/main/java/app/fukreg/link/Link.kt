package app.fukreg.link

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import app.fukreg.crypto.Crypto
import app.fukreg.crypto.Sealer
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * BLE peripheral: advertises SERVICE, exposes PSM_CHAR (L2CAP PSM as u16),
 * accepts an L2CAP CoC and runs handshake + encrypted framing.
 */
@SuppressLint("MissingPermission")
class Link(private val ctx: Context, private val store: PairStore, private val onMessage: suspend (JSONObject, Link) -> Unit) {
    companion object {
        val SERVICE: UUID = UUID.fromString("6f2a0001-6b7e-4c3a-9f1e-fa0c0de00001")
        val PSM_CHAR: UUID = UUID.fromString("6f2a0002-6b7e-4c3a-9f1e-fa0c0de00001")
        const val TAG = "FukRegLink"
    }

    var onStatus: (Boolean) -> Unit = {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bm = ctx.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGattServer? = null
    private var server: BluetoothServerSocket? = null
    @Volatile private var out: DataOutputStream? = null
    private var tx: Sealer? = null
    private val sendLock = Any()

    fun start() {
        val adapter = bm.adapter
        server = adapter.listenUsingL2capChannel() // encrypted link-layer + our own E2E
        val psm = server!!.psm
        Log.i(TAG, "L2CAP PSM $psm")

        val ch = BluetoothGattCharacteristic(PSM_CHAR, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(ch) }
        gatt = bm.openGattServer(ctx, object : BluetoothGattServerCallback() {
            override fun onCharacteristicReadRequest(d: BluetoothDevice, id: Int, off: Int, c: BluetoothGattCharacteristic) {
                val v = ByteBuffer.allocate(2).putShort(psm.toShort()).array()
                gatt?.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, 0, v.copyOfRange(off.coerceAtMost(2), 2))
            }
        }).apply { addService(svc) }

        adapter.bluetoothLeAdvertiser.startAdvertising(
            AdvertiseSettings.Builder().setConnectable(true).setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).build(),
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).build(),
            object : AdvertiseCallback() {})

        scope.launch { acceptLoop() }
    }

    private suspend fun acceptLoop() {
        while (scope.isActive) {
            val sock = try { server!!.accept() } catch (e: Exception) { Log.w(TAG, "accept", e); delay(1000); continue }
            try { session(sock) } catch (e: Exception) { Log.w(TAG, "session ended", e) }
            finally { out = null; tx = null; onStatus(false); runCatching { sock.close() } }
        }
    }

    private fun b(s: String) = Base64.decode(s, Base64.NO_WRAP)
    private fun s(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private suspend fun session(sock: BluetoothSocket) {
        val inp = DataInputStream(sock.inputStream); val o = DataOutputStream(sock.outputStream)
        fun readPlain() = ByteArray(inp.readInt()).also { inp.readFully(it) }.let { JSONObject(String(it)) }
        fun writePlain(j: JSONObject) { val by = j.toString().toByteArray(); o.writeInt(by.size); o.write(by); o.flush() }

        var first = readPlain()
        // --- pairing (only once) ---
        if (first.getString("t") == "pair.req") {
            val clientPub = b(first.getString("pk"))
            val shared = Crypto.dh(store.hostKey.private, clientPub)
            val pk = Crypto.hkdf(shared, store.pairId, "fukreg-pair")
            val confirm = readPlain()
            require(confirm.getString("t") == "pair.confirm")
            require(MessageDigestEq(b(confirm.getString("mac")), Crypto.hmac(pk, "confirm".toByteArray()))) { "bad pair mac" }
            store.clientPub = clientPub; store.pairKey = pk
            writePlain(JSONObject().put("t", "pair.ok"))
            first = readPlain()
        }
        // --- session handshake ---
        require(first.getString("t") == "hello")
        val pairKey = store.pairKey ?: error("not paired")
        val eph = Crypto.newKeyPair(); val nonceH = Crypto.random(32)
        writePlain(JSONObject().put("t", "hello").put("pk", s(Crypto.rawPublic(eph))).put("n", s(nonceH)))
        val dh = Crypto.dh(eph.private, b(first.getString("pk")))
        val salt = b(first.getString("n")) + nonceH
        val rx = Sealer(Crypto.hkdf(dh + pairKey, salt, "fukreg-session-c2h"))
        tx = Sealer(Crypto.hkdf(dh + pairKey, salt, "fukreg-session-h2c"))
        out = o
        onStatus(true)
        Log.i(TAG, "session up")
        // first message must decrypt -> proves client holds pairKey
        while (true) {
            val frame = ByteArray(inp.readInt()).also { inp.readFully(it) }
            val msg = JSONObject(String(rx.open(frame)))
            if (msg.optString("t") == "ping") send(JSONObject().put("t", "pong")) else onMessage(msg, this)
        }
    }

    fun send(j: JSONObject) {
        val o = out ?: return; val t = tx ?: return
        synchronized(sendLock) {
            val f = t.seal(j.toString().toByteArray())
            runCatching { o.writeInt(f.size); o.write(f); o.flush() }
        }
    }

    val connected get() = out != null

    fun stop() {
        scope.cancel(); runCatching { server?.close() }; gatt?.close()
    }

    private fun MessageDigestEq(a: ByteArray, b: ByteArray) = java.security.MessageDigest.isEqual(a, b)
}
