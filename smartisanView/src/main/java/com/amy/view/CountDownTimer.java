package android.view;

import com.android.internal.R;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.os.SystemProperties;

/**
 * Add for smartisan volumepanel
 *
 * @hide
 */
public class CountDownTimer extends LinearLayout implements
        SeekBar.OnSeekBarChangeListener {
    private static final String TAG = "CountDownTimer";
    private static boolean LOGD = (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private Context mContext;
    private AnimationManager animMgr;
    private OnCountDownTimerUpdateListener l;
    private TextView[] tv_anchors = new TextView[5];
    private TextView tv_cursor;
    private SeekBar sb_stepSeeker;
    private Handler mHandler;
    private LinearLayout anchorContainer, cursorContainer;
    private static final int MSG_COUNTING_DOWN = 1;
    private static final int MSG_NO_COUNTING_DOWN = 2;
    private static final int MSG_SET_ALARM = 3;
    private AlarmManager alarmMgr;
    private int step_seeker_track_startpoint, step_seeker_track_length, cursorWidth;
    private Drawable timerTrack, stepTrack;
    private long TIMEOUT_MAX_MS = 8*60*60*1000;
    private static final String COLOR_SELECT = "#565759";
    private static final String COLOR_UNSELECT = "#B0B2B4";
    private PendingIntent mPendingIntentForAlarm;
    public CountDownTimer(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        LayoutInflater.from(mContext).inflate(R.layout.count_down_timer, this);
        tv_anchors[0] = (TextView) findViewById(R.id.anchor1);
        tv_anchors[1] = (TextView) findViewById(R.id.anchor2);
        tv_anchors[2] = (TextView) findViewById(R.id.anchor3);
        tv_anchors[3] = (TextView) findViewById(R.id.anchor4);
        tv_anchors[4] = (TextView) findViewById(R.id.anchor5);
        tv_cursor = (TextView) findViewById(R.id.cursor);
        anchorContainer = (LinearLayout) findViewById(R.id.anchor_container);
        cursorContainer = (LinearLayout) findViewById(R.id.cursor_container);
        cursorContainer.setVisibility(View.GONE);
        sb_stepSeeker = (SeekBar) findViewById(R.id.step_seekbar);
        sb_stepSeeker.setOnSeekBarChangeListener(this);
        step_seeker_track_startpoint = (int)mContext.getResources().getDimension(R.dimen.step_seekbar_track_startpoint_offset);
        step_seeker_track_length = (int)mContext.getResources().getDimension(R.dimen.step_seekbar_track_length);
        sb_stepSeeker.setThumbOffset(sb_stepSeeker.getThumbOffset() - step_seeker_track_startpoint);
        sb_stepSeeker.setProgress(0);
        if (LOGD) Log.d(TAG, "the offset of the start point of the step seekbar track is " + step_seeker_track_startpoint + ", track length is " + step_seeker_track_length);
        animMgr = new AnimationManager(context);

        timerTrack = mContext.getResources().getDrawable(R.drawable.unmute_timer_track);
        stepTrack = mContext.getResources().getDrawable(R.drawable.step_seek_track);
        cursorWidth = (int)mContext.getResources().getDimension(R.dimen.countdowm_timer_cursor_width);
        mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                LinearLayout.LayoutParams para = new LinearLayout.LayoutParams(
                        cursorWidth,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                switch (msg.what) {
                case MSG_COUNTING_DOWN:
                    int max = sb_stepSeeker.getMax();
                    int second = msg.arg1;
                    int min = second / 60;

                    int progress = min2progress(min);

                    if (timerTrack != sb_stepSeeker.getProgressDrawable()) {
                        sb_stepSeeker.setProgressDrawable(timerTrack);
                        sb_stepSeeker.setThumb(mContext.getResources().getDrawable(R.drawable.countdown_thumb));
                        sb_stepSeeker.setThumbOffset(sb_stepSeeker.getThumbOffset() - step_seeker_track_startpoint);
                    }
                    sb_stepSeeker.setProgress(progress);

                    tv_cursor.setText(echoTime(second));

                    Rect rect = sb_stepSeeker.getThumb().getBounds();
                    if (LOGD) Log.d(TAG, "thumb bounds is " + rect.toString() + ", thumb offset is " + sb_stepSeeker.getThumbOffset());

                    int margin = sb_stepSeeker.getLeft() - cursorContainer.getLeft() + step_seeker_track_startpoint;
                    margin = margin + (rect.left + rect.right) / 2;
                    margin = margin - cursorWidth / 2;

                    if (LOGD) Log.d(TAG, "set cursor margin, max = " + max
                            + ", progress = " + progress + ", seekbar width = "
                            + sb_stepSeeker.getWidth() + ", tv_cursor width = "
                            + tv_cursor.getWidth() + ", margin = " + margin);
                    para.setMarginStart(margin);
                    tv_cursor.setLayoutParams(para);

                    if (anchorContainer.getVisibility() != View.GONE) {
                        anchorContainer.setVisibility(GONE);
                    }
                    if (cursorContainer.getVisibility() != View.VISIBLE) {
                        cursorContainer.setVisibility(VISIBLE);
                    }
                    if ( l!= null) l.onShow();
                    break;
                case MSG_NO_COUNTING_DOWN:
                    if (msg.arg1 == 1) {// show
                        para.setMarginStart(0);
                        tv_cursor.setLayoutParams(para);
                        tv_cursor.setText(echoTime(0));

                        if (anchorContainer.getVisibility() != VISIBLE) {
                            anchorContainer.setVisibility(VISIBLE);
                        }
                        animMgr.onProgressChanged(0);

                        if (cursorContainer.getVisibility() != GONE) {
                            cursorContainer.setVisibility(GONE);
                        }
                        sb_stepSeeker.setProgressDrawable(stepTrack);
                        sb_stepSeeker.setThumb(mContext.getResources().getDrawable(R.drawable.step_seek_thumb));
                        if (LOGD) Log.d(TAG, "===============step seekbar thumb offset is " + sb_stepSeeker.getThumbOffset());
                        sb_stepSeeker.setThumbOffset(sb_stepSeeker.getThumbOffset() - step_seeker_track_startpoint);
                        sb_stepSeeker.setProgress(0);
                        if ( l!= null) l.onShow();
                    } else if (msg.arg1 == 0) {
                        if (l!=null) l.onDismiss();
                    }
                    break;
                case MSG_SET_ALARM:
                    if (LOGD) Log.d(TAG, "set alarm: " + msg.arg1);
                    setAlarm(msg.arg1);
                    break;
                }
            }
        };
    }
    @Override
    public void onProgressChanged(SeekBar seekBar, int progress,
            boolean fromUser) {
        // Log.d(TAG, "progress changed" + (fromUser ? " FROM USER " : " ")
        // + "progress is " + seekBar.getProgress());
        if (seekBar.getId() == R.id.step_seekbar) {
            if (fromUser) {
                if ( progress == 0 || progress == 25 || progress == 50 || progress == 75 )
                    sb_stepSeeker.setProgress(progress + 1);
                if ( progress == 100 )
                    sb_stepSeeker.setProgress(progress - 1);
                animMgr.onProgressChanged(progress);
                l.onTracking(OnCountDownTimerUpdateListener.TRACK_ONGOING);
            } else {

            }
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        // TODO Auto-generated method stub
        anchorContainer.setVisibility(VISIBLE);
        cursorContainer.setVisibility(GONE);
        mHandler.removeMessages(MSG_SET_ALARM);
        mHandler.removeMessages(MSG_COUNTING_DOWN);
        if (stepTrack != sb_stepSeeker.getProgressDrawable()) {
            sb_stepSeeker.setProgressDrawable(stepTrack);
            sb_stepSeeker.setThumb(mContext.getResources().getDrawable(R.drawable.step_seek_thumb));
            sb_stepSeeker.setThumbOffset(sb_stepSeeker.getThumbOffset() - step_seeker_track_startpoint);
            seekBar.setProgress(seekBar.getProgress() -1);
        }
        l.onTracking(OnCountDownTimerUpdateListener.TRACK_START);
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        // TODO Auto-generated method stub
        if (LOGD) Log.d(TAG, "stop tracking touch, progress is " + seekBar.getProgress());
        int progress = seekBar.getProgress();
        progress = roundProgress(progress);

        int min = progress2min(progress);
        Log.i(TAG, "tracking touch stoped. seek to anchor " + progress + " countdown timer " + min + " min");

        if ( progress == 0 ) {
            Message msg = mHandler.obtainMessage(MSG_NO_COUNTING_DOWN, 1//show timer
                    , 0);
            mHandler.sendMessage(msg);
        }
        Message msg = mHandler.obtainMessage(MSG_SET_ALARM, min, 0);
        mHandler.sendMessageDelayed(msg, 0);
        l.onTracking(OnCountDownTimerUpdateListener.TRACK_STOP);
    }

    private void onAlarmSet(int second) {
      Message msg = mHandler.obtainMessage(MSG_COUNTING_DOWN, second , 0);
      mHandler.sendMessage(msg);
    }

    private String echoTime(int second) {
        int h = 0, m = 0, s = 0;
        int min = second / 60;
        s = second % 60;
        if (min > 0) {
            h = min/60;//get hours
            m = min%60;//get minutes
        }
        String str = String.format("%d:%02d:%02d", h, m, s);
        return str;
    }
    private int min2progress(int min) {
        int progress = 0;
        if (min <= 0)
            return 0;

        if (min <= 120) {
            progress = min * 5 / 12;
            return progress;
        }
        if (min <= 240) {
            progress = min * 5 / 24 + 25;
            return progress;
        }
        if (min <= 480) {
            progress = min * 5 / 48 + 50;
            return progress;
        }
        return 100;
    }

    private int progress2min(int progress) {
        int min = 0;
//progress would always be >= 0 and <=100
        if (progress <= 50) {
            min = progress * 12 / 5;
            return min;
        }
        if (progress <= 75) {
            min = progress * 24 / 5 - 120;
            return min;
        }
//        if (progress <= 100) {
            min = progress * 48 / 5 - 480;
            return min;
//        }
    }

    private int roundProgress(int progress) {
        if (progress <= 12) {
            return 0;
        }
        if (progress > 12 && progress <= 37) {
            return 25;
        }
        if (progress > 37 && progress <= 62) {
            return 50;
        }
        if (progress > 62 && progress <= 87) {
            return 75;
        }
        // if (progress > 87 && progress <=100) {
        return 100;
        // }
    }

    private int progress2AnimTrigger(int progress) {
        if (progress <= 12) {
            return 0;
        }
        if (progress > 12 && progress <= 37) {
            return 1;
        }
        if (progress > 37 && progress <= 62) {
            return 2;
        }
        if (progress > 62 && progress <= 87) {
            return 3;
        }

        // if (progress > 87 && progress <=100) {
        return 4;
        // }
    }

    private void setAlarm(int fromNowMin) {
        //cancel the alarm

        mHandler.removeMessages(MSG_SET_ALARM);
        NotificationManager nm = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(19851024);

        if (fromNowMin <= 0){
            Settings.System.putLong(mContext.getContentResolver(),
                    Settings.System.MUTE_TIMEOUT, 0);
            setAlarmAt(-1);
            return;
        }

        long timeoutMs = fromNowMin * 60 * 1000;
        timeoutMs = System.currentTimeMillis() + timeoutMs;

        Settings.System.putLong(mContext.getContentResolver(),
                Settings.System.MUTE_TIMEOUT, timeoutMs);
        setAlarmAt(timeoutMs);
        onAlarmSet(fromNowMin * 60);
    }

    private void setAlarm(long fromNowMs) {
        mHandler.removeMessages(MSG_SET_ALARM);
        NotificationManager nm = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(19851024);

        if (fromNowMs <= 0){
            Settings.System.putLong(mContext.getContentResolver(),
                    Settings.System.MUTE_TIMEOUT, 0);
            setAlarmAt(-1);
            return;
        }

        long timeoutMs = System.currentTimeMillis() + fromNowMs;

        Settings.System.putLong(mContext.getContentResolver(),
                Settings.System.MUTE_TIMEOUT, timeoutMs);
        setAlarmAt(timeoutMs);

        onAlarmSet((int)fromNowMs/1000);
    }
    private void setAlarmAt(long atTimeInMs) {
        if (alarmMgr == null) {
            alarmMgr = (AlarmManager) mContext
                    .getSystemService(Context.ALARM_SERVICE);
        }
        Intent intent = new Intent(Intent.ACTION_UNMUTE_AUDIO);
        PendingIntent sender = PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);

        //we don't need this feature at present.
//        intent = new Intent(Intent.ACTION_UNMUTE_FORETELL);
//        PendingIntent foretell = PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);

        if (atTimeInMs <= 0){
            if (mPendingIntentForAlarm != null){
                alarmMgr.cancel(mPendingIntentForAlarm);
                mPendingIntentForAlarm = null;
            }
//            alarmMgr.cancel(foretell);
        } else{
            alarmMgr.setExact(AlarmManager.RTC_WAKEUP, atTimeInMs, sender);
            mPendingIntentForAlarm = sender;
//            long forTellAt = atTimeInMs - 300000;
//            if ( forTellAt >= 0)
//            alarmMgr.set(AlarmManager.RTC_WAKEUP, forTellAt, foretell);
        }
    }

    public void refresh() {
        boolean show = Settings.System.getInt(mContext.getContentResolver(),Settings.System.VOLUME_PANEL_MUTE_ENABLE, 0) == 1;
        //if it is muted
        if (show) {
            setVisibility(View.VISIBLE);
            tv_anchors[0].setText(mContext.getResources().getText(R.string.always));
            tv_anchors[1].setText(mContext.getResources().getText(R.string.one_hour));
            tv_anchors[2].setText(mContext.getResources().getText(R.string.two_hour));
            tv_anchors[3].setText(mContext.getResources().getText(R.string.four_hour));
            tv_anchors[4].setText(mContext.getResources().getText(R.string.eight_hour));
            long timeoutAt = Settings.System.getLong(
                    mContext.getContentResolver(),
                    Settings.System.MUTE_TIMEOUT, 0);
            if (timeoutAt == 0){
                //always mute
                Message msg = mHandler.obtainMessage(MSG_NO_COUNTING_DOWN, 1//show timer
                        , 0);
                mHandler.sendMessage(msg);
                return;
            }

            long currentTimeMs = System.currentTimeMillis();
            long timeout = timeoutAt - currentTimeMs;
            if (timeout <= 0){
                //Alarm went off already. Maybe the devices rebooted. It's really odd, this case should already be handled in AudioService when boot complete.
                //unmute
                Intent intent = new Intent(Intent.ACTION_UNMUTE_AUDIO);
                mContext.sendBroadcast(intent);
                setAlarm(-1);
                Message msg = mHandler.obtainMessage(MSG_NO_COUNTING_DOWN, 1//show timer
                        , 0);
                mHandler.sendMessage(msg);
            }

            //if there is a valid timer
            if (timeout > 0) {
                if (timeout > TIMEOUT_MAX_MS)
                    timeout = TIMEOUT_MAX_MS;
                setAlarm(timeout);
                }
        } else { //not muted
            setVisibility(View.INVISIBLE);
            setAlarm(-1);
            Message msg = mHandler.obtainMessage(MSG_NO_COUNTING_DOWN, 0//show timer
                    , 0);
            mHandler.sendMessage(msg);
            }
    }

    public void setOnUpdateListener(OnCountDownTimerUpdateListener listener) {
        l = listener;
    }

    private class AnimationManager {
        Context mContext;
        Animation[] pushUp = new Animation[5];
        Animation[] pushDown = new Animation[5];
        boolean[] pendingDown = new boolean[5];
        int indexUp = -1;
        int preUpIndex = -1;

        public AnimationManager(Context c) {
            mContext = c;
            for(int i = 0; i < 5; i++){
            pendingDown[i] = false;
            pushUp[i] = AnimationUtils.loadAnimation(mContext, R.anim.tv_push_up);
            pushDown[i] = AnimationUtils.loadAnimation(mContext, R.anim.tv_push_down);
            pushUp[i].setFillAfter(true);
            pushUp[i].setAnimationListener(new AnimationListener(i, true));
            pushDown[i].setAnimationListener(new AnimationListener(i, false));
            }
        }


        public void onProgressChanged(int progress) {
            int i = progress2AnimTrigger(progress);
            if (indexUp != i) {
                if (indexUp >= 0) {
                        tv_anchors[indexUp].startAnimation(pushDown[indexUp]);
                        tv_anchors[indexUp].setTextColor(Color.parseColor(COLOR_UNSELECT));
                }
                tv_anchors[i].startAnimation(pushUp[i]);
                preUpIndex = indexUp;
                indexUp = i;
            }
        }
    }

    private class AnimationListener implements Animation.AnimationListener{
        private int index;
        private boolean isAnimUp;
        public AnimationListener(int i, boolean isUp) {
            index = i;
            isAnimUp = isUp;
        }
        @Override
        public void onAnimationEnd(Animation animation) {
            // TODO Auto-generated method stub
            if (isAnimUp) {
                tv_anchors[index].setTextColor(Color.parseColor(COLOR_SELECT ));
            }
            else {
                tv_anchors[index].setTextColor(Color.parseColor(COLOR_UNSELECT ));
            }
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
            // TODO Auto-generated method stub

        }

        @Override
        public void onAnimationStart(Animation animation) {
            // TODO Auto-generated method stub

        }
    }

    public interface OnCountDownTimerUpdateListener {
        public static final int TRACK_START = 1;
        public static final int TRACK_STOP = 2;
        public static final int TRACK_ONGOING = 3;
        public void onShow();
        public void onDismiss();
        public void onTracking(int status);
    }
}
