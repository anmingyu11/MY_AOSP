
package android.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.graphics.drawable.Drawable;
import android.graphics.Rect;


import com.android.internal.policy.PolicyManager;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.Formatter;
import android.text.format.DateFormat;
import java.util.Locale;


/**
 * A view containing controls for a BrowserMediaPlayer. Typically contains the
 * buttons like "Play/Pause", "Rewind", "Fast Forward" and a progress
 * slider. It takes care of synchronizing the controls with the state
 * of the MediaPlayer.
 * <p>
 * The way to use this class is to instantiate it programatically.
 * The BrowserMediaController will create a default set of controls
 * and put them in a window floating above your application. Specifically,
 * the controls will float above the view specified with setAnchorView().
 * The window will disappear if left idle for three seconds and reappear
 * when the user touches the anchor view.
 * <p>
 * Functions like show() and hide() have no effect when BrowserMediaController
 * is created in an xml layout.
 *
 * BrowserMediaController will hide and
 * show the buttons according to these rules:
 * <ul>
 * <li> The "previous" and "next" buttons are hidden until setPrevNextListeners()
 *   has been called
 * <li> The "previous" and "next" buttons are visible but disabled if
 *   setPrevNextListeners() was called with null listeners
 * <li> The "rewind" and "fastforward" buttons are shown unless requested
 *   otherwise by using the BrowserMediaController(Context, boolean) constructor
 *   with the boolean set to false
 * </ul>
 * @hide
 */
public class BrowserMediaController extends FrameLayout {

    private BMediaPlayerControl  mPlayer;
    private Context             mContext;
    private View                mAnchor;
    private View                mRoot;
    private WindowManager       mWindowManager;
    private Window              mWindow;
    private View                mDecor;
    private WindowManager.LayoutParams mDecorLayoutParams;
    private ProgressBar         mProgress;
    private ProgressBar         mProgressLive;
    private ProgressBar         mProgressVolume;
    private TextView            mEndTime, mCurrentTime;
    private View                mLiveTime;
    private boolean             mShowing;
    private boolean             mDragging;
    private static final int    sDefaultTimeout = 3000;
    private static final int    FADE_OUT = 1;
    private static final int    SHOW_PROGRESS = 2;
    private static final int    SHOW_VOLUM = 3;
    private static final int    SHOW_ALL = 4;
    private static final int    VOLUME_CONSTANT = 10;
    private static final int    VOLUME_CONTROL = 2;
    private boolean             mSoundBarIsTouched = false;
    private SoundBroadcast      mSoundBroadcast;
    private boolean             mUseFastForward;
    private boolean             mFromXml;
    private boolean             mListenersSet;
    private View.OnClickListener mNextListener, mPrevListener;
    StringBuilder               mFormatBuilder;
    Formatter                   mFormatter;
    private ImageButton         mPauseButton;
    private ImageButton         mPlayButton;
    private ImageButton         mBigPauseButton;
    private ImageButton         mBigPlayButton;
    private ImageButton         mFfwdButton;
    private ImageButton         mRewButton;
    private ImageButton         mNextButton;
    private ImageButton         mPrevButton;
    private ImageButton         mBigCloseButton;
    private ImageButton         mVolumeLowButton;
    private ImageButton         mVolumeHighButton;
    private ImageButton         mVolumeMediumButton;
    private ImageButton         mVolumeMuteButton;
    private TextView            mFilmTitle;
    private TextView            mCurrentSystemTime;
    private AudioManager        mAudioManager;
    private int                 mMaxVolume;
    private String              mTextFilmTitle = "";
    private boolean             mAttachWindow = false;
    private LinearLayout        mVolumeContainer;

    public BrowserMediaController(Context context, AttributeSet attrs) {
        super(context, attrs);
        mRoot = this;
        mContext = context;
        mUseFastForward = true;
        mFromXml = true;
        initVolume();
   }

    @Override
    public void onFinishInflate() {
        if (mRoot != null)
            initControllerView(mRoot);
    }

    public BrowserMediaController(Context context, boolean useFastForward) {
        super(context);
        mShowing = false;
        mContext = context;
        mUseFastForward = useFastForward;
        initVolume();
        initFloatingWindowLayout();
        initFloatingWindow();
    }

