package app.fukreg.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.fukreg.data.Sms
import app.fukreg.link.HostService
import org.json.JSONObject

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        val addr = msgs[0].originatingAddress ?: ""
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val sub = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1))
        val item = Sms.item(-1, -1, addr, body, System.currentTimeMillis(), Telephony.Sms.MESSAGE_TYPE_INBOX, sub)
        HostService.link?.send(JSONObject().put("t", "sms.new").put("item", item))
    }
}
