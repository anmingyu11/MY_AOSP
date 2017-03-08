
package android.view;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import android.animation.LayoutTransition;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.content.DialogInterface.OnShowListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.AudioService;
import android.media.RingtoneManager;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.CountDownTimer.OnCountDownTimerUpdateListener;
import android.view.WindowManager.LayoutParams;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.SmartisanItemSwitch;
import android.widget.SmartisanSwitchEx;
import android.widget.TextView;
import android.media.VolumeController;
import com.android.internal.R;
import android.os.SystemProperties;

import com.android.internal.telephony.ITelephony;

/**
 * Handle the volume up and down keys.
 *
 * This code really should be moved elsewhere.
 *
 * Seriously, it really really should be moved elsewhere.  This is used by
 * android.media.AudioService, which actually runs in the system process, to
 * show the volume dialog when the user changes the volume.  What a mess.
 *
 * @hide
 */
public class SmartisanVolumePanel extends Handler implements OnSeekBarChangeListener,
        OnCheckedChangeListener, VolumeController
{
    private static final String TAG = "SmartisanVolumePanel";
    private static boolean LOGD = (SystemProperties.getInt("ro.debuggable", 0) == 1);

    /**
     * The delay before playing a sound. This small period exists so the user
     * can press another key (non-volume keys, too) to have it NOT be audible.
     * <p>
     * PhoneWindow will implement this part.
     */
    public static final int PLAY_SOUND_DELAY = 150;

    /**
     * The delay before vibrating. This small period exists so if the user is
     * moving to silent mode, it will not emit a short vibrate (it normally
     * would since vibrate is between normal mode and silent mode using hardware
     * keys).
     */
    public  static final int VIBRATE_DELAY = 300;

    private static final int VIBRATE_DURATION = 300;
    private static final int BEEP_DURATION = 150;
    private static final int TIMEOUT_DELAY = 3000;
    private static final int VOLUME_PROGRESS_ZERO=0;

    private static final int MSG_VOLUME_CHANGED = 0;
    private static final int MSG_PLAY_SOUND = 1;
    private static final int MSG_STOP_SOUNDS = 2;
    private static final int MSG_VIBRATE = 3;
    private static final int MSG_TIMEOUT = 4;
    private static final int MSG_RINGER_MODE_CHANGED = 5;
    private static final int MSG_MUTE_CHANGED = 6;
    private static final int MSG_REMOTE_VOLUME_CHANGED = 7;
    private static final int MSG_REMOTE_VOLUME_UPDATE_IF_SHOWN = 8;
    private static final int MSG_SLIDER_VISIBILITY_CHANGED = 9;
    private static final int MSG_DISPLAY_SAFE_VOLUME_WARNING = 10;
    private static final int MSG_UPDATE_COUNTDOWN_TIMER = 11;
    private static final int MSG_UPDATE_MUTEPANEL_VISIBILITY = 12;
    private static final int MSG_SET_STREAMSLIDER_VISIBILITY = 13;
    private static final int MSG_CHECK_DIALOG_STATUS = 14;

    private static final int    ADJUST_BRIGHTNESS=-500;

    // Pseudo stream type for master volume
    private static final int STREAM_MASTER = -100;
    // Pseudo stream type for remote volume is defined in AudioService.STREAM_REMOTE_MUSIC
    public static final int VOLUME_OVERLAY_SINGLE = 0;
    public static final int VOLUME_OVERLAY_EXPANDABLE = 1;
    public static final int VOLUME_OVERLAY_EXPANDED = 2;
    public static final int VOLUME_OVERLAY_NONE = 3;

    protected Context mContext;
    private AudioManager mAudioManager;
    private IPowerManager mPowerManager;
    private PowerManager pm;
    protected AudioService mAudioService;
    private boolean mRingIsSilent;
    private int mCurrentOverlayStyle = -1;

    private ITelephony mTelephony;

    private boolean mIsVolumePanelFullSrc = false;

    private View mViewFullSrc;
    private View mPanelFullSrc;
    private ViewGroup mSliderGroupFullSrc;
    private ImageView mSilentImageFullSrc;

    private boolean mForceReorderSliders = false;

    /** Dialog containing all the sliders */
    private  Dialog mDialog;
    /** Dialog's content view */
    private  View mView;

    /** The visible portion of the volume overlay */
    private  ViewGroup mPanel;
    /** The mute panel*/
    private ViewGroup mutePanel;
    /** Contains the sliders and their touchable icons */
    private  ViewGroup mSliderGroup;

    private View mutePanelShadow;

    /** Currently active stream that shows up at the top of the list of sliders */
    private int mActiveStreamType = -1;
    /** All the slider controls mapped by stream type */
    private HashMap<Integer,StreamControl> mStreamControls;

    // Devices for which the volume is fixed and VolumePanel slider should be disabled
    final int mFixedVolumeDevices = AudioSystem.DEVICE_OUT_AUX_DIGITAL |
            AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET |
            AudioSystem.DEVICE_OUT_ALL_USB |
            AudioSystem.DEVICE_OUT_PROXY; // use fixed volume on proxy device(WiFi display)

    private boolean mIsUserTouching = false;
    private static final int SEEK_BAR_RANGE = 1000;
    private int mScreenBrightnessMinimum;
    private int mScreenBrightnessMaximum;
    private SmartisanItemSwitch mSmartisanSwitch;
    private CountDownTimer mCountDownTimer;
    private SmartisanSwitchEx mSwitch;
    private Handler mHandler=new Handler();
    private IWindowManager mWinManager;

    private enum StreamResources {
        BluetoothSCOStream(AudioManager.STREAM_BLUETOOTH_SCO,
                R.string.volume_icon_description_bluetooth,
                R.drawable.ring_vol_min,
                R.drawable.ring_vol_max,
                false),
        RingerStream(AudioManager.STREAM_RING,
                R.string.volume_icon_description_ringer,
                R.drawable.smartisan_vol_min,
                R.drawable.smartisan_vol_max,
                true),
        VoiceStream(AudioManager.STREAM_VOICE_CALL,
                R.string.volume_icon_description_incall,
                R.drawable.ring_vol_min,
                R.drawable.ring_vol_max,
                false),
        AlarmStream(AudioManager.STREAM_ALARM,
                R.string.volume_alarm,
                R.drawable.smartisan_vol_min,
                R.drawable.smartisan_vol_max,
                true),
        MediaStream(AudioManager.STREAM_MUSIC,
                R.string.volume_icon_description_media,
                R.drawable.music_vol_min,
                R.drawable.music_vol_max,
                true),
        NotificationStream(AudioManager.STREAM_NOTIFICATION,
                R.string.volume_icon_description_notification,
                R.drawable.smartisan_vol_min,
                R.drawable.smartisan_vol_max,
                true),
        // for now, use media resources for master volume
        MasterStream(STREAM_MASTER,
                R.string.volume_icon_description_media, //FIXME should have its own description
                R.drawable.smartisan_vol_min,
                R.drawable.smartisan_vol_max,
                false),
        RemoteStream(AudioService.STREAM_REMOTE_MUSIC,
                R.string.volume_icon_description_media, //FIXME should have its own description
                R.drawable.smartisan_vol_min,
                R.drawable.smartisan_vol_max,
                false),// will be dynamically updated
        AdjustBrightness(ADJUST_BRIGHTNESS,
                R.string.volume_icon_description_notification,
                R.drawable.smartisan_brightness_min,
                R.drawable.smartisan_brightness_max,
                true);
        int streamType;
        int descRes;
        int iconRes;
        int secondIconRes;
        // RING, VOICE_CALL & BLUETOOTH_SCO are hidden unless explicitly requested
        boolean show;

        StreamResources(int streamType, int descRes,int iconRes, int secondIconRes, boolean show) {
            this.streamType = streamType;
            this.descRes = descRes;
            this.iconRes = iconRes;
            this.secondIconRes=secondIconRes;
            this.show = show;
        }
    };

    // List of stream types and their order
    private static final StreamResources[] STREAMS = {
        StreamResources.BluetoothSCOStream,
        StreamResources.RingerStream,
        StreamResources.VoiceStream,
        StreamResources.MediaStream,
        StreamResources.NotificationStream,
        StreamResources.AlarmStream,
        StreamResources.MasterStream,
        StreamResources.RemoteStream,
        StreamResources.AdjustBrightness
    };

    /** Object that contains data for each slider */
    private class StreamControl {
        int streamType;
        ViewGroup group;
        ImageView icon;
        ImageView secondIcon;
        SeekBar seekbarView;
        int iconRes;
        int secondIconRes;

        ViewGroup groupFull;
        ImageView iconFull;
        ImageView secondIconFull;
        SeekBar seekbarViewFull;
    }

    private Vibrator mVibrator;
    private SoundPool mAdjustVolumeSounds;
    private int mAdjustVolumeSoundId;
    private int mAdjustVolumeStreamId;

    private static AlertDialog sConfirmSafeVolumeDialog;
    private static Object sConfirmSafeVolumeLock = new Object();

    private static class WarningDialogReceiver extends BroadcastReceiver
            implements DialogInterface.OnDismissListener {
        private final Context mContext;
        private final Dialog mDialog;
        private final SmartisanVolumePanel mVolumePanel;

        WarningDialogReceiver(Context context, Dialog dialog, SmartisanVolumePanel volumePanel) {
            mContext = context;
            mDialog = dialog;
            mVolumePanel = volumePanel;
            IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            context.registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            mDialog.cancel();
            cleanUp();
        }

        public void onDismiss(DialogInterface unused) {
            mContext.unregisterReceiver(this);
            cleanUp();
        }

        private void cleanUp() {
            synchronized (sConfirmSafeVolumeLock) {
                sConfirmSafeVolumeDialog = null;
            }
            mVolumePanel.forceTimeout();
            mVolumePanel.updateStates();
        }
    }

    public SmartisanVolumePanel(final Context context, AudioService volumeService) {
        mContext = context;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mAudioService = volumeService;
        mWinManager = IWindowManager.Stub.asInterface(ServiceManager.getService(Context.WINDOW_SERVICE));

        /*
         * for adjust brightness
         */
        mPowerManager = IPowerManager.Stub.asInterface(ServiceManager.getService("power"));
        pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mScreenBrightnessMinimum = pm.getMinimumScreenBrightnessSetting();
        mScreenBrightnessMaximum = pm.getMaximumScreenBrightnessSetting();
        // For now, only show master volume if master volume is supported
        boolean useMasterVolume = context.getResources().getBoolean(
                com.android.internal.R.bool.config_useMasterVolume);
        if (useMasterVolume) {
            for (int i = 0; i < STREAMS.length; i++) {
                StreamResources streamRes = STREAMS[i];
                streamRes.show = (streamRes.streamType == STREAM_MASTER);
            }
        }

        /*
         * inital volume panel
         */
        mDialog = new Dialog(mContext, R.style.Theme_Panel_Volume_Smartisanos);
        initialVolumePanelView(mDialog);
        setVolumePanelWindowLayoutParams(mDialog);

        mVibrator = (Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);

        /*
         * Initial sound effect of volume panel
         */
        mAdjustVolumeSounds = new SoundPool(1, AudioManager.STREAM_SYSTEM, 0);
        String soundPath = Settings.Global.getString(mContext.getContentResolver(),
                Settings.System.ADJUST_VOLUME_SOUND);
        if (soundPath != null) {
            mAdjustVolumeSoundId = mAdjustVolumeSounds.load(soundPath, 1);
        }
    }

    private void initialVolumePanelView(Dialog volumePanelDialog){
        LayoutInflater inflater = (LayoutInflater) mContext
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mView = inflater.inflate(R.layout.smartisan_volume_adjust, null);
        mView.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                forceTimeout();
                return false;
            }
        });
        mPanel = (ViewGroup) mView.findViewById(R.id.volume_panel);
        mPanel.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                resetTimeout();
                return true;
            }
        });

        mutePanel = (ViewGroup) mView.findViewById(R.id.mute_panel);
        mutePanelShadow = mPanel.findViewById(R.id.padding);
        mSliderGroup = (ViewGroup) mView.findViewById(R.id.slider_group);

        mSmartisanSwitch = (SmartisanItemSwitch) mView.findViewById(R.id.smartisan_switch);
        mSmartisanSwitch.setOnCheckedChangeListener(this);
        mSwitch = (SmartisanSwitchEx) mSmartisanSwitch.findViewById(R.id.smartisan_item_switch);
        mSwitch.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                resetTimeout();
                //Make sure that the SmartisanSwitchEx can handle the event
                return false;
            }
        });
        mSmartisanSwitch.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                resetTimeout();
                return true;
            }
        });

        mCountDownTimer = (CountDownTimer) mView.findViewById(R.id.count_down_timer);
        mCountDownTimer.setOnUpdateListener(new MuteTimerListener());

        //views for full srceen.
        mViewFullSrc = inflater.inflate(R.layout.smartisan_volume_adjust_mini, null);
        mViewFullSrc.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                forceTimeout();
                return false;
            }
        });

        mPanelFullSrc = (ViewGroup) mViewFullSrc.findViewById(R.id.smartisan_mini_vol_panel);
        mPanelFullSrc.setOnTouchListener(new View.OnTouchListener () {
            public boolean onTouch(View v, MotionEvent event) {
                resetTimeout();
                return true;
            }
        });

        mSliderGroupFullSrc = (ViewGroup) mViewFullSrc.findViewById(R.id.slider_group);
        mSilentImageFullSrc = (ImageView) mViewFullSrc.findViewById(R.id.smartisan_mini_vol_silent_icon);
        mSilentImageFullSrc.setClickable(true);
        mSilentImageFullSrc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetTimeout();
                if (Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.VOLUME_PANEL_MUTE_ENABLE, 0) == 1) {
                    Settings.System.putInt(mContext.getContentResolver(),
                        Settings.System.VOLUME_PANEL_MUTE_ENABLE, 0);
                    mSilentImageFullSrc.setImageResource(R.drawable.smartisan_mini_silent_off);
                } else {
                    Settings.System.putInt(mContext.getContentResolver(),
                        Settings.System.VOLUME_PANEL_MUTE_ENABLE, 1);
                    mSilentImageFullSrc.setImageResource(R.drawable.smartisan_mini_silent_on);
                }
                setSeekBarFocusByType(mActiveStreamType);
                refreshMuteTimer();
            }
        });

        volumePanelDialog.setTitle("Volume control");
        volumePanelDialog.setContentView(mView);
        volumePanelDialog.setOnShowListener(mVolumeDialogShowListener);
        volumePanelDialog.setOnDismissListener(mVolumeDialogDismissListener);
        volumePanelDialog.setCanceledOnTouchOutside(true);
    }

    private void setVolumePanelWindowLayoutParams(Dialog volumePanelDialog){
        Window window = volumePanelDialog.getWindow();
        LayoutParams lp = window.getAttributes();
        lp.token = null;
        // Offset from the top
        lp.verticalMargin=(float)-0.05;
        lp.type = LayoutParams.TYPE_VOLUME_OVERLAY;
        lp.width = LayoutParams.MATCH_PARENT;
        lp.height = LayoutParams.MATCH_PARENT;
        lp.dimAmount=0f;
        lp.privateFlags |= LayoutParams.PRIVATE_FLAG_FORCE_SHOW_NAV_BAR;
        window.addFlags(LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                                | LayoutParams.FLAG_KEEP_SCREEN_ON);

        window.setAttributes(lp);
    }

    ContentObserver mContentObserver = new ContentObserver(this) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (!mIsUserTouching) {
                if (mStreamControls != null) {
                    StreamControl sc = mStreamControls.get(ADJUST_BRIGHTNESS);
                    sc.seekbarView.setProgress(getProgressByBrightness());
                    sc.seekbarViewFull.setProgress(getProgressByBrightness());
                }
            }
        }
    };


    OnShowListener mVolumeDialogShowListener = new OnShowListener() {

        @Override
        public void onShow(DialogInterface dialog) {
            if (Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.VOLUME_PANEL_MUTE_ENABLE, 0) == 1) {
                mSmartisanSwitch.setOnCheckedChangeListener(null);
                mSmartisanSwitch.setChecked(false);
                mSmartisanSwitch
                        .setOnCheckedChangeListener(SmartisanVolumePanel.this);
                mSmartisanSwitch.setChecked(true);
            } else {
                mSmartisanSwitch.setOnCheckedChangeListener(null);
                mSmartisanSwitch.setChecked(true);
                mSmartisanSwitch
                        .setOnCheckedChangeListener(SmartisanVolumePanel.this);
                mSmartisanSwitch.setChecked(false);
            }

            mContext.getContentResolver()
                    .registerContentObserver(
                            Settings.System
                                    .getUriFor(Settings.System.REALTIME_SCREEN_BRIGHTNESS),
                            true, mContentObserver);
            if(mIsVolumePanelFullSrc) {
                if (mSmartisanSwitch.isChecked()) {
                    mSilentImageFullSrc
                            .setImageResource(R.drawable.smartisan_mini_silent_on);
                } else {
                    mSilentImageFullSrc
                            .setImageResource(R.drawable.smartisan_mini_silent_off);
                }
            } else {
                StreamControl sc = mStreamControls.get(ADJUST_BRIGHTNESS);
                updateSlider(sc);
                refreshMuteTimer();
            }
        }
    };

    OnDismissListener mVolumeDialogDismissListener=new OnDismissListener() {
        public void onDismiss(DialogInterface dialog) {
            if(!mDialog.isShowing()) {
                mActiveStreamType = -1;
                mAudioManager.forceVolumeControlStream(mActiveStreamType);
            } else {
                Log.i(TAG, "mDialog is showing, full " + mIsVolumePanelFullSrc);
            }
            mContext.getContentResolver().unregisterContentObserver(mContentObserver);
        }
    };


    private int getStreamMaxVolume(int streamType) {
        if (streamType == STREAM_MASTER) {
            return mAudioManager.getMasterMaxVolume();
        } else if (streamType == AudioService.STREAM_REMOTE_MUSIC) {
            return mAudioService.getRemoteStreamMaxVolume();
        } else {
            return mAudioManager.getStreamMaxVolume(streamType);
        }
    }

    private int getStreamVolume(int streamType) {
        if (streamType == STREAM_MASTER) {
            return mAudioManager.getMasterVolume();
        } else if (streamType == AudioService.STREAM_REMOTE_MUSIC) {
            return mAudioService.getRemoteStreamVolume();
        } else {
            return mAudioManager.getStreamVolume(streamType);
        }
    }

    private void setStreamVolume(int streamType, int index, int flags) {
        if (streamType == STREAM_MASTER) {
            mAudioManager.setMasterVolume(index, flags);
        } else if (streamType == AudioService.STREAM_REMOTE_MUSIC) {
            mAudioService.setRemoteStreamVolume(index);
        } else {
            mAudioManager.setStreamVolume(streamType, index, flags);
        }
    }

    private boolean isMuted(int streamType) {
        if (streamType == STREAM_MASTER) {
            return mAudioManager.isMasterMute();
        } else if (streamType == AudioService.STREAM_REMOTE_MUSIC) {
            return (mAudioService.getRemoteStreamVolume() <= 0);
        } else {
            return mAudioManager.isStreamMute(streamType);
        }
    }

    private void createSliders() {
        mStreamControls = new HashMap<Integer, StreamControl>(STREAMS.length);
        for (int i = 0; i < STREAMS.length; i++) {
            StreamResources streamRes = STREAMS[i];
            int streamType = streamRes.streamType;
            if (streamType != ADJUST_BRIGHTNESS) {
                createStreamTypeSlider(streamType, streamRes);
            } else {
                createBrightnessTypeSlider(streamRes);
            }
        }
    }

    private void createStreamTypeSlider(int streamType,StreamResources streamRes){
        LayoutInflater inflater = (LayoutInflater) mContext
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        Resources res = mContext.getResources();
        StreamControl sc = new StreamControl();
        sc.streamType = streamType;
        sc.group = (ViewGroup) inflater.inflate(R.layout.smartisan_volume_adjust_item, null);
        sc.group.setTag(sc);
        sc.icon = (ImageView) sc.group.findViewById(R.id.stream_icon);
        sc.secondIcon = (ImageView) sc.group.findViewById(R.id.smarrtisn_second_stream_icon);
        sc.icon.setTag(sc);
        sc.icon.setContentDescription(res.getString(streamRes.descRes));
        sc.iconRes = streamRes.iconRes;
        sc.secondIconRes=streamRes.secondIconRes;
        sc.icon.setImageResource(sc.iconRes);
        sc.secondIcon.setImageResource(sc.secondIconRes);
        sc.seekbarView = (SeekBar) sc.group.findViewById(R.id.seekbar);
        sc.seekbarView.setMax(getStreamMaxVolume(streamType)*10);
        sc.seekbarView.setOnSeekBarChangeListener(this);
        sc.seekbarView.setTag(sc);

        sc.groupFull = (ViewGroup) inflater.inflate(R.layout.smartisan_volume_adjust_item_mini, null);
        sc.groupFull.setTag(sc);
        sc.iconFull = (ImageView) sc.groupFull.findViewById(R.id.stream_icon);
        sc.secondIconFull = (ImageView) sc.groupFull.findViewById(R.id.smarrtisn_second_stream_icon);
        sc.iconFull.setTag(sc);
        sc.iconFull.setContentDescription(res.getString(streamRes.descRes));
        sc.iconFull.setImageResource(sc.iconRes);
        sc.secondIconFull.setImageResource(sc.secondIconRes);
        sc.seekbarViewFull = (SeekBar) sc.groupFull.findViewById(R.id.seekbar);
        sc.seekbarViewFull.setMax(getStreamMaxVolume(streamType)*10);
        sc.seekbarViewFull.setOnSeekBarChangeListener(this);
        sc.seekbarViewFull.setTag(sc);

        mStreamControls.put(streamType, sc);
    }

    private void createBrightnessTypeSlider(StreamResources streamRes){
        LayoutInflater inflater = (LayoutInflater) mContext
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        Resources res = mContext.getResources();
        StreamControl sc = new StreamControl();
        sc.streamType = ADJUST_BRIGHTNESS;
        sc.group = (ViewGroup) inflater.inflate(R.layout.smartisan_brightness_adjust_item, null);
        sc.group.setTag(sc);
        sc.icon = (ImageView) sc.group.findViewById(R.id.smartisan_bright_icon);
        sc.secondIcon = (ImageView) sc.group.findViewById(R.id.smartisan_second_bright_icon);
        sc.icon.setTag(sc);
        sc.icon.setContentDescription(res.getString(streamRes.descRes));
        sc.iconRes = streamRes.iconRes;
        sc.secondIconRes = streamRes.secondIconRes;
        sc.icon.setImageResource(sc.iconRes);
        sc.secondIcon.setImageResource(sc.secondIconRes);
        sc.seekbarView = (SeekBar) sc.group.findViewById(R.id.smartisan_bright_seekbar);
        sc.seekbarView.setMax(SEEK_BAR_RANGE);
        sc.seekbarView.setOnSeekBarChangeListener(this);
        sc.seekbarView.setTag(sc);

        sc.groupFull = (ViewGroup) inflater.inflate(R.layout.smartisan_brightness_adjust_item_mini, null);
        sc.groupFull.setTag(sc);
        sc.iconFull = (ImageView) sc.groupFull.findViewById(R.id.smartisan_bright_icon);
        sc.secondIconFull = (ImageView) sc.groupFull.findViewById(R.id.smartisan_second_bright_icon);
        sc.iconFull.setTag(sc);
        sc.iconFull.setContentDescription(res.getString(streamRes.descRes));
        sc.iconFull.setImageResource(sc.iconRes);
        sc.secondIconFull.setImageResource(sc.secondIconRes);
        sc.seekbarViewFull = (SeekBar) sc.groupFull.findViewById(R.id.smartisan_bright_seekbar);
        sc.seekbarViewFull.setMax(SEEK_BAR_RANGE);
        sc.seekbarViewFull.setOnSeekBarChangeListener(this);
        sc.seekbarViewFull.setTag(sc);

        mStreamControls.put(ADJUST_BRIGHTNESS, sc);
    }

    private void reorderSliders(int activeStreamType) {
        if (mIsVolumePanelFullSrc) {
            mSliderGroupFullSrc.removeAllViews();
            StreamControl active = mStreamControls.get(activeStreamType);
            if (active == null) {
                Log.e("SmartisanVolumePanel", "Missing stream type! - " + activeStreamType);
                mActiveStreamType = -1;
            } else {
                mSliderGroupFullSrc.addView(active.groupFull);
                mActiveStreamType = activeStreamType;
                if (!hasMessages(MSG_SET_STREAMSLIDER_VISIBILITY))
                    sendEmptyMessage(MSG_SET_STREAMSLIDER_VISIBILITY);
            }
        } else {
            mSliderGroup.removeAllViews();
            StreamControl active = mStreamControls.get(activeStreamType);
            if (active == null) {
                Log.e("SmartisanVolumePanel", "Missing stream type! - " + activeStreamType);
                mActiveStreamType = -1;
            } else {
                mSliderGroup.addView(active.group);
                mActiveStreamType = activeStreamType;
                if (!hasMessages(MSG_SET_STREAMSLIDER_VISIBILITY))
                    sendEmptyMessage(MSG_SET_STREAMSLIDER_VISIBILITY);
            }
            addLightVolumes();
        }
    }

    private void doSetStreamSliderVisibility() {
        if(isShowing()) {
            StreamControl active = mStreamControls.get(mActiveStreamType);
            if(active != null) {
                active.group.setVisibility(View.VISIBLE);
                updateSlider(active);
            }
        }
    }

    private void addLightVolumes() {
        StreamControl sc = mStreamControls.get(ADJUST_BRIGHTNESS);
        mSliderGroup.addView(sc.group);
        updateSlider(sc);
    }

    private int getBrightness() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.REALTIME_SCREEN_BRIGHTNESS, 100);
    }

    private int getProgressByBrightness() {
        mScreenBrightnessMaximum = pm.getMaximumScreenBrightnessSetting();
        float brightness = (getBrightness() - mScreenBrightnessMinimum) * 1.0f
                    / (mScreenBrightnessMaximum - mScreenBrightnessMinimum);
        return (int)(brightness*SEEK_BAR_RANGE);
    }

    private void setBrightness(int brightness, boolean tmp) {
        try {
            if (tmp) {
                mPowerManager.setTemporaryScreenBrightnessSettingOverride(brightness);
            } else {
                mPowerManager.setBrightness(brightness);
            }
        } catch (RemoteException e) {
            // do nothing
        }
    }

    private void setBrightnessByProgress(int progress, boolean tmp) {
        mScreenBrightnessMaximum = pm.getMaximumScreenBrightnessSetting();
        int range = (mScreenBrightnessMaximum - mScreenBrightnessMinimum);
        int brightness = (progress * range)/SEEK_BAR_RANGE + mScreenBrightnessMinimum;
        setBrightness(brightness, tmp);
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        int id = (Integer) buttonView.getTag();
        resetTimeout();
        switch (id) {
            case R.id.smartisan_switch:
                if (isChecked) {
                    Settings.System.putInt(mContext.getContentResolver(),
                            Settings.System.VOLUME_PANEL_MUTE_ENABLE, 1);
                } else {
                    Settings.System.putInt(mContext.getContentResolver(),
                            Settings.System.VOLUME_PANEL_MUTE_ENABLE, 0);
                }
                setSeekBarFocusByType(mActiveStreamType);
                refreshMuteTimer();
                break;
            default:
                break;
        }
    }

    private void doRefreshMuteTimer() {
        mCountDownTimer.refresh();
 
        if (isShowing() && mSmartisanSwitch.isChecked()) {
            sendEmptyMessageDelayed(MSG_UPDATE_COUNTDOWN_TIMER, 1000);
        } else {
            removeMessages(MSG_UPDATE_COUNTDOWN_TIMER);
        }
    }

    private void refreshMuteTimer() {
        removeMessages(MSG_UPDATE_COUNTDOWN_TIMER);
        sendEmptyMessageDelayed(MSG_UPDATE_COUNTDOWN_TIMER, 0);
    }

    /** Update the mute and progress state of a slider */
    private void updateSlider(StreamControl sc) {
        SeekBar curSeekBar = sc.seekbarView;
        if(mIsVolumePanelFullSrc) {
            curSeekBar = sc.seekbarViewFull;
        }
        int index;
        if (sc.streamType != ADJUST_BRIGHTNESS) {
            index=getStreamVolume(sc.streamType) * 10;
            if (index > (curSeekBar.getProgress() + 3) || (curSeekBar.getProgress() + 3) - index >= 10)
                mHandler.post(new RefineSeekbarProgress(curSeekBar,index));
            final boolean muted = isMuted(sc.streamType);
            if (sc.streamType == AudioService.STREAM_REMOTE_MUSIC) {
                // never disable touch interactions for remote playback, the
                // muting is not tied to
                // the state of the phone.
        //      sc.seekbarView.setEnabled(true);
        //    } else if (muted &&
        //             sc.streamType != AudioManager.STREAM_VOICE_CALL) {
        //        sc.group.setAlpha(0.4f);
            } else if(mSmartisanSwitch.isChecked() &&
                    sc.streamType != AudioManager.STREAM_ALARM &&
                    sc.streamType != AudioManager.STREAM_VOICE_CALL &&
                    (mAudioManager.getDevicesForStream(sc.streamType) & AudioSystem.DEVICE_OUT_SPEAKER) != 0) {
                //sc.group.setAlpha(0.4f);
                //sc.seekbarView.setEnabled(false);
                setSeekbarDrawable(sc, false);
            } else {
                setSeekbarDrawable(sc, true);
            //    sc.seekbarView.setEnabled(true);
            //    sc.group.setAlpha(1.0f);
            }
        } else {
            index=getProgressByBrightness();
            mHandler.post(new RefineSeekbarProgress(curSeekBar,index));
        }
    }

    private void setSeekbarDrawable(StreamControl sc, boolean active)
    {
        if (sc == null)
            return;

        SeekBar curSeekBar = sc.seekbarView;
        if (mIsVolumePanelFullSrc) {
            curSeekBar = sc.seekbarViewFull;
        }

        if (active) {
            curSeekBar.setProgressDrawable(mContext.getResources().getDrawable(
                    R.drawable.smartisan_volume_brightness_progress));
        } else {
            curSeekBar.setProgressDrawable(mContext.getResources().getDrawable(
                    R.drawable.smartisan_vol_sysmute_progress));
        }
    }

    private void updateStates() {
        final int count = mSliderGroup.getChildCount();
        for (int i = 0; i < count; i++) {
            StreamControl sc = (StreamControl) mSliderGroup.getChildAt(i).getTag();
            updateSlider(sc);
        }
    }

    public void postVolumeChanged(int streamType, int flags) {
        if (hasMessages(MSG_VOLUME_CHANGED)) {
            return;
        }

        synchronized (this) {
            if (mStreamControls == null) {
                createSliders();
            }
        }
        obtainMessage(MSG_CHECK_DIALOG_STATUS, streamType, flags).sendToTarget();
//        setSeekBarFocusByType(streamType);
        obtainMessage(MSG_VOLUME_CHANGED, streamType, flags).sendToTarget();
    }

    private void onCheckDialogStatus(int streamType, int flags) {
        boolean isFull = false;
        if ((flags & AudioManager.FLAG_SHOW_UI) != 0) {
            try {
                if(mTelephony == null) {
                    mTelephony = ITelephony.Stub.asInterface(
                            ServiceManager.checkService(Context.TELEPHONY_SERVICE));
                }
                isFull = mWinManager.isFocusedWinFullScreen() || mTelephony.isRinging();

                Log.i(TAG, "onCheckDialogStatus isFull " + isFull + " mIsUserTouching " + mIsUserTouching);
                if(!mIsUserTouching) {
                    if (mDialog.isShowing()
                            && mIsVolumePanelFullSrc != isFull) {
                        Log.i(TAG, "volume panel mode is change, dismiss the panel");
                        mForceReorderSliders = true;
                        mDialog.dismiss();
                        mActiveStreamType = -1;
                        mAudioManager.forceVolumeControlStream(mActiveStreamType);
                    }
                    mIsVolumePanelFullSrc = isFull;
                }
            } catch (Exception e) {
            //} catch (RemoteException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
                isFull = mIsVolumePanelFullSrc;
            }
        }

        int streamTypeBrightness = streamType >> 16;
        int streamTypeAudio = streamType & 0x0000FFFF;
        int streamActual = streamType;
        if(mIsVolumePanelFullSrc && (streamType >> 16) != 0) {
            streamActual = streamType >> 16;
        } else {
            streamActual = streamType & 0x0000FFFF;
        }

        Log.i(TAG, "onCheckDialogStatus mActiveStreamType " + mActiveStreamType + " streamType " + streamType + " streamActual " + streamActual + " full " + mIsVolumePanelFullSrc);
        if(mDialog.isShowing() && ((flags & AudioManager.FLAG_SHOW_UI) != 0) && mActiveStreamType != -1 &&
                ((mActiveStreamType == ADJUST_BRIGHTNESS && streamActual != ADJUST_BRIGHTNESS) ||
                 (mActiveStreamType != ADJUST_BRIGHTNESS && streamActual == ADJUST_BRIGHTNESS))) {
            if(mIsUserTouching) {
                mIsVolumePanelFullSrc = isFull;
            }
            Log.i(TAG, "dismiss dialog");
            mDialog.dismiss();
            mActiveStreamType = -1;
            mAudioManager.forceVolumeControlStream(mActiveStreamType);
        }
    }

    public boolean isShowing() {
        return mDialog.isShowing();
    }
    private void setSeekBarFocusByType(int streamType){
//        Iterator iter = mStreamControls.entrySet().iterator();
//        while (iter.hasNext()) {
//            Map.Entry entry = (Map.Entry) iter.next();
//            Integer key = (Integer) entry.getKey();
//            StreamControl sc = (StreamControl) entry.getValue();
//            if(key==streamType){
//                sc.seekbarView.setProgressDrawable(mContext.getResources().
//                        getDrawable(R.drawable.smartisan_volume_brightness_progress));
//            }else{
//                sc.seekbarView.setProgressDrawable(mContext.getResources().
//                        getDrawable(R.drawable.smartisan_volume_brightness_progress_inactive));
//            }
//
//            if ((key == AudioManager.STREAM_RING) && mSmartisanSwitch.isChecked()) {
//                sc.seekbarView.setProgressDrawable(mContext.getResources().
//                        getDrawable(R.drawable.smartisan_volume_brightness_progress_inactive));
//            }
//        }
    }

    public void postRemoteVolumeChanged(int streamType, int flags) {
        if (hasMessages(MSG_REMOTE_VOLUME_CHANGED)) return;
        synchronized (this) {
            if (mStreamControls == null) {
                createSliders();
            }
        }
        obtainMessage(MSG_REMOTE_VOLUME_CHANGED, streamType, flags).sendToTarget();
    }

    public void postRemoteSliderVisibility(boolean visible) {
        obtainMessage(MSG_SLIDER_VISIBILITY_CHANGED,
                AudioService.STREAM_REMOTE_MUSIC, visible ? 1 : 0).sendToTarget();
    }

    /**
     * Called by AudioService when it has received new remote playback information that
     * would affect the VolumePanel display (mainly volumes). The difference with
     * {@link #postRemoteVolumeChanged(int, int)} is that the handling of the posted message
     * (MSG_REMOTE_VOLUME_UPDATE_IF_SHOWN) will only update the volume slider if it is being
     * displayed.
     * This special code path is due to the fact that remote volume updates arrive to AudioService
     * asynchronously. So after AudioService has sent the volume update (which should be treated
     * as a request to update the volume), the application will likely set a new volume. If the UI
     * is still up, we need to refresh the display to show this new value.
     */
    public void postHasNewRemotePlaybackInfo() {
        if (hasMessages(MSG_REMOTE_VOLUME_UPDATE_IF_SHOWN)) return;
        // don't create or prevent resources to be freed, if they disappear, this update came too
        //   late and shouldn't warrant the panel to be displayed longer
        obtainMessage(MSG_REMOTE_VOLUME_UPDATE_IF_SHOWN).sendToTarget();
    }

    public void postMasterVolumeChanged(int flags) {
        postVolumeChanged(STREAM_MASTER, flags);
    }

    public void postMuteChanged(int streamType, int flags) {
        if (hasMessages(MSG_MUTE_CHANGED)) return;
        synchronized (this) {
            if (mStreamControls == null) {
                createSliders();
            }
        }
        obtainMessage(MSG_MUTE_CHANGED, streamType, flags).sendToTarget();
    }

    public void postMasterMuteChanged(int flags) {
        postMuteChanged(STREAM_MASTER, flags);
    }

    public void postDisplaySafeVolumeWarning(int flags) {
        if (hasMessages(MSG_DISPLAY_SAFE_VOLUME_WARNING)) return;
        obtainMessage(MSG_DISPLAY_SAFE_VOLUME_WARNING, 0, 0).sendToTarget();
    }

    /**
     * Override this if you have other work to do when the volume changes (for
     * example, vibrating, playing a sound, etc.). Make sure to call through to
     * the superclass implementation.
     */
    protected void onVolumeChanged(int streamType, int flags) {
        if (LOGD) Log.d(TAG, "onVolumeChanged(streamType: " + streamType + ", flags: " + flags + ")");
        int streamTypeOri = streamType;

        synchronized (this) {

            if(mIsVolumePanelFullSrc && (streamTypeOri >> 16) != 0) {
                streamType = streamTypeOri >> 16;
            } else {
                streamType = streamTypeOri & 0x0000FFFF;
            }

            if ((flags & AudioManager.FLAG_SHOW_UI) != 0) {
                if (sConfirmSafeVolumeDialog != null
                        && sConfirmSafeVolumeDialog.isShowing()) {
                    // safe volume warning is showing
                    if ((flags & AudioManager.FLAG_SHOW_UI_FORCE) != 0) {
                        // the FLAG_SHOW_UI_FORCE is set, dismiss the warning
                        // dialog
                        sConfirmSafeVolumeDialog.dismiss();
                    } else {
                        // do nothing
                        return;
                    }
                }
                if (streamType != mActiveStreamType || mForceReorderSliders) {
                    mForceReorderSliders = false;
                    reorderSliders(streamType);
                }
                onShowVolumeChanged(streamType, flags);
            } else if (isShowing() && !mIsUserTouching) {//volume panel is showing and the user is not touching, update the silder
                if (!hasMessages(MSG_VOLUME_CHANGED) && (streamType == mActiveStreamType)) {
                    StreamControl active = mStreamControls.get(streamType);
                    if (active == null) {
                        Log.e(TAG, "update the slider, missing stream type! - " + streamType);
                    } else {
                        if (LOGD) Log.d(TAG, "volume panel is showing, update slider " + streamType);
                        updateSlider(active);
                    }
                }
            }
        }

        if ((flags & AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE) != 0) {
            removeMessages(MSG_VIBRATE);
            onStopSounds();
        }

        if ( ((flags & AudioManager.FLAG_VIBRATE) != 0) &&
                (streamType == AudioManager.STREAM_RING ||
                streamType == AudioManager.STREAM_NOTIFICATION) &&
                mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE &&
                !mSmartisanSwitch.isChecked()) {
            sendMessageDelayed(obtainMessage(MSG_VIBRATE), VIBRATE_DELAY);
        }

        resetTimeout();
    }

    protected void onMuteChanged(int streamType, int flags) {
        if (LOGD) Log.d(TAG, "onMuteChanged(streamType: " + streamType + ", flags: " + flags + ")");
        if (Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.VOLUME_PANEL_MUTE_ENABLE, 0) == 1) {
            Log.i(TAG, "the device is muted now");
            if (!mSmartisanSwitch.isChecked()){
                if (LOGD) Log.d(TAG, "check the mute switch");
                mSmartisanSwitch.setChecked(true);
            }

            if(Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.TELEPHONY_VIBRATION_ENABLED, 0) == 1 &&
                    mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_VIBRATE) {
                sendMessageDelayed(obtainMessage(MSG_VIBRATE), VIBRATE_DELAY);
            }
            if(mDialog.isShowing() && mIsVolumePanelFullSrc)
                mSilentImageFullSrc.setImageResource(R.drawable.smartisan_mini_silent_on);
        } else {
            Log.i(TAG, "the device is unmuted now");
            if (mSmartisanSwitch.isChecked()){
                if (LOGD) Log.d(TAG, "uncheck the mute switch");
                mSmartisanSwitch.setChecked(false);}
            if(mDialog.isShowing() && mIsVolumePanelFullSrc)
                mSilentImageFullSrc.setImageResource(R.drawable.smartisan_mini_silent_off);
        }
        StreamControl sc = mStreamControls.get(streamType);
        updateSlider(sc);
        onVolumeChanged(streamType, flags);
    }

    protected void onShowVolumeChanged(int streamType, int flags) {

        if (streamType != ADJUST_BRIGHTNESS) {
            int index = getStreamVolume(streamType) * 10;
            mRingIsSilent = false;

            if (LOGD) {
                Log.d(TAG, "onShowVolumeChanged(streamType: " + streamType
                        + ", flags: " + flags + "), index: " + index);
            }

            // get max volume for progress bar

            int max = getStreamMaxVolume(streamType) * 10;
            switch (streamType) {

            case AudioManager.STREAM_RING: {
                Uri ringuri = RingtoneManager.getActualDefaultRingtoneUri(
                        mContext, RingtoneManager.TYPE_RINGTONE);
                if (ringuri == null) {
                    mRingIsSilent = true;
                }
                break;
            }

            case AudioManager.STREAM_MUSIC: {
                break;
            }

            case AudioManager.STREAM_VOICE_CALL: {
                break;
            }

            case AudioManager.STREAM_ALARM: {
                break;
            }

            case AudioManager.STREAM_NOTIFICATION: {
                Uri ringuri = RingtoneManager.getActualDefaultRingtoneUri(
                        mContext, RingtoneManager.TYPE_NOTIFICATION);
                if (ringuri == null) {
                    mRingIsSilent = true;
                }
                break;
            }

            case AudioManager.STREAM_BLUETOOTH_SCO: {
                break;
            }

            case AudioService.STREAM_REMOTE_MUSIC: {
                if (LOGD) {
                    Log.d(TAG, "showing remote volume " + index + " over "
                            + max);
                }
                break;
            }
            }
            StreamControl sc = mStreamControls.get(streamType);
            if (sc != null) {
                SeekBar curSeekBar = sc.seekbarView;
                if(mIsVolumePanelFullSrc) {
                    curSeekBar = sc.seekbarViewFull;
                }
                if (curSeekBar.getMax() != max) {
                    mHandler.post(new RefineSeekbarProgress(curSeekBar,
                            VOLUME_PROGRESS_ZERO));
                    curSeekBar.setMax(max);
                }

                if (sc.streamType == AudioManager.STREAM_RING
                        || sc.streamType == AudioManager.STREAM_NOTIFICATION) {
                    int iconRes = sc.iconRes;
                    if (Settings.Global.getInt(mContext.getContentResolver(),
                            Settings.Global.TELEPHONY_VIBRATION_ENABLED, 0) == 1) {
                        iconRes = R.drawable.smartisan_vol_vibrate;
                    } else {
                        iconRes = R.drawable.smartisan_vol_silent;
                    }
                    if (sc.iconRes != iconRes) {
                        sc.iconRes = iconRes;
                        sc.icon.setImageResource(sc.iconRes);
                        sc.iconFull.setImageResource(sc.iconRes);
                    }
                }

                // if (
                // !mSmartisanSwitch.isChecked() ||
                // sc.streamType == AudioManager.STREAM_VOICE_CALL)
                {
                    if (index > (curSeekBar.getProgress() + 3)
                            || (curSeekBar.getProgress() + 3) - index >= 10)
                        mHandler.post(new RefineSeekbarProgress(curSeekBar,
                                index));
                }
                if ((flags & AudioManager.FLAG_FIXED_VOLUME) != 0) {
                    // sc.seekbarView.setEnabled(false);
                    // sc.group.setAlpha(0.4f);
                    // } else if(streamType != AudioService.STREAM_REMOTE_MUSIC
                    // &&
                    // streamType != AudioManager.STREAM_VOICE_CALL &&
                    // isMuted(streamType)) {
                    // sc.group.setAlpha(0.4f);
                } else if (mSmartisanSwitch.isChecked()
                        && streamType != AudioService.STREAM_REMOTE_MUSIC
                        && streamType != AudioManager.STREAM_VOICE_CALL
                        && streamType != AudioManager.STREAM_ALARM
                        && (mAudioManager.getDevicesForStream(sc.streamType) & AudioSystem.DEVICE_OUT_SPEAKER) != 0) {
                    // sc.group.setAlpha(0.4f);
                    setSeekbarDrawable(sc, false);
                } else {
                    setSeekbarDrawable(sc, true);
                    // sc.seekbarView.setEnabled(true);
                    setSeekBarFocusByType(streamType);
                    // sc.group.setAlpha(1.0f);
                }
            }
        }

        // Only Show if style needs it
        if (!mDialog.isShowing() && mCurrentOverlayStyle != VOLUME_OVERLAY_NONE) {
            int stream = (streamType == AudioService.STREAM_REMOTE_MUSIC) ? -1
                    : streamType;
            // when the stream is for remote playback, use -1 to reset the
            // stream type evaluatio
            TextView title = (TextView) mSmartisanSwitch
                    .findViewById(R.id.smartisan_item_switch_title);
            title.setText(R.string.smartisan_all_mute);

            if(stream != ADJUST_BRIGHTNESS)
                mAudioManager.forceVolumeControlStream(stream);

            if (mIsVolumePanelFullSrc) {
                mDialog.setContentView(mViewFullSrc);
                Window win = mDialog.getWindow();
                win.setWindowAnimations(R.style.Smartisanos_Volume_Panel_Mini);
                mViewFullSrc.invalidate();
            } else {
                mDialog.setContentView(mView);
                Window win = mDialog.getWindow();
                win.setWindowAnimations(R.style.Animation_VolumePanel);
                mView.invalidate();
            }
            mDialog.show();
        }

        // Do a little vibrate if applicable (only when going into vibrate mode)
//        if ((streamType != AudioService.STREAM_REMOTE_MUSIC) &&
//                ((flags & AudioManager.FLAG_VIBRATE) != 0) &&
//                mAudioService.isStreamAffectedByRingerMode(streamType) &&
//                mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE) {
//            sendMessageDelayed(obtainMessage(MSG_VIBRATE), VIBRATE_DELAY);
//        }
    }

    protected void onPlaySound(int streamType, int flags) {
        // If preference is no sound - just exit here
        if (Settings.System.getInt(mContext.getContentResolver(),
                 Settings.System.VOLUME_ADJUST_SOUNDS_ENABLED, 1) == 0) {
             return;
         }

        if (hasMessages(MSG_STOP_SOUNDS)) {
            removeMessages(MSG_STOP_SOUNDS);
            // Force stop right now
            onStopSounds();
        }

        synchronized (this) {
            if(mAudioManager!=null&&mAdjustVolumeSounds!=null){
                float mLockSoundVolume=mAudioManager.getMasterVolume();
                mAdjustVolumeStreamId = mAdjustVolumeSounds.play(mAdjustVolumeSoundId,
                        mLockSoundVolume, mLockSoundVolume, 1, 0, 1.0f);
                sendMessageDelayed(obtainMessage(MSG_STOP_SOUNDS), BEEP_DURATION);
            }
        }
    }

    protected void onStopSounds() {

        synchronized (this) {
            if(mAdjustVolumeSounds!=null){
                mAdjustVolumeSounds.stop(mAdjustVolumeStreamId);
            }
        }
    }

    protected void onVibrate() {

        // Make sure we ended up in vibrate ringer mode
        if (mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_VIBRATE &&
            !mSmartisanSwitch.isChecked()) {
            return;
        }

        mVibrator.vibrateWithPrivilege(VIBRATE_DURATION);
    }

    protected void onRemoteVolumeChanged(int streamType, int flags) {
        // streamType is the real stream type being affected, but for the UI sliders, we
        // refer to AudioService.STREAM_REMOTE_MUSIC. We still play the beeps on the real
        // stream type.
        if (LOGD) Log.d(TAG, "onRemoteVolumeChanged(stream:"+streamType+", flags: " + flags + ")");

        if (((flags & AudioManager.FLAG_SHOW_UI) != 0) || mDialog.isShowing()) {
            synchronized (this) {
                if (mActiveStreamType != AudioService.STREAM_REMOTE_MUSIC) {
                    reorderSliders(AudioService.STREAM_REMOTE_MUSIC);
                }
                onShowVolumeChanged(AudioService.STREAM_REMOTE_MUSIC, flags);
            }
        } else {
            if (LOGD) Log.d(TAG, "not calling onShowVolumeChanged(), no FLAG_SHOW_UI or no UI");
        }


        if ((flags & AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE) != 0) {
            removeMessages(MSG_VIBRATE);
            onStopSounds();
        }

        resetTimeout();
    }

    protected void onRemoteVolumeUpdateIfShown() {
        if (LOGD) Log.d(TAG, "onRemoteVolumeUpdateIfShown()");
        if (mDialog.isShowing()
                && (mActiveStreamType == AudioService.STREAM_REMOTE_MUSIC)
                && (mStreamControls != null)) {
            onShowVolumeChanged(AudioService.STREAM_REMOTE_MUSIC, 0);
        }
    }

    /**
     * Handler for MSG_SLIDER_VISIBILITY_CHANGED
     * Hide or show a slider
     * @param streamType can be a valid stream type value, or VolumePanel.STREAM_MASTER,
     *                   or AudioService.STREAM_REMOTE_MUSIC
     * @param visible
     */
    synchronized protected void onSliderVisibilityChanged(int streamType, int visible) {
        if (LOGD) Log.d(TAG, "onSliderVisibilityChanged(stream="+streamType+", visi="+visible+")");
        boolean isVisible = (visible == 1);
        for (int i = STREAMS.length - 1 ; i >= 0 ; i--) {
            StreamResources streamRes = STREAMS[i];
            if (streamRes.streamType == streamType) {
                streamRes.show = isVisible;
                if (!isVisible && (mActiveStreamType == streamType)) {
                    mActiveStreamType = -1;
                }
                break;
            }
        }
    }

    protected void onDisplaySafeVolumeWarning(int flags) {
//        if ((flags & AudioManager.FLAG_SHOW_UI) != 0 || mDialog.isShowing()) {
            synchronized (sConfirmSafeVolumeLock) {
                if (sConfirmSafeVolumeDialog != null) {
                    return;
                }
                sConfirmSafeVolumeDialog = new AlertDialog.Builder(mContext, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                        .setMessage(com.android.internal.R.string.safe_media_volume_warning)
                        .setPositiveButton(com.android.internal.R.string.yes,
                                            new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                mAudioService.disableSafeMediaVolume();
                            }
                        })
                        .setNegativeButton(com.android.internal.R.string.no, null)
                        .setIconAttribute(android.R.attr.alertDialogIcon)
                        .create();
                final WarningDialogReceiver warning = new WarningDialogReceiver(mContext,
                        sConfirmSafeVolumeDialog, this);

                sConfirmSafeVolumeDialog.setOnDismissListener(warning);
                sConfirmSafeVolumeDialog.getWindow().setType(
                                                WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
                sConfirmSafeVolumeDialog.getWindow().addFlags(LayoutParams.FLAG_NOT_FOCUSABLE);
                sConfirmSafeVolumeDialog.show();
            }
            updateStates();
//        }
        resetTimeout();
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {

            case MSG_VOLUME_CHANGED: {
                onVolumeChanged(msg.arg1, msg.arg2);
                break;
            }

            case MSG_MUTE_CHANGED: {
                onMuteChanged(msg.arg1, msg.arg2);
                break;
            }

            case MSG_STOP_SOUNDS: {
                onStopSounds();
                break;
            }

            case MSG_VIBRATE: {
                onVibrate();
                break;
            }

            case MSG_TIMEOUT: {
                if (mDialog.isShowing()) {
                    mDialog.dismiss();
                    mActiveStreamType = -1;
                }
                break;
            }

            case MSG_REMOTE_VOLUME_CHANGED: {
                onRemoteVolumeChanged(msg.arg1, msg.arg2);
                break;
            }

            case MSG_REMOTE_VOLUME_UPDATE_IF_SHOWN:
                onRemoteVolumeUpdateIfShown();
                break;

            case MSG_SLIDER_VISIBILITY_CHANGED:
                onSliderVisibilityChanged(msg.arg1, msg.arg2);
                break;

            case MSG_DISPLAY_SAFE_VOLUME_WARNING:
                if (mDialog.isShowing()) {
                    mDialog.dismiss();
                    mActiveStreamType = -1;
                }
                onDisplaySafeVolumeWarning(msg.arg1);
                break;
            case MSG_UPDATE_COUNTDOWN_TIMER:
                doRefreshMuteTimer();
                break;
            case MSG_UPDATE_MUTEPANEL_VISIBILITY:
                doUpdateMutePanelVisibility(msg.arg1);
                break;
            case MSG_SET_STREAMSLIDER_VISIBILITY:
                doSetStreamSliderVisibility();
                break;
            case MSG_CHECK_DIALOG_STATUS:
                onCheckDialogStatus(msg.arg1, msg.arg2);
                break;
        }
    }

    /**
     * Handler for MSG_RINGER_MODE_CHANGED
     *
     * when the ringer mode is changed, the slider left icon
     * should change to the corresponding one.
     */
    private void changeRingStreamIcon() {
        //make sure the mStreamControls is initialized.
        if(mStreamControls != null)
        {
            StreamControl sc = mStreamControls.get(AudioManager.STREAM_RING);
            int iconRes = sc.iconRes;
            int ringerMode = mAudioManager.getRingerMode();
            if(ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                iconRes = R.drawable.smartisan_vol_vibrate;
                Log.i(TAG, "RINGER_MODE_VIBRATE iconRes " + iconRes);
            } else if(ringerMode == AudioManager.RINGER_MODE_SILENT) {
                iconRes = R.drawable.smartisan_vol_silent;
                Log.i(TAG, "RINGER_MODE_SILENT iconRes " + iconRes);
            } else {
                iconRes = StreamResources.RingerStream.iconRes;
                Log.i(TAG, "RINGER_MODE_NORMAL iconRes " + iconRes);
            }
            sc.iconRes = iconRes;
            sc.icon.setImageResource(sc.iconRes);
        }
    }

    private void resetTimeout() {
        removeMessages(MSG_TIMEOUT);
        sendMessageDelayed(obtainMessage(MSG_TIMEOUT), TIMEOUT_DELAY);
    }

    private void forceTimeout() {
        removeMessages(MSG_TIMEOUT);
        sendMessage(obtainMessage(MSG_TIMEOUT));
    }

    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        final Object tag = seekBar.getTag();
        if (fromUser && tag instanceof StreamControl) {
            StreamControl sc = (StreamControl) tag;
            if(sc.streamType!=ADJUST_BRIGHTNESS){
                int volume = (progress + 3)/10;
                int curVolume = getStreamVolume(sc.streamType);
                if(curVolume != volume) {
                    setStreamVolume(sc.streamType, volume, 0);
                }
            }else{
                setBrightnessByProgress(progress, true);
            }
        }
        resetTimeout();
    }

    public void onStartTrackingTouch(SeekBar seekBar) {
        final Object tag = seekBar.getTag();
        if (tag instanceof StreamControl) {
            StreamControl mStreamControlBytouch = (StreamControl) tag;
            setSeekBarFocusByType(mStreamControlBytouch.streamType);
            mIsUserTouching = true;
        }
    }

    public void onStopTrackingTouch(SeekBar seekBar) {
        final Object tag = seekBar.getTag();
        if (tag instanceof StreamControl) {
            StreamControl sc = (StreamControl) tag;
            mIsUserTouching = false;
            // because remote volume updates are asynchronous, AudioService might have received
            // a new remote volume value since the finger adjusted the slider. So when the
            // progress of the slider isn't being tracked anymore, adjust the slider to the last
            // "published" remote volume value, so the UI reflects the actual volume.
            if (sc.streamType == AudioService.STREAM_REMOTE_MUSIC) {
                seekBar.setProgress(getStreamVolume(AudioService.STREAM_REMOTE_MUSIC) * 10);
            } else if (sc.streamType == ADJUST_BRIGHTNESS) {
                setBrightnessByProgress(seekBar.getProgress(), false);
            } else if(sc.streamType == AudioManager.STREAM_MUSIC) {
                int device = mAudioManager.getDevicesForStream(AudioManager.STREAM_MUSIC);
                if((device & mFixedVolumeDevices) != 0) {
                    int index = 0;
                    int curIndex = getStreamVolume(sc.streamType);
                    if(sc.seekbarView.getProgress() < 7) {
                        index = 0;
                    } else {
                        index = getStreamMaxVolume(sc.streamType);
                    }
                    if(curIndex != index) {
                        setStreamVolume(sc.streamType, index, 0);
                    } else {
                        mHandler.post(new RefineSeekbarProgress(sc.seekbarView,curIndex * 10));
                    }
                }
            }
        }
    }

    private void sendUpdateVisibilityMsg(int visibility) {
        removeMessages(MSG_UPDATE_MUTEPANEL_VISIBILITY);
        obtainMessage(MSG_UPDATE_MUTEPANEL_VISIBILITY, visibility, 0).sendToTarget();
    }

    private void doUpdateMutePanelVisibility(int visibility) {
        mutePanelShadow.setVisibility(visibility);
    }

    public class RefineSeekbarProgress implements Runnable{
        private final SeekBar mSeekbar;
        private final int mProgress;
        public RefineSeekbarProgress(SeekBar sbar, int index){
            mSeekbar=sbar;
            mProgress=index;
        }
        public void run(){
            mSeekbar.setProgress(mProgress);
        }
    }
    public class MuteTimerListener implements
            CountDownTimer.OnCountDownTimerUpdateListener {
        private LayoutTransition mLayoutTransitionA,mLayoutTransitionB;
        public MuteTimerListener(){
            mLayoutTransitionA = new LayoutTransition();

            mLayoutTransitionA.setDuration(150);

            mLayoutTransitionA.setStartDelay(LayoutTransition.CHANGE_APPEARING, 0);
            mLayoutTransitionA.setStartDelay(LayoutTransition.APPEARING, 0);
            mLayoutTransitionA.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0);
            mLayoutTransitionA.setStartDelay(LayoutTransition.DISAPPEARING, 0);

            mLayoutTransitionA.setAnimateParentHierarchy(false);

            mutePanel.setLayoutTransition(mLayoutTransitionA);

            mLayoutTransitionB = new LayoutTransition();

            mLayoutTransitionB.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
            mLayoutTransitionB.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            mLayoutTransitionB.disableTransitionType(LayoutTransition.APPEARING);
            mLayoutTransitionB.disableTransitionType(LayoutTransition.DISAPPEARING);

            mLayoutTransitionB.enableTransitionType(LayoutTransition.CHANGING);
            mLayoutTransitionB.setDuration(LayoutTransition.CHANGING, 150);

            mPanel.setLayoutTransition(mLayoutTransitionB);
            LayoutTransition.TransitionListener transitionListener = new LayoutTransition.TransitionListener(){
                @Override
                public void startTransition(LayoutTransition transition,
                        ViewGroup container, View view, int transitionType) {
                    if (LOGD) Log.d(TAG, "start LayoutTransition A for view " + view + " on container " + container + " type " + transitionType);
                    mLayoutTransitionB.enableTransitionType(LayoutTransition.CHANGING);
                    mSwitch.setClickable(false);
                }

                @Override
                public void endTransition(LayoutTransition transition,
                        ViewGroup container, View view, int transitionType) {
                    if (LOGD) Log.d(TAG, "end LayoutTransition A for view " + view + " on container " + container + " type " + transitionType);
                    mLayoutTransitionB.disableTransitionType(LayoutTransition.CHANGING);
                    mSwitch.setClickable(true);
                }};
                mLayoutTransitionA.addTransitionListener(transitionListener);

            LayoutTransition.TransitionListener transitionListenerB = new LayoutTransition.TransitionListener(){
                @Override
                public void startTransition(LayoutTransition transition,
                        ViewGroup container, View view, int transitionType) {
                    if (LOGD) Log.d(TAG, "start LayoutTransition B for view " + view + " on container " + container + " type " + transitionType);
                }

                @Override
                public void endTransition(LayoutTransition transition,
                        ViewGroup container, View view, int transitionType) {
                    if (LOGD) Log.d(TAG, "end LayoutTransition B for view " + view + " on container " + container  + " type " + transitionType);
                }};
                mLayoutTransitionB.addTransitionListener(transitionListenerB);
        }

        @Override
        public void onShow() {
            Log.i(TAG, "show the countdown timer");
            if (mutePanelShadow.getVisibility() != View.VISIBLE){
                mLayoutTransitionB.enableTransitionType(LayoutTransition.CHANGING);
                //mutePanelShadow.setVisibility(View.VISIBLE);
                sendUpdateVisibilityMsg(View.VISIBLE);
                Log.i(TAG, "pad the mute panel");
            }
        }

        @Override
        public void onDismiss() {
            Log.i(TAG, "dismiss the countdown timer");
            if (mutePanelShadow.getVisibility() != View.GONE){
                mLayoutTransitionB.enableTransitionType(LayoutTransition.CHANGING);
                //mutePanelShadow.setVisibility(View.GONE);
                sendUpdateVisibilityMsg(View.GONE);
                Log.i(TAG, "un-pad the mute panel");
            }
        }

        @Override
        public void onTracking(int status) {
            resetTimeout();
            if (status == OnCountDownTimerUpdateListener.TRACK_START) {
                removeMessages(MSG_UPDATE_COUNTDOWN_TIMER);
            } else if (status == OnCountDownTimerUpdateListener.TRACK_STOP) {
                sendEmptyMessageDelayed(MSG_UPDATE_COUNTDOWN_TIMER, 500);
            }
        }
    }

    public void setLayoutDirection(int layoutDirection) {
        mPanel.setLayoutDirection(layoutDirection);
        updateStates();
    }
}
