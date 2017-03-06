package android.widget;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.WindowManager;

/**
 * A helper class used to detect gesture which rotate current window
 * 
 * @hide
 */
public class WindowRotationGestureDetector {
    private static final String TAG = "RotateWindowGestureDetector";
    private static final boolean DEBUG = false;
    private static final int GESTURE_TIME_OUT = 300;
    private static final int DIRECTION_UNKNOWN = 0;
    private static final int DIRECTION_CLOCK_WISE = 1;
    private static final int DIRECTION_COUNTER_CLOCK_WISE = 2;

    private Context mContext;
    private OnWindowRotationGestureDeteced mListener;
    private WindowManager mWM;
    private Point mScreenSize = new Point();
    private float mRotationGestureLength;
    private float mRotationGestureStartLength;
    private boolean mMaybeRotationGesture;
    private long mRotationGestureStartTime;
    private int mOrientation;

    private int m1stPointerId = -1;
    private int m2ndPointerId = -1;
    private boolean mRotationGesture1stSatisfied;
    private boolean mRotationGesture2ndSatisfied;
    private float m1StartX;
    private float m1StartY;
    private float m2StartX;
    private float m2StartY;
    private float m1EndX;
    private float m1EndY;
    private float m2EndX;
    private float m2EndY;
    private int m1stPointerDirection;
    private int m2ndPointerDirection;
    private int mDetectedRotation;
    private boolean mEnabled;

    public static interface OnWindowRotationGestureDeteced {
        public void onRotationGestureDectced(boolean clockwise);
    };

    static private int computeRotation(int rotation1, int rotation2) {
        if (rotation1 == rotation2) {
            return rotation2;
        } else if (rotation1 == DIRECTION_UNKNOWN) {
            return rotation2;
        } else {
            return DIRECTION_UNKNOWN;
        }
    }

    private int computeRotation(float startX, float startY, float endX, float endY) {
        final float centerX = mScreenSize.x / 2;
        final float centerY = mScreenSize.y / 2;

        double inputLineAngle = Math.atan2(endY - startY, endX - startX);
        double centerToStartAngle = Math.atan2(startY - centerY, startX - centerX);

        if (inputLineAngle == centerToStartAngle
                || Math.abs(inputLineAngle - centerToStartAngle) == Math.PI) {
            // A float equal to another, it is almost impossible.
            return DIRECTION_UNKNOWN;
        }

        if (centerToStartAngle <= 0) {
            if ((inputLineAngle <= 0 && inputLineAngle > centerToStartAngle)
                    || (inputLineAngle >= 0 && inputLineAngle - centerToStartAngle < Math.PI)) {
                return DIRECTION_CLOCK_WISE;
            } else {
                return DIRECTION_COUNTER_CLOCK_WISE;
            }
        } else {
            if ((inputLineAngle > centerToStartAngle)
                    || (inputLineAngle < centerToStartAngle - Math.PI)) {
                return DIRECTION_CLOCK_WISE;
            } else {
                return DIRECTION_COUNTER_CLOCK_WISE;
            }
        }

    }

    public WindowRotationGestureDetector(Context context, OnWindowRotationGestureDeteced listener) {
        mContext = context;
        mListener = listener;
        mWM = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        final Display display = mWM.getDefaultDisplay();
        display.getSize(mScreenSize);
        mRotationGestureLength = Math.min(mScreenSize.x, mScreenSize.y) / 3;
        mRotationGestureStartLength = mRotationGestureLength / 10;
        mOrientation = mContext.getResources().getConfiguration().orientation;
    }

    private void endRotationGestureDetect() {
        mMaybeRotationGesture = false;
        mRotationGestureStartTime = 0;
    }

    private void initRotationGestureDetect() {
        m1stPointerId = -1;
        m2ndPointerId = -1;
        mRotationGesture1stSatisfied = false;
        mRotationGesture2ndSatisfied = false;
        mDetectedRotation = DIRECTION_UNKNOWN;
    }