    public BrowserMediaController(Context context) {
        this(context, true);
    }

    private void initFloatingWindow() {
        mWindowManager = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        mWindow = PolicyManager.makeNewWindow(mContext);
        mWindow.setWindowManager(mWindowManager, null, null);
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        mDecor = mWindow.getDecorView();
        mDecor.setOnTouchListener(mTouchListener);
        mWindow.setContentView(this);
        mWindow.setBackgroundDrawableResource(android.R.color.transparent);

        // While the media controller is up, the volume control keys should
        // affect the media stream type
        mWindow.setVolumeControlStream(AudioManager.STREAM_MUSIC);

        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        requestFocus();
    }

    // Allocate and initialize the static parts of mDecorLayoutParams. Must
    // also call updateFloatingWindowLayout() to fill in the dynamic parts
    // (y and width) before mDecorLayoutParams can be used.
    private void initFloatingWindowLayout() {
        mDecorLayoutParams = new WindowManager.LayoutParams();
        WindowManager.LayoutParams p = mDecorLayoutParams;
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.height = LayoutParams.MATCH_PARENT;
        p.x = 0;
        p.format = PixelFormat.TRANSLUCENT;
        p.type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
        p.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
        p.token = null;
        p.windowAnimations = 0; // android.R.style.DropDownAnimationDown;
    }

