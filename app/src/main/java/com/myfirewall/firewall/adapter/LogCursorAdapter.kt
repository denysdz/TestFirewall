package com.myfirewall.firewall.adapter

import android.content.Context
import android.database.Cursor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.myfirewall.R
import com.net.firewall.DatabaseHelper
import com.net.firewall.ServiceFirewall
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogRecyclerViewAdapter(private val context: Context, private var cursor: Cursor?) :
    RecyclerView.Adapter<LogRecyclerViewAdapter.LogViewHolder>() {

    inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val addressTextView: TextView = itemView.findViewById(R.id.address)
        val dateTextView: TextView = itemView.findViewById(R.id.date)
        val statusTextView: TextView = itemView.findViewById(R.id.txt_status)
        val imageView: ImageView = itemView.findViewById(R.id.ic_log)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.log_item, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        cursor?.moveToPosition(position)
        cursor?.let {
            val id = it.getLong(it.getColumnIndexOrThrow("ID"))
            val address = it.getString(it.getColumnIndexOrThrow("daddr"))
            val date = it.getLong(it.getColumnIndexOrThrow("time"))
            val status = it.getInt(it.getColumnIndexOrThrow("block"))

            holder.addressTextView.text = address
            holder.dateTextView.text = formatDate(date)

            holder.statusTextView.text = if (status > 0) "Block" else "Allow"
            holder.imageView.setImageResource(R.drawable.baseline_link_24)

            holder.statusTextView.setOnClickListener {
                val st = if (status > 0) -1 else 1
                DatabaseHelper.getInstance(context).setAccess(id, st)
                holder.statusTextView.text = if (st > 0) "Block" else "Allow"
                ServiceFirewall.reload("allow host", context, false)
            }
        }


    }

    override fun getItemCount(): Int {
        return cursor?.count ?: 0
    }

    fun swapCursor(newCursor: Cursor?) {
        if (cursor != null) {
            cursor?.close()
        }
        cursor = newCursor
        newCursor?.let { notifyDataSetChanged() }
    }

    private fun formatDate(date: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date(date))
    }
}
