package com.myfirewall

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.myfirewall.firewall.MainFragment

class MainActivity : AppCompatActivity() {


    companion object {
        @kotlin.jvm.JvmField
        var EXTRA_SEARCH = "Search"
        @kotlin.jvm.JvmField
        var ACTION_RULES_CHANGED = "eu.faircode.netguard.ACTION_RULES_CHANGED"
        @kotlin.jvm.JvmField
        var ACTION_QUEUE_CHANGED = "eu.faircode.netguard.ACTION_QUEUE_CHANGED"
        @kotlin.jvm.JvmField
        var EXTRA_REFRESH = "Refresh"
        @kotlin.jvm.JvmField
        var EXTRA_RELATED = "Related"
        @kotlin.jvm.JvmField
        var EXTRA_APPROVE = "Approve"
        @kotlin.jvm.JvmField
        var EXTRA_CONNECTED = "Connected"
        @kotlin.jvm.JvmField
        var EXTRA_METERED = "Metered"
        @kotlin.jvm.JvmField
        var EXTRA_SIZE = "Size"
        @kotlin.jvm.JvmField
        var REQUEST_VPN = 1
        @kotlin.jvm.JvmField
        var REQUEST_INVITE = 2
        @kotlin.jvm.JvmField
        var REQUEST_ROAMING = 3
        @kotlin.jvm.JvmField
        var REQUEST_NOTIFICATIONS = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        init()
    }

    fun init () {
        supportFragmentManager.beginTransaction().replace(R.id.frame_layout, MainFragment()).commit()
    }


}