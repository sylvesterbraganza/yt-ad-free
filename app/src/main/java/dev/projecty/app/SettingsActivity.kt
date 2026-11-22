package dev.projecty.app

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.mozilla.geckoview.StorageController

/**
 * A deliberately tiny settings screen. There is no button for it inside the app (the app shows
 * only YouTube); open it by long-pressing the app icon on the home screen and tapping "Settings".
 */
class SettingsActivity : ComponentActivity() {

    private val app get() = application as ProjectYApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        val root = findViewById<android.view.View>(R.id.settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<TextView>(R.id.version).text =
            getString(R.string.settings_version, packageManager.getPackageInfo(packageName, 0).versionName)

        findViewById<Button>(R.id.btn_reload).setOnClickListener {
            app.session?.loadUri(SitePolicy.HOME_URL)
            finish()
        }

        bindSwitch(R.id.sw_h264, app.h264Only) { app.h264Only = it }
        bindSwitch(R.id.sw_chrome_ua, app.chromeUserAgent) { app.chromeUserAgent = it }
        bindSwitch(R.id.sw_adblock, app.adBlockEnabled) { app.adBlockEnabled = it }

        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_clear_title)
                .setMessage(R.string.settings_clear_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_clear_confirm) { _, _ -> clearAllData() }
                .show()
        }
    }

    /** Shows a saved setting on a switch; on change, saves it and reloads YouTube to apply it. */
    private fun bindSwitch(id: Int, current: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = current
            setOnCheckedChangeListener { _, checked ->
                onChange(checked)
                // Give the extension enable/disable a moment to finish before reloading.
                postDelayed({ app.session?.reload() }, 300)
            }
        }
    }

    private fun clearAllData() {
        app.runtime.storageController
            .clearData(StorageController.ClearFlags.ALL)
            .accept(
                {
                    app.session?.loadUri(SitePolicy.HOME_URL)
                    Toast.makeText(this, R.string.settings_cleared, Toast.LENGTH_SHORT).show()
                },
                { Toast.makeText(this, R.string.settings_clear_failed, Toast.LENGTH_SHORT).show() },
            )
    }
}
