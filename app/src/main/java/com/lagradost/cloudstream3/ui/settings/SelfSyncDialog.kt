package com.lagradost.cloudstream3.ui.settings

import android.app.Activity
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.SelfHostedSync

/** Settings dialog for [SelfHostedSync]: server address, token and an on/off switch. */
object SelfSyncDialog {
    fun show(activity: Activity) {
        val cfg = SelfHostedSync.getConfig(activity)
        val pad = (16 * activity.resources.displayMetrics.density).toInt()

        val url = EditText(activity).apply {
            hint = activity.getString(R.string.self_sync_url_hint)
            setText(cfg.url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val token = EditText(activity).apply {
            hint = activity.getString(R.string.self_sync_token_hint)
            setText(cfg.token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val enabled = CheckBox(activity).apply {
            text = activity.getString(R.string.self_sync_enabled)
            isChecked = cfg.enabled
        }
        val status = TextView(activity).apply { text = SelfHostedSync.status }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(url)
            addView(token)
            addView(enabled)
            addView(status)
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.self_sync_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                SelfHostedSync.configure(
                    activity,
                    url.text.toString(),
                    token.text.toString(),
                    enabled.isChecked
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
