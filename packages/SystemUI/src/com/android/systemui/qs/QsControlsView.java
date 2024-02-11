/*
 * Copyright (C) 2024 the risingOS Android Project
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
package com.android.systemui.qs;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.PorterDuff;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.RemoteControlClient;
import android.media.RemoteController;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.hardware.camera2.CameraManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.DeviceIdleManager;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.graphics.ColorUtils;

import com.android.settingslib.Utils;

import com.android.systemui.R;
import com.android.systemui.animation.view.LaunchableImageView;
import com.android.systemui.media.dialog.MediaOutputDialogFactory;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qs.VerticalSlider;
import com.android.systemui.statusbar.NotificationMediaManager;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import java.util.ArrayList;
import java.util.List;

import android.os.SystemClock;
import android.view.KeyEvent;

public class QsControlsView extends FrameLayout {

    private final static String PERSONALIZATIONS_ACTIVITY = "com.android.settings.Settings$personalizationSettingsLayoutActivity";

    private List<View> mControlTiles = new ArrayList<>();
    private List<View> mMediaPlayerViews = new ArrayList<>();
    private List<View> mWidgetViews = new ArrayList<>();
    private List<Runnable> metadataCheckRunnables = new ArrayList<>();

    private View mSettingsButton, mVoiceAssist, mRunningServiceButton, mInterfaceButton, mMediaCard, mAccessBg, mWidgetsBg;
    private View mClockTimer, mCalculator, mCamera, mPagerLayout, mMediaLayout, mAccessLayout, mWidgetsLayout;
    private ImageView mTorch;
    
    private QsControlsPageIndicator mAccessPageIndicator, mMediaPageIndicator, mWidgetsPageIndicator;
    private VerticalSlider mBrightnessSlider, mVolumeSlider;

    private ActivityStarter mActivityStarter;
    private FalsingManager mFalsingManager;
    private MediaOutputDialogFactory mMediaOutputDialogFactory;
    private NotificationMediaManager mNotifManager;

    private ViewPager mViewPager;
    private PagerAdapter pagerAdapter;

    private CameraManager cameraManager;
    private String cameraId;
    private boolean isFlashOn = false;

    private int mAccentColor, mBgColor, mTintColor, mContainerColor;
    
    private Context mContext;
    
    private boolean mMediaActive = false;
    private TextView mMediaTitle, mMediaArtist;
    private ImageView mMediaPrevBtn, mMediaPlayBtn, mMediaNextBtn, mMediaAlbumArtBg, mPlayerIcon;
    private AudioManager mAudioManager;
    private RemoteController mRemoteController;
    
    private MediaMetadata mMediaMetadata = null;
    private MediaController mMediaController = null;
    private MediaSession.Token mMediaToken = null;
    private boolean mInflated = false;
    private Bitmap mAlbumArt = null;
    
    private PackageManager mPackageManager;
    
    private boolean isClearingMetadata = false;

    public QsControlsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        cameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mRemoteController = new RemoteController(mContext, mRCClientUpdateListener);
        mAudioManager.registerRemoteController(mRemoteController);
        mPackageManager = mContext.getPackageManager();
        mPagerLayout = LayoutInflater.from(mContext).inflate(R.layout.qs_controls_tile_pager, null);
        try {
            cameraId = cameraManager.getCameraIdList()[0];
        } catch (Exception e) {}
    }

    public void injectDependencies(
            ActivityStarter activityStarter, 
            FalsingManager falsingManager, 
            NotificationMediaManager notificationMediaManager,
            MediaOutputDialogFactory mediaOutputDialogFactory) {
        mActivityStarter = activityStarter;
        mFalsingManager = falsingManager;
        mNotifManager = notificationMediaManager;
        mMediaOutputDialogFactory = mediaOutputDialogFactory;
    }

    MediaController.Callback mMediaListener = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            super.onPlaybackStateChanged(state);
            if (state != null) {
                setupMediaController();
                mMediaActive = mNotifManager != null 
                    && PlaybackState.STATE_PLAYING 
                    == mNotifManager.getMediaControllerPlaybackState(mMediaController) 
                    && mMediaMetadata != null;
                if (!mMediaActive) {
                    clearMediaMetadata();
                } else {
                    updateMediaPlaybackState();
                }
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            setupMediaController();
            mMediaMetadata = metadata;
            mMediaActive = mNotifManager != null 
                && PlaybackState.STATE_PLAYING 
                == mNotifManager.getMediaControllerPlaybackState(mMediaController) 
                && mMediaMetadata != null;
            if (!mMediaActive) {
                clearMediaMetadata();
            } else {
                updateMediaPlaybackState();
            }
        }
    };

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mInflated = true;
		mViewPager = findViewById(R.id.qs_controls_pager);
        mBrightnessSlider = findViewById(R.id.qs_controls_brightness_slider);
        mVolumeSlider = findViewById(R.id.qs_controls_volume_slider);
        mMediaLayout = mPagerLayout.findViewById(R.id.qs_controls_media);
        mAccessLayout = mPagerLayout.findViewById(R.id.qs_controls_tile_access);
        mWidgetsLayout = mPagerLayout.findViewById(R.id.qs_controls_tile_widgets);
        mVoiceAssist = mAccessLayout.findViewById(R.id.qs_voice_assist);
        mSettingsButton = mAccessLayout.findViewById(R.id.settings_button);
        mRunningServiceButton = mAccessLayout.findViewById(R.id.running_services_button);
        mInterfaceButton = mAccessLayout.findViewById(R.id.interface_button);
        mTorch = mWidgetsLayout.findViewById(R.id.qs_flashlight);
        mClockTimer = mWidgetsLayout.findViewById(R.id.qs_clock_timer);
        mCalculator = mWidgetsLayout.findViewById(R.id.qs_calculator);
        mCamera = mWidgetsLayout.findViewById(R.id.qs_camera);
        mMediaAlbumArtBg = mMediaLayout.findViewById(R.id.media_art_bg);
        mMediaAlbumArtBg.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mMediaTitle = mMediaLayout.findViewById(R.id.media_title);
        mMediaArtist = mMediaLayout.findViewById(R.id.artist_name);
        mMediaPrevBtn = mMediaLayout.findViewById(R.id.previous_button);
        mMediaPlayBtn = mMediaLayout.findViewById(R.id.play_button);
        mMediaNextBtn = mMediaLayout.findViewById(R.id.next_button);
        mPlayerIcon = mMediaLayout.findViewById(R.id.player_icon);
        mMediaCard = mMediaLayout.findViewById(R.id.media_cardview);
        mAccessBg = mAccessLayout.findViewById(R.id.qs_controls_access_layout);
        mWidgetsBg = mWidgetsLayout.findViewById(R.id.qs_controls_widgets_layout);
        mAccessPageIndicator = mAccessLayout.findViewById(R.id.access_page_indicator);
        mMediaPageIndicator = mMediaLayout.findViewById(R.id.media_page_indicator);
        mWidgetsPageIndicator = mWidgetsLayout.findViewById(R.id.widgets_page_indicator);
        collectViews(mControlTiles, mVoiceAssist, mSettingsButton, mRunningServiceButton, 
            mInterfaceButton, (View) mTorch, mClockTimer, mCalculator, mCamera);
        collectViews(mMediaPlayerViews, mMediaPrevBtn, mMediaPlayBtn, mMediaNextBtn, 
                mMediaAlbumArtBg, mPlayerIcon, mMediaTitle, mMediaArtist);
        collectViews(mWidgetViews, mMediaLayout, mAccessLayout, mWidgetsLayout);
        setupViewPager();
	}

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mInflated) {
            return;
        }
        setClickListeners();
        updateResources();
    }

    private void setClickListeners() {
        mTorch.setOnClickListener(view -> toggleFlashlight());
        mClockTimer.setOnClickListener(view -> launchTimer());
        mCalculator.setOnClickListener(view -> launchCalculator());
        mVoiceAssist.setOnClickListener(view -> launchVoiceAssistant());
        mCamera.setOnClickListener(view -> launchCamera());
        mSettingsButton.setOnClickListener(mSettingsOnClickListener);
        mRunningServiceButton.setOnClickListener(mSettingsOnClickListener);
        mInterfaceButton.setOnClickListener(mSettingsOnClickListener);
        mMediaPlayBtn.setOnClickListener(view -> performMediaAction(MediaAction.TOGGLE_PLAYBACK));
        mMediaPrevBtn.setOnClickListener(view -> performMediaAction(MediaAction.PLAY_PREVIOUS));
        mMediaNextBtn.setOnClickListener(view -> performMediaAction(MediaAction.PLAY_NEXT));
        mMediaAlbumArtBg.setOnClickListener(view -> launchMusicPlayerApp());
        ((LaunchableImageView) mMediaAlbumArtBg).setOnLongClickListener(view -> {
            showMediaOutputDialog();
            return true;
        });
    }

    private void clearMediaMetadata() {
        if (isClearingMetadata) return;
        isClearingMetadata = true;
        mMediaMetadata = null;
        mAlbumArt = null;
        updateMediaMetadata();  
        isClearingMetadata = false;
    }

    private void setupMediaController() {
        if (mNotifManager == null) return;
        MediaSession.Token newMediaToken = mNotifManager.getMediaToken();
        if (newMediaToken == null) return;
        if (mMediaToken == null || !mMediaToken.equals(newMediaToken)) {
            mMediaToken = newMediaToken;
        }
        MediaController newMediaController = new MediaController(mContext, mMediaToken);
        if (newMediaController == null) return;
        if (mMediaController == null || !mNotifManager.sameSessions(mMediaController, newMediaController)) {
            mMediaController = newMediaController;
            mMediaController.registerCallback(mMediaListener);
        }
        MediaMetadata newMediaMetadata = mMediaController.getMetadata();
        if (newMediaMetadata == null) return;
        if (mMediaMetadata == null || !newMediaMetadata.equals(mMediaMetadata)) {
            mMediaMetadata = newMediaMetadata;
        }
    }

    public void updateMediaPlaybackState() {
        updateMediaMetadata();
        scheduleMetadataChecks(3, mMediaActive);
    }

    private void scheduleMetadataChecks(int count, boolean active) {
        if (active) {
            if (count <= 0) return;
            postDelayed(() -> {
                updateMediaMetadata();
                scheduleMetadataChecks(count - 1, mMediaActive);
            }, 1000);
        } else {
            for (Runnable runnable : metadataCheckRunnables) {
                removeCallbacks(runnable);
            }
            metadataCheckRunnables.clear();
        }
    }

    private void updateMediaMetadata() {
        Bitmap albumArt = mMediaMetadata == null ? null : mMediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (albumArt != null) {
            new ProcessArtworkTask().execute(albumArt);
        } else {
            mMediaAlbumArtBg.setImageBitmap(null);
        }
        updateMediaViews();
    }

    private void updateMediaViews() {
        if (mMediaPlayBtn != null) {
            mMediaPlayBtn.setImageResource(mMediaActive && mMediaMetadata != null ? R.drawable.ic_media_pause : R.drawable.ic_media_play);
        }
        CharSequence title = mMediaMetadata == null ? null : mMediaMetadata.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence artist = mMediaMetadata == null ? null : mMediaMetadata.getText(MediaMetadata.METADATA_KEY_ARTIST);
        mMediaTitle.setText(title != null ? title : mContext.getString(R.string.no_media_playing));
        mMediaArtist.setText(artist != null ? artist : "");
        mPlayerIcon.setImageIcon(mNotifManager == null ? null : mNotifManager.getMediaIcon());
        final int mediaItemColor = getMediaItemColor();
        for (View view : mMediaPlayerViews) {
            if (view instanceof TextView) {
                ((TextView) view).setTextColor(mediaItemColor);
            } else if (view instanceof ImageView) {
                ((ImageView) view).setImageTintList(ColorStateList.valueOf(mediaItemColor));
            }
        }
    }

    private class ProcessArtworkTask extends AsyncTask<Bitmap, Void, Bitmap> {
        protected Bitmap doInBackground(Bitmap... bitmaps) {
            Bitmap bitmap = bitmaps[0];
            int width = mMediaAlbumArtBg.getWidth();
            int height = mMediaAlbumArtBg.getHeight();
            return getScaledRoundedBitmap(bitmap, width, height);
        }
        protected void onPostExecute(Bitmap result) {
            if (result == null) return;
            if (mAlbumArt == null || mAlbumArt != result) {
                mAlbumArt = result;
                final int mediaFadeLevel = mContext.getResources().getInteger(R.integer.media_player_fade);
                final int fadeFilter = ColorUtils.blendARGB(Color.TRANSPARENT, mNotifManager == null ? Color.BLACK : mNotifManager.getMediaBgColor(), mediaFadeLevel / 100f);
                mMediaAlbumArtBg.setColorFilter(fadeFilter, PorterDuff.Mode.SRC_ATOP);
                mMediaAlbumArtBg.setImageBitmap(mAlbumArt);
            }
        }
    }

    private Bitmap getScaledRoundedBitmap(Bitmap bitmap, int width, int height) {
        if (bitmap == null || width <= 0 || height <= 0) {
            return null;
        }
        float radius = mContext.getResources().getDimensionPixelSize(R.dimen.qs_controls_slider_corner_radius);
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
        if (scaledBitmap == null) {
            return null;
        }
        Bitmap output = Bitmap.createBitmap(scaledBitmap.getWidth(), scaledBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setShader(new BitmapShader(scaledBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        RectF rect = new RectF(0, 0, scaledBitmap.getWidth(), scaledBitmap.getHeight());
        canvas.drawRoundRect(rect, radius, radius, paint);
        return output;
    }

    private void setupViewPager() {
        if (mViewPager == null) return;
        pagerAdapter = new PagerAdapter() {
            @Override
            public int getCount() {
                return mWidgetViews.size();
            }
            @Override
            public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
                return view == object;
            }
            @NonNull
            @Override
            public Object instantiateItem(@NonNull ViewGroup container, int position) {
                View view = mWidgetViews.get(position);
                container.addView(view);
                return view;
            }
            @Override
            public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
                container.removeView((View) object);
            }
        };
        mViewPager.setAdapter(pagerAdapter);
        mAccessPageIndicator.setupWithViewPager(mViewPager);
        mMediaPageIndicator.setupWithViewPager(mViewPager);
        mWidgetsPageIndicator.setupWithViewPager(mViewPager);
        mViewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {}
            @Override
            public void onPageSelected(int position) {
                updatePages(position);
            }
            @Override
            public void onPageScrollStateChanged(int state) {}
        });
    }
    
    public ViewPager getViewPager() {
        return mViewPager;
    }
    
    public void updatePages(int position) {
        if (position == 0) {
            mMediaLayout.setVisibility(View.VISIBLE);
            mAccessLayout.setVisibility(View.GONE);
            mWidgetsLayout.setVisibility(View.GONE);
        } else if (position == 1) {
            mMediaLayout.setVisibility(View.GONE);
            mAccessLayout.setVisibility(View.VISIBLE);
            mWidgetsLayout.setVisibility(View.GONE);
        } else if (position == 2) {
            mMediaLayout.setVisibility(View.GONE);
            mAccessLayout.setVisibility(View.GONE);
            mWidgetsLayout.setVisibility(View.VISIBLE);
        }
    }

    public void updateColors() {
        boolean isNightMode = (mContext.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        mAccentColor = mContext.getColor(isNightMode ? R.color.qs_controls_active_color_dark : R.color.lockscreen_widget_active_color_light);
        mBgColor = mContext.getColor(isNightMode ? R.color.qs_controls_bg_color_dark : R.color.qs_controls_bg_color_light);
        mTintColor = mContext.getColor(isNightMode ? R.color.qs_controls_bg_color_light : R.color.qs_controls_bg_color_dark);
        mContainerColor = mContext.getColor(isNightMode ? R.color.qs_controls_container_bg_color_dark : R.color.qs_controls_container_bg_color_light);
        updateTiles();
        if (mAccessBg != null && mMediaCard != null && mWidgetsBg != null) {
            mMediaCard.getBackground().setTint(mContainerColor);
            mAccessBg.setBackgroundTintList(ColorStateList.valueOf(mContainerColor));
            mWidgetsBg.setBackgroundTintList(ColorStateList.valueOf(mContainerColor));
        }
        if (mAccessPageIndicator != null && mMediaPageIndicator != null && mWidgetsPageIndicator != null) {
            mAccessPageIndicator.updateColors(isNightMode);
            mMediaPageIndicator.updateColors(isNightMode);
            mWidgetsPageIndicator.updateColors(isNightMode);
        }
        if (!mMediaActive) {
            clearMediaMetadata();
        } else {
            updateMediaPlaybackState();
        }
    }
    
    private void updateTiles() {
        for (View view : mControlTiles) {
            if (view instanceof ImageView) {
                ImageView tile = (ImageView) view;
                int backgroundResource;
                int imageTintColor;
                int backgroundTintColor;
                if (tile == mTorch) {
                    backgroundResource = isFlashOn ? R.drawable.qs_controls_tile_background_active : R.drawable.qs_controls_tile_background;
                    imageTintColor = isFlashOn ? mBgColor : mTintColor;
                    backgroundTintColor = isFlashOn ? mAccentColor : mBgColor;
                } else if (tile == mInterfaceButton || tile == mCamera) {
                    backgroundResource = R.drawable.qs_controls_tile_background_active;
                    imageTintColor = mBgColor;
                    backgroundTintColor = mAccentColor;
                } else {
                    backgroundResource = R.drawable.qs_controls_tile_background;
                    imageTintColor = mTintColor;
                    backgroundTintColor = mBgColor;
                }
                tile.setBackgroundResource(backgroundResource);
                tile.setImageTintList(ColorStateList.valueOf(imageTintColor));
                tile.setBackgroundTintList(ColorStateList.valueOf(backgroundTintColor));
            }
        }
    }
    
    private int getMediaItemColor() {
        return mMediaMetadata == null ? mTintColor : Color.WHITE;
    }

    private final View.OnClickListener mSettingsOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
	        if (mFalsingManager != null && mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
		        return;
	        }
	        if (v == mSettingsButton) {
		        startSettingsActivity();
	        } else if (v == mRunningServiceButton) {
		        launchSettingsComponent("com.android.settings.Settings$DevRunningServicesActivity");
	        } else if (v == mInterfaceButton) {
		        launchSettingsComponent(PERSONALIZATIONS_ACTIVITY);
	        }
        }
    };
    
    public void updateSliderProgress() {
        if (mBrightnessSlider != null && mVolumeSlider != null) {
            ((BrightnessSlider) mBrightnessSlider).updateProgressRect();
            ((VolumeSlider) mVolumeSlider).updateProgressRect();
        }
    }

    public void updateResources() {
        if (mBrightnessSlider != null && mVolumeSlider != null) {
            mBrightnessSlider.updateSliderPaint();
            mVolumeSlider.updateSliderPaint();
        }
        updateColors();
    }

    private void collectViews(List<View> viewList, View... views) {
        for (View view : views) {
            if (!viewList.contains(view)) {
                viewList.add(view);
            }
        }
    }

    private void startSettingsActivity() {
        if (mActivityStarter == null) return;
		mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS), true /* dismissShade */);
    }
    
    private void launchSettingsComponent(String className) {
        if (mActivityStarter == null) return;
        Intent intent = className.equals(PERSONALIZATIONS_ACTIVITY) ? new Intent(Intent.ACTION_MAIN) : new Intent();
        intent.setComponent(new ComponentName("com.android.settings", className));
        mActivityStarter.startActivity(intent, true);
    }
    
    private void launchAppIfAvailable(Intent launchIntent, @StringRes int appTypeResId) {
        if (mActivityStarter == null) return;
        final List<ResolveInfo> apps = mPackageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY);
        if (!apps.isEmpty() && mActivityStarter != null) {
            mActivityStarter.startActivity(launchIntent, true);
        } else {
            showNoDefaultAppFoundToast(appTypeResId);
        }
    }

    private void launchVoiceAssistant() {
        DeviceIdleManager dim = mContext.getSystemService(DeviceIdleManager.class);
        if (dim != null) {
            dim.endIdle("voice-search");
        }
        Intent voiceIntent = new Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE);
        voiceIntent.putExtra(RecognizerIntent.EXTRA_SECURE, true);
        mActivityStarter.startActivity(voiceIntent, true);
    }

    private void launchCamera() {
        final Intent launchIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
        launchAppIfAvailable(launchIntent, R.string.camera);
    }

    private void launchTimer() {
        final Intent launchIntent = new Intent(AlarmClock.ACTION_SET_TIMER);
        launchAppIfAvailable(launchIntent, R.string.clock_timer);
    }

    private void launchCalculator() {
        final Intent launchIntent = new Intent();
        launchIntent.setAction(Intent.ACTION_MAIN);
        launchIntent.addCategory(Intent.CATEGORY_APP_CALCULATOR);
        launchAppIfAvailable(launchIntent, R.string.calculator);
    }

    private void toggleFlashlight() {
        if (mActivityStarter == null) return;
        try {
            cameraManager.setTorchMode(cameraId, !isFlashOn);
            isFlashOn = !isFlashOn;
            int tintColor = isFlashOn ? mBgColor : mTintColor;
            int bgColor = isFlashOn ? mAccentColor : mBgColor;
            mTorch.setBackgroundResource(isFlashOn ?  R.drawable.qs_controls_tile_background_active :  R.drawable.qs_controls_tile_background);
            mTorch.setImageTintList(ColorStateList.valueOf(tintColor));
            mTorch.setBackgroundTintList(ColorStateList.valueOf(bgColor));
        } catch (Exception e) {}
    }
    
    private void showNoDefaultAppFoundToast(@StringRes int appTypeResId) {
        final String appType = mContext.getString(appTypeResId);
        final String message = mContext.getString(R.string.no_default_app_found, appType);
        Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    private void performMediaAction(MediaAction action) {
        setupMediaController();
        boolean skipUpdate = false;
        if (mMediaController != null) {
            switch (action) {
                case TOGGLE_PLAYBACK:
                    if (mMediaActive) {
                        mMediaController.getTransportControls().pause();
                    } else {
                        mMediaController.getTransportControls().play();
                        skipUpdate = true;
                        clearMediaMetadata();
                    }
                    break;
                case PLAY_PREVIOUS:
                    mMediaController.getTransportControls().skipToPrevious();
                    break;
                case PLAY_NEXT:
                    mMediaController.getTransportControls().skipToNext();
                    break;
            }
        }
        if (!skipUpdate) {
            updateMediaPlaybackState();
        }
    }

    private String getInstalledMusicApp() {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_APP_MUSIC);
        final  List<ResolveInfo> musicApps = mPackageManager.queryIntentActivities(intent, 0);
        ResolveInfo musicApp = musicApps.isEmpty() ? null : musicApps.get(0);
        return musicApp != null ? musicApp.activityInfo.packageName : "";
    }

    private void showMediaOutputDialog() {
        String packageName = getInstalledMusicApp();
        if (!packageName.isEmpty()) {
            mMediaOutputDialogFactory.create(packageName, true, (LaunchableImageView) mMediaAlbumArtBg);
        }
    }

    private void launchMusicPlayerApp() {
        String packageName = getInstalledMusicApp();
        if (!packageName.isEmpty()) {
            Intent launchIntent = mPackageManager.getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                mActivityStarter.startActivity(launchIntent, true);
            }
        }
    }

   // Since rcc OnClientUpdateListener playback updates were unreliable, 
   // use this API to let us know that a new session is created so
   // we can retrieve media controller dependencies from notification manager
   private RemoteController.OnClientUpdateListener mRCClientUpdateListener =
            new RemoteController.OnClientUpdateListener() {
        @Override
        public void onClientChange(boolean clearing) {
            setupMediaController();
        }
        @Override
        public void onClientPlaybackStateUpdate(int state, long stateChangeTimeMs,
                long currentPosMs, float speed) {
            setupMediaController();
        }
        @Override
        public void onClientPlaybackStateUpdate(int state) {
            setupMediaController();
        }
        @Override
        public void onClientMetadataUpdate(RemoteController.MetadataEditor data) {
            setupMediaController();
        }
        @Override
        public void onClientTransportControlUpdate(int transportControlFlags) {
        }
    };
    
    private enum MediaAction {
        TOGGLE_PLAYBACK,
        PLAY_PREVIOUS,
        PLAY_NEXT
    }
}
