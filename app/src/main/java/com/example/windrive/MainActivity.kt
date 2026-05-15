package com.example.windrive

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private lateinit var adapter: FileListAdapter
    private var selectedUploadUri: android.net.Uri? = null
    private lateinit var statusText: TextView

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedUploadUri = uri
        if (uri != null) {
            val resolvedName = resolveDisplayName(uri) ?: "upload.bin"
            uploadSelectedFile(resolvedName)
        } else {
            statusText.text = "No file selected"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val settingsBtn = findViewById<Button>(R.id.settingsBtn)
        val uploadPickBtn = findViewById<Button>(R.id.uploadPickBtn)
        statusText = findViewById(R.id.statusText)
        val filesList = findViewById<RecyclerView>(R.id.filesList)

        adapter = FileListAdapter { fileName -> showFileActionsPopup(fileName) }
        filesList.layoutManager = GridLayoutManager(this, 2)
        filesList.adapter = adapter

        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        uploadPickBtn.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshListingFromCurrentSettings()
    }

    private fun refreshListingFromCurrentSettings() {
        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        val baseUrl = normalizeServerUrl(prefs.getString("last_server_url", "") ?: "")
        val path = prefs.getString("last_folder_path", "/") ?: "/"
        if (baseUrl.isBlank() || path.isBlank()) {
            statusText.text = "Configure server in Settings"
            adapter.submit(emptyList())
            return
        }

        statusText.text = "Loading..."
        uiScope.launch {
            val result = fetchFolderListing(baseUrl, path)
            if (result.isSuccess) {
                val files = result.getOrNull().orEmpty()
                statusText.text = "Connected • ${files.size} item(s)"
                adapter.submit(files)
            } else {
                statusText.text = result.exceptionOrNull()?.message ?: "Connection failed"
                adapter.submit(emptyList())
            }
        }
    }

    private fun uploadSelectedFile(fileName: String) {
        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        val baseUrl = normalizeServerUrl(prefs.getString("last_server_url", "") ?: "")
        val path = prefs.getString("last_folder_path", "/") ?: "/"
        val uri = selectedUploadUri ?: return
        if (baseUrl.isBlank() || path.isBlank()) {
            statusText.text = "Configure server in Settings"
            return
        }

        uiScope.launch {
            statusText.text = "Uploading $fileName..."
            val result = uploadFile(baseUrl, path, fileName, uri)
            statusText.text = result.fold(
                onSuccess = { "Uploaded $fileName" },
                onFailure = { it.message ?: "Upload failed" }
            )
            refreshListingFromCurrentSettings()
        }
    }

    private fun downloadFromCurrentSettings(fileName: String) {
        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        val baseUrl = normalizeServerUrl(prefs.getString("last_server_url", "") ?: "")
        val path = prefs.getString("last_folder_path", "/") ?: "/"
        if (baseUrl.isBlank() || path.isBlank()) {
            statusText.text = "Configure server in Settings"
            return
        }

        uiScope.launch {
            statusText.text = "Downloading $fileName..."
            val result = downloadFile(baseUrl, path, fileName)
            statusText.text = result.fold(
                onSuccess = { location -> "Downloaded to $location" },
                onFailure = { it.message ?: "Download failed" }
            )
        }
    }

    private fun deleteFromCurrentSettings(fileName: String) {
        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        val baseUrl = normalizeServerUrl(prefs.getString("last_server_url", "") ?: "")
        val path = prefs.getString("last_folder_path", "/") ?: "/"
        if (baseUrl.isBlank() || path.isBlank()) {
            statusText.text = "Configure server in Settings"
            return
        }

        uiScope.launch {
            statusText.text = "Deleting $fileName..."
            val result = deleteFile(baseUrl, path, fileName)
            statusText.text = result.fold(
                onSuccess = { "Deleted $fileName" },
                onFailure = { it.message ?: "Delete failed" }
            )
            refreshListingFromCurrentSettings()
        }
    }


    private fun showFileActionsPopup(fileName: String) {
        val options = arrayOf("Download", "Delete", "Cancel")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(fileName)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> downloadFromCurrentSettings(fileName)
                    1 -> deleteFromCurrentSettings(fileName)
                    else -> dialog.dismiss()
                }
            }
            .setCancelable(true)
            .show()
    }

    private fun normalizeServerUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isBlank()) return trimmed
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }

    private fun resolveDisplayName(uri: android.net.Uri): String? {
        val cursor = contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return null
    }

    private suspend fun fetchFolderListing(baseUrl: String, path: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedPath = URLEncoder.encode(path.removePrefix("/"), "UTF-8")
            val connection = openConnection("$baseUrl/list?path=$encodedPath", "GET")
            val body = connection.inputStream.use { BufferedReader(InputStreamReader(it)).readText() }
            if (connection.responseCode != 200) error("Server returned ${connection.responseCode}")
            body.lines().filter { it.isNotBlank() }
        }
    }

    private suspend fun uploadFile(baseUrl: String, path: String, fileName: String, fileUri: android.net.Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val p = URLEncoder.encode(path.removePrefix("/"), "UTF-8")
            val f = URLEncoder.encode(fileName, "UTF-8")
            val connection = openConnection("$baseUrl/upload?path=$p&name=$f", "POST")
            connection.doOutput = true
            contentResolver.openInputStream(fileUri)?.use { input ->
                connection.outputStream.use { out -> input.copyTo(out) }
            } ?: error("Unable to read selected file")
            if (connection.responseCode !in 200..299) error("Upload failed: ${connection.responseCode}")
        }
    }

    private suspend fun downloadFile(baseUrl: String, path: String, fileName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val p = URLEncoder.encode(path.removePrefix("/"), "UTF-8")
            val f = URLEncoder.encode(fileName, "UTF-8")
            val connection = openConnection("$baseUrl/download?path=$p&name=$f", "GET")
            if (connection.responseCode != 200) error("Download failed: ${connection.responseCode}")
            val headerName = connection.getHeaderField("Content-Disposition")
                ?.substringAfter("filename=", fileName)
                ?.trim('"')
                ?.ifBlank { fileName }
                ?: fileName

            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, headerName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create download file")

            contentResolver.openOutputStream(uri)?.use { out ->
                connection.inputStream.use { input -> input.copyTo(out) }
            } ?: error("Unable to write download file")

            "Downloads/$headerName"
        }
    }

    private suspend fun deleteFile(baseUrl: String, path: String, fileName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val p = URLEncoder.encode(path.removePrefix("/"), "UTF-8")
            val f = URLEncoder.encode(fileName, "UTF-8")
            val connection = openConnection("$baseUrl/delete?path=$p&name=$f", "POST")
            if (connection.responseCode !in 200..299) error("Delete failed: ${connection.responseCode}")
        }
    }

    private fun openConnection(url: String, method: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 10000
        }
    }
}
