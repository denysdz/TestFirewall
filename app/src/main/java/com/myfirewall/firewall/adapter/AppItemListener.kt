package com.myfirewall.firewall.adapter

import com.net.firewall.Rule

interface AppItemListener {

    fun settingsChanged (rule: Rule)

    fun onItemClicked (uid:Int)

}