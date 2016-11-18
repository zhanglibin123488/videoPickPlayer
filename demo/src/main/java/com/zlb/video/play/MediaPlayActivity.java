package com.zlb.video.play;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.zlb.android.video.R;


/**
 * Created by ZhangLIbin on 2016/11/10.
 */

public class MediaPlayActivity extends Activity{
    private static final String TAG = "MediaPlayActivity";
    private CustomExoPlayerView simpleExoPlayerView;
    private Uri uri;
    private long position;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uri = getIntent().getData();
        Log.i(TAG, "uri======"+uri.toString());
//        uri = Uri.parse("file:///android_asset/test.mp4");
        setContentView(R.layout.activity_mediaplay);
        simpleExoPlayerView = (CustomExoPlayerView) findViewById(R.id.player_view);
        simpleExoPlayerView.setUri(uri, true);
//        String picUrl = getIntent().getStringExtra("picUrl");
//        if (!CommonTools.isNull(picUrl)){
//            simpleExoPlayerView.setThumbImage(picUrl, ScreenUtil.screenWidth, ScreenUtil.screenWidth * 2/3);
//        }
        simpleExoPlayerView.setCanFullScreen(true);
        if (savedInstanceState != null){
            position = savedInstanceState.getLong("position");
            simpleExoPlayerView.setPosition(position);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putLong("position", position);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onResume() {
        super.onResume();
        simpleExoPlayerView.initializePlayer();
    }

    @Override
    public void onPause() {
        super.onPause();
        position = simpleExoPlayerView.getPosition();
        simpleExoPlayerView.releasePlayer();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE){
            simpleExoPlayerView.controller.fullScreen.performClick();
            return;
        }
        super.onBackPressed();
    }
}
