package com.example.windrive

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        val urlInput = findViewById<EditText>(R.id.urlInput)
        val pathInput = findViewById<EditText>(R.id.pathInput)
        val connectedServerText = findViewById<TextView>(R.id.connectedServerText)
        val saveButton = findViewById<Button>(R.id.saveSettingsBtn)
        val autoSaveCheckbox = findViewById<CheckBox>(R.id.autoSaveMediaCheckbox)

        val url = prefs.getString("last_server_url", "") ?: ""
        val path = prefs.getString("last_folder_path", "/") ?: "/"

        urlInput.setText(url)
        pathInput.setText(path)
        connectedServerText.text = "Connected server: ${if (url.isBlank()) "none" else url}"
        autoSaveCheckbox.isChecked = prefs.getBoolean("auto_save_camera_media", false)

        saveButton.setOnClickListener {
            val normalized = normalizeServerUrl(urlInput.text.toString())
            prefs.edit()
                .putString("last_server_url", normalized)
                .putString("last_folder_path", pathInput.text.toString().trim())
                .putBoolean("auto_save_camera_media", autoSaveCheckbox.isChecked)
                .apply()
            connectedServerText.text = "Connected server: $normalized"
            finish()
        }
    }

    private fun normalizeServerUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isBlank()) return trimmed
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }
}
