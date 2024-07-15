package com.myfirewall

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.RequestOptions
import com.myfirewall.databinding.ActivityAppLogBinding
import com.myfirewall.firewall.adapter.LogRecyclerViewAdapter
import com.net.firewall.DatabaseHelper
import com.net.firewall.Rule

class AppLogActivity : AppCompatActivity() {

    private var uid = -1
    private lateinit var binding:ActivityAppLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        intent.extras.let {
            uid = it?.getInt("uid", -1) ?: -1
        }
        val item = Rule.getRules(false, this).find { it.uid == uid }
        if (item != null) {
            if (item.icon <= 0) binding.imageView.setImageResource(android.R.drawable.sym_def_app_icon) else {
                val uri = Uri.parse("android.resource://" + item.packageName + "/" + item.icon)
                Glide.with(binding.imageView.getContext())
                    .applyDefaultRequestOptions(RequestOptions().format(DecodeFormat.PREFER_RGB_565))
                    .load(uri) //.diskCacheStrategy(DiskCacheStrategy.NONE)
                    //.skipMemoryCache(true)
                    .into(binding.imageView)
            }
            binding.textView.text = item.name
            binding.textView1.text = item.packageName
            val cursor = DatabaseHelper.getInstance(this).getAccess(uid)
            val adapter = LogRecyclerViewAdapter(this, cursor)
            binding.logListView.adapter = adapter
            val manager = LinearLayoutManager(this)
            manager.orientation = LinearLayoutManager.VERTICAL
            binding.logListView.layoutManager = manager
        }
    }

}