    public boolean interpretTouchEvent(MotionEvent ev) {
        if (!mEnabled) {
            return false;
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                initRotationGestureDetect();
                m1StartX = ev.getX();
                m1stPointerId = ev.getPointerId(0);
                m1StartY = ev.getY();

                if (DEBUG) {
                    Log.d(TAG, "ACTION_DOWN m1stPointerId=" + m1stPointerId
                            + " m1stPointerStartX=" + m1StartX
                            + " m1stPointerStartY=" + m1StartY);
                }
            }
            break;

            case MotionEvent.ACTION_POINTER_DOWN: {
                if (m2ndPointerId != -1) {
                    mMaybeRotationGesture = false;
                } else {
                    m2ndPointerId = ev.getActionIndex();
                    m2StartX = ev.getX(m2ndPointerId);
                    m2StartY = ev.getY(m2ndPointerId);
                    mMaybeRotationGesture = true;

                    if (DEBUG) {
                        Log.d(TAG, "ACTION_POINTER_DOWN m2ndPointerId=" + m2ndPointerId
                                + " m2ndPointerStartX=" + m2StartX
                                + " m2ndPointerStartY=" + m2StartY);
                    }
                }
            }
            break;

            case MotionEvent.ACTION_MOVE: {

                if (mDetectedRotation != DIRECTION_UNKNOWN)
                    return true;

                if (!mMaybeRotationGesture) {
                    return false;
                }

                for (int i = 0; i < ev.getPointerCount(); i++) {
                    int pointerId = ev.getPointerId(i);
                    final float x = ev.getX(i);
                    final float y = ev.getY(i);

                    if (!mRotationGesture1stSatisfied && pointerId == m1stPointerId) {
                        final float distanceX = x - m1StartX;
                        final float distanceY = y - m1StartY;
                        final double distance = Math.hypot(distanceX, distanceY);

                        if (mRotationGestureStartTime == 0 &&
                                Math.abs(distance) > mRotationGestureStartLength) {
                            mRotationGestureStartTime = SystemClock.uptimeMillis();
                        }

                        if (Math.abs(distance) > mRotationGestureLength) {
                            final long now = SystemClock.uptimeMillis();

                            if (now - mRotationGestureStartTime > GESTURE_TIME_OUT) {
                                endRotationGestureDetect();
                            } else {
                                m1EndX = x;
                                m1EndY = y;
                                m1stPointerDirection = computeRotation(m1StartX, m1StartY,
                                        m1EndX, m1EndY);
                                mRotationGesture1stSatisfied = true;

                                if (DEBUG) {
                                    Log.d(TAG, "1st pointer"
                                            + " endX=" + x
                                            + " endY=" + y
                                            + " m1stPointerDirection=" + m1stPointerDirection);
                                }
                            }
                        }
                    } else if (!mRotationGesture2ndSatisfied && pointerId == m2ndPointerId) {
                        final float distanceX = x - m2StartX;
                        final float distanceY = y - m2StartY;
                        final double distance = Math.hypot(distanceX, distanceY);

                        if (mRotationGestureStartTime == 0 &&
                                Math.abs(distance) > mRotationGestureStartLength) {
                            mRotationGestureStartTime = SystemClock.uptimeMillis();
                        }

                        if (Math.abs(distance) > mRotationGestureLength) {
                            final long now = SystemClock.uptimeMillis();

                            if (now - mRotationGestureStartTime > GESTURE_TIME_OUT) {
                                endRotationGestureDetect();
                            } else {
                                m2EndX = x;
                                m2EndY = y;
                                m2ndPointerDirection = computeRotation(m2StartX, m2StartY,
                                        m2EndX, m2EndY);
                                mRotationGesture2ndSatisfied = true;

                                if (DEBUG) {
                                    Log.d(TAG, "2nd pointer"
                                            + " endX=" + x
                                            + " endY=" + y
                                            + " m2ndPointerDirection=" + m2ndPointerDirection);
                                }
                            }
                        }
                    }
                }

                if (mRotationGesture1stSatisfied && mRotationGesture2ndSatisfied) {
                    mDetectedRotation = computeRotation(m1stPointerDirection,
                            m2ndPointerDirection);
                    endRotationGestureDetect();

                    final double startToStartPointAngle;
                    startToStartPointAngle = Math.atan2(m1StartY - m2StartY, m1StartX - m2StartX);
                    final double lineOf1stPointerAngle;
                    lineOf1stPointerAngle = Math.atan2(m1EndY - m1StartY, m1EndX - m1StartX);
                    final double lineOf2ndPointerAngle;
                    lineOf2ndPointerAngle = Math.atan2(m2EndY - m2StartY, m2EndX - m2StartX);
                    final double minus1 = lineOf2ndPointerAngle - lineOf1stPointerAngle;
                    final double minus2 = startToStartPointAngle - lineOf1stPointerAngle;


                    if (DEBUG) {
                        Log.d(TAG, "startToStartPointAngle=" + startToStartPointAngle
                                + " lineOf1stPointerAngle=" + lineOf1stPointerAngle
                                + " lineOf2ndPointerAngle=" + lineOf2ndPointerAngle
                                + " minus1=" + minus1
                                + " minus2=" + minus2);
                    }

                    // If minus1 is less than Pi/2, it means 2 finger slide to
                    // the same direction, so it is not a twist gesture.
                    // If minus2 is about Pi or 0, the gesture is "pinch"
                    if (Math.abs(minus1) < Math.PI / 2
                            || (Math.abs(minus2) > 2.6 && Math.abs(minus2) < 3.6)
                            || Math.abs(minus2) < 1) {
                        mDetectedRotation = DIRECTION_UNKNOWN;
                    }

                    if (mDetectedRotation == DIRECTION_CLOCK_WISE) {
                        mListener.onRotationGestureDectced(true);
                        return true;
                    } else if (mDetectedRotation == DIRECTION_COUNTER_CLOCK_WISE) {
                        mListener.onRotationGestureDectced(false);
                        return true;
                    }
                }
            }
            break;

            case MotionEvent.ACTION_UP: {
                if (mDetectedRotation != DIRECTION_UNKNOWN) {
                    ev.setAction(MotionEvent.ACTION_CANCEL);
                }

                endRotationGestureDetect();

                if (DEBUG) {
                    Log.d(TAG, "ACTION_UP: " + ev.getPointerId(0)
                            + ", pointerCount=" + ev.getPointerCount());
                }
            }
            break;

            case MotionEvent.ACTION_POINTER_UP: {
                if (mDetectedRotation != DIRECTION_UNKNOWN) {
                    ev.setAction(MotionEvent.ACTION_CANCEL);
                }

                if (DEBUG) {
                    Log.d(TAG, "ACTION_POINTER_UP: " + ev.getActionIndex()
                            + ", pointerCount=" + ev.getPointerCount());
                }

                if (mDetectedRotation != DIRECTION_UNKNOWN)
                  return true;

            }
            break;
        }

        return false;
    }

    public void onConfigurationChanged(Configuration newConfig) {
        if (newConfig.orientation != mOrientation) {
            mOrientation = newConfig.orientation;
            final Display display = mWM.getDefaultDisplay();
            display.getSize(mScreenSize);
        }
    }

    public void enable() {
        mEnabled = true;
    }

    public void disable() {
        mEnabled = false;
    }
}