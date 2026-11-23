package app.fukreg.link

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.fukreg.R
import app.fukreg.data.Contacts
import app.fukreg.data.Sims
import app.fukreg.data.Sms
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

class HostService : Service() {
    companion object {
        val connected = MutableStateFlow(false)
        @Volatile var link: Link? = null
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("link", "Link", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "link").setContentTitle(getString(R.string.svc_running))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        link = Link(this, PairStore(this)) { msg, l -> handle(msg, l) }.also {
            it.onStatus = { c -> connected.value = c }
            it.start()
        }
    }

    private fun handle(m: JSONObject, l: Link) {
        val id = m.opt("id")
        fun reply(j: JSONObject) = l.send(j.put("re", id))
        when (m.getString("t")) {
            "contacts.sync" -> reply(JSONObject().put("t", "contacts.list").put("items", Contacts.all(this)))
            "sims" -> reply(JSONObject().put("t", "sims").put("items", Sims.list(this)))
            "sms.list" -> reply(JSONObject().put("t", "sms.items")
                .put("items", Sms.list(this, m.optLong("thread", -1), m.optLong("before", Long.MAX_VALUE), m.optInt("limit", 50))))
            "sms.send" -> {
                val ok = Sms.send(this, m.getString("addr"), m.getString("body"), m.optInt("sim", -1))
                reply(JSONObject().put("t", "sms.sent").put("ok", ok))
            }
        }
    }

    override fun onDestroy() { link?.stop(); link = null; super.onDestroy() }
}
