package com.example.systemoptimizer;

import android.app.Activity;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.MediaController;
import android.widget.VideoView;
import android.widget.Toast;

/**
 * مشغل فيديو بملء الشاشة. يشغّل ملفاً من مكتبة الفيديو داخل res/raw
 * (video_<n>.mp4) عبر android.resource://. يغلّق نفسه عند الاكتمال أو الخطأ.
 */
public class VideoPlayerActivity extends Activity {

    public static final String EXTRA_RAW_RES_ID = "raw_res_id";
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

        int resId = getIntent().getIntExtra(EXTRA_RAW_RES_ID, 0);
        if (resId == 0) {
            Toast.makeText(this, "لا يوجد فيديو محدد", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Uri uri = Uri.parse("android.resource://" + getPackageName() + "/" + resId);
        MediaController controller = new MediaController(this);
        videoView.setMediaController(controller);
        controller.setAnchorView(videoView);
        videoView.setVideoURI(uri);

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

    @Override
    protected void onDestroy() {
        if (videoView != null) {
            videoView.stopPlayback();
        }
        super.onDestroy();
    }
}