package com.example.windrive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.webkit.MimeTypeMap
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
    private var allFiles: List<String> = emptyList()

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
        val addFolderFab = findViewById<Button>(R.id.addFolderFab)
        val searchInput = findViewById<android.widget.EditText>(R.id.searchInput)
        statusText = findViewById(R.id.statusText)
        val filesList = findViewById<RecyclerView>(R.id.filesList)

        adapter = FileListAdapter(
            onTileClicked = { fileName -> openMediaOnShortPress(fileName) },
            onTileLongClicked = { fileName -> showFileActionsPopup(fileName) },
            onImagePreviewRequested = { fileName, callback -> loadImagePreview(fileName, callback) }
        )
        filesList.layoutManager = GridLayoutManager(this, 2)
        filesList.adapter = adapter

        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        uploadPickBtn.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        addFolderFab.setOnClickListener {
            promptCreateFolder()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applySearchFilter(s?.toString().orEmpty())
            }
        })
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
                allFiles = files
                statusText.text = "Connected • ${files.size} item(s)"
                applySearchFilter("")
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




    private fun openMediaOnShortPress(fileName: String) {
        if (!isImageOrVideo(fileName)) {
            showFileActionsPopup(fileName)
            return
        }

        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        val baseUrl = normalizeServerUrl(prefs.getString("last_server_url", "") ?: "")
        val path = prefs.getString("last_folder_path", "/") ?: "/"
        if (baseUrl.isBlank() || path.isBlank()) {
            statusText.text = "Configure server in Settings"
            return
        }

        uiScope.launch {
            statusText.text = "Opening $fileName..."
            val result = fetchMediaToDownloads(baseUrl, path, fileName)
            result.onSuccess { uri ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeTypeFor(fileName))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivity(intent) }
                    .onFailure { statusText.text = "No app available to open this file" }
            }.onFailure {
                statusText.text = it.message ?: "Open failed"
            }
        }
    }

    private fun isImageOrVideo(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".webp") ||
            lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv") || lower.endsWith(".webm")
    }

    private fun mimeTypeFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun loadImagePreview(fileName: String, callback: (Bitmap?) -> Unit) {
        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        val baseUrl = normalizeServerUrl(prefs.getString("last_server_url", "") ?: "")
        val path = prefs.getString("last_folder_path", "/") ?: "/"
        if (baseUrl.isBlank() || path.isBlank()) {
            callback(null)
            return
        }

        uiScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val p = URLEncoder.encode(path.removePrefix("/"), "UTF-8")
                    val f = URLEncoder.encode(fileName, "UTF-8")
                    val connection = openConnection("$baseUrl/download?path=$p&name=$f", "GET")
                    if (connection.responseCode != 200) return@runCatching null
                    connection.inputStream.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }.getOrNull()
            }
            callback(bitmap)
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


    private fun applySearchFilter(query: String) {
        val q = query.trim().lowercase()
        if (q.isBlank()) {
            adapter.submit(allFiles)
            return
        }
        adapter.submit(allFiles.filter { it.lowercase().contains(q) })
    }

    private fun promptCreateFolder() {
        val input = android.widget.EditText(this)
        input.hint = "Folder name"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val folderName = input.text.toString().trim()
                if (folderName.isNotBlank()) createFolderFromCurrentSettings(folderName)
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(true)
            .show()
    }

    private fun createFolderFromCurrentSettings(folderName: String) {
        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        val baseUrl = normalizeServerUrl(prefs.getString("last_server_url", "") ?: "")
        val path = prefs.getString("last_folder_path", "/") ?: "/"
        if (baseUrl.isBlank() || path.isBlank()) {
            statusText.text = "Configure server in Settings"
            return
        }
        uiScope.launch {
            statusText.text = "Creating folder $folderName..."
            val result = createFolder(baseUrl, path, folderName)
            statusText.text = result.fold(
                onSuccess = { "Created folder $folderName" },
                onFailure = { "Create folder failed (server must support /mkdir)" }
            )
            refreshListingFromCurrentSettings()
        }
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

    private suspend fun fetchMediaToDownloads(baseUrl: String, path: String, fileName: String): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val p = URLEncoder.encode(path.removePrefix("/"), "UTF-8")
            val f = URLEncoder.encode(fileName, "UTF-8")
            val connection = openConnection("$baseUrl/download?path=$p&name=$f", "GET")
            if (connection.responseCode != 200) error("Open failed: ${connection.responseCode}")

            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(fileName))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create file")

            contentResolver.openOutputStream(uri)?.use { out ->
                connection.inputStream.use { input -> input.copyTo(out) }
            } ?: error("Unable to write file")
            uri
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


    private suspend fun createFolder(baseUrl: String, path: String, folderName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val p = URLEncoder.encode(path.removePrefix("/"), "UTF-8")
            val n = URLEncoder.encode(folderName, "UTF-8")
            val connection = openConnection("$baseUrl/mkdir?path=$p&name=$n", "POST")
            if (connection.responseCode !in 200..299) error("Create folder failed: ${connection.responseCode}")
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
