package com.example.windrive

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class FileListAdapter(
    private val onTileClicked: (String) -> Unit,
    private val onImagePreviewRequested: (String, (Bitmap?) -> Unit) -> Unit
) : RecyclerView.Adapter<FileListAdapter.FileViewHolder>() {
    private val files = mutableListOf<String>()

    fun submit(items: List<String>) {
        files.clear()
        files.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun getItemCount(): Int = files.size

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val name = files[position]
        holder.fileName.text = name
        holder.fileIcon.text = iconFor(name)
        holder.filePreview.text = previewFor(name)
        holder.fileImagePreview.visibility = View.GONE
        holder.fileImagePreview.setImageBitmap(null)

        if (isImage(name)) {
            onImagePreviewRequested(name) { bitmap ->
                if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION && files[holder.bindingAdapterPosition] == name && bitmap != null) {
                    holder.fileImagePreview.visibility = View.VISIBLE
                    holder.fileImagePreview.setImageBitmap(bitmap)
                    holder.fileIcon.visibility = View.GONE
                }
            }
        } else {
            holder.fileIcon.visibility = View.VISIBLE
        }

        holder.itemView.setOnClickListener { onTileClicked(name) }
    }

    private fun isImage(fileName: String): Boolean {
        val lower = fileName.lowercase(Locale.US)
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".webp")
    }

    private fun iconFor(fileName: String): String {
        val lower = fileName.lowercase(Locale.US)
        return when {
            isImage(lower) -> "🖼️"
            lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv") -> "🎬"
            lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac") -> "🎵"
            lower.endsWith(".pdf") -> "📕"
            lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") -> "🗜️"
            lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log") -> "📝"
            lower.endsWith(".apk") -> "📱"
            lower.endsWith(".doc") || lower.endsWith(".docx") -> "📘"
            lower.endsWith(".xls") || lower.endsWith(".xlsx") -> "📗"
            lower.endsWith(".ppt") || lower.endsWith(".pptx") -> "📙"
            else -> "📄"
        }
    }

    private fun previewFor(fileName: String): String {
        val lower = fileName.lowercase(Locale.US)
        return when {
            isImage(lower) -> "Image preview"
            lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv") -> "Video file"
            lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac") -> "Audio file"
            lower.endsWith(".pdf") -> "PDF document"
            lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log") -> "Text document"
            lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") -> "Archive"
            else -> "File"
        }
    }

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fileName: TextView = view.findViewById(R.id.fileName)
        val fileIcon: TextView = view.findViewById(R.id.fileIcon)
        val filePreview: TextView = view.findViewById(R.id.filePreview)
        val fileImagePreview: ImageView = view.findViewById(R.id.fileImagePreview)
    }
}
