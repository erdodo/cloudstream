package com.lagradost.cloudstream3.ui.settings

import android.app.Activity
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.View
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
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_NEXT
            id = View.generateViewId()
        }
        val token = EditText(activity).apply {
            hint = activity.getString(R.string.self_sync_token_hint)
            setText(cfg.token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            id = View.generateViewId()
        }
        val enabled = CheckBox(activity).apply {
            text = activity.getString(R.string.self_sync_enabled)
            isChecked = cfg.enabled
            id = View.generateViewId()
        }

        // A remote's D-pad must visit the fields in order: address, token, switch.
        url.nextFocusDownId = token.id
        token.nextFocusUpId = url.id
        token.nextFocusDownId = enabled.id
        enabled.nextFocusUpId = token.id

        // The keyboard's "next" key moves on to the token field.
        url.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_NEXT) {
                token.requestFocus()
                (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(token, InputMethodManager.SHOW_IMPLICIT)
                true
            } else false
        }
        token.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                enabled.requestFocus()
                true
            } else false
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
