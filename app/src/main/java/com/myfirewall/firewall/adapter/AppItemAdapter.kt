package com.myfirewall.firewall.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.RequestOptions
import com.myfirewall.R
import com.net.firewall.Rule

class AppItemAdapter(
    private val items: List<Rule>,
    private val appItemListener: AppItemListener
) : RecyclerView.Adapter<AppItemAdapter.AppItemViewHolder>() {

    class AppItemViewHolder(
        itemView: View,
        private val appItemListener: AppItemListener
    ) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.ic_app)
        val appName: TextView = itemView.findViewById(R.id.tv_app_name)
        val packageName: TextView = itemView.findViewById(R.id.tv_package_name)
        val switch: Switch = itemView.findViewById(R.id.enabledSw)

        fun bind(item: Rule) {
            if (item.icon <= 0) appIcon.setImageResource(android.R.drawable.sym_def_app_icon) else {
                val uri = Uri.parse("android.resource://" + item.packageName + "/" + item.icon)
                Glide.with(appIcon.getContext())
                    .applyDefaultRequestOptions(RequestOptions().format(DecodeFormat.PREFER_RGB_565))
                    .load(uri) //.diskCacheStrategy(DiskCacheStrategy.NONE)
                    //.skipMemoryCache(true)
                    .into(appIcon)
            }
            //if (item.icon != 0) appIcon.setImageResource(item.icon)
            appName.text = item.name
            packageName.text = item.packageName
            switch.isChecked = !item.wifi_blocked && !item.other_blocked

            switch.setOnCheckedChangeListener { buttonView, isChecked ->
                item.wifi_blocked = !isChecked
                item.other_blocked = !isChecked
                appItemListener.settingsChanged(item)
            }

            itemView.setOnClickListener {
                appItemListener.onItemClicked(item.uid)
            }

        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppItemViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.app_item, parent, false)
        return AppItemViewHolder(view, appItemListener)
    }

    override fun onBindViewHolder(holder: AppItemViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

}
