package com.myfirewall.firewall

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.myfirewall.AppLogActivity
import com.myfirewall.MainActivity
import com.myfirewall.databinding.FragmentMainBinding
import com.myfirewall.firewall.adapter.AppItemAdapter
import com.myfirewall.firewall.adapter.AppItemListener
import com.net.firewall.DatabaseHelper
import com.net.firewall.Rule
import com.net.firewall.ServiceFirewall
import com.net.firewall.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class MainFragment : Fragment(), AppItemListener {

    private lateinit var binding:FragmentMainBinding

    private lateinit var rule:MutableList<Rule>

    private lateinit var prefs:SharedPreferences

    private val LOCATION_PERMISSION_REQUEST_CODE = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentMainBinding.inflate(inflater)
        return binding.root
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted->
            if (isGranted){}
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            rule = Rule.getRules(false, requireActivity())
            if (prefs.getBoolean("isFirstStart", true)) {
                for (r in rule) {
                    r.wifi_blocked = false
                    r.other_blocked = false
                    updateRule(requireContext(), r, false, rule.toMutableList())
                }
                prefs.edit().putBoolean("isFirstStart", false).apply()
            }
            lifecycleScope.launch {
                val adapter = AppItemAdapter(rule, this@MainFragment)
                val manager = LinearLayoutManager(requireContext())
                manager.orientation = LinearLayoutManager.VERTICAL
                binding.appsList.layoutManager = manager
                binding.appsList.adapter = adapter
                binding.progress.visibility = View.GONE
            }
        }
        enableFireWall()

        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val notificationManager: NotificationManager = requireContext().getSystemService(
            NotificationManager::class.java
        )
        val channel = NotificationChannel(
            "foreground",
            "Foreground Service",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun enableFireWall () {
        binding.firewallSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enabled", isChecked).apply()
            prefs.edit().putBoolean("filter", true).apply()
            prefs.edit().putBoolean("log_app", true).apply()
            if (isChecked) {
                val prepare = VpnService.prepare(requireContext())
                if (prepare == null) {
                    onActivityResult(
                        MainActivity.REQUEST_VPN,
                        Activity.RESULT_OK,
                        null
                    )
                } else {
                    startActivityForResult(prepare, MainActivity.REQUEST_VPN)
                }
            } else {
                ServiceFirewall.stop("switch off", requireContext(), false)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.i(
         "", "onActivityResult request=" + requestCode + " result=" + requestCode + " ok=" + (resultCode == Activity.RESULT_OK)
        )
        Util.logExtras(data)
        if (requestCode == MainActivity.REQUEST_VPN) {
            // Handle VPN approval
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            prefs.edit().putBoolean("enabled", resultCode == Activity.RESULT_OK).apply()
            if (resultCode == Activity.RESULT_OK) {
                ServiceFirewall.start("prepared", requireContext())
                val on: Toast =
                    Toast.makeText(requireContext(), "On", Toast.LENGTH_LONG)
                on.setGravity(Gravity.CENTER, 0, 0)
                on.show()
                //checkDoze()
            } else if (resultCode == Activity.RESULT_CANCELED)
                Toast.makeText(
                requireContext(),
                "Cancelled",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Log.w(
                "",
                "Unknown activity result request=$requestCode"
            )
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

     private fun updateRule(context: Context, rule: Rule, root: Boolean, listAll: List<Rule>) {
        val wifi = context.getSharedPreferences("wifi", Context.MODE_PRIVATE)
        val other = context.getSharedPreferences("other", Context.MODE_PRIVATE)
        val apply = context.getSharedPreferences("apply", Context.MODE_PRIVATE)
        val screen_wifi = context.getSharedPreferences("screen_wifi", Context.MODE_PRIVATE)
        val screen_other = context.getSharedPreferences("screen_other", Context.MODE_PRIVATE)
        val roaming = context.getSharedPreferences("roaming", Context.MODE_PRIVATE)
        val lockdown = context.getSharedPreferences("lockdown", Context.MODE_PRIVATE)
        val notify = context.getSharedPreferences("notify", Context.MODE_PRIVATE)
        if (rule.wifi_blocked == rule.wifi_default) wifi.edit().remove(rule.packageName)
            .apply() else wifi.edit().putBoolean(rule.packageName, rule.wifi_blocked).apply()
        if (rule.other_blocked == rule.other_default) other.edit().remove(rule.packageName)
            .apply() else other.edit().putBoolean(rule.packageName, rule.other_blocked).apply()
        if (rule.apply) apply.edit().remove(rule.packageName).apply() else apply.edit()
            .putBoolean(rule.packageName, rule.apply).apply()
        if (rule.screen_wifi == rule.screen_wifi_default) screen_wifi.edit()
            .remove(rule.packageName).apply() else screen_wifi.edit()
            .putBoolean(rule.packageName, rule.screen_wifi).apply()
        if (rule.screen_other == rule.screen_other_default) screen_other.edit()
            .remove(rule.packageName).apply() else screen_other.edit()
            .putBoolean(rule.packageName, rule.screen_other).apply()
        if (rule.roaming == rule.roaming_default) roaming.edit().remove(rule.packageName)
            .apply() else roaming.edit().putBoolean(rule.packageName, rule.roaming).apply()
        if (rule.lockdown) lockdown.edit().putBoolean(rule.packageName, rule.lockdown)
            .apply() else lockdown.edit().remove(rule.packageName).apply()
        if (rule.notify) notify.edit().remove(rule.packageName).apply() else notify.edit()
            .putBoolean(rule.packageName, rule.notify).apply()
        rule.updateChanged(context)
        Log.i("Rule Updater", "Updated $rule")
        val listModified: MutableList<Rule> = ArrayList()
        for (pkg in rule.related) {
            for (related in listAll) if (related.packageName == pkg) {
                related.wifi_blocked = rule.wifi_blocked
                related.other_blocked = rule.other_blocked
                related.apply = rule.apply
                related.screen_wifi = rule.screen_wifi
                related.screen_other = rule.screen_other
                related.roaming = rule.roaming
                related.lockdown = rule.lockdown
                related.notify = rule.notify
                listModified.add(related)
            }
        }
        val listSearch: MutableList<Rule> = if (root) ArrayList(listAll) else listAll.toMutableList()
        listSearch.remove(rule)
        for (modified in listModified) listSearch.remove(modified)
        for (modified in listModified) updateRule(context, modified, false, listSearch)
        /*
        if (root) {
            notifyDataSetChanged()
            NotificationManagerCompat.from(context).cancel(rule.uid)
            ServiceSinkhole.reload("rule changed", context, false)
        }*/
    }

    override fun onResume() {
        super.onResume()
        val enabled = prefs.getBoolean("enabled", false)
        binding.firewallSwitch.setChecked(enabled)
    }

    override fun settingsChanged(rule: Rule) {
        updateRule(requireContext(), rule, false, Rule.getRules(false, requireContext()))
        ServiceFirewall.reload("rule changed", context, false)
    }

    override fun onItemClicked(uid: Int) {
        val moreIntent = Intent(requireContext(), AppLogActivity::class.java)
        moreIntent.putExtra("uid", uid)
        startActivity(moreIntent)
    }

}