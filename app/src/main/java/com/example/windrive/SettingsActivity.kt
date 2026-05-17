package com.example.windrive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private var monitorFolderUri: Uri? = null

    private val monitorFolderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            monitorFolderUri = uri
            findViewById<TextView>(R.id.monitorFolderText).text = "Monitor folder: $uri"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        val urlInput = findViewById<EditText>(R.id.urlInput)
        val pathInput = findViewById<EditText>(R.id.pathInput)
        val connectedServerText = findViewById<TextView>(R.id.connectedServerText)
        val monitorFolderText = findViewById<TextView>(R.id.monitorFolderText)
        val selectMonitorFolderBtn = findViewById<Button>(R.id.selectMonitorFolderBtn)
        val saveButton = findViewById<Button>(R.id.saveSettingsBtn)
        val autoSaveCheckbox = findViewById<CheckBox>(R.id.autoSaveMediaCheckbox)

        val url = prefs.getString("last_server_url", "") ?: ""
        val path = prefs.getString("last_folder_path", "/") ?: "/"
        val monitorFolderUriString = prefs.getString("monitor_folder_uri", "") ?: ""
        monitorFolderUri = if (monitorFolderUriString.isNotBlank()) Uri.parse(monitorFolderUriString) else null

        urlInput.setText(url)
        pathInput.setText(path)
        connectedServerText.text = "Connected server: ${if (url.isBlank()) "none" else url}"
        monitorFolderText.text = "Monitor folder: ${monitorFolderUri?.toString() ?: "not set"}"
        autoSaveCheckbox.isChecked = prefs.getBoolean("auto_save_camera_media", false)

        selectMonitorFolderBtn.setOnClickListener {
            monitorFolderPickerLauncher.launch(monitorFolderUri)
        }

        saveButton.setOnClickListener {
            val normalized = normalizeServerUrl(urlInput.text.toString())
            prefs.edit()
                .putString("last_server_url", normalized)
                .putString("last_folder_path", pathInput.text.toString().trim())
                .putBoolean("auto_save_camera_media", autoSaveCheckbox.isChecked)
                .putString("monitor_folder_uri", monitorFolderUri?.toString() ?: "")
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
