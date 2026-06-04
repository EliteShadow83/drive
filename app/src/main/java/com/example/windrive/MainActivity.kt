package com.example.windrive

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var selectedUploadUri: Uri? = null
    private lateinit var statusText: TextView
    private var allFiles: List<String> = emptyList()
    private var autoSaveMonitorJob: Job? = null

    private val uploadNotificationId = 1001
    private val uploadNotificationChannel = "upload_progress"

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && ::statusText.isInitialized) {
            statusText.text = "Upload notifications are disabled"
        }
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedUploadUri = uri
        if (uri != null) {
            val resolvedName = resolveDisplayName(uri) ?: "upload.bin"
            uploadSelectedFile(resolvedName)
        } else {
            statusText.text = "No file selected"
        }
    }

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            persistFolderReadPermission(treeUri)
            uploadFolderFromTree(treeUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createNotificationChannel()

        val settingsBtn = findViewById<Button>(R.id.settingsBtn)
        val uploadPickBtn = findViewById<Button>(R.id.uploadPickBtn)
        val addFolderFab = findViewById<Button>(R.id.addFolderFab)
        val searchInput = findViewById<android.widget.EditText>(R.id.searchInput)
        statusText = findViewById(R.id.statusText)
        requestNotificationPermissionIfNeeded()
        val filesList = findViewById<RecyclerView>(R.id.filesList)

        adapter = FileListAdapter(
            onTileClicked = { fileName -> openMediaOnShortPress(fileName) },
            onTileLongClicked = { fileName -> showFileActionsPopup(fileName) },
            onImagePreviewRequested = { fileName, callback -> loadImagePreview(fileName, callback) }
        )
        filesList.layoutManager = GridLayoutManager(this, 2)
        filesList.adapter = adapter

        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        uploadPickBtn.setOnClickListener { filePickerLauncher.launch("*/*") }
        addFolderFab.setOnClickListener { showPlusOptions() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { applySearchFilter(s?.toString().orEmpty()) }
        })
    }

    override fun onResume() {
        super.onResume()
        refreshListingFromCurrentSettings()
        startAutoSaveMonitorIfEnabled()
    }

    override fun onDestroy() {
        autoSaveMonitorJob?.cancel()
        autoSaveMonitorJob = null
        super.onDestroy()
    }

    private fun startAutoSaveMonitorIfEnabled() {
        autoSaveMonitorJob?.cancel()
        autoSaveMonitorJob = null

        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_save_camera_media", false)) return

        val monitorFolderUri = prefs.getString("monitor_folder_uri", "").orEmpty()
        if (monitorFolderUri.isBlank()) {
            statusText.text = "Select a camera monitor folder in Settings"
            return
        }

        val root = DocumentFile.fromTreeUri(this, Uri.parse(monitorFolderUri)) ?: run {
            statusText.text = "Monitor folder is unavailable"
            return
        }
        val baseUrl = normalizeServerUrl(prefs.getString("last_server_url", "") ?: "")
        val path = prefs.getString("last_folder_path", "/") ?: "/"
        if (baseUrl.isBlank() || path.isBlank()) {
            statusText.text = "Configure server in Settings"
            return
        }

        autoSaveMonitorJob = uiScope.launch {
            runCatching { baselineExistingCameraMediaIfNeeded(prefs, root, monitorFolderUri) }
                .onFailure { statusText.text = "Auto-save setup failed: ${it.message ?: "folder access error"}" }

            while (isActive) {
                runCatching { uploadNewCameraMedia(prefs, root, monitorFolderUri, baseUrl, path) }
                    .onSuccess { uploadedCount ->
                        if (uploadedCount > 0) {
                            statusText.text = "Auto-saved $uploadedCount new camera file(s)"
                            refreshListingFromCurrentSettings()
                        }
                    }
                    .onFailure { statusText.text = "Auto-save paused: ${it.message ?: "folder access error"}" }
                delay(15_000)
            }
        }
    }

    private fun baselineExistingCameraMediaIfNeeded(
        prefs: android.content.SharedPreferences,
        root: DocumentFile,
        monitorFolderUri: String
    ) {
        val initializedKey = "auto_save_initialized_$monitorFolderUri"
        if (prefs.getBoolean(initializedKey, false)) return

        val seen = collectMediaFiles(root).map { it.uri.toString() }.toSet()
        prefs.edit()
            .putStringSet("auto_saved_media_$monitorFolderUri", seen)
            .putBoolean(initializedKey, true)
            .apply()
        statusText.text = "Auto-save is watching for new photos/videos"
    }

    private suspend fun uploadNewCameraMedia(
        prefs: android.content.SharedPreferences,
        root: DocumentFile,
        monitorFolderUri: String,
        baseUrl: String,
        path: String
    ): Int {
        val seenKey = "auto_saved_media_$monitorFolderUri"
        val seen = prefs.getStringSet(seenKey, emptySet()).orEmpty().toMutableSet()
        var uploaded = 0

        collectMediaFiles(root)
            .filterNot { seen.contains(it.uri.toString()) }
            .forEach { file ->
                val fileName = file.name ?: return@forEach
                val result = uploadFile(baseUrl, path, fileName, file.uri)
                if (result.isSuccess) {
                    seen.add(file.uri.toString())
                    uploaded++
                    prefs.edit().putStringSet(seenKey, seen.toSet()).apply()
                }
            }

        return uploaded
    }

    private fun collectMediaFiles(folder: DocumentFile): List<DocumentFile> {
        val children = runCatching { folder.listFiles() }.getOrElse { emptyArray() }
        val files = mutableListOf<DocumentFile>()
        children.forEach { child ->
            when {
                child.isDirectory -> files += collectMediaFiles(child)
                child.isFile && isCameraMediaFile(child) -> files += child
            }
        }
        return files
    }

    private fun isCameraMediaFile(file: DocumentFile): Boolean {
        val mimeType = file.type.orEmpty()
        val name = file.name.orEmpty()
        return mimeType.startsWith("image/") || mimeType.startsWith("video/") || isImageOrVideo(name)
    }

    private fun persistFolderReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure {
            statusText.text = "Folder access is temporary; reselect folder if upload fails"
        }
    }

    private fun showPlusOptions() {
        val options = arrayOf("Create Server Folder", "Upload Phone Folder", "Cancel")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add")
            .setItems(options) { d, which ->
                when (which) {
                    0 -> promptCreateFolder()
                    1 -> folderPickerLauncher.launch(null)
                    else -> d.dismiss()
                }
            }
            .setCancelable(true)
            .show()
    }

    private fun uploadFolderFromTree(treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: run {
            statusText.text = "Invalid folder"
            return
        }
        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        val baseUrl = normalizeServerUrl(prefs.getString("last_server_url", "") ?: "")
        val path = prefs.getString("last_folder_path", "/") ?: "/"
        if (baseUrl.isBlank() || path.isBlank()) { statusText.text = "Configure server in Settings"; return }

        uiScope.launch {
            statusText.text = "Uploading folder..."
            val files = runCatching { root.listFiles().filter { it.isFile } }.getOrElse {
                statusText.text = "Unable to read selected folder"
                emptyList()
            }
            var ok = 0
            files.forEach { file ->
                val name = file.name ?: return@forEach
                val result = uploadFile(baseUrl, path, name, file.uri)
                if (result.isSuccess) ok++
            }
            statusText.text = "Uploaded $ok/${files.size} files"
            refreshListingFromCurrentSettings()
        }
    }

    private fun refreshListingFromCurrentSettings() { /* unchanged core */
        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        val baseUrl = normalizeServerUrl(prefs.getString("last_server_url", "") ?: "")
        val path = prefs.getString("last_folder_path", "/") ?: "/"
        if (baseUrl.isBlank() || path.isBlank()) { statusText.text = "Configure server in Settings"; adapter.submit(emptyList()); return }
        statusText.text = "Loading..."
        uiScope.launch {
            val result = fetchFolderListing(baseUrl, path)
            if (result.isSuccess) { val files = result.getOrNull().orEmpty(); allFiles = files; statusText.text = "Connected • ${files.size} item(s)"; applySearchFilter("") }
            else { statusText.text = result.exceptionOrNull()?.message ?: "Connection failed"; adapter.submit(emptyList()) }
        }
    }

    private fun uploadSelectedFile(fileName: String) {
        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        val baseUrl = normalizeServerUrl(prefs.getString("last_server_url", "") ?: "")
        val path = prefs.getString("last_folder_path", "/") ?: "/"
        val uri = selectedUploadUri ?: return
        if (baseUrl.isBlank() || path.isBlank()) { statusText.text = "Configure server in Settings"; return }
        uiScope.launch {
            statusText.text = "Uploading $fileName..."
            val result = uploadFile(baseUrl, path, fileName, uri)
            statusText.text = result.fold(onSuccess = { "Uploaded $fileName" }, onFailure = { it.message ?: "Upload failed" })
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(this@MainActivity).cancel(uploadNotificationId)
            }
            refreshListingFromCurrentSettings()
        }
    }

    private fun applySearchFilter(query: String) { val q = query.trim().lowercase(); adapter.submit(if (q.isBlank()) allFiles else allFiles.filter { it.lowercase().contains(q) }) }
    private fun promptCreateFolder() { val input = android.widget.EditText(this); input.hint = "Folder name"; androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Add Folder").setView(input).setPositiveButton("Create") { _, _ -> val n=input.text.toString().trim(); if(n.isNotBlank()) createFolderFromCurrentSettings(n)}.setNegativeButton("Cancel", null).setCancelable(true).show() }
    private fun createFolderFromCurrentSettings(folderName: String) { val prefs=getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE); val baseUrl=normalizeServerUrl(prefs.getString("last_server_url", "")?:""); val path=prefs.getString("last_folder_path", "/")?:"/"; if(baseUrl.isBlank()||path.isBlank()){statusText.text="Configure server in Settings"; return}; uiScope.launch{statusText.text="Creating folder $folderName..."; val result=createFolder(baseUrl,path,folderName); statusText.text=result.fold(onSuccess={"Created folder $folderName"}, onFailure={"Create folder failed (server must support /mkdir)"}); refreshListingFromCurrentSettings() } }

    private fun openMediaOnShortPress(fileName: String) { /* unchanged */
        if (!isImageOrVideo(fileName)) { showFileActionsPopup(fileName); return }
        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        val baseUrl = normalizeServerUrl(prefs.getString("last_server_url", "") ?: "")
        val path = prefs.getString("last_folder_path", "/") ?: "/"
        if (baseUrl.isBlank() || path.isBlank()) { statusText.text = "Configure server in Settings"; return }
        uiScope.launch {
            statusText.text = "Opening $fileName..."
            fetchMediaToDownloads(baseUrl, path, fileName).onSuccess { uri ->
                val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mimeTypeFor(fileName)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                runCatching { startActivity(intent) }.onFailure { statusText.text = "No app available to open this file" }
            }.onFailure { statusText.text = it.message ?: "Open failed" }
        }
    }

    private fun isImageOrVideo(fileName: String): Boolean { val l=fileName.lowercase(); return l.endsWith(".jpg")||l.endsWith(".jpeg")||l.endsWith(".png")||l.endsWith(".gif")||l.endsWith(".webp")||l.endsWith(".mp4")||l.endsWith(".mov")||l.endsWith(".mkv")||l.endsWith(".webm") }
    private fun mimeTypeFor(fileName: String): String { val ext=fileName.substringAfterLast('.', "").lowercase(); return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream" }

    private fun loadImagePreview(fileName: String, callback: (Bitmap?) -> Unit) { /* unchanged */
        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        val baseUrl = normalizeServerUrl(prefs.getString("last_server_url", "") ?: "")
        val path = prefs.getString("last_folder_path", "/") ?: "/"
        if (baseUrl.isBlank() || path.isBlank()) { callback(null); return }
        uiScope.launch { val bitmap = withContext(Dispatchers.IO) { runCatching { val p=URLEncoder.encode(path.removePrefix("/"), "UTF-8"); val f=URLEncoder.encode(fileName, "UTF-8"); val c=openConnection("$baseUrl/download?path=$p&name=$f", "GET"); if(c.responseCode!=200) return@runCatching null; c.inputStream.use { BitmapFactory.decodeStream(it) } }.getOrNull() }; callback(bitmap) }
    }

    private fun showFileActionsPopup(fileName: String) { val options=arrayOf("Download","Delete","Cancel"); androidx.appcompat.app.AlertDialog.Builder(this).setTitle(fileName).setItems(options){d,w->when(w){0->downloadFromCurrentSettings(fileName);1->deleteFromCurrentSettings(fileName);else->d.dismiss()}}.setCancelable(true).show() }
    private fun normalizeServerUrl(input: String): String { val t=input.trim().trimEnd('/'); if (t.isBlank()) return t; return if (t.startsWith("http://")||t.startsWith("https://")) t else "http://$t" }
    private fun resolveDisplayName(uri: Uri): String? { val c=contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null); c?.use { if (it.moveToFirst()) return it.getString(0) }; return null }

    private suspend fun fetchFolderListing(baseUrl: String, path: String): Result<List<String>> = withContext(Dispatchers.IO) { runCatching { val p=URLEncoder.encode(path.removePrefix("/"), "UTF-8"); val c=openConnection("$baseUrl/list?path=$p", "GET"); val body=c.inputStream.use { BufferedReader(InputStreamReader(it)).readText() }; if (c.responseCode!=200) error("Server returned ${c.responseCode}"); body.lines().filter { it.isNotBlank() } } }

    private suspend fun uploadFile(baseUrl: String, path: String, fileName: String, fileUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val p = URLEncoder.encode(path.removePrefix("/"), "UTF-8")
            val f = URLEncoder.encode(fileName, "UTF-8")
            val connection = openConnection("$baseUrl/upload?path=$p&name=$f", "POST")
            connection.doOutput = true

            val totalBytes = contentResolver.openAssetFileDescriptor(fileUri, "r")?.length ?: -1L
            contentResolver.openInputStream(fileUri)?.use { input ->
                connection.outputStream.use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var uploaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } >= 0) {
                        out.write(buffer, 0, read)
                        uploaded += read
                        showUploadProgressNotification(fileName, uploaded, totalBytes)
                    }
                }
            } ?: error("Unable to read selected file")
            if (connection.responseCode !in 200..299) error("Upload failed: ${connection.responseCode}")
            showUploadProgressNotification(fileName, totalBytes, totalBytes, done = true)
        }
    }

    private suspend fun fetchMediaToDownloads(baseUrl: String, path: String, fileName: String): Result<Uri> = withContext(Dispatchers.IO) { runCatching { val p=URLEncoder.encode(path.removePrefix("/"), "UTF-8"); val f=URLEncoder.encode(fileName, "UTF-8"); val c=openConnection("$baseUrl/download?path=$p&name=$f", "GET"); if(c.responseCode!=200) error("Open failed: ${c.responseCode}"); val v=android.content.ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME,fileName); put(MediaStore.Downloads.MIME_TYPE,mimeTypeFor(fileName)); put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS)}; val uri=contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?:error("Unable to create file"); contentResolver.openOutputStream(uri)?.use { out -> c.inputStream.use { it.copyTo(out) } }?:error("Unable to write file"); uri } }
    private suspend fun downloadFile(baseUrl: String, path: String, fileName: String): Result<String> = withContext(Dispatchers.IO) { runCatching { val p=URLEncoder.encode(path.removePrefix("/"), "UTF-8"); val f=URLEncoder.encode(fileName, "UTF-8"); val c=openConnection("$baseUrl/download?path=$p&name=$f", "GET"); if(c.responseCode!=200) error("Download failed: ${c.responseCode}"); val h=c.getHeaderField("Content-Disposition")?.substringAfter("filename=", fileName)?.trim('"')?.ifBlank { fileName }?:fileName; val v=android.content.ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME,h); put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream"); put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS)}; val uri=contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?:error("Unable to create download file"); contentResolver.openOutputStream(uri)?.use { out -> c.inputStream.use { it.copyTo(out) } }?:error("Unable to write download file"); "Downloads/$h" } }
    private suspend fun deleteFile(baseUrl: String, path: String, fileName: String): Result<Unit> = withContext(Dispatchers.IO) { runCatching { val p=URLEncoder.encode(path.removePrefix("/"), "UTF-8"); val f=URLEncoder.encode(fileName, "UTF-8"); val c=openConnection("$baseUrl/delete?path=$p&name=$f", "POST"); if(c.responseCode !in 200..299) error("Delete failed: ${c.responseCode}") } }
    private suspend fun createFolder(baseUrl: String, path: String, folderName: String): Result<Unit> = withContext(Dispatchers.IO) { runCatching { val p=URLEncoder.encode(path.removePrefix("/"), "UTF-8"); val n=URLEncoder.encode(folderName, "UTF-8"); val c=openConnection("$baseUrl/mkdir?path=$p&name=$n", "POST"); if(c.responseCode !in 200..299) error("Create folder failed: ${c.responseCode}") } }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(uploadNotificationChannel, "Upload Progress", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showUploadProgressNotification(fileName: String, uploaded: Long, total: Long, done: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val builder = NotificationCompat.Builder(this, uploadNotificationChannel)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (done) "Upload complete" else "Uploading $fileName")
            .setOnlyAlertOnce(true)
            .setOngoing(!done)

        if (total > 0 && !done) {
            val percent = ((uploaded * 100) / total).toInt().coerceIn(0, 100)
            builder.setContentText("$percent%")
            builder.setProgress(100, percent, false)
        } else if (!done) {
            builder.setContentText("Uploading...")
            builder.setProgress(0, 0, true)
        } else {
            builder.setContentText(fileName)
            builder.setProgress(0, 0, false)
            builder.setSmallIcon(android.R.drawable.stat_sys_upload_done)
        }

        runCatching { NotificationManagerCompat.from(this).notify(uploadNotificationId, builder.build()) }
            .onFailure { if (::statusText.isInitialized) statusText.text = "Upload notifications unavailable" }
    }

    private fun openConnection(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 5000; readTimeout = 10000 }

    private fun downloadFromCurrentSettings(fileName: String) { val prefs=getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE); val baseUrl=normalizeServerUrl(prefs.getString("last_server_url", "")?:""); val path=prefs.getString("last_folder_path", "/")?:"/"; if(baseUrl.isBlank()||path.isBlank()){statusText.text="Configure server in Settings"; return}; uiScope.launch { statusText.text="Downloading $fileName..."; val r=downloadFile(baseUrl,path,fileName); statusText.text=r.fold(onSuccess={"Downloaded to $it"}, onFailure={it.message?:"Download failed"}) } }
    private fun deleteFromCurrentSettings(fileName: String) { val prefs=getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE); val baseUrl=normalizeServerUrl(prefs.getString("last_server_url", "")?:""); val path=prefs.getString("last_folder_path", "/")?:"/"; if(baseUrl.isBlank()||path.isBlank()){statusText.text="Configure server in Settings"; return}; uiScope.launch { statusText.text="Deleting $fileName..."; val r=deleteFile(baseUrl,path,fileName); statusText.text=r.fold(onSuccess={"Deleted $fileName"}, onFailure={it.message?:"Delete failed"}); refreshListingFromCurrentSettings() } }
}
