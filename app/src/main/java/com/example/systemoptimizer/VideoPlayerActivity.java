package com.example.systemoptimizer;

import android.app.Activity;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * مشغل فيديو بملء الشاشة من مكتبة الفيديو داخل assets/video
 * (video_<n>.mp4). يُنسخ الملف إلى الذاكرة المؤقتة أولاً لأن
 * VideoView لا يستطيع فتح file:///android_asset/ على الأنظمة الحديثة.
 * يغلق نفسه عند الاكتمال أو الخطأ ويحرر موارد المشغل.
 */
public class VideoPlayerActivity extends Activity {

    private static final String TAG = "SystemOptimizerVideo";
    public static final String EXTRA_VIDEO_NAME = "video_name";

    private VideoView videoView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_video_player);
        videoView = findViewById(R.id.video_view);

        String videoName = getIntent().getStringExtra(EXTRA_VIDEO_NAME);
        if (videoName == null || videoName.trim().isEmpty()) {
            Toast.makeText(this, "لا يوجد فيديو محدد", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        File dest = prepareVideoFile(videoName);
        if (dest == null) {
            Toast.makeText(this, "تعذر تجهيز الفيديو", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        MediaController controller = new MediaController(this);
        videoView.setMediaController(controller);
        controller.setAnchorView(videoView);
        videoView.setVideoPath(dest.getAbsolutePath());

        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Toast.makeText(VideoPlayerActivity.this,
                        "خطأ في تشغيل الفيديو", Toast.LENGTH_LONG).show();
                finish();
                return true;
            }
        });

        videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                finish();
            }
        });

        videoView.start();
    }

    private File prepareVideoFile(String videoName) {
        File dir = new File(getCacheDir(), "playback");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File dest = new File(dir, videoName);
        try {
            InputStream in = getAssets().open("video/" + videoName);
            FileOutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            in.close();
            return dest;
        } catch (IOException e) {
            Log.w(TAG, "prepareVideoFile failed: " + videoName, e);
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        if (videoView != null) {
            videoView.stopPlayback();
        }
        super.onDestroy();
    }
}