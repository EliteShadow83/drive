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

class MainActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val urlInput = findViewById<EditText>(R.id.urlInput)
        val pathInput = findViewById<EditText>(R.id.pathInput)
        val connectBtn = findViewById<Button>(R.id.connectBtn)
        val statusText = findViewById<TextView>(R.id.statusText)
        val filesList = findViewById<RecyclerView>(R.id.filesList)

        val prefs = getSharedPreferences("windrive_prefs", Context.MODE_PRIVATE)
        urlInput.setText(prefs.getString("last_server_url", ""))
        pathInput.setText(prefs.getString("last_folder_path", "/"))

        val adapter = FileListAdapter()
        filesList.layoutManager = LinearLayoutManager(this)
        filesList.adapter = adapter

        connectBtn.setOnClickListener {
            val baseUrl = urlInput.text.toString().trim().trimEnd('/')
            val path = pathInput.text.toString().trim()
            if (baseUrl.isBlank() || path.isBlank()) {
                statusText.text = "Please enter server URL and folder path"
                return@setOnClickListener
            }

            prefs.edit()
                .putString("last_server_url", baseUrl)
                .putString("last_folder_path", path)
                .apply()

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
    }

    private suspend fun fetchFolderListing(baseUrl: String, path: String): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val encodedPath = path.removePrefix("/")
                val url = URL("$baseUrl/list?path=$encodedPath")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                val body = connection.inputStream.use { input ->
                    BufferedReader(InputStreamReader(input)).readText()
                }

                if (connection.responseCode != 200) {
                    error("Server returned ${connection.responseCode}")
                }

                body.lines().filter { it.isNotBlank() }
            }
        }
    }
}
