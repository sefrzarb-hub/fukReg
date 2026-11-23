package app.fukreg.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import org.json.JSONArray
import org.json.JSONObject

object Contacts {
    fun all(ctx: Context): JSONArray {
        val map = LinkedHashMap<Long, JSONObject>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER), null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val o = map.getOrPut(id) { JSONObject().put("id", id).put("name", c.getString(1) ?: "").put("phones", JSONArray()) }
                o.getJSONArray("phones").put(c.getString(2))
            }
        }
        return JSONArray(map.values)
    }
}

object Sims {
    @SuppressLint("MissingPermission")
    fun list(ctx: Context): JSONArray {
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        return JSONArray((sm.activeSubscriptionInfoList ?: emptyList()).map {
            JSONObject().put("slot", it.simSlotIndex).put("sub", it.subscriptionId).put("carrier", it.carrierName?.toString() ?: "")
        })
    }
}

object Sms {
    private val OTP = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")
    private val FA_DIGITS = "۰۱۲۳۴۵۶۷۸۹"

    fun normalize(s: String) = s.map { c -> FA_DIGITS.indexOf(c).let { if (it >= 0) '0' + it else c } }.joinToString("")

    fun otp(body: String): String? {
        val n = normalize(body)
        val hint = Regex("(?i)code|otp|رمز|کد|verification|تایید|تأیید").containsMatchIn(n)
        return if (hint) OTP.find(n)?.value else null
    }

    fun item(id: Long, thread: Long, addr: String, body: String, ts: Long, type: Int, sub: Int) = JSONObject()
        .put("id", id).put("thread", thread).put("addr", addr).put("body", body).put("ts", ts)
        .put("dir", if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) "in" else "out").put("sim", sub)
        .apply { otp(body)?.let { put("otp", it) } }

    fun list(ctx: Context, thread: Long, before: Long, limit: Int): JSONArray {
        val arr = JSONArray()
        val sel = StringBuilder("${Telephony.Sms.DATE} < ?"); val args = mutableListOf(before.toString())
        if (thread >= 0) { sel.append(" AND ${Telephony.Sms.THREAD_ID} = ?"); args += thread.toString() }
        ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.SUBSCRIPTION_ID),
            sel.toString(), args.toTypedArray(), "${Telephony.Sms.DATE} DESC LIMIT $limit")?.use { c ->
            while (c.moveToNext()) arr.put(item(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "",
                c.getLong(4), c.getInt(5), c.getInt(6)))
        }
        return arr
    }

    fun send(ctx: Context, addr: String, body: String, sub: Int): Boolean = runCatching {
        val base = ctx.getSystemService(SmsManager::class.java)
        val sm = if (sub >= 0) base.createForSubscriptionId(sub) else base
        sm.sendMultipartTextMessage(addr, null, sm.divideMessage(body), null, null)
    }.isSuccess
}