    // Update the dynamic parts of mDecorLayoutParams
    // Must be called with mAnchor != NULL.
    private void updateFloatingWindowLayout() {
        int width = mAnchor.getWidth();
        int height = 0;

        if (width == 1) {
            width = 0;
        } else {
            width = mWindowManager.getDefaultDisplay().getWidth();
            height = mWindowManager.getDefaultDisplay().getHeight();
        }
        // we need to know the size of the controller so we can properly position it
        // within its space
        mDecor.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));

        WindowManager.LayoutParams p = mDecorLayoutParams;
        p.width = width;
        p.x = 0;
        p.y = 0;
    }

    // This is called whenever mAnchor's layout bound changes
    private OnLayoutChangeListener mLayoutChangeListener =
            new OnLayoutChangeListener() {
        public void onLayoutChange(View v, int left, int top, int right,
                int bottom, int oldLeft, int oldTop, int oldRight,
                int oldBottom) {
            updateFloatingWindowLayout();
            if (mShowing) {
                mWindowManager.updateViewLayout(mDecor, mDecorLayoutParams);
            }
        }
    };

    private OnTouchListener mTouchListener = new OnTouchListener() {
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (mShowing) {
                    hide();
                }
            }
            return false;
        }
    };

    public void setMediaPlayer(BMediaPlayerControl player) {
        mPlayer = player;
        updateUI();
    }

    /**
     * Set the view that acts as the anchor for the control view.
     * This can for example be a VideoView, or your Activity's main view.
     * When VideoView calls this method, it will use the VideoView's parent
     * as the anchor.
     * @param view The view to which to anchor the controller when it is visible.
     */
    public void setAnchorView(View view) {
        if (mAnchor != null) {
            mAnchor.removeOnLayoutChangeListener(mLayoutChangeListener);
        }
        mAnchor = view;
        if (mAnchor != null) {
            mAnchor.addOnLayoutChangeListener(mLayoutChangeListener);
        }

        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );

        removeAllViews();
        View v = makeControllerView();
        addView(v, frameParams);
    }

    /**
     * Create the view that holds the widgets that control playback.
     * Derived classes can override this to create their own.
     * @return The controller view.
     * @hide This doesn't work as advertised
     */
    protected View makeControllerView() {
        LayoutInflater inflate = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mRoot = inflate.inflate(com.android.internal.R.layout.browser_media_controller, null);

        initControllerView(mRoot);

        return mRoot;
    }

    public int getVolume() {
        return mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                * VOLUME_CONSTANT;
    }

    public int getMaxVolume() {
        return mMaxVolume * VOLUME_CONSTANT;
    }

    public void setVolume(int volume) {
        if (getVolume() / VOLUME_CONSTANT != volume / VOLUME_CONSTANT) {
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume
                    / VOLUME_CONSTANT, 0);
        }
    }

    public void initVolume() {
        mAudioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        mMaxVolume = mAudioManager
                .getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (mSoundBroadcast == null) {
            // AudioManager.RINGER_MODE_CHANGED_ACTION
            IntentFilter MUTE_FILTER = new IntentFilter();
            MUTE_FILTER.addAction("android.media.VOLUME_CHANGED_ACTION");
            MUTE_FILTER.addAction("android.media.MASTER_MUTE_CHANGED_ACTION");
            mSoundBroadcast = new SoundBroadcast();
            mContext.registerReceiver(mSoundBroadcast, MUTE_FILTER);
        }
    }

    public void unRegisterResource() {
        unRegisterVolume();
        mWindowManager.removeView(mDecor);
    }

    private void unRegisterVolume() {
        if (mSoundBroadcast != null) {
            mContext.unregisterReceiver(mSoundBroadcast);
            mSoundBroadcast = null;
        }
    }

    private boolean isVolumeControlMode(int controlMode) {
        return controlMode == VOLUME_CONTROL;
    }

    public void onSoundVolumeChanged(int progress) {
        if (!mSoundBarIsTouched && mShowing) {
            int value = mProgressVolume.getProgress() / VOLUME_CONSTANT;

            if (progress / VOLUME_CONSTANT != value) {
                if(mVolumeContainer != null)
                    mVolumeContainer.setVisibility(View.VISIBLE);
                mProgressVolume.setProgress(progress);
            }
            updateVolumeUI();
        }
    }

    private final String getCurrentSystemTime() {
        Context context = getContext();
        Calendar cal = Calendar.getInstance(TimeZone.getDefault());
        cal.setTimeInMillis(System.currentTimeMillis());
        boolean is24 = DateFormat.is24HourFormat(context);

        int nHour = cal.get(Calendar.HOUR);
        int nMinute = cal.get(Calendar.MINUTE);
        if (is24)
            nHour = cal.get(Calendar.HOUR_OF_DAY);
        else if(nHour == 0) {
            nHour = 12;
        }

        String result = nHour + ":";

        if (nMinute < 10)
            result += "0";
        result += nMinute + "";

        if (!is24) {
            result += " ";
            if (Calendar.AM==cal.get(Calendar.AM_PM))
                result += "AM";
            else
                result += "PM";
        }
        return result;
    }

    private void initControllerView(View v) {
        mPauseButton = (ImageButton) v.findViewById(com.android.internal.R.id.pause);
        if (mPauseButton != null) {
            if(mPlayer.isPlaying())
                mPauseButton.setVisibility(View.VISIBLE);
            else
                mPauseButton.setVisibility(View.GONE);
            mPauseButton.requestFocus();
            mPauseButton.setOnClickListener(mPauseListener);
        }
        mPlayButton = (ImageButton) v.findViewById(com.android.internal.R.id.play);
        if (mPlayButton != null) {
            if(mPlayer.isPlaying())
                mPlayButton.setVisibility(View.GONE);
            else
                mPlayButton.setVisibility(View.VISIBLE);
            mPlayButton.setOnClickListener(mPlayListener);
        }

        mBigPauseButton = (ImageButton) v.findViewById(com.android.internal.R.id.bigpause);
        if (mBigPauseButton != null) {
            mBigPauseButton.setVisibility(View.GONE);
            mBigPauseButton.setOnClickListener(mBigPauseListener);
        }

        mBigPlayButton = (ImageButton) v.findViewById(com.android.internal.R.id.bigplay);
        if (mBigPlayButton != null) {
            mBigPlayButton.setOnClickListener(mBigPlayListener);
            if(mPlayer.isPlaying())
                mBigPlayButton.setVisibility(View.GONE);
            else
                mBigPlayButton.setVisibility(View.VISIBLE);
        }

        mBigCloseButton = (ImageButton) v.findViewById(com.android.internal.R.id.bigclose);
        if (mBigCloseButton != null) {
            mBigCloseButton.setOnClickListener(mBigCloseListener);
        }

        mFilmTitle = (TextView) v.findViewById(com.android.internal.R.id.filmtitle);
        if (mFilmTitle != null) {
            mFilmTitle.setText(mTextFilmTitle);
        }

        mCurrentSystemTime = (TextView) v.findViewById(com.android.internal.R.id.bmcontroller_ctime);
        if (mCurrentSystemTime != null) {
            mCurrentSystemTime.setText(getCurrentSystemTime());
        }

        mFfwdButton = (ImageButton) v.findViewById(com.android.internal.R.id.ffwd);
        if (mFfwdButton != null) {
            mFfwdButton.setOnClickListener(mFfwdListener);
            if (!mFromXml) {
                mFfwdButton.setVisibility(mUseFastForward ? View.VISIBLE : View.GONE);
            }
        }

        mRewButton = (ImageButton) v.findViewById(com.android.internal.R.id.rew);
        if (mRewButton != null) {
            mRewButton.setOnClickListener(mRewListener);
            if (!mFromXml) {
                mRewButton.setVisibility(mUseFastForward ? View.VISIBLE : View.GONE);
            }
        }

        // By default these are hidden. They will be enabled when setPrevNextListeners() is called
        mNextButton = (ImageButton) v.findViewById(com.android.internal.R.id.next);
        if (mNextButton != null && !mFromXml && !mListenersSet) {
            mNextButton.setVisibility(View.GONE);
        }
        mPrevButton = (ImageButton) v.findViewById(com.android.internal.R.id.prev);
        if (mPrevButton != null && !mFromXml && !mListenersSet) {
            mPrevButton.setVisibility(View.GONE);
        }

        mVolumeLowButton = (ImageButton) v.findViewById(com.android.internal.R.id.volumelow);
        mVolumeHighButton = (ImageButton) v.findViewById(com.android.internal.R.id.volumehigh);
        mVolumeMediumButton = (ImageButton) v.findViewById(com.android.internal.R.id.volumemedium);
        mVolumeMuteButton = (ImageButton) v.findViewById(com.android.internal.R.id.volumemute);
        mVolumeContainer = (LinearLayout)v.findViewById(com.android.internal.R.id.volumecontainer);
        if(mVolumeContainer != null) {
            mVolumeContainer.setVisibility(View.GONE);
        }
        if (mVolumeMuteButton != null) {
            mVolumeMuteButton.setOnClickListener(mVolumeButtonListener);
        }
        if (mVolumeLowButton != null) {
            mVolumeLowButton.setOnClickListener(mVolumeButtonListener);
        }
        if (mVolumeHighButton != null) {
            mVolumeHighButton.setOnClickListener(mVolumeButtonListener);
        }
        if (mVolumeMediumButton != null) {
            mVolumeMediumButton.setOnClickListener(mVolumeButtonListener);
        }


        mProgress = (ProgressBar) v.findViewById(com.android.internal.R.id.mediacontroller_progress);
        if (mProgress != null) {
            if (mProgress instanceof SeekBar) {
                SeekBar seeker = (SeekBar) mProgress;
                seeker.setOnSeekBarChangeListener(mSeekListener);
            }
            mProgress.setMax(1000);
        }

        mProgressLive = (ProgressBar) v.findViewById(com.android.internal.R.id.mediacontroller_progress_live);

        mProgressVolume = (ProgressBar) v.findViewById(com.android.internal.R.id.volume_progress);
        if (mProgressVolume != null) {
            if (mProgressVolume instanceof SeekBar) {
                SeekBar seeker = (SeekBar) mProgressVolume;
                seeker.setOnSeekBarChangeListener(mSeekVolumeListener);
            }
            mProgressVolume.setMax(getMaxVolume());
            int currentVolume = getVolume();
            mProgressVolume.setProgress(currentVolume);
        }

        mEndTime = (TextView) v.findViewById(com.android.internal.R.id.time);
        mLiveTime = (View) v.findViewById(com.android.internal.R.id.livetime);
        mCurrentTime = (TextView) v.findViewById(com.android.internal.R.id.time_current);
        mFormatBuilder = new StringBuilder();
        mFormatter = new Formatter(mFormatBuilder, Locale.getDefault());

        updateUI();
        installPrevNextListeners();
    }

    /**
     * Show the controller on screen. It will go away
     * automatically after 3 seconds of inactivity.
     */
    public void show() {
        show(sDefaultTimeout);
    }

    public void show(int timeout) {
        Message msg = mHandler.obtainMessage(SHOW_ALL);
        msg.arg1 = timeout;
        mHandler.removeMessages(SHOW_ALL);
        mHandler.sendMessageDelayed(msg, 0);
    }

    /**
     * Disable pause or seek buttons if the stream cannot be paused or seeked.
     * This requires the control interface to be a MediaPlayerControlExt
     */
    private void disableUnsupportedButtons() {
        try {
            if (mPauseButton != null && !mPlayer.canPause()) {
                mPauseButton.setEnabled(false);
            }
            if (mRewButton != null && !mPlayer.canSeekBackward()) {
                mRewButton.setEnabled(false);
            }
            if (mFfwdButton != null && !mPlayer.canSeekForward()) {
                mFfwdButton.setEnabled(false);
            }
        } catch (IncompatibleClassChangeError ex) {
            // We were given an old version of the interface, that doesn't have
            // the canPause/canSeekXYZ methods. This is OK, it just means we
            // assume the media can be paused and seeked, and so we don't disable
            // the buttons.
        }
    }

    /**
     * Show the controller on screen. It will go away
     * automatically after 'timeout' milliseconds of inactivity.
     * @param timeout The timeout in milliseconds. Use 0 to show
     * the controller until hide() is called.
     */
    private void showImpl(int timeout) {
        if (!mShowing && mAnchor != null) {
            setProgress();
            if (mPauseButton != null) {
                mPauseButton.requestFocus();
            }
            disableUnsupportedButtons();
            updateFloatingWindowLayout();
            if (!mAttachWindow) {
                mAttachWindow = true;
                mWindowManager.addView(mDecor, mDecorLayoutParams);
            }
           mDecor.setVisibility(View.VISIBLE);
           mWindowManager.updateViewLayout(mDecor, mDecorLayoutParams);
           mShowing = true;
        }
        updateUI();
        onSoundVolumeChanged(getVolume());
        // cause the progress bar to be updated even if mShowing
        // was already true.  This happens, for example, if we're
        // paused with the progress bar showing the user hits play.
        mHandler.sendEmptyMessage(SHOW_PROGRESS);

        Message msg = mHandler.obtainMessage(FADE_OUT);
        if (timeout != 0) {
            mHandler.removeMessages(FADE_OUT);
            mHandler.sendMessageDelayed(msg, timeout);
        }
    }

    public boolean isShowing() {
        return mShowing;
    }

    /**
     * Remove the controller from the screen.
     */
    public void hide() {
        Message msg = mHandler.obtainMessage(FADE_OUT);
        mHandler.removeMessages(FADE_OUT);
        mHandler.sendMessageDelayed(msg, 0);
    }

    private void hideImpl() {
        if (mAnchor == null)
            return;

        if (mShowing) {
            try {
                mHandler.removeMessages(SHOW_PROGRESS);
                mDecor.setVisibility(View.GONE);
                mVolumeContainer.setVisibility(View.GONE);
            } catch (IllegalArgumentException ex) {
                Log.w("BrowserMediaController", "already removed");
            }

            mShowing = false;
        }
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            int pos;
            switch (msg.what) {
                case FADE_OUT:
                    hideImpl();
                    break;
                case SHOW_PROGRESS:
                    pos = setProgress();
                    if (!mDragging && mShowing && mPlayer.isPlaying()) {
                        msg = obtainMessage(SHOW_PROGRESS);
                        sendMessageDelayed(msg, 1000 - (pos % 1000));
                    }
                    break;
                case SHOW_VOLUM:
                    OnShowVoumeState();
                    break;
                case SHOW_ALL:
                    showImpl(msg.arg1);
                    break;
            }
        }
    };

    private String stringForTime(int timeMs) {
        int totalSeconds = timeMs / 1000;

        int seconds = totalSeconds % 60;
        int minutes = (totalSeconds / 60) % 60;
        int hours   = totalSeconds / 3600;

        mFormatBuilder.setLength(0);
        if (hours > 0) {
            return mFormatter.format("%d:%02d:%02d", hours, minutes, seconds).toString();
        } else {
            return mFormatter.format("%02d:%02d", minutes, seconds).toString();
        }
    }

    private int setProgress() {
        if (mPlayer == null || mDragging) {
            return 0;
        }
        int position = mPlayer.getCurrentPosition();
        int duration = mPlayer.getDuration();
        if (mProgress != null) {
            if (duration > 0) {
                // use long to avoid overflow
                long pos = 1000L * position / duration;
                mProgress.setProgress( (int) pos);
            }
            int percent = mPlayer.getBufferPercentage();
            mProgress.setSecondaryProgress(percent * 10);
        }

        if (mEndTime != null)
            mEndTime.setText(stringForTime(duration));
        if (mCurrentTime != null)
            mCurrentTime.setText(stringForTime(position));

        return position;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP  || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (mShowing) {
                Message msg = mHandler.obtainMessage(FADE_OUT);
                mHandler.removeMessages(FADE_OUT);
                mHandler.sendMessageDelayed(msg, 0);
            } else
                show(sDefaultTimeout);
        }

        return true;
    }

    @Override
    public boolean onTrackballEvent(MotionEvent ev) {
        show(sDefaultTimeout);
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int controlMode = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.BRIGHTNESS_KEY_FUNCTION, 1);
        final boolean uniqueDown = event.getRepeatCount() == 0
                && event.getAction() == KeyEvent.ACTION_DOWN;
        if (keyCode ==  KeyEvent.KEYCODE_HEADSETHOOK
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_SPACE) {
            if (uniqueDown) {
                doPauseResume();
                show(sDefaultTimeout);
                if (mPauseButton != null) {
                    mPauseButton.requestFocus();
                }
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
            if (uniqueDown && !mPlayer.isPlaying()) {
                mPlayer.start();
                updatePausePlay();
                show(sDefaultTimeout);
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (uniqueDown && mPlayer.isPlaying()) {
                mPlayer.pause();
                updatePausePlay();
                show(sDefaultTimeout);
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BRIGHTNESS_UP
                && isVolumeControlMode(controlMode) || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE, 0);
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BRIGHTNESS_DOWN
                && isVolumeControlMode(controlMode) || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER, 0);
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
                || keyCode == KeyEvent.KEYCODE_CAMERA) {
            // don't show the controls for volume adjustment
            return super.dispatchKeyEvent(event);
        } else if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
            if (uniqueDown) {
                hide();
            }
            return true;
        }

        show(sDefaultTimeout);
        return super.dispatchKeyEvent(event);
    }

    private View.OnClickListener mPauseListener = new View.OnClickListener() {
        public void onClick(View v) {
            doPauseResume();
            show(sDefaultTimeout);
        }
    };

    private View.OnClickListener mPlayListener = new View.OnClickListener() {
        public void onClick(View v) {
            doPauseResume();
            show(sDefaultTimeout);
        }
    };

    private View.OnClickListener mBigPauseListener = new View.OnClickListener() {
        public void onClick(View v) {
            doPauseResume();
            show(sDefaultTimeout);
        }
    };

    private View.OnClickListener mBigPlayListener = new View.OnClickListener() {
        public void onClick(View v) {
            doPauseResume();
            show(sDefaultTimeout);
        }
    };

    private View.OnClickListener mBigCloseListener;
    public void setBigCloseListener(View.OnClickListener listener ){
        mBigCloseListener = listener;
    }

    private View.OnClickListener mVolumeButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            show(sDefaultTimeout);
            if(mVolumeContainer.getVisibility() == View.VISIBLE)
                mVolumeContainer.setVisibility(View.GONE);
            else
                mVolumeContainer.setVisibility(View.VISIBLE);
        }
    };

    private void updateUI() {
        updatePausePlay();
        updateProgress();
        updateTimeUI();
        updateVolumeUI();

        if (mCurrentSystemTime != null) {
            mCurrentSystemTime.setText(getCurrentSystemTime());
        }
    }

    private void updateVolumeUI() {
        if (mVolumeLowButton != null || mVolumeHighButton != null
            || mVolumeMediumButton != null || mVolumeMuteButton != null ) {

            int maxVolume =  getMaxVolume();
            int currentVolume = getVolume();

            if (currentVolume <= 0) {
                mVolumeMuteButton.setVisibility(View.VISIBLE);
                mVolumeLowButton.setVisibility(View.GONE);
                mVolumeHighButton.setVisibility(View.GONE);
                mVolumeMediumButton.setVisibility(View.GONE);
            } else if (currentVolume < maxVolume/4) {
                mVolumeMuteButton.setVisibility(View.GONE);
                mVolumeLowButton.setVisibility(View.VISIBLE);
                mVolumeHighButton.setVisibility(View.GONE);
                mVolumeMediumButton.setVisibility(View.GONE);
            } else if (currentVolume < ((maxVolume/4)*3)) {
                mVolumeMuteButton.setVisibility(View.GONE);
                mVolumeLowButton.setVisibility(View.GONE);
                mVolumeHighButton.setVisibility(View.GONE);
                mVolumeMediumButton.setVisibility(View.VISIBLE);
            } else {
                mVolumeMuteButton.setVisibility(View.GONE);
                mVolumeLowButton.setVisibility(View.GONE);
                mVolumeHighButton.setVisibility(View.VISIBLE);
                mVolumeMediumButton.setVisibility(View.GONE);
            }
        }
    }

    private void updateProgress() {
        if (mProgress != null) {
            long duration = mPlayer.getDuration();
            //Drawable drawable = null;
            if (duration > 0) {
                mProgress.setVisibility(View.VISIBLE);
                mProgressLive.setVisibility(View.GONE);
            } else {
                mProgress.setVisibility(View.GONE);
                mProgressLive.setVisibility(View.VISIBLE);
                mProgressLive.setEnabled(false);
            }
        }
    }

    private void updateTimeUI() {
        if (mEndTime == null || mLiveTime == null)
            return;

        long duration = mPlayer.getDuration();
        if (duration > 0) {
            mEndTime.setVisibility(View.VISIBLE);
            mLiveTime.setVisibility(View.GONE);
        } else {
            mEndTime.setVisibility(View.GONE);
            mLiveTime.setVisibility(View.VISIBLE);
        }
    }

    private void updatePausePlay() {
        if (mRoot == null || mPauseButton == null)
            return;

        if (mPlayer.isPlaying()) {
            mPlayButton.setVisibility(View.GONE);
            mPauseButton.setVisibility(View.VISIBLE);
            mBigPlayButton.setVisibility(View.GONE);
            mBigPauseButton.setVisibility(View.GONE);
        } else {
            mPlayButton.setVisibility(View.VISIBLE);
            mPauseButton.setVisibility(View.GONE);
            mBigPlayButton.setVisibility(View.VISIBLE);
            mBigPauseButton.setVisibility(View.GONE);
        }
    }

    private void doPauseResume() {
        if (mPlayer.isPlaying()) {
            mPlayer.pause();
        } else {
            mPlayer.start();
        }
        updatePausePlay();
    }

    private void OnShowVoumeState() {
        if (!mSoundBarIsTouched) {//if changed from entity key
            show();
        }

        onSoundVolumeChanged(getVolume());
    }

    private final class SoundBroadcast extends BroadcastReceiver {
        @Override
        public void onReceive(Context arg0, Intent arg1) {

            Message msg = mHandler.obtainMessage(SHOW_VOLUM);
            mHandler.removeMessages(SHOW_VOLUM);
            mHandler.sendMessageDelayed(msg, 0);
        }
    };

    private OnSeekBarChangeListener mSeekVolumeListener = new OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar arg0, int progress,
                boolean fromUser) {
            if (fromUser) {
                setVolume(progress);
                updateVolumeUI();
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            show(3600000);
            mSoundBarIsTouched = true;
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            mSoundBarIsTouched = false;
            show(sDefaultTimeout);
            setVolume(getVolume());
            updateVolumeUI();
        }
    };

    // There are two scenarios that can trigger the seekbar listener to trigger:
    //
    // The first is the user using the touchpad to adjust the posititon of the
    // seekbar's thumb. In this case onStartTrackingTouch is called followed by
    // a number of onProgressChanged notifications, concluded by onStopTrackingTouch.
    // We're setting the field "mDragging" to true for the duration of the dragging
    // session to avoid jumps in the position in case of ongoing playback.
    //
    // The second scenario involves the user operating the scroll ball, in this
    // case there WON'T BE onStartTrackingTouch/onStopTrackingTouch notifications,
    // we will simply apply the updated position without suspending regular updates.
    private OnSeekBarChangeListener mSeekListener = new OnSeekBarChangeListener() {
        public void onStartTrackingTouch(SeekBar bar) {
            show(3600000);

            mDragging = true;

            // By removing these pending progress messages we make sure
            // that a) we won't update the progress while the user adjusts
            // the seekbar and b) once the user is done dragging the thumb
            // we will post one of these messages to the queue again and
            // this ensures that there will be exactly one message queued up.
            mHandler.removeMessages(SHOW_PROGRESS);
        }

        public void onProgressChanged(SeekBar bar, int progress, boolean fromuser) {
            if (!fromuser) {
                // We're not interested in programmatically generated changes to
                // the progress bar's position.
                return;
            }

            long duration = mPlayer.getDuration();
            long newposition = (duration * progress) / 1000L;
            mPlayer.seekTo( (int) newposition);
            if (mCurrentTime != null)
                mCurrentTime.setText(stringForTime( (int) newposition));
        }

        public void onStopTrackingTouch(SeekBar bar) {
            mDragging = false;
            setProgress();
            updatePausePlay();
            show(sDefaultTimeout);

            // Ensure that progress is properly updated in the future,
            // the call to show() does not guarantee this because it is a
            // no-op if we are already showing.
            mHandler.sendEmptyMessage(SHOW_PROGRESS);
        }
    };

    @Override
    public void setEnabled(boolean enabled) {
        if (mPauseButton != null) {
            mPauseButton.setEnabled(enabled);
        }
        if (mFfwdButton != null) {
            mFfwdButton.setEnabled(enabled);
        }
        if (mRewButton != null) {
            mRewButton.setEnabled(enabled);
        }
        if (mNextButton != null) {
            mNextButton.setEnabled(enabled && mNextListener != null);
        }
        if (mPrevButton != null) {
            mPrevButton.setEnabled(enabled && mPrevListener != null);
        }
        if (mProgress != null) {
            mProgress.setEnabled(enabled);
        }
        disableUnsupportedButtons();
        super.setEnabled(enabled);
    }

    public void setTitle(String filmTitle) {
        mTextFilmTitle = filmTitle;
        if (mFilmTitle != null) {
            mFilmTitle.setText(filmTitle);
        }
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(BrowserMediaController.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(BrowserMediaController.class.getName());
    }

    private View.OnClickListener mRewListener = new View.OnClickListener() {
        public void onClick(View v) {
            int pos = mPlayer.getCurrentPosition();
            pos -= 10000; // milliseconds
            if (pos <= 0)
                pos = 0;
            mPlayer.seekTo(pos);
            setProgress();

            show(sDefaultTimeout);
        }
    };

    private View.OnClickListener mFfwdListener = new View.OnClickListener() {
        public void onClick(View v) {
            int pos = mPlayer.getCurrentPosition();
            pos += 15000; // milliseconds
            int duration = mPlayer.getDuration();
            if (pos >= duration)
                pos = duration;
            mPlayer.seekTo(pos);
            setProgress();

            show(sDefaultTimeout);
        }
    };

    private void installPrevNextListeners() {
        if (mNextButton != null) {
            mNextButton.setOnClickListener(mNextListener);
            mNextButton.setEnabled(mNextListener != null);
        }

        if (mPrevButton != null) {
            mPrevButton.setOnClickListener(mPrevListener);
            mPrevButton.setEnabled(mPrevListener != null);
        }
    }

    public void setPrevNextListeners(View.OnClickListener next, View.OnClickListener prev) {
        mNextListener = next;
        mPrevListener = prev;
        mListenersSet = true;

        if (mRoot != null) {
            installPrevNextListeners();

            if (mNextButton != null && !mFromXml) {
                mNextButton.setVisibility(View.VISIBLE);
            }
            if (mPrevButton != null && !mFromXml) {
                mPrevButton.setVisibility(View.VISIBLE);
            }
        }
    }

    public interface BMediaPlayerControl {
        void    start();
        void    pause();
        int     getDuration();
        int     getCurrentPosition();
        void    seekTo(int pos);
        boolean isPlaying();
        int     getBufferPercentage();
        boolean canPause();
        boolean canSeekBackward();
        boolean canSeekForward();

        /**
         * Get the audio session id for the player used by this VideoView. This can be used to
         * apply audio effects to the audio track of a video.
         * @return The audio session, or 0 if there was an error.
         */
        int     getAudioSessionId();
    }
}
