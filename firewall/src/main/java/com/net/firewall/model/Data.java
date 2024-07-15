package com.net.firewall.model;

import android.os.Build;

public class Data {

    private static final int REQUEST_VPN = 1;
    private static final int REQUEST_INVITE = 2;
    public static final int REQUEST_ROAMING = 3;
    private static final int REQUEST_NOTIFICATIONS = 4;

    private static final int MIN_SDK = Build.VERSION_CODES.LOLLIPOP_MR1;

    public static final String ACTION_RULES_CHANGED = "ACTION_RULES_CHANGED";
    public static final String ACTION_QUEUE_CHANGED = "ACTION_QUEUE_CHANGED";
    public static final String EXTRA_REFRESH = "Refresh";
    public static final String EXTRA_SEARCH = "Search";
    public static final String EXTRA_RELATED = "Related";
    public static final String EXTRA_APPROVE = "Approve";
    public static final String EXTRA_CONNECTED = "Connected";
    public static final String EXTRA_METERED = "Metered";
    public static final String EXTRA_SIZE = "Size";

}
