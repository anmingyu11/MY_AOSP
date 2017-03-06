
package android.widget;

import com.android.internal.R;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.CheckBox;
import android.os.Handler;
import android.os.Message;
import android.graphics.drawable.BitmapDrawable;

/**
 * @hide
 */
public class SmartisanSwitchEx extends CheckBox {

    private final float VELOCITY = 350;
    private final float EXTENDED_OFFSET_Y = 2;

    private final Resources mResources;

    private Paint mPaint;
    private ViewParent mParent;
    private Bitmap mBottom;
    private Bitmap mCurBtnPic;
    private Bitmap mBtnNormal;
    private Bitmap mFrame;
    private Bitmap mMask;
    private RectF mSaveLayerRectF;
    private PorterDuffXfermode mXfermode;

    private float mFirstDownY;
    private float mFirstDownX;
    private float mRealPos;
    private float mBtnPos;
    private float mBtnOnPos;
    private float mBtnOffPos;
    private float mMaskWidth;
    private float mMaskHeight;
    private float mBtnWidth;
    private float mBtnInitPos;
    private float mVelocity;
    private float mExtendOffsetY;
    private float mAnimationPosition;

    private float mAnimatedVelocity;
    private int mClickTimeout;
    private int mTouchSlop;
    private final int MAX_ALPHA = 255;
    private int mAlpha = MAX_ALPHA;
    private boolean mChecked = false;
    private boolean mBroadcasting;
    private boolean mTurningOn;
    private boolean mAnimating;

    private PerformClick mPerformClick;
    private OnCheckedChangeListener mOnCheckedChangeListener;
    private OnCheckedChangeListener mOnCheckedChangeWidgetListener;

