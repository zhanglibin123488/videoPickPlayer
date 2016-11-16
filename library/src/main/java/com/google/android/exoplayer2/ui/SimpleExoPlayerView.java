/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.ui;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.R;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextRenderer;
import java.util.List;

/**
 * Displays a video stream.
 */
@TargetApi(16)
public class SimpleExoPlayerView extends FrameLayout {

  private final View surfaceView;
  private final View shutterView;
  private final SubtitleView subtitleLayout;
  private final AspectRatioFrameLayout layout;
  public final PlaybackControlView controller;
  private final ComponentListener componentListener;

  private SimpleExoPlayer player;
  private boolean useController = true;
  private int controllerShowTimeoutMs;

  public SimpleExoPlayerView(Context context) {
    this(context, null);
  }

  public SimpleExoPlayerView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public SimpleExoPlayerView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    boolean useTextureView = false;
    int resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
    int rewindMs = PlaybackControlView.DEFAULT_REWIND_MS;
    int fastForwardMs = PlaybackControlView.DEFAULT_FAST_FORWARD_MS;
    int controllerShowTimeoutMs = PlaybackControlView.DEFAULT_SHOW_TIMEOUT_MS;
    if (attrs != null) {
      TypedArray a = context.getTheme().obtainStyledAttributes(attrs,
          R.styleable.SimpleExoPlayerView, 0, 0);
      try {
        useController = a.getBoolean(R.styleable.SimpleExoPlayerView_use_controller, useController);
        useTextureView = a.getBoolean(R.styleable.SimpleExoPlayerView_use_texture_view,
            useTextureView);
        resizeMode = a.getInt(R.styleable.SimpleExoPlayerView_resize_mode,
            AspectRatioFrameLayout.RESIZE_MODE_FIT);
        rewindMs = a.getInt(R.styleable.SimpleExoPlayerView_rewind_increment, rewindMs);
        fastForwardMs = a.getInt(R.styleable.SimpleExoPlayerView_fastforward_increment,
            fastForwardMs);
        controllerShowTimeoutMs = a.getInt(R.styleable.SimpleExoPlayerView_show_timeout,
            controllerShowTimeoutMs);
      } finally {
        a.recycle();
      }
    }

    LayoutInflater.from(context).inflate(R.layout.exo_simple_player_view, this);
    componentListener = new ComponentListener();
    layout = (AspectRatioFrameLayout) findViewById(R.id.video_frame);
    layout.setResizeMode(resizeMode);
    shutterView = findViewById(R.id.shutter);
    subtitleLayout = (SubtitleView) findViewById(R.id.subtitles);
    subtitleLayout.setUserDefaultStyle();
    subtitleLayout.setUserDefaultTextSize();

    controller = (PlaybackControlView) findViewById(R.id.control);
    controller.hide();
    controller.setRewindIncrementMs(rewindMs);
    controller.setFastForwardIncrementMs(fastForwardMs);
    this.controllerShowTimeoutMs = controllerShowTimeoutMs;

