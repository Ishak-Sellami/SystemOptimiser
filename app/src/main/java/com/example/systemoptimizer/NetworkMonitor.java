package com.example.systemoptimizer;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Zero-polling connectivity watcher built on ConnectivityManager callbacks.
 *
 * Android already knows when the active network appears / changes / disconnects;
 * we simply observe it and nudge the connection manager. No periodic polling is
 * involved. Registered on the main looper but the callback itself only toggles
 * an atomic flag and interrupts the blocking socket, so it is cheap.
 */
public final class NetworkMonitor {

    private static final String TAG = "NetworkMonitor";
    private static final long CALLBACK_TIMEOUT_MS = 3000;

    public interface Listener {
        /** Called when the active network may have changed (may be a network change). */
        void onNetworkChanged();
    }

    private final ConnectivityManager cm;
    private final Listener listener;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    private volatile String lastInterface;
    private volatile String lastTransport = "unknown";

    public NetworkMonitor(Context context, Listener listener) {
        this.cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
    }

    public void register() {
        if (cm == null || registered.getAndSet(true)) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(networkCallback);
            } else {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                cm.registerNetworkCallback(request, networkCallback);
            }
            Log.i(TAG, "network callback registered");
            refreshCurrent();
        } catch (Exception e) {
            Log.w(TAG, "network callback registration failed", e);
            registered.set(false);
        }
    }

    public void unregister() {
        if (!registered.getAndSet(false)) {
            return;
        }
        try {
            cm.unregisterNetworkCallback(networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "network callback unregister failed", e);
        }
    }

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    refreshCurrent();
                    notifyChange();
                }

                @Override
                public void onLost(Network network) {
                    refreshCurrent();
                    notifyChange();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    notifyChange();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    refreshCurrent();
                    notifyChange();
                }
            };

    private void notifyChange() {
        try {
            if (lastInterface != null && lastInterface.endsWith("10")) {
                // ignore LTE/5G iface noise on some vendors
            }
        } catch (Exception ignored) {
        }
        // A short async notify: run on a dedicated thread so we never block the
        // ConnectivityManager dispatcher for CALLBACK_TIMEOUT_MS.
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(CALLBACK_TIMEOUT_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                listener.onNetworkChanged();
            } catch (Exception e) {
                Log.w(TAG, "listener threw during network change", e);
            }
        }, "net-change");
        t.setDaemon(true);
        t.start();
    }

    private void refreshCurrent() {
        try {
            Network active = cm.getActiveNetwork();
            if (active == null) {
                lastInterface = null;
                lastTransport = "none";
                return;
            }
            LinkProperties lp = cm.getLinkProperties(active);
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            lastInterface = lp == null ? null : lp.getInterfaceName();
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    lastTransport = "wifi";
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    lastTransport = "cellular";
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    lastTransport = "ethernet";
                } else {
                    lastTransport = "other";
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "refreshCurrent failed", e);
        }
    }

    public String currentInterface() {
        return lastInterface;
    }

    public String currentTransport() {
        return lastTransport;
    }
}