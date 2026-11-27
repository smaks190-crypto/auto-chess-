package dev.nox.local

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.TextView

/** Explicitly launched, visible, dev-only helper. Writes supplied text only. */
class RunnerClipboardActivity : Activity() {
    private var handled = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "N.O.X Dev · подготовка текста для вставки"; textSize = 18f })
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val token = intent.getStringExtra("token").orEmpty()
        val encoded = intent.getStringExtra("utf8").orEmpty()
        val ok = runCatching {
            require(token.matches(Regex("[a-f0-9]{32}")))
            require(encoded.length in 1..22000 && DeviceInteraction.unlocked(this))
            val data = Base64.decode(encoded, Base64.DEFAULT)
            require(data.size in 1..16000)
            val value = data.toString(Charsets.UTF_8)
            require(value.toByteArray(Charsets.UTF_8).contentEquals(data) && '\u0000' !in value)
            requireNotNull(getSystemService(ClipboardManager::class.java))
                .setPrimaryClip(ClipData.newPlainText("N.O.X Dev", value))
        }.isSuccess
        if (token.matches(Regex("[a-f0-9]{32}"))) Log.i("NoxRunner", "clipboard token=" + token + " ok=" + ok)
        intent.removeExtra("utf8")
        finish()
    }
}
