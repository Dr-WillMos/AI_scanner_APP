package com.example.ai_scanner;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

class NetworkInfoHelper {

    private static final String TAG = "NetworkInfoHelper";

    private NetworkInfoHelper() {
    }

    static NetworkSnapshot collect(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean connected = false;
        String networkType = "未知";
        String wifiSsid = null;

        if (cm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNetwork = cm.getActiveNetwork();
                NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                if (caps != null) {
                    connected = true;
                    networkType = describeCapabilities(caps);
                }
            } else {
                NetworkInfo activeInfo = cm.getActiveNetworkInfo();
                if (activeInfo != null && activeInfo.isConnected()) {
                    connected = true;
                    networkType = describeNetworkType(activeInfo.getType(), activeInfo.getSubtypeName());
                }
            }
        }

        if (connected) {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                try {
                    WifiInfo info = wm.getConnectionInfo();
                    String ssid = info.getSSID();
                    if (ssid != null && !ssid.equals("<unknown ssid>") && !ssid.isEmpty()) {
                        wifiSsid = ssid.replace("\"", "");
                    }
                } catch (SecurityException e) {
                    Log.w(TAG, "Cannot access WiFi info", e);
                }
            }
        }

        List<String> ipv4List = new ArrayList<>();
        List<String> ipv6List = new ArrayList<>();
        collectIpAddresses(ipv4List, ipv6List);

        return new NetworkSnapshot(connected, networkType, wifiSsid, ipv4List, ipv6List);
    }

    private static void collectIpAddresses(List<String> ipv4Out, List<String> ipv6Out) {
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (addr.isLoopbackAddress()) {
                        continue;
                    }
                    String host = addr.getHostAddress();
                    if (host == null || host.isEmpty()) {
                        continue;
                    }
                    int scopeIdx = host.indexOf('%');
                    if (scopeIdx >= 0) {
                        host = host.substring(0, scopeIdx);
                    }
                    if (addr instanceof Inet4Address) {
                        ipv4Out.add(host);
                    } else if (addr instanceof Inet6Address) {
                        ipv6Out.add(host);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "collectIpAddresses failed", e);
        }
    }

    private static String describeCapabilities(NetworkCapabilities caps) {
        if (caps == null) {
            return "未知";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "WiFi";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "移动数据";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "以太网";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "VPN";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            return "蓝牙";
        }
        return "其他";
    }

    @SuppressWarnings("deprecation")
    private static String describeNetworkType(int type, String subtype) {
        switch (type) {
            case ConnectivityManager.TYPE_WIFI:
                return "WiFi";
            case ConnectivityManager.TYPE_MOBILE:
                return "移动数据 (" + (subtype != null ? subtype : "") + ")";
            case ConnectivityManager.TYPE_ETHERNET:
                return "以太网";
            case ConnectivityManager.TYPE_VPN:
                return "VPN";
            case ConnectivityManager.TYPE_BLUETOOTH:
                return "蓝牙";
            default:
                return "其他 (" + type + ")";
        }
    }

    static boolean isMobileData(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network active = cm.getActiveNetwork();
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.getType() == ConnectivityManager.TYPE_MOBILE;
        }
    }

    static String describeIps(List<String> ips) {
        if (ips == null || ips.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ips.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(ips.get(i));
        }
        return sb.toString();
    }

    static class NetworkSnapshot {
        final boolean connected;
        final String networkType;
        final String wifiSsid;
        final List<String> ipv4Addresses;
        final List<String> ipv6Addresses;

        NetworkSnapshot(boolean connected,
                        String networkType,
                        String wifiSsid,
                        List<String> ipv4Addresses,
                        List<String> ipv6Addresses) {
            this.connected = connected;
            this.networkType = networkType != null ? networkType : "未知";
            this.wifiSsid = wifiSsid;
            this.ipv4Addresses = ipv4Addresses;
            this.ipv6Addresses = ipv6Addresses;
        }
    }
}
