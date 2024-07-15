package com.net.firewall;

/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015-2024 by Marcel Bokhorst (M66B)
*/

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.TypedValue;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;


import com.net.firewall.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.net.firewall.model.Data;

public class ServiceFirewall extends VpnService implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "Service";

    private boolean registeredUser = false;
    private boolean registeredIdleState = false;
    private boolean registeredConnectivityChanged = false;
    private boolean registeredPackageChanged = false;

    private boolean phone_state = false;
    private Object networkCallback = null;

    private boolean registeredInteractiveState = false;
    private PhoneStateListener callStateListener = null;

    private State state = State.none;
    private boolean user_foreground = true;
    private boolean last_connected = false;
    private boolean last_metered = true;
    private boolean last_interactive = false;

    private int last_allowed = -1;
    private int last_blocked = -1;
    private int last_hosts = -1;

    private static Object jni_lock = new Object();
    private static long jni_context = 0;
    private Thread tunnelThread = null;
    private Builder last_builder = null;
    private ParcelFileDescriptor vpn = null;
    private boolean temporarilyStopped = false;

    private long last_hosts_modified = 0;
    private long last_malware_modified = 0;
    private Map<String, Boolean> mapHostsBlocked = new HashMap<>();
    private Map<String, Boolean> mapMalware = new HashMap<>();
    private Map<Integer, Boolean> mapUidAllowed = new HashMap<>();
    private Map<Integer, Integer> mapUidKnown = new HashMap<>();
    private final Map<IPKey, Map<InetAddress, IPRule>> mapUidIPFilters = new HashMap<>();
    private Map<Integer, Forward> mapForward = new HashMap<>();
    private Map<Integer, Boolean> mapNotify = new HashMap<>();
    private ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    private volatile Looper commandLooper;
    private volatile Looper logLooper;
    private volatile Looper statsLooper;
    private volatile CommandHandler commandHandler;
    private volatile LogHandler logHandler;
    private volatile StatsHandler statsHandler;

    private static final int NOTIFY_ENFORCING = 1;
    private static final int NOTIFY_WAITING = 2;
    private static final int NOTIFY_DISABLED = 3;
    private static final int NOTIFY_LOCKDOWN = 4;
    private static final int NOTIFY_AUTOSTART = 5;
    private static final int NOTIFY_ERROR = 6;
    private static final int NOTIFY_TRAFFIC = 7;
    private static final int NOTIFY_UPDATE = 8;
    public static final int NOTIFY_EXTERNAL = 9;
    public static final int NOTIFY_DOWNLOAD = 10;

    public static final String EXTRA_COMMAND = "Command";
    private static final String EXTRA_REASON = "Reason";
    public static final String EXTRA_NETWORK = "Network";
    public static final String EXTRA_UID = "UID";
    public static final String EXTRA_PACKAGE = "Package";
    public static final String EXTRA_BLOCKED = "Blocked";
    public static final String EXTRA_INTERACTIVE = "Interactive";
    public static final String EXTRA_TEMPORARY = "Temporary";

    private static final int MSG_STATS_START = 1;
    private static final int MSG_STATS_STOP = 2;
    private static final int MSG_STATS_UPDATE = 3;
    private static final int MSG_PACKET = 4;
    private static final int MSG_USAGE = 5;

    private enum State {none, waiting, enforcing, stats}

    public enum Command {run, start, reload, stop, stats, set, householding, watchdog}

    private static volatile PowerManager.WakeLock wlInstance = null;

    private ExecutorService executor = Executors.newCachedThreadPool();

    private static final String ACTION_HOUSE_HOLDING = "HOUSE_HOLDING";
    private static final String ACTION_SCREEN_OFF_DELAYED = "SCREEN_OFF_DELAYED";
    private static final String ACTION_WATCHDOG = "WATCHDOG";

    private native long jni_init(int sdk);

    private native void jni_start(long context, int loglevel);

    private native void jni_run(long context, int tun, boolean fwd53, int rcode);

    private native void jni_stop(long context);

    private native void jni_clear(long context);

    private native int jni_get_mtu();

    private native int[] jni_get_stats(long context);

    private static native void jni_pcap(String name, int record_size, int file_size);

    private native void jni_socks5(String addr, int port, String username, String password);

    private native void jni_done(long context);

    public static void setPcap(boolean enabled, Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        int record_size = 64;
        try {
            String r = prefs.getString("pcap_record_size", null);
            if (TextUtils.isEmpty(r))
                r = "64";
            record_size = Integer.parseInt(r);
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }

        int file_size = 2 * 1024 * 1024;
        try {
            String f = prefs.getString("pcap_file_size", null);
            if (TextUtils.isEmpty(f))
                f = "2";
            file_size = Integer.parseInt(f) * 1024 * 1024;
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }

        File pcap = (enabled ? new File(context.getDir("data", MODE_PRIVATE), "netguard.pcap") : null);
        jni_pcap(pcap == null ? null : pcap.getAbsolutePath(), record_size, file_size);
    }

    synchronized private static PowerManager.WakeLock getLock(Context context) {
        if (wlInstance == null) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            wlInstance = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, context.getString(R.string.app_name) + " wakelock");
            wlInstance.setReferenceCounted(true);
        }
        return wlInstance;
    }

    synchronized private static void releaseLock(Context context) {
        if (wlInstance != null) {
            while (wlInstance.isHeld())
                wlInstance.release();
            wlInstance = null;
        }
    }

    private final class CommandHandler extends Handler {
        public int queue = 0;

        public CommandHandler(Looper looper) {
            super(looper);
        }

        private void reportQueueSize() {
            Intent ruleset = new Intent(Data.ACTION_QUEUE_CHANGED);
            ruleset.putExtra(Data.EXTRA_SIZE, queue);
            LocalBroadcastManager.getInstance(ServiceFirewall.this).sendBroadcast(ruleset);
        }

        public void queue(Intent intent) {
            synchronized (this) {
                queue++;
                reportQueueSize();
            }
            Command cmd = (Command) intent.getSerializableExtra(EXTRA_COMMAND);
            Message msg = commandHandler.obtainMessage();
            msg.obj = intent;
            msg.what = cmd.ordinal();
            commandHandler.sendMessage(msg);
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                synchronized (ServiceFirewall.this) {
                    handleIntent((Intent) msg.obj);
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            } finally {
                synchronized (this) {
                    queue--;
                    reportQueueSize();
                }
                try {
                    PowerManager.WakeLock wl = getLock(ServiceFirewall.this);
                    if (wl.isHeld())
                        wl.release();
                    else
                        Log.w(TAG, "Wakelock under-locked");
                    //Log.i(TAG, "Messages=" + hasMessages(0) + " wakelock=" + wlInstance.isHeld());
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                }
            }
        }

        private void handleIntent(Intent intent) {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);

            Command cmd = (Command) intent.getSerializableExtra(EXTRA_COMMAND);
            String reason = intent.getStringExtra(EXTRA_REASON);
            Log.i(TAG, "Executing intent=" + intent + " command=" + cmd + " reason=" + reason +
                    " vpn=" + (vpn != null) + " user=" + (Process.myUid() / 100000));

            // Check if foreground
            if (cmd != Command.stop)
                if (!user_foreground) {
                    Log.i(TAG, "Command " + cmd + " ignored for background user");
                    return;
                }

            // Handle temporary stop
            if (cmd == Command.stop)
                temporarilyStopped = intent.getBooleanExtra(EXTRA_TEMPORARY, false);
            else if (cmd == Command.start)
                temporarilyStopped = false;
            else if (cmd == Command.reload && temporarilyStopped) {
                // Prevent network/interactive changes from restarting the VPN
                Log.i(TAG, "Command " + cmd + " ignored because of temporary stop");
                return;
            }

            // Optionally listen for interactive state changes
            if (prefs.getBoolean("screen_on", true)) {
                if (!registeredInteractiveState) {
                    Log.i(TAG, "Starting listening for interactive state changes");
                    last_interactive = Util.isInteractive(ServiceFirewall.this);
                    IntentFilter ifInteractive = new IntentFilter();
                    ifInteractive.addAction(Intent.ACTION_SCREEN_ON);
                    ifInteractive.addAction(Intent.ACTION_SCREEN_OFF);
                    ifInteractive.addAction(ACTION_SCREEN_OFF_DELAYED);
                    ContextCompat.registerReceiver(ServiceFirewall.this, interactiveStateReceiver, ifInteractive, ContextCompat.RECEIVER_NOT_EXPORTED);
                    registeredInteractiveState = true;
                }
            } else {
                if (registeredInteractiveState) {
                    Log.i(TAG, "Stopping listening for interactive state changes");
                    unregisterReceiver(interactiveStateReceiver);
                    registeredInteractiveState = false;
                    last_interactive = false;
                }
            }

            // Optionally listen for call state changes
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (prefs.getBoolean("disable_on_call", false)) {
                if (tm != null && callStateListener == null && Util.hasPhoneStatePermission(ServiceFirewall.this)) {
                    Log.i(TAG, "Starting listening for call states");
                    PhoneStateListener listener = new PhoneStateListener() {
                        @Override
                        public void onCallStateChanged(int state, String incomingNumber) {
                            Log.i(TAG, "New call state=" + state);
                            if (prefs.getBoolean("enabled", false))
                                if (state == TelephonyManager.CALL_STATE_IDLE)
                                    ServiceFirewall.start("call state", ServiceFirewall.this);
                                else
                                    ServiceFirewall.stop("call state", ServiceFirewall.this, true);
                        }
                    };
                    tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
                    callStateListener = listener;
                }
            } else {
                if (tm != null && callStateListener != null) {
                    Log.i(TAG, "Stopping listening for call states");
                    tm.listen(callStateListener, PhoneStateListener.LISTEN_NONE);
                    callStateListener = null;
                }
            }

            // Watchdog
            if (cmd == Command.start || cmd == Command.reload || cmd == Command.stop) {
                Intent watchdogIntent = new Intent(ServiceFirewall.this, ServiceFirewall.class);
                watchdogIntent.setAction(ACTION_WATCHDOG);

                /*
                PendingIntent pi;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    pi = PendingIntentCompat.getForegroundService(ServiceSinkhole.this, 1, watchdogIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                else
                    pi = PendingIntentCompat.getService(ServiceSinkhole.this, 1, watchdogIntent, PendingIntent.FLAG_UPDATE_CURRENT);*/

                PendingIntent pi;
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    pi = PendingIntent.getForegroundService(ServiceFirewall.this, 1, watchdogIntent, flags);
                } else {
                    pi = PendingIntent.getService(ServiceFirewall.this, 1, watchdogIntent, flags);
                }



                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                am.cancel(pi);

                if (cmd != Command.stop) {
                    int watchdog = Integer.parseInt(prefs.getString("watchdog", "0"));
                    if (watchdog > 0) {
                        Log.i(TAG, "Watchdog " + watchdog + " minutes");
                        am.setInexactRepeating(AlarmManager.RTC, SystemClock.elapsedRealtime() + watchdog * 60 * 1000, watchdog * 60 * 1000, pi);
                    }
                }
            }

            try {
                switch (cmd) {
                    case run:
                        break;

                    case start:
                        start();
                        break;

                    case reload:
                        reload(intent.getBooleanExtra(EXTRA_INTERACTIVE, false));
                        break;

                    case stop:
                        stop(temporarilyStopped);
                        break;

                    case stats:
                        statsHandler.sendEmptyMessage(MSG_STATS_STOP);
                        statsHandler.sendEmptyMessage(MSG_STATS_START);
                        break;

                    case householding:
                        householding(intent);
                        break;

                    case watchdog:
                        watchdog(intent);
                        break;

                    default:
                        Log.e(TAG, "Unknown command=" + cmd);
                }

                if (cmd == Command.start || cmd == Command.reload) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        boolean filter = prefs.getBoolean("filter", false);
                        if (filter && isLockdownEnabled())
                            showLockdownNotification();
                        else
                            removeLockdownNotification();
                    }
                }

                if (cmd == Command.start || cmd == Command.reload || cmd == Command.stop) {
                    // Update main view
                    Intent ruleset = new Intent(Data.ACTION_RULES_CHANGED);
                    ruleset.putExtra(Data.EXTRA_CONNECTED, cmd == Command.stop ? false : last_connected);
                    ruleset.putExtra(Data.EXTRA_METERED, cmd == Command.stop ? false : last_metered);
                    LocalBroadcastManager.getInstance(ServiceFirewall.this).sendBroadcast(ruleset);
                }

                // Stop service if needed
                if (!commandHandler.hasMessages(Command.start.ordinal()) &&
                        !commandHandler.hasMessages(Command.reload.ordinal()) &&
                        !prefs.getBoolean("enabled", false) &&
                        !prefs.getBoolean("show_stats", false))
                    stopForeground(true);

                // Request garbage collection
                System.gc();
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));

                if (cmd == Command.start || cmd == Command.reload) {
                    if (VpnService.prepare(ServiceFirewall.this) == null) {
                        Log.w(TAG, "VPN prepared connected=" + last_connected);
                        if (last_connected && !(ex instanceof StartFailedException)) {
                            //showAutoStartNotification();
                            if (!Util.isPlayStoreInstall(ServiceFirewall.this))
                                showErrorNotification(ex.toString());
                        }
                        // Retried on connectivity change
                    } else {
                        showErrorNotification(ex.toString());

                        // Disable firewall
                        if (!(ex instanceof StartFailedException)) {
                            prefs.edit().putBoolean("enabled", false).apply();
                        }
                    }
                } else
                    showErrorNotification(ex.toString());
            }
        }

        private void start() {
            if (vpn == null) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=" + state.toString());
                    stopForeground(true);
                }
                startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1));
                state = State.enforcing;
                Log.d(TAG, "Start foreground state=" + state.toString());

                List<Rule> listRule = Rule.getRules(true, ServiceFirewall.this);
                List<Rule> listAllowed = getAllowedRules(listRule);

                last_builder = getBuilder(listAllowed, listRule);
                vpn = startVPN(last_builder);
                if (vpn == null)
                    throw new StartFailedException(getString((R.string.msg_start_failed)));

                startNative(vpn, listAllowed, listRule);

                removeWarningNotifications();
                updateEnforcingNotification(listAllowed.size(), listRule.size());
            }
        }

        private void reload(boolean interactive) {
            List<Rule> listRule = Rule.getRules(true, ServiceFirewall.this);

            // Check if rules needs to be reloaded
            if (interactive) {
                boolean process = false;
                for (Rule rule : listRule) {
                    boolean blocked = (last_metered ? rule.other_blocked : rule.wifi_blocked);
                    boolean screen = (last_metered ? rule.screen_other : rule.screen_wifi);
                    if (blocked && screen) {
                        process = true;
                        break;
                    }
                }
                if (!process) {
                    Log.i(TAG, "No changed rules on interactive state change");
                    return;
                }
            }

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);

            if (state != State.enforcing) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=" + state.toString());
                    stopForeground(true);
                }
                startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1));
                state = State.enforcing;
                Log.d(TAG, "Start foreground state=" + state.toString());
            }

            List<Rule> listAllowed = getAllowedRules(listRule);
            Builder builder = getBuilder(listAllowed, listRule);

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                last_builder = builder;
                Log.i(TAG, "Legacy restart");

                if (vpn != null) {
                    stopNative(vpn);
                    stopVPN(vpn);
                    vpn = null;
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }
                }
                vpn = startVPN(last_builder);

            } else {
                if (vpn != null && prefs.getBoolean("filter", false) && builder.equals(last_builder)) {
                    Log.i(TAG, "Native restart");
                    stopNative(vpn);

                } else {
                    last_builder = builder;

                    boolean handover = prefs.getBoolean("handover", false);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        handover = false;
                    Log.i(TAG, "VPN restart handover=" + handover);

                    if (handover) {
                        // Attempt seamless handover
                        ParcelFileDescriptor prev = vpn;
                        vpn = startVPN(builder);

                        if (prev != null && vpn == null) {
                            Log.w(TAG, "Handover failed");
                            stopNative(prev);
                            stopVPN(prev);
                            prev = null;
                            try {
                                Thread.sleep(3000);
                            } catch (InterruptedException ignored) {
                            }
                            vpn = startVPN(last_builder);
                            if (vpn == null)
                                throw new IllegalStateException("Handover failed");
                        }

                        if (prev != null) {
                            stopNative(prev);
                            stopVPN(prev);
                        }
                    } else {
                        if (vpn != null) {
                            stopNative(vpn);
                            stopVPN(vpn);
                        }

                        vpn = startVPN(builder);
                    }
                }
            }

            if (vpn == null)
                throw new StartFailedException(getString((R.string.msg_start_failed)));

            startNative(vpn, listAllowed, listRule);

            removeWarningNotifications();
            updateEnforcingNotification(listAllowed.size(), listRule.size());
        }

        private void stop(boolean temporary) {
            if (vpn != null) {
                stopNative(vpn);
                stopVPN(vpn);
                vpn = null;
                unprepare();
            }
            if (state == State.enforcing && !temporary) {
                Log.d(TAG, "Stop foreground state=" + state.toString());
                last_allowed = -1;
                last_blocked = -1;
                last_hosts = -1;

                stopForeground(true);

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
                if (prefs.getBoolean("show_stats", false)) {
                    startForeground(NOTIFY_WAITING, getWaitingNotification());
                    state = State.waiting;
                    Log.d(TAG, "Start foreground state=" + state.toString());
                } else {
                    state = State.none;
                    stopSelf();
                }
            }
        }

        private void householding(Intent intent) {
            // Keep log records for three days
            DatabaseHelper.getInstance(ServiceFirewall.this).cleanupLog(new Date().getTime() - 3 * 24 * 3600 * 1000L);

            // Clear expired DNS records
            DatabaseHelper.getInstance(ServiceFirewall.this).cleanupDns();

            // Check for update
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
        }

        private void watchdog(Intent intent) {
            if (vpn == null) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
                if (prefs.getBoolean("enabled", false)) {
                    Log.e(TAG, "Service was killed");
                    start();
                }
            }
        }

        private class StartFailedException extends IllegalStateException {
            public StartFailedException(String msg) {
                super(msg);
            }
        }
    }

    private final class LogHandler extends Handler {
        public int queue = 0;

        private static final int MAX_QUEUE = 250;

        public LogHandler(Looper looper) {
            super(looper);
        }

        public void queue(Packet packet) {
            Message msg = obtainMessage();
            msg.obj = packet;
            msg.what = MSG_PACKET;
            msg.arg1 = (last_connected ? (last_metered ? 2 : 1) : 0);
            msg.arg2 = (last_interactive ? 1 : 0);

            synchronized (this) {
                if (queue > MAX_QUEUE) {
                    Log.w(TAG, "Log queue full");
                    return;
                }

                sendMessage(msg);

                queue++;
            }
        }

        public void account(Usage usage) {
            Message msg = obtainMessage();
            msg.obj = usage;
            msg.what = MSG_USAGE;

            synchronized (this) {
                if (queue > MAX_QUEUE) {
                    Log.w(TAG, "Log queue full");
                    return;
                }

                sendMessage(msg);

                queue++;
            }
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                switch (msg.what) {
                    case MSG_PACKET:
                        log((Packet) msg.obj, msg.arg1, msg.arg2 > 0);
                        break;

                    case MSG_USAGE:
                        usage((Usage) msg.obj);
                        break;

                    default:
                        Log.e(TAG, "Unknown log message=" + msg.what);
                }

                synchronized (this) {
                    queue--;
                }

            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }
        }

        private void log(Packet packet, int connection, boolean interactive) {
            // Get settings
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
            boolean log = prefs.getBoolean("log", true);
            boolean log_app = prefs.getBoolean("log_app", true);

            DatabaseHelper dh = DatabaseHelper.getInstance(ServiceFirewall.this);

            // Get real name
            String dname = dh.getQName(packet.uid, packet.daddr);

            // Traffic log
            if (log) {
                dh.insertLog(packet, dname, connection, interactive);
                //System.out.println("Just Log: " + packet.daddr);
            }

            // Application log
            if (log_app && packet.uid >= 0 &&
                    !(packet.uid == 0 && (packet.protocol == 6 || packet.protocol == 17) && packet.dport == 53)) {
                if (!(packet.protocol == 6 /* TCP */ || packet.protocol == 17 /* UDP */))
                    packet.dport = 0;
                if (dh.updateAccess(packet, dname, -1)) {
                    System.out.println("Log update app: " + packet);
                    lock.readLock().lock();
                    //if (!mapNotify.containsKey(packet.uid) || mapNotify.get(packet.uid))
                        //showAccessNotification(packet.uid);
                    lock.readLock().unlock();
                }
            }
        }

        private void usage(Usage usage) {
            if (usage.Uid >= 0 && !(usage.Uid == 0 && usage.Protocol == 17 && usage.DPort == 53)) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
                boolean filter = prefs.getBoolean("filter", false);
                boolean log_app = prefs.getBoolean("log_app", false);
                boolean track_usage = prefs.getBoolean("track_usage", false);
                if (filter && log_app && track_usage) {
                    DatabaseHelper dh = DatabaseHelper.getInstance(ServiceFirewall.this);
                    String dname = dh.getQName(usage.Uid, usage.DAddr);
                    Log.i(TAG, "Usage account " + usage + " dname=" + dname);
                    dh.updateUsage(usage, dname);
                }
            }
        }
    }

    private final class StatsHandler extends Handler {
        private boolean stats = false;
        private long when;

        private long t = -1;
        private long tx = -1;
        private long rx = -1;

        private List<Long> gt = new ArrayList<>();
        private List<Float> gtx = new ArrayList<>();
        private List<Float> grx = new ArrayList<>();

        private HashMap<Integer, Long> mapUidBytes = new HashMap<>();

        public StatsHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                switch (msg.what) {
                    case MSG_STATS_START:
                        startStats();
                        break;

                    case MSG_STATS_STOP:
                        stopStats();
                        break;

                    case MSG_STATS_UPDATE:
                        break;

                    default:
                        Log.e(TAG, "Unknown stats message=" + msg.what);
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }
        }

        private void startStats() {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
            boolean enabled = (!stats && prefs.getBoolean("show_stats", false));
            Log.i(TAG, "Stats start enabled=" + enabled);
            if (enabled) {
                when = new Date().getTime();
                t = -1;
                tx = -1;
                rx = -1;
                gt.clear();
                gtx.clear();
                grx.clear();
                mapUidBytes.clear();
                stats = true;
            }
        }

        private void stopStats() {
            Log.i(TAG, "Stats stop");
            stats = false;
            this.removeMessages(MSG_STATS_UPDATE);
            if (state == State.stats) {
                Log.d(TAG, "Stop foreground state=" + state.toString());
                stopForeground(true);
                state = State.none;
            } else
                NotificationManagerCompat.from(ServiceFirewall.this).cancel(NOTIFY_TRAFFIC);
        }

    }

    public static List<InetAddress> getDns(Context context) {
        List<InetAddress> listDns = new ArrayList<>();
        List<String> sysDns = Util.getDefaultDNS(context);

        // Get custom DNS servers
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean ip6 = prefs.getBoolean("ip6", true);
        boolean filter = prefs.getBoolean("filter", false);
        String vpnDns1 = prefs.getString("dns", null);
        String vpnDns2 = prefs.getString("dns2", null);
        Log.i(TAG, "DNS system=" + TextUtils.join(",", sysDns) + " config=" + vpnDns1 + "," + vpnDns2);

        if (vpnDns1 != null)
            try {
                InetAddress dns = InetAddress.getByName(vpnDns1);
                if (!(dns.isLoopbackAddress() || dns.isAnyLocalAddress()) &&
                        (ip6 || dns instanceof Inet4Address))
                    listDns.add(dns);
            } catch (Throwable ignored) {
            }

        if (vpnDns2 != null)
            try {
                InetAddress dns = InetAddress.getByName(vpnDns2);
                if (!(dns.isLoopbackAddress() || dns.isAnyLocalAddress()) &&
                        (ip6 || dns instanceof Inet4Address))
                    listDns.add(dns);
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }

        if (listDns.size() == 2)
            return listDns;

        for (String def_dns : sysDns)
            try {
                InetAddress ddns = InetAddress.getByName(def_dns);
                if (!listDns.contains(ddns) &&
                        !(ddns.isLoopbackAddress() || ddns.isAnyLocalAddress()) &&
                        (ip6 || ddns instanceof Inet4Address))
                    listDns.add(ddns);
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }

        // Remove local DNS servers when not routing LAN
        int count = listDns.size();
        boolean lan = prefs.getBoolean("lan", false);
        boolean use_hosts = prefs.getBoolean("use_hosts", false);
        if (lan && use_hosts && filter)
            try {
                List<Pair<InetAddress, Integer>> subnets = new ArrayList<>();
                subnets.add(new Pair<>(InetAddress.getByName("10.0.0.0"), 8));
                subnets.add(new Pair<>(InetAddress.getByName("172.16.0.0"), 12));
                subnets.add(new Pair<>(InetAddress.getByName("192.168.0.0"), 16));

                for (Pair<InetAddress, Integer> subnet : subnets) {
                    InetAddress hostAddress = subnet.first;
                    BigInteger host = new BigInteger(1, hostAddress.getAddress());

                    int prefix = subnet.second;
                    BigInteger mask = BigInteger.valueOf(-1).shiftLeft(hostAddress.getAddress().length * 8 - prefix);

                    for (InetAddress dns : new ArrayList<>(listDns))
                        if (hostAddress.getAddress().length == dns.getAddress().length) {
                            BigInteger ip = new BigInteger(1, dns.getAddress());

                            if (host.and(mask).equals(ip.and(mask))) {
                                Log.i(TAG, "Local DNS server host=" + hostAddress + "/" + prefix + " dns=" + dns);
                                listDns.remove(dns);
                            }
                        }
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }

        // Always set DNS servers
        if (listDns.size() == 0 || listDns.size() < count)
            try {
                listDns.add(InetAddress.getByName("8.8.8.8"));
                listDns.add(InetAddress.getByName("8.8.4.4"));
                if (ip6) {
                    listDns.add(InetAddress.getByName("2001:4860:4860::8888"));
                    listDns.add(InetAddress.getByName("2001:4860:4860::8844"));
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }

        Log.i(TAG, "Get DNS=" + TextUtils.join(",", listDns));

        return listDns;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private ParcelFileDescriptor startVPN(Builder builder) throws SecurityException {
        try {
            ParcelFileDescriptor pfd = builder.establish();

            // Set underlying network
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                Network active = (cm == null ? null : cm.getActiveNetwork());
                if (active != null) {
                    Log.i(TAG, "Setting underlying network=" + active + " " + cm.getNetworkInfo(active));
                    setUnderlyingNetworks(new Network[]{active});
                }
            }

            return pfd;
        } catch (SecurityException ex) {
            throw ex;
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            return null;
        }
    }

    private Builder getBuilder(List<Rule> listAllowed, List<Rule> listRule) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean subnet = prefs.getBoolean("subnet", false);
        boolean tethering = prefs.getBoolean("tethering", false);
        boolean lan = prefs.getBoolean("lan", false);
        boolean ip6 = prefs.getBoolean("ip6", true);
        boolean filter = prefs.getBoolean("filter", false);
        boolean system = prefs.getBoolean("manage_system", false);

        // Build VPN service
        Builder builder = new Builder();
        builder.setSession(getString(R.string.app_name));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            builder.setMetered(Util.isMeteredNetwork(this));

        // VPN address
        String vpn4 = prefs.getString("vpn4", "10.1.10.1");
        Log.i(TAG, "Using VPN4=" + vpn4);
        builder.addAddress(vpn4, 32);
        if (ip6) {
            String vpn6 = prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1");
            Log.i(TAG, "Using VPN6=" + vpn6);
            builder.addAddress(vpn6, 128);
        }

        // DNS address
        if (filter)
            for (InetAddress dns : getDns(ServiceFirewall.this)) {
                if (ip6 || dns instanceof Inet4Address) {
                    Log.i(TAG, "Using DNS=" + dns);
                    builder.addDnsServer(dns);
                }
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                Network active = (cm == null ? null : cm.getActiveNetwork());
                LinkProperties props = (active == null ? null : cm.getLinkProperties(active));
                String domain = (props == null ? null : props.getDomains());
                if (domain != null) {
                    Log.i(TAG, "Using search domain=" + domain);
                    builder.addSearchDomain(domain);
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }

        // Subnet routing
        if (subnet) {
            // Exclude IP ranges
            List<IPUtil.CIDR> listExclude = new ArrayList<>();
            listExclude.add(new IPUtil.CIDR("127.0.0.0", 8)); // localhost

            if (tethering && !lan) {
                // USB tethering 192.168.42.x
                // Wi-Fi tethering 192.168.43.x
                listExclude.add(new IPUtil.CIDR("192.168.42.0", 23));
                // Bluetooth tethering 192.168.44.x
                listExclude.add(new IPUtil.CIDR("192.168.44.0", 24));
                // Wi-Fi direct 192.168.49.x
                listExclude.add(new IPUtil.CIDR("192.168.49.0", 24));
            }

            if (lan) {
                // https://tools.ietf.org/html/rfc1918
                listExclude.add(new IPUtil.CIDR("10.0.0.0", 8));
                listExclude.add(new IPUtil.CIDR("172.16.0.0", 12));
                listExclude.add(new IPUtil.CIDR("192.168.0.0", 16));
            }

            if (!filter) {
                for (InetAddress dns : getDns(ServiceFirewall.this))
                    if (dns instanceof Inet4Address)
                        listExclude.add(new IPUtil.CIDR(dns.getHostAddress(), 32));

                String dns_specifier = Util.getPrivateDnsSpecifier(ServiceFirewall.this);
                if (!TextUtils.isEmpty(dns_specifier))
                    try {
                        Log.i(TAG, "Resolving private dns=" + dns_specifier);
                        for (InetAddress pdns : InetAddress.getAllByName(dns_specifier))
                            if (pdns instanceof Inet4Address)
                                listExclude.add(new IPUtil.CIDR(pdns.getHostAddress(), 32));
                    } catch (Throwable ex) {
                        Log.e(TAG, ex.toString());
                    }
            }

            // https://en.wikipedia.org/wiki/Mobile_country_code
            Configuration config = getResources().getConfiguration();

            // T-Mobile Wi-Fi calling
            if (config.mcc == 310 && (config.mnc == 160 ||
                    config.mnc == 200 ||
                    config.mnc == 210 ||
                    config.mnc == 220 ||
                    config.mnc == 230 ||
                    config.mnc == 240 ||
                    config.mnc == 250 ||
                    config.mnc == 260 ||
                    config.mnc == 270 ||
                    config.mnc == 310 ||
                    config.mnc == 490 ||
                    config.mnc == 660 ||
                    config.mnc == 800)) {
                listExclude.add(new IPUtil.CIDR("66.94.2.0", 24));
                listExclude.add(new IPUtil.CIDR("66.94.6.0", 23));
                listExclude.add(new IPUtil.CIDR("66.94.8.0", 22));
                listExclude.add(new IPUtil.CIDR("208.54.0.0", 16));
            }

            // Verizon wireless calling
            if ((config.mcc == 310 &&
                    (config.mnc == 4 ||
                            config.mnc == 5 ||
                            config.mnc == 6 ||
                            config.mnc == 10 ||
                            config.mnc == 12 ||
                            config.mnc == 13 ||
                            config.mnc == 350 ||
                            config.mnc == 590 ||
                            config.mnc == 820 ||
                            config.mnc == 890 ||
                            config.mnc == 910)) ||
                    (config.mcc == 311 && (config.mnc == 12 ||
                            config.mnc == 110 ||
                            (config.mnc >= 270 && config.mnc <= 289) ||
                            config.mnc == 390 ||
                            (config.mnc >= 480 && config.mnc <= 489) ||
                            config.mnc == 590)) ||
                    (config.mcc == 312 && (config.mnc == 770))) {
                listExclude.add(new IPUtil.CIDR("66.174.0.0", 16)); // 66.174.0.0 - 66.174.255.255
                listExclude.add(new IPUtil.CIDR("66.82.0.0", 15)); // 69.82.0.0 - 69.83.255.255
                listExclude.add(new IPUtil.CIDR("69.96.0.0", 13)); // 69.96.0.0 - 69.103.255.255
                listExclude.add(new IPUtil.CIDR("70.192.0.0", 11)); // 70.192.0.0 - 70.223.255.255
                listExclude.add(new IPUtil.CIDR("97.128.0.0", 9)); // 97.128.0.0 - 97.255.255.255
                listExclude.add(new IPUtil.CIDR("174.192.0.0", 9)); // 174.192.0.0 - 174.255.255.255
                listExclude.add(new IPUtil.CIDR("72.96.0.0", 9)); // 72.96.0.0 - 72.127.255.255
                listExclude.add(new IPUtil.CIDR("75.192.0.0", 9)); // 75.192.0.0 - 75.255.255.255
                listExclude.add(new IPUtil.CIDR("97.0.0.0", 10)); // 97.0.0.0 - 97.63.255.255
            }

            // SFR MMS
            if (config.mnc == 10 && config.mcc == 208)
                listExclude.add(new IPUtil.CIDR("10.151.0.0", 24));

            // Broadcast
            listExclude.add(new IPUtil.CIDR("224.0.0.0", 3));

            Collections.sort(listExclude);

            try {
                InetAddress start = InetAddress.getByName("0.0.0.0");
                for (IPUtil.CIDR exclude : listExclude) {
                    Log.i(TAG, "Exclude " + exclude.getStart().getHostAddress() + "..." + exclude.getEnd().getHostAddress());
                    for (IPUtil.CIDR include : IPUtil.toCIDR(start, IPUtil.minus1(exclude.getStart())))
                        try {
                            builder.addRoute(include.address, include.prefix);
                        } catch (Throwable ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
                    start = IPUtil.plus1(exclude.getEnd());
                }
                String end = (lan ? "255.255.255.254" : "255.255.255.255");
                for (IPUtil.CIDR include : IPUtil.toCIDR("224.0.0.0", end))
                    try {
                        builder.addRoute(include.address, include.prefix);
                    } catch (Throwable ex) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    }
            } catch (UnknownHostException ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }
        } else
            builder.addRoute("0.0.0.0", 0);

        Log.i(TAG, "IPv6=" + ip6);
        if (ip6)
            builder.addRoute("2000::", 3); // unicast

        // MTU
        int mtu = jni_get_mtu();
        Log.i(TAG, "MTU=" + mtu);
        builder.setMtu(mtu);

        // Add list of allowed applications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (last_connected && !filter) {
                Map<String, Rule> mapDisallowed = new HashMap<>();
                for (Rule rule : listRule)
                    mapDisallowed.put(rule.packageName, rule);
                for (Rule rule : listAllowed)
                    mapDisallowed.remove(rule.packageName);
                for (String packageName : mapDisallowed.keySet())
                    try {
                        builder.addAllowedApplication(packageName);
                        //Log.i(TAG, "Sinkhole " + packageName);
                    } catch (PackageManager.NameNotFoundException ex) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    }
                if (mapDisallowed.size() == 0)
                    try {
                        builder.addAllowedApplication(getPackageName());
                    } catch (PackageManager.NameNotFoundException ex) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    }
            } else if (filter) {
                try {
                    builder.addDisallowedApplication(getPackageName());
                } catch (PackageManager.NameNotFoundException ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                }
                for (Rule rule : listRule)
                    if (!rule.apply || (!system && rule.system))
                        try {
                            Log.i(TAG, "Not routing " + rule.packageName);
                            builder.addDisallowedApplication(rule.packageName);
                        } catch (PackageManager.NameNotFoundException ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
            }
        }

        // Build configure intent
        Intent configure = new Intent();
        configure.setClassName("com.myfirewall", "com.myfirewall.app.MainActivity");

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pi = PendingIntent.getActivity(this, 0, configure, flags);
        builder.setConfigureIntent(pi);

        return builder;
    }

    private void startNative(final ParcelFileDescriptor vpn, List<Rule> listAllowed, List<Rule> listRule) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
        boolean log = prefs.getBoolean("log", false);
        boolean log_app = prefs.getBoolean("log_app", false);
        boolean filter = prefs.getBoolean("filter", false);

        Log.i(TAG, "Start native log=" + log + "/" + log_app + " filter=" + filter);

        // Prepare rules
        if (filter) {
            prepareUidAllowed(listAllowed, listRule);
            prepareHostsBlocked();
            prepareMalwareList();
            prepareUidIPFilters(null);
            prepareForwarding();
        } else {
            lock.writeLock().lock();
            mapUidAllowed.clear();
            mapUidKnown.clear();
            mapHostsBlocked.clear();
            mapMalware.clear();
            mapUidIPFilters.clear();
            mapForward.clear();
            lock.writeLock().unlock();
        }

        if (log_app)
            prepareNotify(listRule);
        else {
            lock.writeLock().lock();
            mapNotify.clear();
            lock.writeLock().unlock();
        }

        if (log || log_app || filter) {
            int prio = Integer.parseInt(prefs.getString("loglevel", Integer.toString(Log.WARN)));
            final int rcode = Integer.parseInt(prefs.getString("rcode", "3"));
            if (prefs.getBoolean("socks5_enabled", false))
                jni_socks5(
                        prefs.getString("socks5_addr", ""),
                        Integer.parseInt(prefs.getString("socks5_port", "0")),
                        prefs.getString("socks5_username", ""),
                        prefs.getString("socks5_password", ""));
            else
                jni_socks5("", 0, "", "");

            if (tunnelThread == null) {
                Log.i(TAG, "Starting tunnel thread context=" + jni_context);
                jni_start(jni_context, prio);

                tunnelThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Log.i(TAG, "Running tunnel context=" + jni_context);
                        jni_run(jni_context, vpn.getFd(), mapForward.containsKey(53), rcode);
                        Log.i(TAG, "Tunnel exited");
                        tunnelThread = null;
                    }
                });
                //tunnelThread.setPriority(Thread.MAX_PRIORITY);
                tunnelThread.start();

                Log.i(TAG, "Started tunnel thread");
            }
        }
    }

    private void stopNative(ParcelFileDescriptor vpn) {
        Log.i(TAG, "Stop native");

        if (tunnelThread != null) {
            Log.i(TAG, "Stopping tunnel thread");

            jni_stop(jni_context);

            Thread thread = tunnelThread;
            while (thread != null && thread.isAlive()) {
                try {
                    Log.i(TAG, "Joining tunnel thread context=" + jni_context);
                    thread.join();
                } catch (InterruptedException ignored) {
                    Log.i(TAG, "Joined tunnel interrupted");
                }
                thread = tunnelThread;
            }
            tunnelThread = null;

            jni_clear(jni_context);

            Log.i(TAG, "Stopped tunnel thread");
        }
    }

    private void unprepare() {
        lock.writeLock().lock();
        mapUidAllowed.clear();
        mapUidKnown.clear();
        mapHostsBlocked.clear();
        mapMalware.clear();
        mapUidIPFilters.clear();
        mapForward.clear();
        mapNotify.clear();
        lock.writeLock().unlock();
    }

    private void prepareUidAllowed(List<Rule> listAllowed, List<Rule> listRule) {
        lock.writeLock().lock();

        mapUidAllowed.clear();
        for (Rule rule : listAllowed)
            mapUidAllowed.put(rule.uid, true);

        mapUidKnown.clear();
        for (Rule rule : listRule)
            mapUidKnown.put(rule.uid, rule.uid);

        lock.writeLock().unlock();
    }

    private void prepareHostsBlocked() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
        boolean use_hosts = prefs.getBoolean("filter", false) && prefs.getBoolean("use_hosts", false);
        File hosts = new File(getFilesDir(), "hosts.txt");
        if (!use_hosts || !hosts.exists() || !hosts.canRead()) {
            Log.i(TAG, "Hosts file use=" + use_hosts + " exists=" + hosts.exists());
            lock.writeLock().lock();
            mapHostsBlocked.clear();
            lock.writeLock().unlock();
            return;
        }

        boolean changed = (hosts.lastModified() != last_hosts_modified);
        if (!changed && mapHostsBlocked.size() > 0) {
            Log.i(TAG, "Hosts file unchanged");
            return;
        }
        last_hosts_modified = hosts.lastModified();

        lock.writeLock().lock();

        mapHostsBlocked.clear();

        int count = 0;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(hosts));
            String line;
            while ((line = br.readLine()) != null) {
                int hash = line.indexOf('#');
                if (hash >= 0)
                    line = line.substring(0, hash);
                line = line.trim();
                if (line.length() > 0) {
                    String[] words = line.split("\\s+");
                    if (words.length == 2) {
                        count++;
                        mapHostsBlocked.put(words[1], true);
                    } else
                        Log.i(TAG, "Invalid hosts file line: " + line);
                }
            }
            mapHostsBlocked.put("test.netguard.me", true);
            Log.i(TAG, count + " hosts read");
        } catch (IOException ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        } finally {
            if (br != null)
                try {
                    br.close();
                } catch (IOException exex) {
                    Log.e(TAG, exex.toString() + "\n" + Log.getStackTraceString(exex));
                }
        }

        lock.writeLock().unlock();
    }

    private void prepareMalwareList() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
        boolean malware = prefs.getBoolean("filter", false) && prefs.getBoolean("malware", false);
        File file = new File(getFilesDir(), "malware.txt");
        if (!malware || !file.exists() || !file.canRead()) {
            Log.i(TAG, "Malware use=" + malware + " exists=" + file.exists());
            lock.writeLock().lock();
            mapMalware.clear();
            lock.writeLock().unlock();
            return;
        }

        boolean changed = (file.lastModified() != last_malware_modified);
        if (!changed && mapMalware.size() > 0) {
            Log.i(TAG, "Malware unchanged");
            return;
        }
        last_malware_modified = file.lastModified();

        lock.writeLock().lock();

        mapMalware.clear();

        int count = 0;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null) {
                int hash = line.indexOf('#');
                if (hash >= 0)
                    line = line.substring(0, hash);
                line = line.trim();
                if (line.length() > 0) {
                    String[] words = line.split("\\s+");
                    if (words.length > 1) {
                        count++;
                        mapMalware.put(words[1], true);
                    } else
                        Log.i(TAG, "Invalid malware file line: " + line);
                }
            }
            Log.i(TAG, count + " malware read");
        } catch (IOException ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        } finally {
            if (br != null)
                try {
                    br.close();
                } catch (IOException exex) {
                    Log.e(TAG, exex.toString() + "\n" + Log.getStackTraceString(exex));
                }
        }

        lock.writeLock().unlock();
    }

    private void prepareUidIPFilters(String dname) {
        SharedPreferences lockdown = getSharedPreferences("lockdown", Context.MODE_PRIVATE);

        lock.writeLock().lock();

        if (dname == null) {
            mapUidIPFilters.clear();
        }

        try (Cursor cursor = DatabaseHelper.getInstance(ServiceFirewall.this).getAccessDns(dname)) {
            int colUid = cursor.getColumnIndex("uid");
            int colVersion = cursor.getColumnIndex("version");
            int colProtocol = cursor.getColumnIndex("protocol");
            int colDAddr = cursor.getColumnIndex("daddr");
            int colResource = cursor.getColumnIndex("resource");
            int colDPort = cursor.getColumnIndex("dport");
            int colBlock = cursor.getColumnIndex("block");
            int colTime = cursor.getColumnIndex("time");
            int colTTL = cursor.getColumnIndex("ttl");
            while (cursor.moveToNext()) {
                int uid = cursor.getInt(colUid);
                int version = cursor.getInt(colVersion);
                int protocol = cursor.getInt(colProtocol);
                String daddr = cursor.getString(colDAddr);
                String dresource = (cursor.isNull(colResource) ? null : cursor.getString(colResource));
                int dport = cursor.getInt(colDPort);
                boolean block = (cursor.getInt(colBlock) > 0);
                long time = (cursor.isNull(colTime) ? new Date().getTime() : cursor.getLong(colTime));
                long ttl = (cursor.isNull(colTTL) ? 7 * 24 * 3600 * 1000L : cursor.getLong(colTTL));

                if (isLockedDown(last_metered)) {
                    String[] pkg = getPackageManager().getPackagesForUid(uid);
                    if (pkg != null && pkg.length > 0) {
                        if (!lockdown.getBoolean(pkg[0], false))
                            continue;
                    }
                }

                IPKey key = new IPKey(version, protocol, dport, uid);
                synchronized (mapUidIPFilters) {
                    if (!mapUidIPFilters.containsKey(key))
                        mapUidIPFilters.put(key, new HashMap());

                    try {
                        String name = (dresource == null ? daddr : dresource);
                        if (Util.isNumericAddress(name)) {
                            InetAddress iname = InetAddress.getByName(name);
                            if (version == 4 && !(iname instanceof Inet4Address))
                                continue;
                            if (version == 6 && !(iname instanceof Inet6Address))
                                continue;

                            boolean exists = mapUidIPFilters.get(key).containsKey(iname);
                            if (!exists || !mapUidIPFilters.get(key).get(iname).isBlocked()) {
                                IPRule rule = new IPRule(key, name + "/" + iname, block, time, ttl);
                                mapUidIPFilters.get(key).put(iname, rule);
                                if (exists)
                                    Log.w(TAG, "Address conflict " + key + " " + daddr + "/" + dresource);
                            } else if (exists) {
                                mapUidIPFilters.get(key).get(iname).updateExpires(time, ttl);
                                if (dname != null && ttl > 60 * 1000L)
                                    Log.w(TAG, "Address updated " + key + " " + daddr + "/" + dresource);
                            } else {
                                if (dname != null)
                                    Log.i(TAG, "Ignored " + key + " " + daddr + "/" + dresource + "=" + block);
                            }
                        } else
                            Log.w(TAG, "Address not numeric " + name);
                    } catch (UnknownHostException ex) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    }
                }
            }
        }

        lock.writeLock().unlock();
    }

    private void prepareForwarding() {
        lock.writeLock().lock();
        mapForward.clear();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("filter", false)) {
            try (Cursor cursor = DatabaseHelper.getInstance(ServiceFirewall.this).getForwarding()) {
                int colProtocol = cursor.getColumnIndex("protocol");
                int colDPort = cursor.getColumnIndex("dport");
                int colRAddr = cursor.getColumnIndex("raddr");
                int colRPort = cursor.getColumnIndex("rport");
                int colRUid = cursor.getColumnIndex("ruid");
                while (cursor.moveToNext()) {
                    Forward fwd = new Forward();
                    fwd.protocol = cursor.getInt(colProtocol);
                    fwd.dport = cursor.getInt(colDPort);
                    fwd.raddr = cursor.getString(colRAddr);
                    fwd.rport = cursor.getInt(colRPort);
                    fwd.ruid = cursor.getInt(colRUid);
                    mapForward.put(fwd.dport, fwd);
                    Log.i(TAG, "Forward " + fwd);
                }
            }
        }
        lock.writeLock().unlock();
    }

    private void prepareNotify(List<Rule> listRule) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean notify = prefs.getBoolean("notify_access", false);
        boolean system = prefs.getBoolean("manage_system", false);

        lock.writeLock().lock();
        mapNotify.clear();
        for (Rule rule : listRule)
            mapNotify.put(rule.uid, notify && rule.notify && (system || !rule.system));
        lock.writeLock().unlock();
    }

    private boolean isLockedDown(boolean metered) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
        boolean lockdown = prefs.getBoolean("lockdown", false);
        boolean lockdown_wifi = prefs.getBoolean("lockdown_wifi", true);
        boolean lockdown_other = prefs.getBoolean("lockdown_other", true);
        if (metered ? !lockdown_other : !lockdown_wifi)
            lockdown = false;

        return lockdown;
    }

    private List<Rule> getAllowedRules(List<Rule> listRule) {
        List<Rule> listAllowed = new ArrayList<>();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Check state
        boolean wifi = Util.isWifiActive(this);
        boolean metered = Util.isMeteredNetwork(this);
        boolean useMetered = prefs.getBoolean("use_metered", false);
        Set<String> ssidHomes = prefs.getStringSet("wifi_homes", new HashSet<String>());
        String ssidNetwork = Util.getWifiSSID(this);
        String generation = Util.getNetworkGeneration(this);
        boolean unmetered_2g = prefs.getBoolean("unmetered_2g", false);
        boolean unmetered_3g = prefs.getBoolean("unmetered_3g", false);
        boolean unmetered_4g = prefs.getBoolean("unmetered_4g", false);
        boolean roaming = Util.isRoaming(ServiceFirewall.this);
        boolean national = prefs.getBoolean("national_roaming", false);
        boolean eu = prefs.getBoolean("eu_roaming", false);
        boolean tethering = prefs.getBoolean("tethering", false);
        boolean filter = prefs.getBoolean("filter", false);

        // Update connected state
        last_connected = Util.isConnected(ServiceFirewall.this);

        boolean org_metered = metered;
        boolean org_roaming = roaming;

        // https://issuetracker.google.com/issues/70633700
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
            ssidHomes.clear();

        // Update metered state
        if (wifi && !useMetered)
            metered = false;

        if (wifi && ssidHomes.size() > 0 &&
                !(ssidHomes.contains(ssidNetwork) || ssidHomes.contains('"' + ssidNetwork + '"'))) {
            metered = true;
            Log.i(TAG, "!@home=" + ssidNetwork + " homes=" + TextUtils.join(",", ssidHomes));
        }

        if (unmetered_2g && "2G".equals(generation))
            metered = false;
        if (unmetered_3g && "3G".equals(generation))
            metered = false;
        if (unmetered_4g && "4G".equals(generation))
            metered = false;

        last_metered = metered;

        boolean lockdown = isLockedDown(last_metered);

        // Update roaming state
        if (roaming && eu)
            roaming = !Util.isEU(this);
        if (roaming && national)
            roaming = !Util.isNational(this);

        Log.i(TAG, "Get allowed" +
                " connected=" + last_connected +
                " wifi=" + wifi +
                " home=" + TextUtils.join(",", ssidHomes) +
                " network=" + ssidNetwork +
                " metered=" + metered + "/" + org_metered +
                " generation=" + generation +
                " roaming=" + roaming + "/" + org_roaming +
                " interactive=" + last_interactive +
                " tethering=" + tethering +
                " filter=" + filter +
                " lockdown=" + lockdown);

        if (last_connected)
            for (Rule rule : listRule) {
                boolean blocked = (metered ? rule.other_blocked : rule.wifi_blocked);
                boolean screen = (metered ? rule.screen_other : rule.screen_wifi);
                if ((!blocked || (screen && last_interactive)) &&
                        (!metered || !(rule.roaming && roaming)) &&
                        (!lockdown || rule.lockdown))
                    listAllowed.add(rule);
            }

        Log.i(TAG, "Allowed " + listAllowed.size() + " of " + listRule.size());
        return listAllowed;
    }

    private void stopVPN(ParcelFileDescriptor pfd) {
        Log.i(TAG, "Stopping");
        try {
            pfd.close();
        } catch (IOException ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }
    }

    // Called from native code
    private void nativeExit(String reason) {
        Log.w(TAG, "Native exit reason=" + reason);
        if (reason != null) {
            showErrorNotification(reason);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putBoolean("enabled", false).apply();
        }
    }

    // Called from native code
    private void nativeError(int error, String message) {
        Log.w(TAG, "Native error " + error + ": " + message);
        showErrorNotification(message);
    }

    // Called from native code
    private void logPacket(Packet packet) {
        System.out.println("LLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLL");
        logHandler.queue(packet);
    }

    // Called from native code
    private void dnsResolved(ResourceRecord rr) {
        if (DatabaseHelper.getInstance(ServiceFirewall.this).insertDns(rr)) {
            Log.i(TAG, "New IP " + rr);
            prepareUidIPFilters(rr.QName);
        }
        if (rr.uid > 0 && !TextUtils.isEmpty(rr.AName)) {
            lock.readLock().lock();
            boolean malware = (mapMalware.containsKey(rr.AName) && mapMalware.get(rr.AName));
            lock.readLock().unlock();

            if (malware) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                boolean notified = prefs.getBoolean("malware." + rr.uid, false);
                if (!notified) {
                    prefs.edit().putBoolean("malware." + rr.uid, true).apply();
                    //notifyNewApplication(rr.uid, true);
                }
            }
        }
    }

    // Called from native code
    private boolean isDomainBlocked(String name) {
        lock.readLock().lock();
        boolean blocked = (mapHostsBlocked.containsKey(name) && mapHostsBlocked.get(name));
        lock.readLock().unlock();
        return blocked;
    }

    // Called from native code
    @TargetApi(Build.VERSION_CODES.Q)
    private int getUidQ(int version, int protocol, String saddr, int sport, String daddr, int dport) {
        if (protocol != 6 /* TCP */ && protocol != 17 /* UDP */)
            return Process.INVALID_UID;

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null)
            return Process.INVALID_UID;

        InetSocketAddress local = new InetSocketAddress(saddr, sport);
        InetSocketAddress remote = new InetSocketAddress(daddr, dport);

        Log.i(TAG, "Get uid local=" + local + " remote=" + remote);
        int uid = cm.getConnectionOwnerUid(protocol, local, remote);
        Log.i(TAG, "Get uid=" + uid);
        return uid;
    }

    private boolean isSupported(int protocol) {
        return (protocol == 1 /* ICMPv4 */ ||
                protocol == 58 /* ICMPv6 */ ||
                protocol == 6 /* TCP */ ||
                protocol == 17 /* UDP */);
    }

    // Called from native code
    private Allowed isAddressAllowed(Packet packet) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        lock.readLock().lock();

        packet.allowed = false;
        if (prefs.getBoolean("filter", false)) {
            // https://android.googlesource.com/platform/system/core/+/master/include/private/android_filesystem_config.h
            if (packet.protocol == 17 /* UDP */ && !prefs.getBoolean("filter_udp", false)) {
                // Allow unfiltered UDP
                packet.allowed = true;
                Log.i(TAG, "Allowing UDP " + packet);
            } else if (packet.uid < 2000 &&
                    !last_connected && isSupported(packet.protocol) && false) {
                // Allow system applications in disconnected state
                packet.allowed = true;
                Log.w(TAG, "Allowing disconnected system " + packet);
            } else if ((packet.uid < 2000) &&
                    !mapUidKnown.containsKey(packet.uid) && isSupported(packet.protocol)) {
                // Allow unknown (system) traffic
                packet.allowed = true;
                Log.w(TAG, "Allowing unknown system " + packet);
            } else if (packet.uid == Process.myUid()) {
                // Allow self
                packet.allowed = true;
                Log.w(TAG, "Allowing self " + packet);
            } else {
                boolean filtered = false;
                IPKey key = new IPKey(packet.version, packet.protocol, packet.dport, packet.uid);
                if (mapUidIPFilters.containsKey(key))
                    try {
                        InetAddress iaddr = InetAddress.getByName(packet.daddr);
                        Map<InetAddress, IPRule> map = mapUidIPFilters.get(key);
                        if (map != null && map.containsKey(iaddr)) {
                            IPRule rule = map.get(iaddr);
                            if (rule.isExpired())
                                Log.i(TAG, "DNS expired " + packet + " rule " + rule);
                            else {
                                filtered = true;
                                packet.allowed = !rule.isBlocked();
                                Log.i(TAG, "Filtering " + packet +
                                        " allowed=" + packet.allowed + " rule " + rule);
                            }
                        }
                    } catch (UnknownHostException ex) {
                        Log.w(TAG, "Allowed " + ex.toString() + "\n" + Log.getStackTraceString(ex));
                    }

                if (!filtered)
                    if (mapUidAllowed.containsKey(packet.uid))
                        packet.allowed = mapUidAllowed.get(packet.uid);
                    else
                        Log.w(TAG, "No rules for " + packet);
            }
        }

        Allowed allowed = null;
        if (packet.allowed) {
            if (mapForward.containsKey(packet.dport)) {
                Forward fwd = mapForward.get(packet.dport);
                if (fwd.ruid == packet.uid) {
                    allowed = new Allowed();
                } else {
                    allowed = new Allowed(fwd.raddr, fwd.rport);
                    packet.data = "> " + fwd.raddr + "/" + fwd.rport;
                }
            } else
                allowed = new Allowed();
        }

        lock.readLock().unlock();

        if (prefs.getBoolean("log", false) || prefs.getBoolean("log_app", false))
            if (packet.protocol != 6 /* TCP */ || !"".equals(packet.flags))
                if (packet.uid != Process.myUid())
                    logPacket(packet);

        return allowed;
    }

    // Called from native code
    private void accountUsage(Usage usage) {
        logHandler.account(usage);
    }

    private BroadcastReceiver interactiveStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            executor.submit(new Runnable() {
                @Override
                public void run() {
                    AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                    Intent i = new Intent(ACTION_SCREEN_OFF_DELAYED);
                    i.setPackage(context.getPackageName());
                    /*
                    PendingIntent pi = PendingIntentCompat.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
                    am.cancel(pi);*/

                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        flags |= PendingIntent.FLAG_IMMUTABLE;
                    }

                    PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, flags);
                    am.cancel(pi);

                    try {
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
                        int delay;
                        try {
                            delay = Integer.parseInt(prefs.getString("screen_delay", "0"));
                        } catch (NumberFormatException ignored) {
                            delay = 0;
                        }
                        boolean interactive = Intent.ACTION_SCREEN_ON.equals(intent.getAction());

                        if (interactive || delay == 0) {
                            last_interactive = interactive;
                            reload("interactive state changed", ServiceFirewall.this, true);
                        } else {
                            if (ACTION_SCREEN_OFF_DELAYED.equals(intent.getAction())) {
                                last_interactive = interactive;
                                reload("interactive state changed", ServiceFirewall.this, true);
                            } else {
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
                                    am.set(AlarmManager.RTC_WAKEUP, new Date().getTime() + delay * 60 * 1000L, pi);
                                else
                                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, new Date().getTime() + delay * 60 * 1000L, pi);
                            }
                        }

                        // Start/stop stats
                        statsHandler.sendEmptyMessage(
                                Util.isInteractive(ServiceFirewall.this) ? MSG_STATS_START : MSG_STATS_STOP);
                    } catch (Throwable ex) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));

                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
                            am.set(AlarmManager.RTC_WAKEUP, new Date().getTime() + 15 * 1000L, pi);
                        else
                            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, new Date().getTime() + 15 * 1000L, pi);
                    }
                }
            });
        }
    };

    private BroadcastReceiver userReceiver = new BroadcastReceiver() {
        @Override
        @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            user_foreground = Intent.ACTION_USER_FOREGROUND.equals(intent.getAction());
            Log.i(TAG, "User foreground=" + user_foreground + " user=" + (Process.myUid() / 100000));

            if (user_foreground) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
                if (prefs.getBoolean("enabled", false)) {
                    // Allow service of background user to stop
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                    }

                    start("foreground", ServiceFirewall.this);
                }
            } else
                stop("background", ServiceFirewall.this, true);
        }
    };

    private BroadcastReceiver idleStateReceiver = new BroadcastReceiver() {
        @Override
        @TargetApi(Build.VERSION_CODES.M)
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            Log.i(TAG, "device idle=" + pm.isDeviceIdleMode());

            // Reload rules when coming from idle mode
            if (!pm.isDeviceIdleMode())
                reload("idle state changed", ServiceFirewall.this, false);
        }
    };

    private BroadcastReceiver connectivityChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Filter VPN connectivity changes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                int networkType = intent.getIntExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, ConnectivityManager.TYPE_DUMMY);
                if (networkType == ConnectivityManager.TYPE_VPN)
                    return;
            }

            // Reload rules
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);
            reload("connectivity changed", ServiceFirewall.this, false);
        }
    };

    ConnectivityManager.NetworkCallback networkMonitorCallback = new ConnectivityManager.NetworkCallback() {
        private String TAG = "NetGuard.Monitor";

        private Map<Network, Long> validated = new HashMap<>();

        // https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/connectivity/NetworkMonitor.java

        @Override
        public void onAvailable(Network network) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getNetworkInfo(network);
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            Log.i(TAG, "Available network " + network + " " + ni);
            Log.i(TAG, "Capabilities=" + capabilities);
            checkConnectivity(network, ni, capabilities);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getNetworkInfo(network);
            Log.i(TAG, "New capabilities network " + network + " " + ni);
            Log.i(TAG, "Capabilities=" + capabilities);
            checkConnectivity(network, ni, capabilities);
        }

        @Override
        public void onLosing(Network network, int maxMsToLive) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getNetworkInfo(network);
            Log.i(TAG, "Losing network " + network + " within " + maxMsToLive + " ms " + ni);
        }

        @Override
        public void onLost(Network network) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getNetworkInfo(network);
            Log.i(TAG, "Lost network " + network + " " + ni);

            synchronized (validated) {
                validated.remove(network);
            }
        }

        @Override
        public void onUnavailable() {
            Log.i(TAG, "No networks available");
        }

        private void checkConnectivity(Network network, NetworkInfo ni, NetworkCapabilities capabilities) {
            if (ni != null && capabilities != null &&
                    ni.getDetailedState() != NetworkInfo.DetailedState.SUSPENDED &&
                    ni.getDetailedState() != NetworkInfo.DetailedState.BLOCKED &&
                    ni.getDetailedState() != NetworkInfo.DetailedState.DISCONNECTED &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {

                synchronized (validated) {
                    if (validated.containsKey(network) &&
                            validated.get(network) + 20 * 1000 > new Date().getTime()) {
                        Log.i(TAG, "Already validated " + network + " " + ni);
                        return;
                    }
                }

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
                String host = prefs.getString("validate", "www.google.com");
                Log.i(TAG, "Validating " + network + " " + ni + " host=" + host);

                Socket socket = null;
                try {
                    socket = network.getSocketFactory().createSocket();
                    socket.connect(new InetSocketAddress(host, 443), 10000);
                    Log.i(TAG, "Validated " + network + " " + ni + " host=" + host);
                    synchronized (validated) {
                        validated.put(network, new Date().getTime());
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                        cm.reportNetworkConnectivity(network, true);
                        Log.i(TAG, "Reported " + network + " " + ni);
                    }
                } catch (IOException ex) {
                    Log.e(TAG, ex.toString());
                    Log.i(TAG, "No connectivity " + network + " " + ni);
                } finally {
                    if (socket != null)
                        try {
                            socket.close();
                        } catch (IOException ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
                }
            }
        }
    };

    private PhoneStateListener phoneStateListener = new PhoneStateListener() {
        private String last_generation = null;

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            if (state == TelephonyManager.DATA_CONNECTED) {
                String current_generation = Util.getNetworkGeneration(ServiceFirewall.this);
                Log.i(TAG, "Data connected generation=" + current_generation);

                if (last_generation == null || !last_generation.equals(current_generation)) {
                    Log.i(TAG, "New network generation=" + current_generation);
                    last_generation = current_generation;

                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
                    if (prefs.getBoolean("unmetered_2g", false) ||
                            prefs.getBoolean("unmetered_3g", false) ||
                            prefs.getBoolean("unmetered_4g", false))
                        reload("data connection state changed", ServiceFirewall.this, false);
                }
            }
        }
    };

    private BroadcastReceiver packageChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            try {
                if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                    // Application added
                    Rule.clearCache(context);

                    if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        // Show notification
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                        /*
                        if (IAB.isPurchased(ActivityPro.SKU_NOTIFY, context) && prefs.getBoolean("install", true)) {
                            int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                            notifyNewApplication(uid, false);
                        }
                        */
                    }

                    reload("package added", context, false);

                } else if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                    // Application removed
                    Rule.clearCache(context);

                    if (intent.getBooleanExtra(Intent.EXTRA_DATA_REMOVED, false)) {
                        // Remove settings
                        String packageName = intent.getData().getSchemeSpecificPart();
                        Log.i(TAG, "Deleting settings package=" + packageName);
                        context.getSharedPreferences("wifi", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                        context.getSharedPreferences("other", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                        context.getSharedPreferences("screen_wifi", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                        context.getSharedPreferences("screen_other", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                        context.getSharedPreferences("roaming", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                        context.getSharedPreferences("lockdown", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                        context.getSharedPreferences("apply", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                        context.getSharedPreferences("notify", Context.MODE_PRIVATE).edit().remove(packageName).apply();

                        int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);
                        if (uid > 0) {
                            DatabaseHelper dh = DatabaseHelper.getInstance(context);
                            dh.clearLog(uid);
                            dh.clearAccess(uid, false);

                            NotificationManagerCompat.from(context).cancel(uid); // installed notification
                            NotificationManagerCompat.from(context).cancel(uid + 10000); // access notification
                        }
                    }

                    reload("package deleted", context, false);
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }
        }
    };

    public void notifyNewApplication(int uid, boolean malware) {
        /*
        if (uid < 0)
            return;
        if (uid == Process.myUid())
            return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        try {
            // Get application name
            List<String> names = Util.getApplicationNames(uid, this);
            if (names.size() == 0)
                return;
            String name = TextUtils.join(", ", names);

            // Get application info
            PackageManager pm = getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            if (packages == null || packages.length < 1)
                throw new PackageManager.NameNotFoundException(Integer.toString(uid));
            boolean internet = Util.hasInternet(uid, this);

            // Build notification
            Intent main = new Intent();
            main.setClassName("com.myfirewall", "com.myfirewall.app.MainActivity");

            //Intent main = new Intent(this, ActivityMain.class);
            main.putExtra(Data.EXTRA_REFRESH, true);
            main.putExtra(Data.EXTRA_SEARCH, Integer.toString(uid));
            PendingIntent pi = PendingIntentCompat.getActivity(this, uid, main, PendingIntent.FLAG_UPDATE_CURRENT);

            TypedValue tv = new TypedValue();
            //getTheme().resolveAttribute(R.attr.colorPrimary, tv, true);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this,
                    malware ? "malware" : "notify");
            builder.setSmallIcon(R.drawable.ic_security_white_24dp)
                    .setContentIntent(pi)
                    .setColor(tv.data)
                    .setAutoCancel(true);

            if (malware)
                builder.setContentTitle(name)
                        .setContentText(getString(R.string.msg_malware, name));
            else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    builder.setContentTitle(name)
                            .setContentText(getString(R.string.msg_installed_n));
                else
                    builder.setContentTitle(getString(R.string.app_name))
                            .setContentText(getString(R.string.msg_installed, name));
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setVisibility(NotificationCompat.VISIBILITY_SECRET);

            // Get defaults
            SharedPreferences prefs_wifi = getSharedPreferences("wifi", Context.MODE_PRIVATE);
            SharedPreferences prefs_other = getSharedPreferences("other", Context.MODE_PRIVATE);
            boolean wifi = prefs_wifi.getBoolean(packages[0], prefs.getBoolean("whitelist_wifi", true));
            boolean other = prefs_other.getBoolean(packages[0], prefs.getBoolean("whitelist_other", true));

            // Build Wi-Fi action
            Intent riWifi = new Intent(this, ServiceSinkhole.class);
            riWifi.putExtra(ServiceSinkhole.EXTRA_COMMAND, Command.set);
            riWifi.putExtra(ServiceSinkhole.EXTRA_NETWORK, "wifi");
            riWifi.putExtra(ServiceSinkhole.EXTRA_UID, uid);
            riWifi.putExtra(ServiceSinkhole.EXTRA_PACKAGE, packages[0]);
            riWifi.putExtra(ServiceSinkhole.EXTRA_BLOCKED, !wifi);

            PendingIntent piWifi = PendingIntentCompat.getService(this, uid, riWifi, PendingIntent.FLAG_UPDATE_CURRENT);
            NotificationCompat.Action wAction = new NotificationCompat.Action.Builder(
                    wifi ? R.drawable.wifi_on : R.drawable.wifi_off,
                    getString(wifi ? R.string.title_allow_wifi : R.string.title_block_wifi),
                    piWifi
            ).build();
            builder.addAction(wAction);

            // Build mobile action
            Intent riOther = new Intent(this, ServiceSinkhole.class);
            riOther.putExtra(ServiceSinkhole.EXTRA_COMMAND, Command.set);
            riOther.putExtra(ServiceSinkhole.EXTRA_NETWORK, "other");
            riOther.putExtra(ServiceSinkhole.EXTRA_UID, uid);
            riOther.putExtra(ServiceSinkhole.EXTRA_PACKAGE, packages[0]);
            riOther.putExtra(ServiceSinkhole.EXTRA_BLOCKED, !other);
            PendingIntent piOther = PendingIntentCompat.getService(this, uid + 10000, riOther, PendingIntent.FLAG_UPDATE_CURRENT);
            NotificationCompat.Action oAction = new NotificationCompat.Action.Builder(
                    other ? R.drawable.other_on : R.drawable.other_off,
                    getString(other ? R.string.title_allow_other : R.string.title_block_other),
                    piOther
            ).build();
            builder.addAction(oAction);

            // Show notification
            if (internet) {
                if (Util.canNotify(this))
                    NotificationManagerCompat.from(this).notify(uid, builder.build());
            } else {
                NotificationCompat.BigTextStyle expanded = new NotificationCompat.BigTextStyle(builder);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    expanded.bigText(getString(R.string.msg_installed_n));
                else
                    expanded.bigText(getString(R.string.msg_installed, name));
                expanded.setSummaryText(getString(R.string.title_internet));
                if (Util.canNotify(this))
                    NotificationManagerCompat.from(this).notify(uid, expanded.build());
            }

        } catch (PackageManager.NameNotFoundException ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }*/
    }

    @Override
    public void onCreate() {
        Log.i(TAG, "Create version=" + Util.getSelfVersionName(this) + "/" + Util.getSelfVersionCode(this));
        startForeground(NOTIFY_WAITING, getWaitingNotification());

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (jni_context != 0) {
            Log.w(TAG, "Create with context=" + jni_context);
            jni_stop(jni_context);
            synchronized (jni_lock) {
                jni_done(jni_context);
                jni_context = 0;
            }
        }

        // Native init
        jni_context = jni_init(Build.VERSION.SDK_INT);
        Log.i(TAG, "Created context=" + jni_context);
        boolean pcap = prefs.getBoolean("pcap", false);
        setPcap(pcap, this);

        prefs.registerOnSharedPreferenceChangeListener(this);

        //Util.setTheme(this);
        super.onCreate();

        HandlerThread commandThread = new HandlerThread(getString(R.string.app_name) + " command", Process.THREAD_PRIORITY_FOREGROUND);
        HandlerThread logThread = new HandlerThread(getString(R.string.app_name) + " log", Process.THREAD_PRIORITY_BACKGROUND);
        HandlerThread statsThread = new HandlerThread(getString(R.string.app_name) + " stats", Process.THREAD_PRIORITY_BACKGROUND);
        commandThread.start();
        logThread.start();
        statsThread.start();

        commandLooper = commandThread.getLooper();
        logLooper = logThread.getLooper();
        statsLooper = statsThread.getLooper();

        commandHandler = new CommandHandler(commandLooper);
        logHandler = new LogHandler(logLooper);
        statsHandler = new StatsHandler(statsLooper);

        // Listen for user switches
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            IntentFilter ifUser = new IntentFilter();
            ifUser.addAction(Intent.ACTION_USER_BACKGROUND);
            ifUser.addAction(Intent.ACTION_USER_FOREGROUND);
            ContextCompat.registerReceiver(this, userReceiver, ifUser, ContextCompat.RECEIVER_NOT_EXPORTED);
            registeredUser = true;
        }

        // Listen for idle mode state changes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            IntentFilter ifIdle = new IntentFilter();
            ifIdle.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            ContextCompat.registerReceiver(this, idleStateReceiver, ifIdle, ContextCompat.RECEIVER_NOT_EXPORTED);
            registeredIdleState = true;
        }

        // Listen for added/removed applications
        IntentFilter ifPackage = new IntentFilter();
        ifPackage.addAction(Intent.ACTION_PACKAGE_ADDED);
        ifPackage.addAction(Intent.ACTION_PACKAGE_REMOVED);
        ifPackage.addDataScheme("package");
        ContextCompat.registerReceiver(this, packageChangedReceiver, ifPackage, ContextCompat.RECEIVER_NOT_EXPORTED);
        registeredPackageChanged = true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            try {
                listenNetworkChanges();
            } catch (Throwable ex) {
                Log.w(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                listenConnectivityChanges();
            }
        else
            listenConnectivityChanges();

        // Monitor networks
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.registerNetworkCallback(
                new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                networkMonitorCallback);

        // Setup house holding
        Intent alarmIntent = new Intent(this, ServiceFirewall.class);
        alarmIntent.setAction(ACTION_HOUSE_HOLDING);

//        PendingIntent pi;
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
//            pi = PendingIntentCompat.getForegroundService(this, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT);
//        else
//            pi = PendingIntentCompat.getService(this, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent pi;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pi = PendingIntent.getForegroundService(this, 0, alarmIntent, flags);
        } else {
            pi = PendingIntent.getService(this, 0, alarmIntent, flags);
        }


        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.setInexactRepeating(AlarmManager.RTC, SystemClock.elapsedRealtime() + 60 * 1000, AlarmManager.INTERVAL_HALF_DAY, pi);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void listenNetworkChanges() {
        // Listen for network changes
        Log.i(TAG, "Starting listening to network changes");
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);

        ConnectivityManager.NetworkCallback nc = new ConnectivityManager.NetworkCallback() {
            private Network last_network = null;
            private Boolean last_connected = null;
            private Boolean last_metered = null;
            private String last_generation = null;
            private List<InetAddress> last_dns = null;

            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "Available network=" + network);
                last_connected = Util.isConnected(ServiceFirewall.this);
                last_metered = Util.isMeteredNetwork(ServiceFirewall.this);
                reload("network available", ServiceFirewall.this, false);
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                Log.i(TAG, "Changed properties=" + network + " props=" + linkProperties);

                // Make sure the right DNS servers are being used
                List<InetAddress> dns = linkProperties.getDnsServers();
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? !same(last_dns, dns)
                        : prefs.getBoolean("reload_onconnectivity", false)) {
                    Log.i(TAG, "Changed link properties=" + linkProperties +
                            "DNS cur=" + TextUtils.join(",", dns) +
                            "DNS prv=" + (last_dns == null ? null : TextUtils.join(",", last_dns)));
                    last_dns = dns;
                    reload("link properties changed", ServiceFirewall.this, false);
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                Log.i(TAG, "Changed capabilities=" + network + " caps=" + networkCapabilities);

                boolean connected = Util.isConnected(ServiceFirewall.this);
                boolean metered = Util.isMeteredNetwork(ServiceFirewall.this);
                String generation = Util.getNetworkGeneration(ServiceFirewall.this);
                Log.i(TAG, "Connected=" + connected + "/" + last_connected +
                        " unmetered=" + metered + "/" + last_metered +
                        " generation=" + generation + "/" + last_generation);

                String reason = null;

                if (reason == null && !Objects.equals(network, last_network))
                    reason = "Network changed";

                if (reason == null && last_connected != null && !last_connected.equals(connected))
                    reason = "Connected state changed";

                if (reason == null && last_metered != null && !last_metered.equals(metered))
                    reason = "Unmetered state changed";

                if (reason == null && last_generation != null && !last_generation.equals(generation)) {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
                    if (prefs.getBoolean("unmetered_2g", false) ||
                            prefs.getBoolean("unmetered_3g", false) ||
                            prefs.getBoolean("unmetered_4g", false))
                        reason = "Generation changed";
                }

                if (reason != null)
                    reload(reason, ServiceFirewall.this, false);

                last_network = network;
                last_connected = connected;
                last_metered = metered;
                last_generation = generation;
            }

            @Override
            public void onLost(Network network) {
                Log.i(TAG, "Lost network=" + network);
                last_connected = Util.isConnected(ServiceFirewall.this);
                reload("network lost", ServiceFirewall.this, false);
            }

            boolean same(List<InetAddress> last, List<InetAddress> current) {
                if (last == null || current == null)
                    return false;
                if (last == null || last.size() != current.size())
                    return false;

                for (int i = 0; i < current.size(); i++)
                    if (!last.get(i).equals(current.get(i)))
                        return false;

                return true;
            }
        };
        cm.registerNetworkCallback(builder.build(), nc);
        networkCallback = nc;
    }

    private void listenConnectivityChanges() {
        // Listen for connectivity updates
        Log.i(TAG, "Starting listening to connectivity changes");
        IntentFilter ifConnectivity = new IntentFilter();
        ifConnectivity.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        ContextCompat.registerReceiver(this, connectivityChangedReceiver, ifConnectivity, ContextCompat.RECEIVER_NOT_EXPORTED);
        registeredConnectivityChanged = true;

        // Listen for phone state changes
        Log.i(TAG, "Starting listening to service state changes");
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            tm.listen(phoneStateListener, PhoneStateListener.LISTEN_DATA_CONNECTION_STATE);
            phone_state = true;
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String name) {
        if ("theme".equals(name)) {
            Log.i(TAG, "Theme changed");
            //Util.setTheme(this);
            if (state != State.none) {
                Log.d(TAG, "Stop foreground state=" + state.toString());
                stopForeground(true);
            }
            if (state == State.enforcing)
                startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1));
            else if (state != State.none)
                startForeground(NOTIFY_WAITING, getWaitingNotification());
            Log.d(TAG, "Start foreground state=" + state.toString());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (state == State.enforcing)
            startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1));
        else
            startForeground(NOTIFY_WAITING, getWaitingNotification());

        Log.i(TAG, "Received " + intent);
        Util.logExtras(intent);

        // Check for set command
        if (intent != null && intent.hasExtra(EXTRA_COMMAND) &&
                intent.getSerializableExtra(EXTRA_COMMAND) == Command.set) {
            set(intent);
            return START_STICKY;
        }

        // Keep awake
        getLock(this).acquire();

        // Get state
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean enabled = prefs.getBoolean("enabled", false);

        // Handle service restart
        if (intent == null) {
            Log.i(TAG, "Restart");

            // Recreate intent
            intent = new Intent(this, ServiceFirewall.class);
            intent.putExtra(EXTRA_COMMAND, enabled ? Command.start : Command.stop);
        }

        if (ACTION_HOUSE_HOLDING.equals(intent.getAction()))
            intent.putExtra(EXTRA_COMMAND, Command.householding);
        if (ACTION_WATCHDOG.equals(intent.getAction()))
            intent.putExtra(EXTRA_COMMAND, Command.watchdog);

        Command cmd = (Command) intent.getSerializableExtra(EXTRA_COMMAND);
        if (cmd == null)
            intent.putExtra(EXTRA_COMMAND, enabled ? Command.start : Command.stop);
        String reason = intent.getStringExtra(EXTRA_REASON);
        Log.i(TAG, "Start intent=" + intent + " command=" + cmd + " reason=" + reason +
                " vpn=" + (vpn != null) + " user=" + (Process.myUid() / 100000));

        commandHandler.queue(intent);

        return START_STICKY;
    }

    private void set(Intent intent) {
        // Get arguments
        int uid = intent.getIntExtra(EXTRA_UID, 0);
        String network = intent.getStringExtra(EXTRA_NETWORK);
        String pkg = intent.getStringExtra(EXTRA_PACKAGE);
        boolean blocked = intent.getBooleanExtra(EXTRA_BLOCKED, false);
        Log.i(TAG, "Set " + pkg + " " + network + "=" + blocked);

        // Get defaults
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(ServiceFirewall.this);
        boolean default_wifi = settings.getBoolean("whitelist_wifi", true);
        boolean default_other = settings.getBoolean("whitelist_other", true);

        // Update setting
        SharedPreferences prefs = getSharedPreferences(network, Context.MODE_PRIVATE);
        if (blocked == ("wifi".equals(network) ? default_wifi : default_other))
            prefs.edit().remove(pkg).apply();
        else
            prefs.edit().putBoolean(pkg, blocked).apply();

        // Apply rules
        ServiceFirewall.reload("notification", ServiceFirewall.this, false);

        // Update notification
        notifyNewApplication(uid, false);

        // Update UI
        Intent ruleset = new Intent(Data.ACTION_RULES_CHANGED);
        LocalBroadcastManager.getInstance(ServiceFirewall.this).sendBroadcast(ruleset);
    }

    @Override
    public void onRevoke() {
        Log.i(TAG, "Revoke");

        // Disable firewall (will result in stop command)
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putBoolean("enabled", false).apply();

        // Feedback
        showDisabledNotification();

        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        synchronized (this) {
            Log.i(TAG, "Destroy");
            commandLooper.quit();
            logLooper.quit();
            statsLooper.quit();

            for (Command command : Command.values())
                commandHandler.removeMessages(command.ordinal());
            releaseLock(this);

            // Registered in command loop
            if (registeredInteractiveState) {
                unregisterReceiver(interactiveStateReceiver);
                registeredInteractiveState = false;
            }
            if (callStateListener != null) {
                TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                tm.listen(callStateListener, PhoneStateListener.LISTEN_NONE);
                callStateListener = null;
            }

            // Register in onCreate
            if (registeredUser) {
                unregisterReceiver(userReceiver);
                registeredUser = false;
            }
            if (registeredIdleState) {
                unregisterReceiver(idleStateReceiver);
                registeredIdleState = false;
            }
            if (registeredPackageChanged) {
                unregisterReceiver(packageChangedReceiver);
                registeredPackageChanged = false;
            }

            if (networkCallback != null) {
                unlistenNetworkChanges();
                networkCallback = null;
            }
            if (registeredConnectivityChanged) {
                unregisterReceiver(connectivityChangedReceiver);
                registeredConnectivityChanged = false;
            }

            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.unregisterNetworkCallback(networkMonitorCallback);

            if (phone_state) {
                TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                tm.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
                phone_state = false;
            }

            try {
                if (vpn != null) {
                    stopNative(vpn);
                    stopVPN(vpn);
                    vpn = null;
                    unprepare();
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }

            Log.i(TAG, "Destroy context=" + jni_context);
            synchronized (jni_lock) {
                jni_done(jni_context);
                jni_context = 0;
            }

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.unregisterOnSharedPreferenceChangeListener(this);
        }

        super.onDestroy();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void unlistenNetworkChanges() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.unregisterNetworkCallback((ConnectivityManager.NetworkCallback) networkCallback);
    }

    private Notification getEnforcingNotification(int allowed, int blocked, int hosts) {
        Intent main = new Intent();
        main.setClassName("com.myfirewall", "com.myfirewall.app.MainActivity");

        //Intent main = new Intent(this, ActivityMain.class);
        //PendingIntent pi = PendingIntentCompat.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        // Створення PendingIntent для Activity
        PendingIntent pi = PendingIntent.getActivity(this, 0, main, flags);

        TypedValue tv = new TypedValue();
        //getTheme().resolveAttribute(R.attr.colorPrimary, tv, true);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "foreground");
        builder.setSmallIcon(isLockedDown(last_metered) ? R.drawable.ic_lock_outline_white_24dp : R.drawable.ic_security_white_24dp)
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(true)
                .setAutoCancel(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            builder.setContentTitle(getString(R.string.msg_started));
        else
            builder.setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.msg_started));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setPriority(NotificationCompat.PRIORITY_MIN);

        if (allowed >= 0)
            last_allowed = allowed;
        else
            allowed = last_allowed;
        if (blocked >= 0)
            last_blocked = blocked;
        else
            blocked = last_blocked;
        if (hosts >= 0)
            last_hosts = hosts;
        else
            hosts = last_hosts;

        if (allowed >= 0 || blocked >= 0 || hosts >= 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (Util.isPlayStoreInstall(this))
                    builder.setContentText(getString(R.string.msg_packages, allowed, blocked));
                else
                    builder.setContentText(getString(R.string.msg_hosts, allowed, blocked, hosts));
                return builder.build();
            } else {
                NotificationCompat.BigTextStyle notification = new NotificationCompat.BigTextStyle(builder);
                notification.bigText(getString(R.string.msg_started));
                if (Util.isPlayStoreInstall(this))
                    notification.setSummaryText(getString(R.string.msg_packages, allowed, blocked));
                else
                    notification.setSummaryText(getString(R.string.msg_hosts, allowed, blocked, hosts));
                return notification.build();
            }
        } else
            return builder.build();
    }

    private void updateEnforcingNotification(int allowed, int total) {
        // Update notification
        Notification notification = getEnforcingNotification(allowed, total - allowed, mapHostsBlocked.size());
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Util.canNotify(this))
            nm.notify(NOTIFY_ENFORCING, notification);
    }

    private Notification getWaitingNotification() {
        //Intent main = new Intent(this, ActivityMain.class);

        Intent main = new Intent();
        main.setClassName("com.myfirewall", "com.myfirewall.app.MainActivity");

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        // Створення PendingIntent для Activity
        PendingIntent pi = PendingIntent.getActivity(this, 0, main, flags);

        //PendingIntent pi = PendingIntentCompat.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT);

        TypedValue tv = new TypedValue();
        //getTheme().resolveAttribute(R.attr.colorPrimary, tv, true);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "foreground");
        builder.setSmallIcon(R.drawable.ic_security_white_24dp)
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(true)
                .setAutoCancel(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            builder.setContentTitle(getString(R.string.msg_waiting));
        else
            builder.setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.msg_waiting));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setPriority(NotificationCompat.PRIORITY_MIN);

        return builder.build();
    }

    private void showDisabledNotification() {
        //Intent main = new Intent(this, ActivityMain.class);
        //PendingIntent pi = PendingIntentCompat.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent main = new Intent();
        main.setClassName("com.myfirewall", "com.myfirewall.app.MainActivity");

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        // Використання PendingIntentCompat для створення PendingIntent
        PendingIntent pi = PendingIntent.getActivity(this, 0, main, flags);

        TypedValue tv = new TypedValue();
        //getTheme().resolveAttribute(R.attr.colorOff, tv, true);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "notify");
        builder.setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_revoked))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET);

        NotificationCompat.BigTextStyle notification = new NotificationCompat.BigTextStyle(builder);
        notification.bigText(getString(R.string.msg_revoked));

        if (Util.canNotify(this))
            NotificationManagerCompat.from(this).notify(NOTIFY_DISABLED, notification.build());
    }

    private void showLockdownNotification() {
        /*
        Intent intent = new Intent(Settings.ACTION_VPN_SETTINGS);
        //PendingIntent pi = PendingIntentCompat.getActivity(this, NOTIFY_LOCKDOWN, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pi = PendingIntent.getActivity(this, NOTIFY_LOCKDOWN, intent, flags);

        TypedValue tv = new TypedValue();
        //getTheme().resolveAttribute(R.attr.colorOff, tv, true);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "notify");
        builder.setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_always_on_lockdown))
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET);

        NotificationCompat.BigTextStyle notification = new NotificationCompat.BigTextStyle(builder);
        notification.bigText(getString(R.string.msg_always_on_lockdown));

        if (Util.canNotify(this))
            NotificationManagerCompat.from(this).notify(NOTIFY_LOCKDOWN, notification.build());*/
    }

    private void removeLockdownNotification() {
        NotificationManagerCompat.from(this).cancel(NOTIFY_LOCKDOWN);
    }

    private void showErrorNotification(String message) {
        Intent main = new Intent();
        main.setClassName("com.myfirewall", "com.myfirewall.app.MainActivity");

        //Intent main = new Intent(this, ActivityMain.class);
        //PendingIntent pi = PendingIntentCompat.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        // Використання PendingIntentCompat для створення PendingIntent
        PendingIntent pi = PendingIntent.getActivity(this, 0, main, flags);

        TypedValue tv = new TypedValue();
        //getTheme().resolveAttribute(R.attr.colorOff, tv, true);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "notify");
        builder.setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_error, message))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET);

        NotificationCompat.BigTextStyle notification = new NotificationCompat.BigTextStyle(builder);
        notification.bigText(getString(R.string.msg_error, message));
        notification.setSummaryText(message);

        if (Util.canNotify(this))
            NotificationManagerCompat.from(this).notify(NOTIFY_ERROR, notification.build());
    }

    private void showAccessNotification(int uid) {
        /*
        List<String> apps = Util.getApplicationNames(uid, ServiceSinkhole.this);
        if (apps.size() == 0)
            return;
        String name = TextUtils.join(", ", apps);

        Intent main = new Intent(ServiceSinkhole.this, ActivityMain.class);

        main.putExtra(Data.EXTRA_SEARCH, Integer.toString(uid));
        PendingIntent pi = PendingIntentCompat.getActivity(ServiceSinkhole.this, uid + 10000, main, PendingIntent.FLAG_UPDATE_CURRENT);

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorOn, tv, true);
        int colorOn = tv.data;
        getTheme().resolveAttribute(R.attr.colorOff, tv, true);
        int colorOff = tv.data;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "access");
        builder.setSmallIcon(R.drawable.ic_cloud_upload_white_24dp)
                .setGroup("AccessAttempt")
                .setContentIntent(pi)
                .setColor(colorOff)
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            builder.setContentTitle(name)
                    .setContentText(getString(R.string.msg_access_n));
        else
            builder.setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.msg_access, name));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET);

        DateFormat df = new SimpleDateFormat("dd HH:mm");

        NotificationCompat.InboxStyle notification = new NotificationCompat.InboxStyle(builder);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            notification.addLine(getString(R.string.msg_access_n));
        else {
            String sname = getString(R.string.msg_access, name);
            int pos = sname.indexOf(name);
            Spannable sp = new SpannableString(sname);
            sp.setSpan(new StyleSpan(Typeface.BOLD), pos, pos + name.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            notification.addLine(sp);
        }

        long since = 0;
        PackageManager pm = getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages != null && packages.length > 0)
            try {
                since = pm.getPackageInfo(packages[0], 0).firstInstallTime;
            } catch (PackageManager.NameNotFoundException ignored) {
            }

        try (Cursor cursor = DatabaseHelper.getInstance(ServiceSinkhole.this).getAccessUnset(uid, 7, since)) {
            int colDAddr = cursor.getColumnIndex("daddr");
            int colTime = cursor.getColumnIndex("time");
            int colAllowed = cursor.getColumnIndex("allowed");
            while (cursor.moveToNext()) {
                StringBuilder sb = new StringBuilder();
                sb.append(df.format(cursor.getLong(colTime))).append(' ');

                String daddr = cursor.getString(colDAddr);
                if (Util.isNumericAddress(daddr))
                    try {
                        daddr = InetAddress.getByName(daddr).getHostName();
                    } catch (UnknownHostException ignored) {
                    }
                sb.append(daddr);

                int allowed = cursor.getInt(colAllowed);
                if (allowed >= 0) {
                    int pos = sb.indexOf(daddr);
                    Spannable sp = new SpannableString(sb);
                    ForegroundColorSpan fgsp = new ForegroundColorSpan(allowed > 0 ? colorOn : colorOff);
                    sp.setSpan(fgsp, pos, pos + daddr.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    notification.addLine(sp);
                } else
                    notification.addLine(sb);
            }
        }

        if (Util.canNotify(this))
            NotificationManagerCompat.from(this).notify(uid + 10000, notification.build());*/
    }

    private void showUpdateNotification(String name, String url) {
        /*
        Intent download = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        PendingIntent pi = PendingIntentCompat.getActivity(this, 0, download, PendingIntent.FLAG_UPDATE_CURRENT);

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorPrimary, tv, true);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "notify");
        builder.setSmallIcon(R.drawable.ic_security_white_24dp)
                .setContentTitle(name)
                .setContentText(getString(R.string.msg_update))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET);

        if (Util.canNotify(this))
            NotificationManagerCompat.from(this).notify(NOTIFY_UPDATE, builder.build());*/
    }

    private void removeWarningNotifications() {
        NotificationManagerCompat.from(this).cancel(NOTIFY_DISABLED);
        NotificationManagerCompat.from(this).cancel(NOTIFY_AUTOSTART);
        NotificationManagerCompat.from(this).cancel(NOTIFY_ERROR);
    }

    private class Builder extends VpnService.Builder {
        private Network activeNetwork;
        private NetworkInfo networkInfo;
        private int mtu;
        private List<String> listAddress = new ArrayList<>();
        private List<String> listRoute = new ArrayList<>();
        private List<InetAddress> listDns = new ArrayList<>();
        private List<String> listAllowed = new ArrayList<>();
        private List<String> listDisallowed = new ArrayList<>();

        private Builder() {
            super();
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            activeNetwork = (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ? null : cm.getActiveNetwork());
            networkInfo = cm.getActiveNetworkInfo();
        }

        @Override
        public VpnService.Builder setMtu(int mtu) {
            this.mtu = mtu;
            super.setMtu(mtu);
            return this;
        }

        @Override
        public Builder addAddress(String address, int prefixLength) {
            listAddress.add(address + "/" + prefixLength);
            super.addAddress(address, prefixLength);
            return this;
        }

        @Override
        public Builder addRoute(String address, int prefixLength) {
            listRoute.add(address + "/" + prefixLength);
            super.addRoute(address, prefixLength);
            return this;
        }

        @Override
        public Builder addRoute(InetAddress address, int prefixLength) {
            listRoute.add(address.getHostAddress() + "/" + prefixLength);
            super.addRoute(address, prefixLength);
            return this;
        }

        @Override
        public Builder addDnsServer(InetAddress address) {
            listDns.add(address);
            super.addDnsServer(address);
            return this;
        }

        @Override
        public VpnService.Builder addAllowedApplication(String packageName) throws PackageManager.NameNotFoundException {
            listAllowed.add(packageName);
            return super.addAllowedApplication(packageName);
        }

        @Override
        public Builder addDisallowedApplication(String packageName) throws PackageManager.NameNotFoundException {
            listDisallowed.add(packageName);
            super.addDisallowedApplication(packageName);
            return this;
        }

        @Override
        public boolean equals(Object obj) {
            Builder other = (Builder) obj;

            if (other == null)
                return false;

            if (!Objects.equals(this.activeNetwork, other.activeNetwork))
                return false;

            if (this.networkInfo == null || other.networkInfo == null ||
                    this.networkInfo.getType() != other.networkInfo.getType())
                return false;

            if (this.mtu != other.mtu)
                return false;

            if (this.listAddress.size() != other.listAddress.size())
                return false;

            if (this.listRoute.size() != other.listRoute.size())
                return false;

            if (this.listDns.size() != other.listDns.size())
                return false;

            if (this.listAllowed.size() != other.listAllowed.size())
                return false;

            if (this.listDisallowed.size() != other.listDisallowed.size())
                return false;

            for (String address : this.listAddress)
                if (!other.listAddress.contains(address))
                    return false;

            for (String route : this.listRoute)
                if (!other.listRoute.contains(route))
                    return false;

            for (InetAddress dns : this.listDns)
                if (!other.listDns.contains(dns))
                    return false;

            for (String pkg : this.listAllowed)
                if (!other.listAllowed.contains(pkg))
                    return false;

            for (String pkg : this.listDisallowed)
                if (!other.listDisallowed.contains(pkg))
                    return false;

            return true;
        }
    }

    private class IPKey {
        int version;
        int protocol;
        int dport;
        int uid;

        public IPKey(int version, int protocol, int dport, int uid) {
            this.version = version;
            this.protocol = protocol;
            // Only TCP (6) and UDP (17) have port numbers
            this.dport = (protocol == 6 || protocol == 17 ? dport : 0);
            this.uid = uid;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof IPKey))
                return false;
            IPKey other = (IPKey) obj;
            return (this.version == other.version &&
                    this.protocol == other.protocol &&
                    this.dport == other.dport &&
                    this.uid == other.uid);
        }

        @Override
        public int hashCode() {
            return (version << 40) | (protocol << 32) | (dport << 16) | uid;
        }

        @Override
        public String toString() {
            return "v" + version + " p" + protocol + " port=" + dport + " uid=" + uid;
        }
    }

    private class IPRule {
        private IPKey key;
        private String name;
        private boolean block;
        private long time;
        private long ttl;

        public IPRule(IPKey key, String name, boolean block, long time, long ttl) {
            this.key = key;
            this.name = name;
            this.block = block;
            this.time = time;
            this.ttl = ttl;
        }

        public boolean isBlocked() {
            return this.block;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > (this.time + this.ttl * 2);
        }

        public void updateExpires(long time, long ttl) {
            this.time = time;
            this.ttl = ttl;
        }

        @Override
        public boolean equals(Object obj) {
            IPRule other = (IPRule) obj;
            return (this.block == other.block &&
                    this.time == other.time &&
                    this.ttl == other.ttl);
        }

        @Override
        public String toString() {
            return this.key + " " + this.name;
        }
    }

    public static void run(String reason, Context context) {
        Intent intent = new Intent(context, ServiceFirewall.class);
        intent.putExtra(EXTRA_COMMAND, Command.run);
        intent.putExtra(EXTRA_REASON, reason);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable ex) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ex instanceof ForegroundServiceStartNotAllowedException) {
                try {
                    context.startService(intent);
                } catch (Throwable exex) {
                    Log.e(TAG, "Failed to start service with startService", exex);
                }
            } else {
                Log.e(TAG, "Failed to start foreground service", ex);
            }
        }
    }

    public static void start(String reason, Context context) {
        Intent intent = new Intent(context, ServiceFirewall.class);
        intent.putExtra(EXTRA_COMMAND, Command.start);
        intent.putExtra(EXTRA_REASON, reason);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable ex) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ex instanceof ForegroundServiceStartNotAllowedException) {
                try {
                    context.startService(intent);
                } catch (Throwable exex) {
                    Log.e(TAG, "Failed to start service with startService", exex);
                }
            } else {
                Log.e(TAG, "Failed to start foreground service", ex);
            }
        }
    }

    public static void reload(String reason, Context context, boolean interactive) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getBoolean("enabled", false)) {
            Intent intent = new Intent(context, ServiceFirewall.class);
            intent.putExtra(EXTRA_COMMAND, Command.reload);
            intent.putExtra(EXTRA_REASON, reason);
            intent.putExtra(EXTRA_INTERACTIVE, interactive);
            try {
                ContextCompat.startForegroundService(context, intent);
            } catch (Throwable ex) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ex instanceof ForegroundServiceStartNotAllowedException)
                    try {
                        context.startService(intent);
                    } catch (Throwable exex) {
                        Log.e(TAG, exex + "\n" + Log.getStackTraceString(exex));
                    }
            }
        }
    }

    public static void stop(String reason, Context context, boolean vpnonly) {
        Intent intent = new Intent(context, ServiceFirewall.class);
        intent.putExtra(EXTRA_COMMAND, Command.stop);
        intent.putExtra(EXTRA_REASON, reason);
        intent.putExtra(EXTRA_TEMPORARY, vpnonly);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable ex) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ex instanceof ForegroundServiceStartNotAllowedException)
                try {
                    context.startService(intent);
                } catch (Throwable exex) {
                    Log.e(TAG, exex + "\n" + Log.getStackTraceString(exex));
                }
        }
    }

    public static void reloadStats(String reason, Context context) {
        Intent intent = new Intent(context, ServiceFirewall.class);
        intent.putExtra(EXTRA_COMMAND, Command.stats);
        intent.putExtra(EXTRA_REASON, reason);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable ex) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ex instanceof ForegroundServiceStartNotAllowedException)
                try {
                    context.startService(intent);
                } catch (Throwable exex) {
                    Log.e(TAG, exex + "\n" + Log.getStackTraceString(exex));
                }
        }
    }
}