    View view = useTextureView ? new TextureView(context) : new SurfaceView(context);
    ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT);
    view.setLayoutParams(params);
    surfaceView = view;
    layout.addView(surfaceView, 0);
  }

  /**
   * Returns the player currently set on this view, or null if no player is set.
   */
  public SimpleExoPlayer getPlayer() {
    return player;
  }

  /**
   * Set the {@link SimpleExoPlayer} to use. The {@link SimpleExoPlayer#setTextOutput} and
   * {@link SimpleExoPlayer#setVideoListener} method of the player will be called and previous
   * assignments are overridden.
   *
   * @param player The {@link SimpleExoPlayer} to use.
   */
  public void setPlayer(SimpleExoPlayer player) {
    if (this.player == player) {
      return;
    }
    if (this.player != null) {
      this.player.setTextOutput(null);
      this.player.setVideoListener(null);
      this.player.removeListener(componentListener);
      this.player.setVideoSurface(null);
    }
    this.player = player;
    if (useController) {
      controller.setPlayer(player);
    }
    if (player != null) {
      if (surfaceView instanceof TextureView) {
        player.setVideoTextureView((TextureView) surfaceView);
      } else if (surfaceView instanceof SurfaceView) {
        player.setVideoSurfaceView((SurfaceView) surfaceView);
      }
      player.setVideoListener(componentListener);
      player.addListener(componentListener);
      player.setTextOutput(componentListener);
      maybeShowController(false);
    } else {
      shutterView.setVisibility(VISIBLE);
      controller.hide();
    }
  }

  /**
   * Sets the resize mode which can be of value {@link AspectRatioFrameLayout#RESIZE_MODE_FIT},
   * {@link AspectRatioFrameLayout#RESIZE_MODE_FIXED_HEIGHT} or
   * {@link AspectRatioFrameLayout#RESIZE_MODE_FIXED_WIDTH}.
   *
   * @param resizeMode The resize mode.
   */
  public void setResizeMode(int resizeMode) {
    layout.setResizeMode(resizeMode);
  }

  /**
   * Returns whether the playback controls are enabled.
   */
  public boolean getUseController() {
    return useController;
  }

  /**
   * Sets whether playback controls are enabled. If set to {@code false} the playback controls are
   * never visible and are disconnected from the player.
   *
   * @param useController Whether playback controls should be enabled.
   */
  public void setUseController(boolean useController) {
    if (this.useController == useController) {
      return;
    }
    this.useController = useController;
    if (useController) {
      controller.setPlayer(player);
    } else {
      controller.hide();
      controller.setPlayer(null);
    }
  }

  /**
   * Returns the playback controls timeout. The playback controls are automatically hidden after
   * this duration of time has elapsed without user input and with playback or buffering in
   * progress.
   *
   * @return The timeout in milliseconds. A non-positive value will cause the controller to remain
   *     visible indefinitely.
   */
  public int getControllerShowTimeoutMs() {
    return controllerShowTimeoutMs;
  }

  /**
   * Sets the playback controls timeout. The playback controls are automatically hidden after this
   * duration of time has elapsed without user input and with playback or buffering in progress.
   *
   * @param controllerShowTimeoutMs The timeout in milliseconds. A non-positive value will cause
   *     the controller to remain visible indefinitely.
   */
  public void setControllerShowTimeoutMs(int controllerShowTimeoutMs) {
    this.controllerShowTimeoutMs = controllerShowTimeoutMs;
  }

  /**
   * Set the {@link PlaybackControlView.VisibilityListener}.
   *
   * @param listener The listener to be notified about visibility changes.
   */
  public void setControllerVisibilityListener(PlaybackControlView.VisibilityListener listener) {
    controller.setVisibilityListener(listener);
  }

  /**
   * Sets the rewind increment in milliseconds.
   *
   * @param rewindMs The rewind increment in milliseconds.
   */
  public void setRewindIncrementMs(int rewindMs) {
    controller.setRewindIncrementMs(rewindMs);
  }

  /**
   * Sets the fast forward increment in milliseconds.
   *
   * @param fastForwardMs The fast forward increment in milliseconds.
   */
  public void setFastForwardIncrementMs(int fastForwardMs) {
    controller.setFastForwardIncrementMs(fastForwardMs);
  }

  /**
   * Get the view onto which video is rendered. This is either a {@link SurfaceView} (default)
   * or a {@link TextureView} if the {@code use_texture_view} view attribute has been set to true.
   *
   * @return either a {@link SurfaceView} or a {@link TextureView}.
   */
  public View getVideoSurfaceView() {
    return surfaceView;
  }

  @Override
  public boolean onTouchEvent(MotionEvent ev) {
    if (!useController || player == null || ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
      return false;
    }
    if (controller.isVisible()) {
      controller.hide();
    } else {
      maybeShowController(true);
    }
    return true;
  }

  @Override
  public boolean onTrackballEvent(MotionEvent ev) {
    if (!useController || player == null) {
      return false;
    }
    maybeShowController(true);
    return true;
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    return useController ? controller.dispatchKeyEvent(event) : super.dispatchKeyEvent(event);
  }

  private void maybeShowController(boolean isForced) {
    if (!useController || player == null) {
      return;
    }
    int playbackState = player.getPlaybackState();
    boolean showIndefinitely = playbackState == ExoPlayer.STATE_IDLE
        || playbackState == ExoPlayer.STATE_ENDED || !player.getPlayWhenReady();
    boolean wasShowingIndefinitely = controller.isVisible() && controller.getShowTimeoutMs() <= 0;
    controller.setShowTimeoutMs(showIndefinitely ? 0 : controllerShowTimeoutMs);
    if (isForced || showIndefinitely || wasShowingIndefinitely) {
      controller.show();
    }
  }

  private final class ComponentListener implements SimpleExoPlayer.VideoListener,
      TextRenderer.Output, ExoPlayer.EventListener {

    // TextRenderer.Output implementation

    @Override
    public void onCues(List<Cue> cues) {
      subtitleLayout.onCues(cues);
    }

    // SimpleExoPlayer.VideoListener implementation

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
        float pixelWidthHeightRatio) {
      layout.setAspectRatio(height == 0 ? 1 : (width * pixelWidthHeightRatio) / height);
    }

    @Override
    public void onRenderedFirstFrame() {
      shutterView.setVisibility(GONE);
    }

    @Override
    public void onVideoTracksDisabled() {
      shutterView.setVisibility(VISIBLE);
    }

    // ExoPlayer.EventListener implementation

    @Override
    public void onLoadingChanged(boolean isLoading) {
      // Do nothing.
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
      maybeShowController(false);
    }

    @Override
    public void onPlayerError(ExoPlaybackException e) {
      // Do nothing.
    }

    @Override
    public void onPositionDiscontinuity() {
      // Do nothing.
    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest) {
      // Do nothing.
    }
  }

  public void setFullScreenListener(PlaybackControlView.FullScreenListener fullScreenListener){
    if (controller != null){
      controller.setFullScreenListener(fullScreenListener);
    }
  }

}
