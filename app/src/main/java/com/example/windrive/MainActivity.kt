package com.example.windrive

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val urlInput = findViewById<EditText>(R.id.urlInput)
        val pathInput = findViewById<EditText>(R.id.pathInput)
        val fileNameInput = findViewById<EditText>(R.id.fileNameInput)
        val fileContentInput = findViewById<EditText>(R.id.fileContentInput)
        val connectBtn = findViewById<Button>(R.id.connectBtn)
        val uploadBtn = findViewById<Button>(R.id.uploadBtn)
        val downloadBtn = findViewById<Button>(R.id.downloadBtn)
        val deleteBtn = findViewById<Button>(R.id.deleteBtn)
        val statusText = findViewById<TextView>(R.id.statusText)
        val filesList = findViewById<RecyclerView>(R.id.filesList)

        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        urlInput.setText(prefs.getString("last_server_url", ""))
        pathInput.setText(prefs.getString("last_folder_path", "/"))

        adapter = FileListAdapter()
        filesList.layoutManager = LinearLayoutManager(this)
        filesList.adapter = adapter

        connectBtn.setOnClickListener {
            val baseUrl = urlInput.text.toString().trim().trimEnd('/')
            val path = pathInput.text.toString().trim()
            if (baseUrl.isBlank() || path.isBlank()) {
                statusText.text = "Please enter server URL and folder path"
                return@setOnClickListener
            }
            savePrefs(prefs, baseUrl, path)
            refreshListing(baseUrl, path, statusText)
        }

        uploadBtn.setOnClickListener {
            val baseUrl = urlInput.text.toString().trim().trimEnd('/')
            val path = pathInput.text.toString().trim()
            val fileName = fileNameInput.text.toString().trim()
            val content = fileContentInput.text.toString()
            if (baseUrl.isBlank() || path.isBlank() || fileName.isBlank()) {
                statusText.text = "URL, path, and file name are required"
                return@setOnClickListener
            }
            savePrefs(prefs, baseUrl, path)
            uiScope.launch {
                statusText.text = "Uploading..."
                val result = uploadFile(baseUrl, path, fileName, content)
                statusText.text = result.fold(
                    onSuccess = { "Uploaded $fileName" },
                    onFailure = { it.message ?: "Upload failed" }
                )
                refreshListing(baseUrl, path, statusText)
            }
        }

        downloadBtn.setOnClickListener {
            val baseUrl = urlInput.text.toString().trim().trimEnd('/')
            val path = pathInput.text.toString().trim()
            val fileName = fileNameInput.text.toString().trim()
            if (baseUrl.isBlank() || path.isBlank() || fileName.isBlank()) {
                statusText.text = "URL, path, and file name are required"
                return@setOnClickListener
            }
            savePrefs(prefs, baseUrl, path)
            uiScope.launch {
                statusText.text = "Downloading..."
                val result = downloadFile(baseUrl, path, fileName)
                statusText.text = result.fold(
                    onSuccess = { location -> "Downloaded to $location" },
                    onFailure = { it.message ?: "Download failed" }
                )
            }
        }

        deleteBtn.setOnClickListener {
            val baseUrl = urlInput.text.toString().trim().trimEnd('/')
            val path = pathInput.text.toString().trim()
            val fileName = fileNameInput.text.toString().trim()
            if (baseUrl.isBlank() || path.isBlank() || fileName.isBlank()) {
                statusText.text = "URL, path, and file name are required"
                return@setOnClickListener
            }
            savePrefs(prefs, baseUrl, path)
            uiScope.launch {
                statusText.text = "Deleting..."
                val result = deleteFile(baseUrl, path, fileName)
                statusText.text = result.fold(
                    onSuccess = { "Deleted $fileName" },
                    onFailure = { it.message ?: "Delete failed" }
                )
                refreshListing(baseUrl, path, statusText)
            }
        }
    }

    private fun refreshListing(baseUrl: String, path: String, statusText: TextView) {
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

    private fun savePrefs(prefs: android.content.SharedPreferences, baseUrl: String, path: String) {
        prefs.edit().putString("last_server_url", baseUrl).putString("last_folder_path", path).apply()
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

    private suspend fun uploadFile(baseUrl: String, path: String, fileName: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val p = URLEncoder.encode(path.removePrefix("/"), "UTF-8")
            val f = URLEncoder.encode(fileName, "UTF-8")
            val connection = openConnection("$baseUrl/upload?path=$p&name=$f", "POST")
            connection.doOutput = true
            connection.outputStream.use { it.write(content.toByteArray()) }
            if (connection.responseCode !in 200..299) error("Upload failed: ${connection.responseCode}")
        }
    }

    private suspend fun downloadFile(baseUrl: String, path: String, fileName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val p = URLEncoder.encode(path.removePrefix("/"), "UTF-8")
            val f = URLEncoder.encode(fileName, "UTF-8")
            val connection = openConnection("$baseUrl/download?path=$p&name=$f", "GET")
            if (connection.responseCode != 200) error("Download failed: ${connection.responseCode}")
            val outFile = java.io.File(getExternalFilesDir(null), fileName)
            connection.inputStream.use { input -> outFile.outputStream().use { input.copyTo(it) } }
            outFile.absolutePath
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
