package com.hearth.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.hearth.app.AppGraph
import com.hearth.app.HearthApp
import com.hearth.app.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Turns incoming bank/UPI SMS into transactions. The message text goes straight into the core
 * parser and is never logged or stored; only the parse outcome is.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val graph = (context.applicationContext as HearthApp).graph
        if (!graph.prefs.smsEnabled || graph.prefs.memberId == null) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // Long SMS arrive as several parts from the same sender.
        val bySender = messages.groupBy { it.originatingAddress.orEmpty() }
        val pending = goAsync()
        scope.launch {
            try {
                var recorded = 0
                for ((sender, parts) in bySender) {
                    val text = parts.joinToString("") { it.messageBody.orEmpty() }
                    if (graph.repo.ingestSms(sender, text, parts.first().timestampMillis)) recorded++
                }
                Log.i(AppGraph.TAG, "sms parsed: transactions=$recorded")
                if (recorded > 0) SyncScheduler.syncNow(context)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