    public SmartisanSwitchEx(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.checkboxStyle);
    }

    public SmartisanSwitchEx(Context context) {
        this(context, null);
    }

    public SmartisanSwitchEx(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mResources=context.getResources();
        initView(context);
    }

    private void initView(Context context) {
        mPaint = new Paint();
        mPaint.setColor(Color.WHITE);
        // get viewConfiguration
        mClickTimeout = ViewConfiguration.getPressedStateDuration()
                + ViewConfiguration.getTapTimeout();
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mBottom = ((BitmapDrawable)mResources.getDrawable(R.drawable.smartisan_switch_ex_bottom)).getBitmap();
        mBtnNormal =((BitmapDrawable)mResources.getDrawable(R.drawable.smartisan_switch_ex_unpressed)).getBitmap();
        mFrame = ((BitmapDrawable)mResources.getDrawable(R.drawable.smartisan_switch_ex_frame)).getBitmap();
        mMask = ((BitmapDrawable)mResources.getDrawable(R.drawable.smartisan_switch_ex_mask)).getBitmap();
        mCurBtnPic = mBtnNormal;
        mBtnWidth = mBtnNormal.getWidth();
        mMaskWidth = mMask.getWidth();
        mMaskHeight = mMask.getHeight();
        mBtnOnPos = mBtnWidth / 2;
        mBtnOffPos = mMaskWidth - mBtnWidth / 2;
        mBtnPos = mChecked ? mBtnOnPos : mBtnOffPos;
        mRealPos = getRealPos(mBtnPos);
        final float density = getResources().getDisplayMetrics().density;
        mVelocity = (int) (VELOCITY * density + 0.5f);
        mExtendOffsetY = (int) (EXTENDED_OFFSET_Y * density + 0.5f);
        mSaveLayerRectF = new RectF(0, mExtendOffsetY, mMask.getWidth(), mMask.getHeight()
                + mExtendOffsetY);
        mXfermode = new PorterDuffXfermode(PorterDuff.Mode.SRC_IN);
    }

    public void setBottomDrawable(int resId) {
        mBottom = ((BitmapDrawable)mResources.getDrawable(resId)).getBitmap();
        invalidate();
    }

    @Override
    public void setEnabled(boolean enabled) {
        mAlpha = enabled ? MAX_ALPHA : MAX_ALPHA / 2;
        super.setEnabled(enabled);
    }

    public boolean isChecked() {
        return mChecked;
    }

    public void toggle() {
        setChecked(!mChecked);
    }

    private void setCheckedDelayed(final boolean checked) {
        this.postDelayed(new Runnable() {
            @Override
            public void run() {
                setChecked(checked);
            }
        }, 10);
    }

    public void setChecked(boolean checked) {
        if (mChecked != checked) {
            mChecked = checked;
            mBtnPos = checked ? mBtnOnPos : mBtnOffPos;
            mRealPos = getRealPos(mBtnPos);
            invalidate();
            // Avoid infinite recursions if setChecked() is called from a
            // listener
            if (mBroadcasting) {
                return;
            }
            mBroadcasting = true;
            if (mOnCheckedChangeListener != null) {
                mOnCheckedChangeListener.onCheckedChanged(SmartisanSwitchEx.this, mChecked);
            }
            if (mOnCheckedChangeWidgetListener != null) {
                mOnCheckedChangeWidgetListener.onCheckedChanged(SmartisanSwitchEx.this, mChecked);
            }
            mBroadcasting = false;
        }
    }

    /**
     * Register a callback to be invoked when the checked state of this button
     * changes.
     * 
     * @param listener the callback to call on checked state change
     */
    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        mOnCheckedChangeListener = listener;
    }

    /**
     * Register a callback to be invoked when the checked state of this button
     * changes. This callback is used for internal purpose only.
     * 
     * @param listener the callback to call on checked state change
     * @hide
     */
    void setOnCheckedChangeWidgetListener(OnCheckedChangeListener listener) {
        mOnCheckedChangeWidgetListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();
        float x = event.getX();
        float y = event.getY();
        float deltaX = Math.abs(x - mFirstDownX);
        float deltaY = Math.abs(y - mFirstDownY);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                attemptClaimDrag();
                mFirstDownX = x;
                mFirstDownY = y;
                mBtnInitPos = mChecked ? mBtnOnPos : mBtnOffPos;
                break;
            case MotionEvent.ACTION_MOVE:
                float time = event.getEventTime() - event.getDownTime();
                mBtnPos = mBtnInitPos + event.getX() - mFirstDownX;
                if (mBtnPos >= mBtnOnPos) {
                    mBtnPos = mBtnOnPos;
                }
                if (mBtnPos <= mBtnOffPos) {
                    mBtnPos = mBtnOffPos;
                }
                mTurningOn = mBtnPos > (mBtnOnPos - mBtnOffPos) / 2 + mBtnOffPos;
                mRealPos = getRealPos(mBtnPos);
                break;
            case MotionEvent.ACTION_UP:
                time = event.getEventTime() - event.getDownTime();
                if (deltaY < mTouchSlop && deltaX < mTouchSlop && time < mClickTimeout) {
                    if (mPerformClick == null) {
                        mPerformClick = new PerformClick();
                    }
                    if (!post(mPerformClick)) {
                        performClick();
                    }
                } else {
                    startAnimation(mTurningOn);
                }
                break;
        }
        invalidate();
        return isEnabled();
    }

    private final class PerformClick implements Runnable {
        public void run() {
            performClick();
        }
    }

    @Override
    public boolean performClick() {
        startAnimation(!mChecked);
        return true;
    }

    /**
     * Tries to claim the user's drag motion, and requests disallowing any
     * ancestors from stealing events in the drag.
     */
    private void attemptClaimDrag() {
        mParent = getParent();
        if (mParent != null) {
            mParent.requestDisallowInterceptTouchEvent(true);
        }
    }

    private float getRealPos(float btnPos) {
        return btnPos - mBtnWidth / 2;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.saveLayerAlpha(mSaveLayerRectF, mAlpha, Canvas.MATRIX_SAVE_FLAG
                | Canvas.CLIP_SAVE_FLAG | Canvas.HAS_ALPHA_LAYER_SAVE_FLAG
                | Canvas.FULL_COLOR_LAYER_SAVE_FLAG | Canvas.CLIP_TO_LAYER_SAVE_FLAG);
        canvas.drawBitmap(mMask, 0, mExtendOffsetY, mPaint);
        mPaint.setXfermode(mXfermode);
        canvas.drawBitmap(mBottom, mRealPos, mExtendOffsetY, mPaint);
        mPaint.setXfermode(null);
        canvas.drawBitmap(mFrame, 0, mExtendOffsetY, mPaint);
        canvas.drawBitmap(mCurBtnPic, mRealPos, mExtendOffsetY, mPaint);
        canvas.restore();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension((int) mMaskWidth, (int) (mMaskHeight + 2 * mExtendOffsetY));
    }

    private void startAnimation(boolean turnOn) {
        mAnimating = true;
        mAnimatedVelocity = turnOn ? mVelocity : -mVelocity;
        mAnimationPosition = mBtnPos;
        new SwitchAnimation().run();
    }

    private void stopAnimation() {
        mAnimating = false;
    }

    private final class SwitchAnimation implements Runnable {
        @Override
        public void run() {
            if (!mAnimating) {
                return;
            }
            doAnimation();
            FrameAnimationController.requestAnimationFrame(this);
        }
    }

    private void doAnimation() {
        mAnimationPosition += mAnimatedVelocity * FrameAnimationController.ANIMATION_FRAME_DURATION
                / 1000;
        if (mAnimationPosition <= mBtnOffPos) {
            stopAnimation();
            mAnimationPosition = mBtnOffPos;
            setCheckedDelayed(false);
        } else if (mAnimationPosition >= mBtnOnPos) {
            stopAnimation();
            mAnimationPosition = mBtnOnPos;
            setCheckedDelayed(true);
        }
        moveView(mAnimationPosition);
    }

    private void moveView(float position) {
        mBtnPos = position;
        mRealPos = getRealPos(mBtnPos);
        invalidate();
    }

    public static class FrameAnimationController {

        private static final int MSG_ANIMATE = 1000;
        public static final int ANIMATION_FRAME_DURATION = 1000 / 60;
        private static final Handler mHandler = new AnimationHandler();

        private FrameAnimationController() {
            throw new UnsupportedOperationException();
        }

        public static void requestAnimationFrame(Runnable runnable) {
            Message message = new Message();
            message.what = MSG_ANIMATE;
            message.obj = runnable;
            mHandler.sendMessageDelayed(message, ANIMATION_FRAME_DURATION);
        }

        public static void requestFrameDelay(Runnable runnable, long delay) {
            Message message = new Message();
            message.what = MSG_ANIMATE;
            message.obj = runnable;
            mHandler.sendMessageDelayed(message, delay);
        }

        private static class AnimationHandler extends Handler {
            public void handleMessage(Message m) {
                switch (m.what) {
                    case MSG_ANIMATE:
                        if (m.obj != null) {
                            ((Runnable) m.obj).run();
                        }
                        break;
                }
            }
        }
    }

}
