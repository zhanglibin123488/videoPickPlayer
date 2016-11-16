package com.zlb.video.play;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageButton;

import com.facebook.drawee.view.SimpleDraweeView;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveVideoTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.ui.SimpleExoPlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.util.Util;
import com.zlb.android.video.R;


/**
 * Created by ZhangLIbin on 2016/11/15.
 */

public class CustomExoPlayerView extends SimpleExoPlayerView implements ExoPlayer.EventListener{
    private SimpleExoPlayer player;
    private Handler mainHandler;
    private MappingTrackSelector trackSelector;
    private long playerPosition;
    private DataSource.Factory mediaDataSourceFactory;
    private static final DefaultBandwidthMeter BANDWIDTH_METER = new DefaultBandwidthMeter();
    private Uri uri;
    private boolean isAutoPlay;
    private boolean canFullScreen = false;
    private SimpleDraweeView thumbImage;
    private ImageButton prePlayerButton;
    public CustomExoPlayerView(Context context) {
        super(context);
        init();
    }

    public CustomExoPlayerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CustomExoPlayerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    private void init(){
        thumbImage = (SimpleDraweeView) findViewById(R.id.iv_thumb);
        prePlayerButton = (ImageButton) findViewById(R.id.btn_play2);
        prePlayerButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                initializePlayer();
            }
        });
    }

    public boolean isCanFullScreen() {
        return canFullScreen;
    }

    public void setCanFullScreen(boolean canFullScreen) {
        this.canFullScreen = canFullScreen;
        controller.setFullScreenVisible(canFullScreen);
    }

    public void setThumbImage(String url, int w, int h){
//        CommonTools.setFrescoImageOfPx(thumbImage, url, w, h);
    }

    public void setUrl(String url, boolean isAutoPlay){
        Uri uri;
        if (url.startsWith("http")){
            uri = Uri.parse(url);
        }else {
            uri = Uri.parse("file://"+url);
        }
        setUri(uri, isAutoPlay);
    }

    public void setUri(Uri uri, boolean isAutoPlay){
        mainHandler = new Handler();
        mediaDataSourceFactory = buildDataSourceFactory(true);
        this.uri = uri;
        this.isAutoPlay = isAutoPlay;
    }
    public void initializePlayer(){
        if (uri == null){
            throw new NullPointerException("uri为空");
        }
        loadVideo();
//        if (!NetworkUtils.isNetworkAvailable(getContext())){
//            Toast.makeText(getContext(), R.string.network_is_not_available, Toast.LENGTH_LONG).show();
//            return;
//        }
//        if (NetworkUtil.isWifi(getContext())){
//            loadVideo();
//        }else {
//            MaterialDialogTools.showMaterialDialog(getContext(), "您当前不是wifi环境，继续使用将消耗你的数据流量", new MaterialDialogTools.OnSelectclickListener() {
//                @Override
//                public void onSure() {
//                    isAutoPlay = true;
//                    loadVideo();
//                }
//
//                @Override
//                public void onCancel() {
//
//                }
//            });
//        }
    }

    public void loadVideo(){
//        CommonTools.showErrorLog("test"+"11111111111");
//        CommonTools.showErrorLog("test"+playerPosition);
        if (player == null){
            TrackSelection.Factory videoTrackSelectionFactory = new AdaptiveVideoTrackSelection.Factory(BANDWIDTH_METER);
            trackSelector = new DefaultTrackSelector(mainHandler, videoTrackSelectionFactory);
            player = ExoPlayerFactory.newSimpleInstance(getContext(), trackSelector, new DefaultLoadControl(), null, false);
            player.addListener(this);
            setPlayer(player);
//            CommonTools.showErrorLog("test"+"222222222");
            if (playerPosition > 0){
//                CommonTools.showErrorLog("test"+"3333333333");
                player.seekTo(playerPosition);
            }
            player.setPlayWhenReady(isAutoPlay);
        }
        if (uri == null){
//            CommonTools.showToast(getContext(), "请选择一个视频来播放");
            return;
        }
        MediaSource mediaSource = buildMediaSource(uri, null);
        player.prepare(mediaSource);
    }

    private MediaSource buildMediaSource(Uri uri, String overrideExtension) {
        int type = Util.inferContentType(!TextUtils.isEmpty(overrideExtension) ? "." + overrideExtension
                : uri.getLastPathSegment());
        switch (type) {
            case C.TYPE_SS:
                return new SsMediaSource(uri, buildDataSourceFactory(false),
                        new DefaultSsChunkSource.Factory(mediaDataSourceFactory), mainHandler, null);
            case C.TYPE_DASH:
                return new DashMediaSource(uri, buildDataSourceFactory(false),
                        new DefaultDashChunkSource.Factory(mediaDataSourceFactory), mainHandler, null);
            case C.TYPE_HLS:
                return new HlsMediaSource(uri, mediaDataSourceFactory, mainHandler, null);
            case C.TYPE_OTHER:
                return new ExtractorMediaSource(uri, mediaDataSourceFactory, new DefaultExtractorsFactory(),
                        mainHandler, null);
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }

    public void setPosition(long playerPosition){
//        CommonTools.showErrorLog("test-setPosition "+playerPosition);
        this.playerPosition = playerPosition;
    }

    public void releasePlayer() {
        if (player != null) {
            playerPosition = player.getCurrentPosition();
            player.release();
            player = null;
            trackSelector = null;
        }
    }

    private DataSource.Factory buildDataSourceFactory(boolean useBandwidthMeter) {
        return ExoApplication.single.buildDataSourceFactory(useBandwidthMeter ? BANDWIDTH_METER : null);
    }

    public long getPosition(){
        if (player != null){
            return player.getCurrentPosition();
        }
        return 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
//        CommonTools.showErrorLog(getClass(), "释放视频资源");
        releasePlayer();
    }

    @Override
    public void onLoadingChanged(boolean isLoading) {
        if (isLoading){
            thumbImage.setVisibility(GONE);
            prePlayerButton.setVisibility(GONE);
        }
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {

    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest) {

    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {

    }

    @Override
    public void onPositionDiscontinuity() {

    }

    public String getVideoFormatString(){
        if (player != null){
            Format videoFormat = player.getVideoFormat();
            return videoFormat.sampleMimeType;
        }
        return "";
    }
    public boolean isSupportFormat(){
        String format= getVideoFormatString();
        if (format == null || format.equals("")){
            return false;
        }
        return true;
    }
}
