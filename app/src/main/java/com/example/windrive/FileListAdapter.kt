package com.example.windrive

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FileListAdapter(
    private val onItemClicked: (String) -> Unit
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
        holder.itemView.setOnClickListener { onItemClicked(name) }
    }

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fileName: TextView = view.findViewById(R.id.fileName)
    }
}
