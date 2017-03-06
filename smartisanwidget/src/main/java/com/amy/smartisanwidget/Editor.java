/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.widget;

import android.content.UndoManager;
import android.content.UndoOperation;
import android.content.UndoOwner;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.InputFilter;
import android.text.SpannableString;

import com.android.internal.app.ThemeUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.widget.EditableInputConnection;

import android.R;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.ClipData;
import android.content.ClipData.Item;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.ExtractEditText;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Browser;
import android.provider.Settings;
import android.text.DynamicLayout;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.ParcelableSpan;
import android.text.Selection;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.text.method.KeyListener;
import android.text.method.MetaKeyKeyListener;
import android.text.method.MovementMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.method.WordIterator;
import android.text.style.EasyEditSpan;
import android.text.style.SuggestionRangeSpan;
import android.text.style.SuggestionSpan;
import android.text.style.TextAppearanceSpan;
import android.text.style.URLSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ActionMode;
import android.view.ActionMode.Callback;
import android.view.DisplayList;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HardwareCanvas;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.DragShadowBuilder;
import android.view.View.OnClickListener;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageView.ScaleType;
import android.widget.TextView.Drawables;
import android.widget.TextView.OnEditorActionListener;

import java.text.BreakIterator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;

import android.content.res.Resources;

/**
 * Helper class used by TextView to handle editable text views.
 *
 * @hide
 */
public class Editor {
    private static final String TAG = "Editor";
    static final boolean DEBUG_UNDO = false;

    static final int BLINK = 500;
    private final int ARROW_HORIZONTAL_OFFSET;
    private static final float[] TEMP_POSITION = new float[2];
    private static int DRAG_SHADOW_MAX_TEXT_LENGTH = 20;
    private static final float MOVE_HORIZONTAL_MAX_SLOPE = 1.0f;
    private static final long INTERCEPT_TOUCH_MIN_TIME = 80;

    UndoManager mUndoManager;
    UndoOwner mUndoOwner;
    InputFilter mUndoInputFilter;

    // Cursor Controllers.
    InsertionPointCursorController mInsertionPointCursorController;
    SelectionModifierCursorController mSelectionModifierCursorController;
    ActionMode mSelectionActionMode;
    boolean mInsertionControllerEnabled;
    boolean mSelectionControllerEnabled;
    boolean mEnableController = true;
    int mHiddenContextMenuItem; // Actions hidden in action mode menu
    boolean mIsSmartisanUrlInputView;
    boolean mHideHandleWhenSelectAll;

    // Used to highlight a word when it is corrected by the IME
    CorrectionHighlighter mCorrectionHighlighter;

    InputContentType mInputContentType;
    InputMethodState mInputMethodState;

    DisplayList[] mTextDisplayLists;

    boolean mFrozenWithFocus;
    boolean mSelectionMoved;
    boolean mTouchFocusSelected;

    KeyListener mKeyListener;
    int mInputType = EditorInfo.TYPE_NULL;

    boolean mDiscardNextActionUp;
    boolean mIgnoreActionUpEvent;
    boolean mInterceptTouchEvent;

    long mShowCursor;
    Blink mBlink;

    boolean mCursorVisible = true;
    boolean mSelectAllOnFocus;
    boolean mTextIsSelectable;

    CharSequence mError;
    boolean mErrorWasChanged;
    ErrorPopup mErrorPopup;

    /**
     * This flag is set if the TextView tries to display an error before it
     * is attached to the window (so its position is still unknown).
     * It causes the error to be shown later, when onAttachedToWindow()
     * is called.
     */
    boolean mShowErrorAfterAttach;

    boolean mInBatchEditControllers;
    boolean mShowSoftInputOnFocus = true;
    boolean mPreserveDetachedSelection;
    boolean mTemporaryDetach;

    SuggestionsPopupWindow mSuggestionsPopupWindow;
    SuggestionRangeSpan mSuggestionRangeSpan;
    Runnable mShowSuggestionRunnable;

    QuickSnippetPopupWindow mQuickSnippetPopupWindow;

    final Drawable[] mCursorDrawable = new Drawable[2];
    int mCursorCount; // Current number of used mCursorDrawable: 0 (resource=0), 1 or 2 (split)

    private Drawable mSelectHandleLeft;
    private Drawable mSelectHandleRight;
    private Drawable mSelectHandleCenter;

    // Global listener that detects changes in the global position of the TextView
    private PositionListener mPositionListener;

    float mLastDownPositionX, mLastDownPositionY;
    private long mLastTouchDownTime;
    private boolean mInContinuousMove;
    Callback mCustomSelectionActionModeCallback;

    // Set when this TextView gained focus with some text selected. Will start selection mode.
    boolean mCreatedWithASelection;

    // The span controller helps monitoring the changes to which the Editor needs to react:
    // - EasyEditSpans, for which we have some UI to display on attach and on hide
    // - SelectionSpans, for which we need to call updateSelection if an IME is attached
    private SpanController mSpanController;

    WordIterator mWordIterator;
    SpellChecker mSpellChecker;

    private Rect mTempRect;

    private TextView mTextView;
    private Context mUiContext;

    Editor(TextView textView) {
        mTextView = textView;
        Resources res = mTextView.getContext().getResources();
        ARROW_HORIZONTAL_OFFSET = res
                .getDimensionPixelSize(com.android.internal.R.dimen.arrow_horizontal_offset);
    }

    private Context getUiContext() {
        if (mUiContext == null) {
            mUiContext = ThemeUtils.createUiContext(mTextView.getContext());
            if (mUiContext != null) {
                mUiContext.setTheme(com.android.internal.R.style.Theme_DeviceDefault_Light);
            } else {
                mUiContext = mTextView.getContext();
            }
        }
        return mUiContext;
    }

    void onAttachedToWindow() {
        if (mShowErrorAfterAttach) {
            showError();
            mShowErrorAfterAttach = false;
        }
        mTemporaryDetach = false;

        final ViewTreeObserver observer = mTextView.getViewTreeObserver();
        // No need to create the controller.
        // The get method will add the listener on controller creation.
        if (mInsertionPointCursorController != null) {
            observer.addOnTouchModeChangeListener(mInsertionPointCursorController);
        }
        if (mSelectionModifierCursorController != null) {
            mSelectionModifierCursorController.resetTouchOffsets();
            observer.addOnTouchModeChangeListener(mSelectionModifierCursorController);
        }
        updateSpellCheckSpans(0, mTextView.getText().length(),
                true /* create the spell checker if needed */);

        if (mTextView.hasTransientState() &&
                mTextView.getSelectionStart() != mTextView.getSelectionEnd()) {
            // Since transient state is reference counted make sure it stays matched
            // with our own calls to it for managing selection.
            // The action mode callback will set this back again when/if the action mode starts.
            mTextView.setHasTransientState(false);

            // We had an active selection from before, start the selection mode.
            startSelectionActionMode();
        }
    }

    void onDetachedFromWindow() {
        if (mError != null) {
            hideError();
        }

        if (mBlink != null) {
            mBlink.removeCallbacks(mBlink);
        }

        if (mInsertionPointCursorController != null) {
            mInsertionPointCursorController.onDetached();
        }

        if (mSelectionModifierCursorController != null) {
            mSelectionModifierCursorController.onDetached();
        }

        if (mShowSuggestionRunnable != null) {
            mTextView.removeCallbacks(mShowSuggestionRunnable);
        }

        invalidateTextDisplayList();

        if (mSpellChecker != null) {
            mSpellChecker.closeSession();
            // Forces the creation of a new SpellChecker next time this window is created.
            // Will handle the cases where the settings has been changed in the meantime.
            mSpellChecker = null;
        }

        mPreserveDetachedSelection = true;
        hideControllers();
        mPreserveDetachedSelection = false;
        mTemporaryDetach = false;
    }

    private void showError() {
        if (mTextView.getWindowToken() == null) {
            mShowErrorAfterAttach = true;
            return;
        }

        if (mErrorPopup == null) {
            LayoutInflater inflater = LayoutInflater.from(mTextView.getContext());
            final TextView err = (TextView) inflater.inflate(
                    com.android.internal.R.layout.textview_hint, null);

            final float scale = mTextView.getResources().getDisplayMetrics().density;
            mErrorPopup = new ErrorPopup(err, (int)(200 * scale + 0.5f), (int)(50 * scale + 0.5f));
            mErrorPopup.setFocusable(false);
            // The user is entering text, so the input method is needed.  We
            // don't want the popup to be displayed on top of it.
            mErrorPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        }

        TextView tv = (TextView) mErrorPopup.getContentView();
        chooseSize(mErrorPopup, mError, tv);
        tv.setText(mError);

        mErrorPopup.showAsDropDown(mTextView, getErrorX(), getErrorY());
        mErrorPopup.fixDirection(mErrorPopup.isAboveAnchor());
    }

    public void setError(CharSequence error, Drawable icon) {
        mError = TextUtils.stringOrSpannedString(error);
        mErrorWasChanged = true;

        if (mError == null) {
            setErrorIcon(null);
            if (mErrorPopup != null) {
                if (mErrorPopup.isShowing()) {
                    mErrorPopup.dismiss();
                }

                mErrorPopup = null;
            }

        } else {
            setErrorIcon(icon);
            if (mTextView.isFocused()) {
                showError();
            }
        }
    }

    private void setErrorIcon(Drawable icon) {
        Drawables dr = mTextView.mDrawables;
        if (dr == null) {
            mTextView.mDrawables = dr = new Drawables(mTextView.getContext());
        }
        dr.setErrorDrawable(icon, mTextView);

        mTextView.resetResolvedDrawables();
        mTextView.invalidate();
        mTextView.requestLayout();
    }

    private void hideError() {
        if (mErrorPopup != null) {
            if (mErrorPopup.isShowing()) {
                mErrorPopup.dismiss();
            }
        }

        mShowErrorAfterAttach = false;
    }

    /**
     * Returns the X offset to make the pointy top of the error point
     * at the middle of the error icon.
     */
    private int getErrorX() {
        /*
         * The "25" is the distance between the point and the right edge
         * of the background
         */
        final float scale = mTextView.getResources().getDisplayMetrics().density;

        final Drawables dr = mTextView.mDrawables;

        final int layoutDirection = mTextView.getLayoutDirection();
        int errorX;
        int offset;
        switch (layoutDirection) {
            default:
            case View.LAYOUT_DIRECTION_LTR:
                offset = - (dr != null ? dr.mDrawableSizeRight : 0) / 2 + (int) (25 * scale + 0.5f);
                errorX = mTextView.getWidth() - mErrorPopup.getWidth() -
                        mTextView.getPaddingRight() + offset;
                break;
            case View.LAYOUT_DIRECTION_RTL:
                offset = (dr != null ? dr.mDrawableSizeLeft : 0) / 2 - (int) (25 * scale + 0.5f);
                errorX = mTextView.getPaddingLeft() + offset;
                break;
        }
        return errorX;
    }

    /**
     * Returns the Y offset to make the pointy top of the error point
     * at the bottom of the error icon.
     */
    private int getErrorY() {
        /*
         * Compound, not extended, because the icon is not clipped
         * if the text height is smaller.
         */
        final int compoundPaddingTop = mTextView.getCompoundPaddingTop();
        int vspace = mTextView.getBottom() - mTextView.getTop() -
                mTextView.getCompoundPaddingBottom() - compoundPaddingTop;

        final Drawables dr = mTextView.mDrawables;

        final int layoutDirection = mTextView.getLayoutDirection();
        int height;
        switch (layoutDirection) {
            default:
            case View.LAYOUT_DIRECTION_LTR:
                height = (dr != null ? dr.mDrawableHeightRight : 0);
                break;
            case View.LAYOUT_DIRECTION_RTL:
                height = (dr != null ? dr.mDrawableHeightLeft : 0);
                break;
        }

        int icontop = compoundPaddingTop + (vspace - height) / 2;

        /*
         * The "2" is the distance between the point and the top edge
         * of the background.
         */
        final float scale = mTextView.getResources().getDisplayMetrics().density;
        return icontop + height - mTextView.getHeight() - (int) (2 * scale + 0.5f);
    }

    void createInputContentTypeIfNeeded() {
        if (mInputContentType == null) {
            mInputContentType = new InputContentType();
        }
    }

    void createInputMethodStateIfNeeded() {
        if (mInputMethodState == null) {
            mInputMethodState = new InputMethodState();
        }
    }

    boolean isCursorVisible() {
        // The default value is true, even when there is no associated Editor
        return mCursorVisible && mTextView.isTextEditable();
    }

    void prepareCursorControllers() {
        boolean windowSupportsHandles = false;

        ViewGroup.LayoutParams params = mTextView.getRootView().getLayoutParams();
        if (params instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams windowParams = (WindowManager.LayoutParams) params;
            windowSupportsHandles = windowParams.type < WindowManager.LayoutParams.FIRST_SUB_WINDOW
                    || windowParams.type > WindowManager.LayoutParams.LAST_SUB_WINDOW;
        }

        boolean enabled = mEnableController && windowSupportsHandles && mTextView.getLayout() != null;
        mInsertionControllerEnabled = enabled && isCursorVisible();
        mSelectionControllerEnabled = enabled && mTextView.textCanBeSelected();

        if (!mInsertionControllerEnabled) {
            hideInsertionPointCursorController();
            if (mInsertionPointCursorController != null) {
                mInsertionPointCursorController.onDetached();
                mInsertionPointCursorController = null;
            }
        }

        if (!mSelectionControllerEnabled) {
            stopSelectionActionMode();
            if (mSelectionModifierCursorController != null) {
                mSelectionModifierCursorController.onDetached();
                mSelectionModifierCursorController = null;
            }
        }
    }

    private void hideInsertionPointCursorController() {
        if (mInsertionPointCursorController != null) {
            mInsertionPointCursorController.hide();
        }
    }

    private void hideSelectionModifierCursorController() {
        if (mSelectionModifierCursorController != null) {
            mSelectionModifierCursorController.hide();
        }
    }

    /**
     * Hides the insertion controller and stops text selection mode, hiding the selection controller
     */
    void hideControllers() {
        hideCursorControllers();
        hideSpanControllers();
    }

    private void hideSpanControllers() {
        if (mSpanController != null) {
            mSpanController.hide();
        }
    }

    private void hideCursorControllers() {
        hideInsertionPointCursorController();
        hideSuggestionAndSelectionControllers();
    }

    private void hideSuggestionAndSelectionControllers() {
        if (mSuggestionsPopupWindow != null && !mSuggestionsPopupWindow.isShowingUp()) {
            // Should be done before hide insertion point controller since it triggers a show of it
            mSuggestionsPopupWindow.hide();
        }
        if (mQuickSnippetPopupWindow != null && !mQuickSnippetPopupWindow.isShowingUp()) {
            // Should be done before hide insertion point controller since it triggers a show of it
            mQuickSnippetPopupWindow.hide();
        }
        hideSelectionModifierCursorController();
        stopSelectionActionMode();
    }

    /**
     * Create new SpellCheckSpans on the modified region.
     */
    private void updateSpellCheckSpans(int start, int end, boolean createSpellChecker) {
        // Remove spans whose adjacent characters are text not punctuation
        mTextView.removeAdjacentSuggestionSpans(start);
        mTextView.removeAdjacentSuggestionSpans(end);

        if (mTextView.isTextEditable() && mTextView.isSuggestionsEnabled() &&
                !(mTextView instanceof ExtractEditText)) {
            if (mSpellChecker == null && createSpellChecker) {
                mSpellChecker = new SpellChecker(mTextView);
            }
            if (mSpellChecker != null) {
                mSpellChecker.spellCheck(start, end);
            }
        }
    }

    void onScreenStateChanged(int screenState) {
        switch (screenState) {
            case View.SCREEN_STATE_ON:
                resumeBlink();
                break;
            case View.SCREEN_STATE_OFF:
                suspendBlink();
                break;
        }
    }

    private void suspendBlink() {
        if (mBlink != null) {
            mBlink.cancel();
        }
    }

    private void resumeBlink() {
        if (mBlink != null) {
            mBlink.uncancel();
            makeBlink();
        }
    }

    void adjustInputType(boolean password, boolean passwordInputType,
            boolean webPasswordInputType, boolean numberPasswordInputType) {
        // mInputType has been set from inputType, possibly modified by mInputMethod.
        // Specialize mInputType to [web]password if we have a text class and the original input
        // type was a password.
        if ((mInputType & EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_TEXT) {
            if (password || passwordInputType) {
                mInputType = (mInputType & ~(EditorInfo.TYPE_MASK_VARIATION))
                        | EditorInfo.TYPE_TEXT_VARIATION_PASSWORD;
            }
            if (webPasswordInputType) {
                mInputType = (mInputType & ~(EditorInfo.TYPE_MASK_VARIATION))
                        | EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD;
            }
        } else if ((mInputType & EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_NUMBER) {
            if (numberPasswordInputType) {
                mInputType = (mInputType & ~(EditorInfo.TYPE_MASK_VARIATION))
                        | EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD;
            }
        }
    }

    private void chooseSize(PopupWindow pop, CharSequence text, TextView tv) {
        int wid = tv.getPaddingLeft() + tv.getPaddingRight();
        int ht = tv.getPaddingTop() + tv.getPaddingBottom();

        int defaultWidthInPixels = mTextView.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.textview_error_popup_default_width);
        Layout l = new StaticLayout(text, tv.getPaint(), defaultWidthInPixels,
                                    Layout.Alignment.ALIGN_NORMAL, 1, 0, true);
        float max = 0;
        for (int i = 0; i < l.getLineCount(); i++) {
            max = Math.max(max, l.getLineWidth(i));
        }

        /*
         * Now set the popup size to be big enough for the text plus the border capped
         * to DEFAULT_MAX_POPUP_WIDTH
         */
        pop.setWidth(wid + (int) Math.ceil(max));
        pop.setHeight(ht + l.getHeight());
    }

    void setFrame() {
        if (mErrorPopup != null) {
            TextView tv = (TextView) mErrorPopup.getContentView();
            chooseSize(mErrorPopup, mError, tv);
            mErrorPopup.update(mTextView, getErrorX(), getErrorY(),
                    mErrorPopup.getWidth(), mErrorPopup.getHeight());
        }
    }

    /**
     * Unlike {@link TextView#textCanBeSelected()}, this method is based on the <i>current</i> state
     * of the TextView. textCanBeSelected() has to be true (this is one of the conditions to have
     * a selection controller (see {@link #prepareCursorControllers()}), but this is not sufficient.
     */
    private boolean canSelectText() {
        return hasSelectionController() && mTextView.getText().length() != 0;
    }

    /**
     * It would be better to rely on the input type for everything. A password inputType should have
     * a password transformation. We should hence use isPasswordInputType instead of this method.
     *
     * We should:
     * - Call setInputType in setKeyListener instead of changing the input type directly (which
     * would install the correct transformation).
     * - Refuse the installation of a non-password transformation in setTransformation if the input
     * type is password.
     *
     * However, this is like this for legacy reasons and we cannot break existing apps. This method
     * is useful since it matches what the user can see (obfuscated text or not).
     *
     * @return true if the current transformation method is of the password type.
     */
    private boolean hasPasswordTransformationMethod() {
        return mTextView.getTransformationMethod() instanceof PasswordTransformationMethod;
    }

    private int mSelectionStart, mSelectionEnd;
    private boolean mShouldSelectAll;

    private boolean canSelectCurrentWord() {
        if (!canSelectText()) {
            return false;
        }

        if (hasPasswordTransformationMethod()) {
            // Always select all on a password field.
            // Cut/copy menu entries are not available for passwords, but being
            // able to select all
            // is however useful to delete or paste to replace the entire
            // content.
            mShouldSelectAll = true;
            return true;
        }

        int inputType = mTextView.getInputType();
        int klass = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;

        // Specific text field types: select the entire text for these
        if (klass == InputType.TYPE_CLASS_NUMBER ||
                klass == InputType.TYPE_CLASS_PHONE ||
                klass == InputType.TYPE_CLASS_DATETIME ||
                (variation == InputType.TYPE_TEXT_VARIATION_URI && !mIsSmartisanUrlInputView) ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_FILTER) {
            mShouldSelectAll = true;
            return true;
        }

        long lastTouchOffsets = getLastTouchOffsets();
        final int minOffset = TextUtils.unpackRangeStartFromLong(lastTouchOffsets);
        final int maxOffset = TextUtils.unpackRangeEndFromLong(lastTouchOffsets);

        // Safety check in case standard touch event handling has been bypassed
        if (minOffset < 0 || minOffset > mTextView.getText().length()) return false;
        if (maxOffset < 0 || maxOffset > mTextView.getText().length()) return false;

        if (minOffset == maxOffset && minOffset == mTextView.getText().length()) {
            mSelectionStart = TextUtils.getOffsetBefore(mTextView.getText(),
                    maxOffset);
            mSelectionEnd = maxOffset;
            return true;
        }

        // If a URLSpan (web address, email, phone...) is found at that
        // position, select it.
        URLSpan[] urlSpans = ((Spanned) mTextView.getText()).
                getSpans(minOffset, maxOffset, URLSpan.class);
        if (urlSpans.length >= 1) {
            URLSpan urlSpan = urlSpans[0];
            mSelectionStart = ((Spanned) mTextView.getText()).getSpanStart(urlSpan);
            mSelectionEnd = ((Spanned) mTextView.getText()).getSpanEnd(urlSpan);
        } else {
            final WordIterator wordIterator = getWordIterator();
            wordIterator.setCharSequence(mTextView.getText(), minOffset, maxOffset);

            mSelectionStart = wordIterator.getBeginning(minOffset);
            mSelectionEnd = wordIterator.getEnd(maxOffset);

            if (mSelectionStart == BreakIterator.DONE || mSelectionEnd == BreakIterator.DONE ||
                    mSelectionStart == mSelectionEnd) {
                // Possible when the word iterator does not properly handle the
                // text's language
                long range = getCharRange(minOffset);
                mSelectionStart = TextUtils.unpackRangeStartFromLong(range);
                mSelectionEnd = TextUtils.unpackRangeEndFromLong(range);
            }
        }

        return mSelectionEnd > mSelectionStart;
    }

    /**
     * Adjusts selection to the word under last touch offset.
     * Return true if the operation was successfully performed.
     */
    private boolean selectCurrentWord() {
        if (!canSelectCurrentWord()) {
            return false;
        }

        mHideHandleWhenSelectAll = false;
        if (mShouldSelectAll) {
            return mTextView.selectAllText();
        } else {
            Selection.setSelection((Spannable) mTextView.getText(), mSelectionStart, mSelectionEnd);
            return true;
        }
    }

    void onLocaleChanged() {
        // Will be re-created on demand in getWordIterator with the proper new locale
        mWordIterator = null;
    }

    /**
     * @hide
     */
    public WordIterator getWordIterator() {
        if (mWordIterator == null) {
            mWordIterator = new WordIterator(mTextView.getTextServicesLocale());
        }
        return mWordIterator;
    }

    private long getCharRange(int offset) {
        final int textLength = mTextView.getText().length();
        if (offset + 1 < textLength) {
            final char currentChar = mTextView.getText().charAt(offset);
            final char nextChar = mTextView.getText().charAt(offset + 1);
            if (Character.isSurrogatePair(currentChar, nextChar)) {
                return TextUtils.packRangeInLong(offset,  offset + 2);
            }
        }
        if (offset < textLength) {
            return TextUtils.packRangeInLong(offset,  offset + 1);
        }
        if (offset - 2 >= 0) {
            final char previousChar = mTextView.getText().charAt(offset - 1);
            final char previousPreviousChar = mTextView.getText().charAt(offset - 2);
            if (Character.isSurrogatePair(previousPreviousChar, previousChar)) {
                return TextUtils.packRangeInLong(offset - 2,  offset);
            }
        }
        if (offset - 1 >= 0) {
            return TextUtils.packRangeInLong(offset - 1,  offset);
        }
        return TextUtils.packRangeInLong(offset,  offset);
    }

    private boolean touchPositionIsInSelection() {
        int selectionStart = mTextView.getSelectionStart();
        int selectionEnd = mTextView.getSelectionEnd();

        if (selectionStart == selectionEnd) {
            return false;
        }

        if (selectionStart > selectionEnd) {
            int tmp = selectionStart;
            selectionStart = selectionEnd;
            selectionEnd = tmp;
            Selection.setSelection((Spannable) mTextView.getText(), selectionStart, selectionEnd);
        }

        SelectionModifierCursorController selectionController = getSelectionController();
        int minOffset = selectionController.getMinTouchOffset();
        int maxOffset = selectionController.getMaxTouchOffset();

        return ((minOffset >= selectionStart) && (maxOffset < selectionEnd));
    }

    private PositionListener getPositionListener() {
        if (mPositionListener == null) {
            mPositionListener = new PositionListener();
        }
        return mPositionListener;
    }

    private interface TextViewPositionListener {
        public void updatePosition(int parentPositionX, int parentPositionY,
                boolean parentPositionChanged, boolean parentScrolled);
    }

    private boolean isPositionVisible(int positionX, int positionY) {
        synchronized (TEMP_POSITION) {
            final float[] position = TEMP_POSITION;
            position[0] = positionX;
            position[1] = positionY;
            View view = mTextView;

            while (view != null) {
                if (view != mTextView) {
                    // Local scroll is already taken into account in positionX/Y
                    position[0] -= view.getScrollX();
                    position[1] -= view.getScrollY();
                }

                if (position[0] < 0 || position[1] < 0 ||
                        position[0] > view.getWidth() || position[1] > view.getHeight()) {
                    return false;
                }

                if (!view.getMatrix().isIdentity()) {
                    view.getMatrix().mapPoints(position);
                }

                position[0] += view.getLeft();
                position[1] += view.getTop();

                final ViewParent parent = view.getParent();
                if (parent instanceof View) {
                    view = (View) parent;
                } else {
                    // We've reached the ViewRoot, stop iterating
                    view = null;
                }
            }
        }

        // We've been able to walk up the view hierarchy and the position was never clipped
        return true;
    }

    private boolean isOffsetVisible(int offset) {
        Layout layout = mTextView.getLayout();
        if (layout == null) return false;

        final int line = layout.getLineForOffset(offset);
        final int lineCenter = (layout.getLineBottom(line) + layout.getLineTop(line)) / 2;
        final int primaryHorizontal = (int) layout.getPrimaryHorizontal(offset);
        return isPositionVisible(primaryHorizontal + mTextView.viewportToContentHorizontalOffset(),
                lineCenter + mTextView.viewportToContentVerticalOffset());
    }

    /** Returns true if the screen coordinates position (x,y) corresponds to a character displayed
     * in the view. Returns false when the position is in the empty space of left/right of text.
     */
    private boolean isPositionOnText(float x, float y) {
        Layout layout = mTextView.getLayout();
        if (layout == null) return false;

        final int line = mTextView.getLineAtCoordinate(y);
        x = mTextView.convertToLocalHorizontalCoordinate(x);

        if (x < layout.getLineLeft(line)) return false;
        if (x > layout.getLineRight(line)) return false;
        return true;
    }

    public boolean performLongClick(boolean handled) {
        // Long press in empty space moves cursor and shows the Paste affordance if available.
        if (!handled && (mTextView.isFocused() || !isPositionOnText(mLastDownPositionX, mLastDownPositionY))
                && mInsertionControllerEnabled) {
            if (getSelectionController() != null) {
                getSelectionController().hide();
            }
            final int offset = mTextView.getOffsetForPosition(mLastDownPositionX,
                    mLastDownPositionY);
            stopSelectionActionMode();
            Selection.setSelection((Spannable) mTextView.getText(), offset);
            if (mTextView.isFocused()) {
                getInsertionController().showWithActionPopup();
            }
            handled = true;
        }
        if (!handled && mSelectionActionMode != null) {
            if (touchPositionIsInSelection()) {
                // Start a drag
                final int start = mTextView.getSelectionStart();
                final int end = mTextView.getSelectionEnd();
                CharSequence selectedText = mTextView.getTransformedText(start, end);
                ClipData data = ClipData.newPlainText(null, selectedText);
                DragLocalState localState = new DragLocalState(mTextView, start, end);
                mTextView.startDrag(data, getTextThumbnailBuilder(selectedText), localState, 0);
                stopSelectionActionMode();
            } else {
                if (hasSelectionController()) {
                    getSelectionController().hide();
                    selectCurrentWord();
                    getSelectionController().show();
                }
            }
            handled = true;
        }

        // Start a new selection
        if (!handled) {
            handled = selectCurrentWord();
            if (handled) getSelectionController().show();
        }

        return handled;
    }

    private long getLastTouchOffsets() {
        SelectionModifierCursorController selectionController =
                getSelectionController();
        final int minOffset = selectionController.getMinTouchOffset();
        final int maxOffset = selectionController.getMaxTouchOffset();
        return TextUtils.packRangeInLong(minOffset, maxOffset);
    }

    void onFocusChanged(boolean focused, int direction) {
        mShowCursor = SystemClock.uptimeMillis();
        ensureEndedBatchEdit();

        if (focused) {
            int selStart = mTextView.getSelectionStart();
            int selEnd = mTextView.getSelectionEnd();

            // SelectAllOnFocus fields are highlighted and not selected. Do not start text selection
            // mode for these, unless there was a specific selection already started.
            final boolean isFocusHighlighted = mSelectAllOnFocus && selStart == 0 &&
                    selEnd == mTextView.getText().length();

            mCreatedWithASelection = mFrozenWithFocus && mTextView.hasSelection() &&
                    !isFocusHighlighted;

            if (!mFrozenWithFocus || (selStart < 0 || selEnd < 0)) {
                // If a tap was used to give focus to that view, move cursor at tap position.
                // Has to be done before onTakeFocus, which can be overloaded.
                final int lastTapPosition = getLastTapPosition();
                if (lastTapPosition >= 0) {
                    Selection.setSelection((Spannable) mTextView.getText(), lastTapPosition);
                }

                // Note this may have to be moved out of the Editor class
                MovementMethod mMovement = mTextView.getMovementMethod();
                if (mMovement != null) {
                    mMovement.onTakeFocus(mTextView, (Spannable) mTextView.getText(), direction);
                }

                // The DecorView does not have focus when the 'Done' ExtractEditText button is
                // pressed. Since it is the ViewAncestor's mView, it requests focus before
                // ExtractEditText clears focus, which gives focus to the ExtractEditText.
                // This special case ensure that we keep current selection in that case.
                // It would be better to know why the DecorView does not have focus at that time.
                if (((mTextView instanceof ExtractEditText) || mSelectionMoved) &&
                        selStart >= 0 && selEnd >= 0) {
                    /*
                     * Someone intentionally set the selection, so let them
                     * do whatever it is that they wanted to do instead of
                     * the default on-focus behavior.  We reset the selection
                     * here instead of just skipping the onTakeFocus() call
                     * because some movement methods do something other than
                     * just setting the selection in theirs and we still
                     * need to go through that path.
                     */
                    Selection.setSelection((Spannable) mTextView.getText(), selStart, selEnd);
                }

                if (mSelectAllOnFocus) {
                    mTextView.selectAllText();
                }

                mTouchFocusSelected = true;
            }

            mFrozenWithFocus = false;
            mSelectionMoved = false;

            if (mError != null) {
                showError();
            }

            makeBlink();
        } else {
            if (mError != null) {
                hideError();
            }
            // Don't leave us in the middle of a batch edit.
            mTextView.onEndBatchEdit();

            if (mTextView instanceof ExtractEditText) {
                // terminateTextSelectionMode removes selection, which we want to keep when
                // ExtractEditText goes out of focus.
                final int selStart = mTextView.getSelectionStart();
                final int selEnd = mTextView.getSelectionEnd();
                hideControllers();
                Selection.setSelection((Spannable) mTextView.getText(), selStart, selEnd);
            } else {
                if (mTemporaryDetach) mPreserveDetachedSelection = true;
                hideControllers();
                if (mTemporaryDetach) mPreserveDetachedSelection = false;
                downgradeEasyCorrectionSpans();
            }

            // No need to create the controller
            if (mSelectionModifierCursorController != null) {
                mSelectionModifierCursorController.resetTouchOffsets();
            }

        }
    }

    /**
     * Downgrades to simple suggestions all the easy correction spans that are not a spell check
     * span.
     */
    private void downgradeEasyCorrectionSpans() {
        CharSequence text = mTextView.getText();
        if (text instanceof Spannable) {
            Spannable spannable = (Spannable) text;
            SuggestionSpan[] suggestionSpans = spannable.getSpans(0,
                    spannable.length(), SuggestionSpan.class);
            for (int i = 0; i < suggestionSpans.length; i++) {
                int flags = suggestionSpans[i].getFlags();
                if ((flags & SuggestionSpan.FLAG_EASY_CORRECT) != 0
                        && (flags & SuggestionSpan.FLAG_MISSPELLED) == 0) {
                    flags &= ~SuggestionSpan.FLAG_EASY_CORRECT;
                    suggestionSpans[i].setFlags(flags);
                }
            }
        }
    }

    void sendOnTextChanged(int start, int after) {
        updateSpellCheckSpans(start, start + after, false);

        // Hide the controllers as soon as text is modified (typing, procedural...)
        // We do not hide the span controllers, since they can be added when a new text is
        // inserted into the text view (voice IME).
        hideCursorControllers();
    }

    private int getLastTapPosition() {
        // No need to create the controller at that point, no last tap position saved
        if (mSelectionModifierCursorController != null) {
            int lastTapPosition = mSelectionModifierCursorController.getMinTouchOffset();
            if (lastTapPosition >= 0) {
                // Safety check, should not be possible.
                if (lastTapPosition > mTextView.getText().length()) {
                    lastTapPosition = mTextView.getText().length();
                }
                return lastTapPosition;
            }
        }

        return -1;
    }

    void onWindowFocusChanged(boolean hasWindowFocus) {
        if (hasWindowFocus) {
            if (mBlink != null) {
                mBlink.uncancel();
            }
            makeBlink();
        } else {
            if (mBlink != null) {
                mBlink.cancel();
            }
            if (mInputContentType != null) {
                mInputContentType.enterDown = false;
            }
            // Order matters! Must be done before onParentLostFocus to rely on isShowingUp
            hideControllers();
            if (mSuggestionsPopupWindow != null) {
                mSuggestionsPopupWindow.onParentLostFocus();
            }
            if (mQuickSnippetPopupWindow != null) {
                mQuickSnippetPopupWindow.onParentLostFocus();
            }

            // Don't leave us in the middle of a batch edit. Same as in onFocusChanged
            ensureEndedBatchEdit();
        }
    }

    void onTouchEvent(MotionEvent event) {
        if (hasSelectionController()) {
            getSelectionController().onTouchEvent(event);
        }

        if (mShowSuggestionRunnable != null) {
            mTextView.removeCallbacks(mShowSuggestionRunnable);
            mShowSuggestionRunnable = null;
        }

        int action = event.getActionMasked();
        switch(action) {
            case MotionEvent.ACTION_DOWN:
                mLastDownPositionX = event.getX();
                mLastDownPositionY = event.getY();

                // Reset this state; it will be re-set if super.onTouchEvent
                // causes focus to move to the view.
                mTouchFocusSelected = false;
                mIgnoreActionUpEvent = false;
                mInterceptTouchEvent = false;
                mInContinuousMove = true;
                mLastTouchDownTime = SystemClock.uptimeMillis();
                if (mInsertionPointCursorController != null) {
                    mInsertionPointCursorController.saveShowingStatus();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if(mTextView.getText().length() > 0) {
                    int selStart = mTextView.getSelectionStart();
                    if(selStart >= 0 && selStart == mTextView.getSelectionEnd()) {
                        float positionX = event.getX();
                        float positionY = event.getY();
                        if (mInterceptTouchEvent ||
                                (SystemClock.uptimeMillis() - mLastTouchDownTime > INTERCEPT_TOUCH_MIN_TIME
                                        && checkTouchMoveSlope(positionX, positionY))) {
                            final int offset = mTextView.getOffsetForPosition(positionX, positionY);
                            if (offset >= 0) {
                                mInterceptTouchEvent = true;
                                if(offset != selStart) {
                                    Selection.setSelection((Spannable) mTextView.getText(), offset);
                                    hideCursorControllers();
                                }
                                break;
                            }
                        }
                    }
                }
                mInterceptTouchEvent = false;
                break;
            case MotionEvent.ACTION_UP:
                if (mIgnoreActionUpEvent) {
                    if (mInsertionPointCursorController != null) {
                        if (mInterceptTouchEvent) {
                            mInsertionPointCursorController.restoreShowingStatus();
                        } else {
                            mInsertionPointCursorController.resetShowingStatus();
                        }
                    }
                }
                mInterceptTouchEvent = false;
            default:
                mInterceptTouchEvent = false;
                mInContinuousMove = false;
                break;
        }
    }

    private boolean checkTouchMoveSlope(float positionX, float positionY) {
        if(mInContinuousMove && positionX != mLastDownPositionX) {
            float slope = (positionY - mLastDownPositionY) / (positionX - mLastDownPositionX);
            return Math.abs(slope) < MOVE_HORIZONTAL_MAX_SLOPE;
        }
        return false;
    }

    public void beginBatchEdit() {
        mInBatchEditControllers = true;
        final InputMethodState ims = mInputMethodState;
        if (ims != null) {
            int nesting = ++ims.mBatchEditNesting;
            if (nesting == 1) {
                ims.mCursorChanged = false;
                ims.mChangedDelta = 0;
                if (ims.mContentChanged) {
                    // We already have a pending change from somewhere else,
                    // so turn this into a full update.
                    ims.mChangedStart = 0;
                    ims.mChangedEnd = mTextView.getText().length();
                } else {
                    ims.mChangedStart = EXTRACT_UNKNOWN;
                    ims.mChangedEnd = EXTRACT_UNKNOWN;
                    ims.mContentChanged = false;
                }
                mTextView.onBeginBatchEdit();
            }
        }
    }

    public void endBatchEdit() {
        mInBatchEditControllers = false;
        final InputMethodState ims = mInputMethodState;
        if (ims != null) {
            int nesting = --ims.mBatchEditNesting;
            if (nesting == 0) {
                finishBatchEdit(ims);
            }
        }
    }

    void ensureEndedBatchEdit() {
        final InputMethodState ims = mInputMethodState;
        if (ims != null && ims.mBatchEditNesting != 0) {
            ims.mBatchEditNesting = 0;
            finishBatchEdit(ims);
        }
    }

    void finishBatchEdit(final InputMethodState ims) {
        mTextView.onEndBatchEdit();

        if (ims.mContentChanged || ims.mSelectionModeChanged) {
            mTextView.updateAfterEdit();
            reportExtractedText();
        } else if (ims.mCursorChanged) {
            // Cheesy way to get us to report the current cursor location.
            mTextView.invalidateCursor();
        }
        // sendUpdateSelection knows to avoid sending if the selection did
        // not actually change.
        sendUpdateSelection();
    }

    static final int EXTRACT_NOTHING = -2;
    static final int EXTRACT_UNKNOWN = -1;

    boolean extractText(ExtractedTextRequest request, ExtractedText outText) {
        return extractTextInternal(request, EXTRACT_UNKNOWN, EXTRACT_UNKNOWN,
                EXTRACT_UNKNOWN, outText);
    }

    private boolean extractTextInternal(ExtractedTextRequest request,
            int partialStartOffset, int partialEndOffset, int delta,
            ExtractedText outText) {
        final CharSequence content = mTextView.getText();
        if (content != null) {
            if (partialStartOffset != EXTRACT_NOTHING) {
                final int N = content.length();
                if (partialStartOffset < 0) {
                    outText.partialStartOffset = outText.partialEndOffset = -1;
                    partialStartOffset = 0;
                    partialEndOffset = N;
                } else {
                    // Now use the delta to determine the actual amount of text
                    // we need.
                    partialEndOffset += delta;
                    // Adjust offsets to ensure we contain full spans.
                    if (content instanceof Spanned) {
                        Spanned spanned = (Spanned)content;
                        Object[] spans = spanned.getSpans(partialStartOffset,
                                partialEndOffset, ParcelableSpan.class);
                        int i = spans.length;
                        while (i > 0) {
                            i--;
                            int j = spanned.getSpanStart(spans[i]);
                            if (j < partialStartOffset) partialStartOffset = j;
                            j = spanned.getSpanEnd(spans[i]);
                            if (j > partialEndOffset) partialEndOffset = j;
                        }
                    }
                    outText.partialStartOffset = partialStartOffset;
                    outText.partialEndOffset = partialEndOffset - delta;

                    if (partialStartOffset > N) {
                        partialStartOffset = N;
                    } else if (partialStartOffset < 0) {
                        partialStartOffset = 0;
                    }
                    if (partialEndOffset > N) {
                        partialEndOffset = N;
                    } else if (partialEndOffset < 0) {
                        partialEndOffset = 0;
                    }
                }
                if ((request.flags&InputConnection.GET_TEXT_WITH_STYLES) != 0) {
                    outText.text = content.subSequence(partialStartOffset,
                            partialEndOffset);
                } else {
                    outText.text = TextUtils.substring(content, partialStartOffset,
                            partialEndOffset);
                }
            } else {
                outText.partialStartOffset = 0;
                outText.partialEndOffset = 0;
                outText.text = "";
            }
            outText.flags = 0;
            if (MetaKeyKeyListener.getMetaState(content, MetaKeyKeyListener.META_SELECTING) != 0) {
                outText.flags |= ExtractedText.FLAG_SELECTING;
            }
            if (mTextView.isSingleLine()) {
                outText.flags |= ExtractedText.FLAG_SINGLE_LINE;
            }
            outText.startOffset = 0;
            outText.selectionStart = mTextView.getSelectionStart();
            outText.selectionEnd = mTextView.getSelectionEnd();
            return true;
        }
        return false;
    }

    boolean reportExtractedText() {
        final Editor.InputMethodState ims = mInputMethodState;
        if (ims != null) {
            final boolean contentChanged = ims.mContentChanged;
            if (contentChanged || ims.mSelectionModeChanged) {
                ims.mContentChanged = false;
                ims.mSelectionModeChanged = false;
                final ExtractedTextRequest req = ims.mExtractedTextRequest;
                if (req != null) {
                    InputMethodManager imm = InputMethodManager.peekInstance();
                    if (imm != null) {
                        if (TextView.DEBUG_EXTRACT) Log.v(TextView.LOG_TAG,
                                "Retrieving extracted start=" + ims.mChangedStart +
                                " end=" + ims.mChangedEnd +
                                " delta=" + ims.mChangedDelta);
                        if (ims.mChangedStart < 0 && !contentChanged) {
                            ims.mChangedStart = EXTRACT_NOTHING;
                        }
                        if (extractTextInternal(req, ims.mChangedStart, ims.mChangedEnd,
                                ims.mChangedDelta, ims.mExtractedText)) {
                            if (TextView.DEBUG_EXTRACT) Log.v(TextView.LOG_TAG,
                                    "Reporting extracted start=" +
                                    ims.mExtractedText.partialStartOffset +
                                    " end=" + ims.mExtractedText.partialEndOffset +
                                    ": " + ims.mExtractedText.text);

                            imm.updateExtractedText(mTextView, req.token, ims.mExtractedText);
                            ims.mChangedStart = EXTRACT_UNKNOWN;
                            ims.mChangedEnd = EXTRACT_UNKNOWN;
                            ims.mChangedDelta = 0;
                            ims.mContentChanged = false;
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void sendUpdateSelection() {
        if (null != mInputMethodState && mInputMethodState.mBatchEditNesting <= 0) {
            final InputMethodManager imm = InputMethodManager.peekInstance();
            if (null != imm) {
                final int selectionStart = mTextView.getSelectionStart();
                final int selectionEnd = mTextView.getSelectionEnd();
                int candStart = -1;
                int candEnd = -1;
                if (mTextView.getText() instanceof Spannable) {
                    final Spannable sp = (Spannable) mTextView.getText();
                    candStart = EditableInputConnection.getComposingSpanStart(sp);
                    candEnd = EditableInputConnection.getComposingSpanEnd(sp);
                }
                // InputMethodManager#updateSelection skips sending the message if
                // none of the parameters have changed since the last time we called it.
                imm.updateSelection(mTextView,
                        selectionStart, selectionEnd, candStart, candEnd);
            }
        }
    }

    void onDraw(Canvas canvas, Layout layout, Path highlight, Paint highlightPaint,
            int cursorOffsetVertical) {
        final int selectionStart = mTextView.getSelectionStart();
        final int selectionEnd = mTextView.getSelectionEnd();

        final InputMethodState ims = mInputMethodState;
        if (ims != null && ims.mBatchEditNesting == 0) {
            InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null) {
                if (imm.isActive(mTextView)) {
                    boolean reported = false;
                    if (ims.mContentChanged || ims.mSelectionModeChanged) {
                        // We are in extract mode and the content has changed
                        // in some way... just report complete new text to the
                        // input method.
                        reported = reportExtractedText();
                    }
                }

                if (imm.isWatchingCursor(mTextView) && highlight != null) {
                    highlight.computeBounds(ims.mTmpRectF, true);
                    ims.mTmpOffset[0] = ims.mTmpOffset[1] = 0;

                    canvas.getMatrix().mapPoints(ims.mTmpOffset);
                    ims.mTmpRectF.offset(ims.mTmpOffset[0], ims.mTmpOffset[1]);

                    ims.mTmpRectF.offset(0, cursorOffsetVertical);

                    ims.mCursorRectInWindow.set((int)(ims.mTmpRectF.left + 0.5),
                            (int)(ims.mTmpRectF.top + 0.5),
                            (int)(ims.mTmpRectF.right + 0.5),
                            (int)(ims.mTmpRectF.bottom + 0.5));

                    imm.updateCursor(mTextView,
                            ims.mCursorRectInWindow.left, ims.mCursorRectInWindow.top,
                            ims.mCursorRectInWindow.right, ims.mCursorRectInWindow.bottom);
                }
            }
        }

        if (mCorrectionHighlighter != null) {
            mCorrectionHighlighter.draw(canvas, cursorOffsetVertical);
        }

        if (highlight != null && selectionStart == selectionEnd && mCursorCount > 0) {
            drawCursor(canvas, cursorOffsetVertical);
            // Rely on the drawable entirely, do not draw the cursor line.
            // Has to be done after the IMM related code above which relies on the highlight.
            highlight = null;
        }

        if (mTextView.canHaveDisplayList() && canvas.isHardwareAccelerated()) {
            drawHardwareAccelerated(canvas, layout, highlight, highlightPaint,
                    cursorOffsetVertical);
        } else {
            layout.draw(canvas, highlight, highlightPaint, cursorOffsetVertical);
        }
    }

    private void drawHardwareAccelerated(Canvas canvas, Layout layout, Path highlight,
            Paint highlightPaint, int cursorOffsetVertical) {
        final long lineRange = layout.getLineRangeForDraw(canvas);
        int firstLine = TextUtils.unpackRangeStartFromLong(lineRange);
        int lastLine = TextUtils.unpackRangeEndFromLong(lineRange);
        if (lastLine < 0) return;

        layout.drawBackground(canvas, highlight, highlightPaint, cursorOffsetVertical,
                firstLine, lastLine);

        if (layout instanceof DynamicLayout) {
            if (mTextDisplayLists == null) {
                mTextDisplayLists = new DisplayList[ArrayUtils.idealObjectArraySize(0)];
            }

            DynamicLayout dynamicLayout = (DynamicLayout) layout;
            int[] blockEndLines = dynamicLayout.getBlockEndLines();
            int[] blockIndices = dynamicLayout.getBlockIndices();
            final int numberOfBlocks = dynamicLayout.getNumberOfBlocks();
            final int indexFirstChangedBlock = dynamicLayout.getIndexFirstChangedBlock();

            int endOfPreviousBlock = -1;
            int searchStartIndex = 0;
            for (int i = 0; i < numberOfBlocks; i++) {
                int blockEndLine = blockEndLines[i];
                int blockIndex = blockIndices[i];

                final boolean blockIsInvalid = blockIndex == DynamicLayout.INVALID_BLOCK_INDEX;
                if (blockIsInvalid) {
                    blockIndex = getAvailableDisplayListIndex(blockIndices, numberOfBlocks,
                            searchStartIndex);
                    // Note how dynamic layout's internal block indices get updated from Editor
                    blockIndices[i] = blockIndex;
                    searchStartIndex = blockIndex + 1;
                }

                DisplayList blockDisplayList = mTextDisplayLists[blockIndex];
                if (blockDisplayList == null) {
                    blockDisplayList = mTextDisplayLists[blockIndex] =
                            mTextView.getHardwareRenderer().createDisplayList("Text " + blockIndex);
                } else {
                    if (blockIsInvalid) blockDisplayList.clear();
                }

                final boolean blockDisplayListIsInvalid = !blockDisplayList.isValid();
                if (i >= indexFirstChangedBlock || blockDisplayListIsInvalid) {
                    final int blockBeginLine = endOfPreviousBlock + 1;
                    final int top = layout.getLineTop(blockBeginLine);
                    final int bottom = layout.getLineBottom(blockEndLine);
                    int left = 0;
                    int right = mTextView.getWidth();
                    if (mTextView.getHorizontallyScrolling()) {
                        float min = Float.MAX_VALUE;
                        float max = Float.MIN_VALUE;
                        for (int line = blockBeginLine; line <= blockEndLine; line++) {
                            min = Math.min(min, layout.getLineLeft(line));
                            max = Math.max(max, layout.getLineRight(line));
                        }
                        left = (int) min;
                        right = (int) (max + 0.5f);
                    }

                    // Rebuild display list if it is invalid
                    if (blockDisplayListIsInvalid) {
                        final HardwareCanvas hardwareCanvas = blockDisplayList.start(
                                right - left, bottom - top);
                        try {
                            // drawText is always relative to TextView's origin, this translation
                            // brings this range of text back to the top left corner of the viewport
                            hardwareCanvas.translate(-left, -top);
                            layout.drawText(hardwareCanvas, blockBeginLine, blockEndLine);
                            // No need to untranslate, previous context is popped after
                            // drawDisplayList
                        } finally {
                            blockDisplayList.end();
                            // Same as drawDisplayList below, handled by our TextView's parent
                            blockDisplayList.setClipToBounds(false);
                        }
                    }

                    // Valid disply list whose index is >= indexFirstChangedBlock
                    // only needs to update its drawing location.
                    blockDisplayList.setLeftTopRightBottom(left, top, right, bottom);
                }

                ((HardwareCanvas) canvas).drawDisplayList(blockDisplayList, null,
                        0 /* no child clipping, our TextView parent enforces it */);

                endOfPreviousBlock = blockEndLine;
            }

            dynamicLayout.setIndexFirstChangedBlock(numberOfBlocks);
        } else {
            // Boring layout is used for empty and hint text
            layout.drawText(canvas, firstLine, lastLine);
        }
    }

    private int getAvailableDisplayListIndex(int[] blockIndices, int numberOfBlocks,
            int searchStartIndex) {
        int length = mTextDisplayLists.length;
        for (int i = searchStartIndex; i < length; i++) {
            boolean blockIndexFound = false;
            for (int j = 0; j < numberOfBlocks; j++) {
                if (blockIndices[j] == i) {
                    blockIndexFound = true;
                    break;
                }
            }
            if (blockIndexFound) continue;
            return i;
        }

        // No available index found, the pool has to grow
        int newSize = ArrayUtils.idealIntArraySize(length + 1);
        DisplayList[] displayLists = new DisplayList[newSize];
        System.arraycopy(mTextDisplayLists, 0, displayLists, 0, length);
        mTextDisplayLists = displayLists;
        return length;
    }

    private void drawCursor(Canvas canvas, int cursorOffsetVertical) {
        final boolean translate = cursorOffsetVertical != 0;
        if (translate) canvas.translate(0, cursorOffsetVertical);
        for (int i = 0; i < mCursorCount; i++) {
            mCursorDrawable[i].draw(canvas);
        }
        if (translate) canvas.translate(0, -cursorOffsetVertical);
    }

    /**
     * Invalidates all the sub-display lists that overlap the specified character range
     */
    void invalidateTextDisplayList(Layout layout, int start, int end) {
        if (mTextDisplayLists != null && layout instanceof DynamicLayout) {
            final int firstLine = layout.getLineForOffset(start);
            final int lastLine = layout.getLineForOffset(end);

            DynamicLayout dynamicLayout = (DynamicLayout) layout;
            int[] blockEndLines = dynamicLayout.getBlockEndLines();
            int[] blockIndices = dynamicLayout.getBlockIndices();
            final int numberOfBlocks = dynamicLayout.getNumberOfBlocks();

            int i = 0;
            // Skip the blocks before firstLine
            while (i < numberOfBlocks) {
                if (blockEndLines[i] >= firstLine) break;
                i++;
            }

            // Invalidate all subsequent blocks until lastLine is passed
            while (i < numberOfBlocks) {
                final int blockIndex = blockIndices[i];
                if (blockIndex != DynamicLayout.INVALID_BLOCK_INDEX) {
                    mTextDisplayLists[blockIndex].clear();
                }
                if (blockEndLines[i] >= lastLine) break;
                i++;
            }
        }
    }

    void invalidateTextDisplayList() {
        if (mTextDisplayLists != null) {
            for (int i = 0; i < mTextDisplayLists.length; i++) {
                if (mTextDisplayLists[i] != null) mTextDisplayLists[i].clear();
            }
        }
    }

    void updateCursorsPositions() {
        if (mTextView.mCursorDrawableRes == 0) {
            mCursorCount = 0;
            return;
        }

        Layout layout = mTextView.getLayout();
        Layout hintLayout = mTextView.getHintLayout();
        final int offset = mTextView.getSelectionStart();
        final int line = layout.getLineForOffset(offset);
        final int top = layout.getLineTop(line);
        final int bottom = layout.getLineTop(line + 1);

        mCursorCount = layout.isLevelBoundary(offset) ? 2 : 1;

        int middle = bottom;
        if (mCursorCount == 2) {
            // Similar to what is done in {@link Layout.#getCursorPath(int, Path, CharSequence)}
            middle = (top + bottom) >> 1;
        }

        boolean clamped = layout.shouldClampCursor(line);
        updateCursorPosition(0, top, middle,
                getPrimaryHorizontal(layout, hintLayout, offset, clamped));

        if (mCursorCount == 2) {
            updateCursorPosition(1, middle, bottom,
                    layout.getSecondaryHorizontal(offset, clamped));
        }
    }

    private float getPrimaryHorizontal(Layout layout, Layout hintLayout, int offset,
            boolean clamped) {
        if (TextUtils.isEmpty(layout.getText()) &&
                hintLayout != null &&
                !TextUtils.isEmpty(hintLayout.getText())) {
            return hintLayout.getPrimaryHorizontal(offset, clamped);
        } else {
            return layout.getPrimaryHorizontal(offset, clamped);
        }
    }

    /**
     * @return true if the selection mode was actually started.
     */
    boolean startSelectionActionMode() {
        if (mSelectionActionMode != null) {
            // Selection action mode is already started
            return false;
        }

        if (!canSelectText() || !mTextView.requestFocus()) {
            Log.w(TextView.LOG_TAG,
                    "TextView does not support text selection. Action mode cancelled.");
            return false;
        }

        if (!mTextView.hasSelection()) {
            // There may already be a selection on device rotation
            if (!selectCurrentWord()) {
                // No word found under cursor or text selection not permitted.
                return false;
            }
        }

        boolean willExtract = extractedTextModeWillBeStarted();

        // Do not start the action mode when extracted text will show up full screen, which would
        // immediately hide the newly created action bar and would be visually distracting.
        if (!willExtract) {
            if (mCustomSelectionActionModeCallback != null) {
                ActionMode.Callback actionModeCallback = new SelectionActionModeCallback();
                mSelectionActionMode = mTextView.startActionMode(actionModeCallback);
            } else {
                getSelectionController().show();
            }
        }

        final boolean selectionStarted = mSelectionActionMode != null || willExtract;
        if (selectionStarted && !mTextView.isTextSelectable() && mShowSoftInputOnFocus) {
            // Show the IME to be able to replace text, except when selecting non editable text.
            final InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null) {
                imm.showSoftInput(mTextView, 0, null);
            }
        }

        return selectionStarted;
    }

    private boolean extractedTextModeWillBeStarted() {
        if (!(mTextView instanceof ExtractEditText)) {
            final InputMethodManager imm = InputMethodManager.peekInstance();
            return  imm != null && imm.isFullscreenMode();
        }
        return false;
    }

    /**
     * @return <code>true</code> if the cursor/current selection overlaps a {@link SuggestionSpan}.
     */
    private boolean isCursorInsideSuggestionSpan() {
        CharSequence text = mTextView.getText();
        if (!(text instanceof Spannable)) return false;

        SuggestionSpan[] suggestionSpans = ((Spannable) text).getSpans(
                mTextView.getSelectionStart(), mTextView.getSelectionEnd(), SuggestionSpan.class);
        return (suggestionSpans.length > 0);
    }

    /**
     * @return <code>true</code> if the cursor is inside an {@link SuggestionSpan} with
     * {@link SuggestionSpan#FLAG_EASY_CORRECT} set.
     */
    private boolean isCursorInsideEasyCorrectionSpan() {
        Spannable spannable = (Spannable) mTextView.getText();
        SuggestionSpan[] suggestionSpans = spannable.getSpans(mTextView.getSelectionStart(),
                mTextView.getSelectionEnd(), SuggestionSpan.class);
        for (int i = 0; i < suggestionSpans.length; i++) {
            if ((suggestionSpans[i].getFlags() & SuggestionSpan.FLAG_EASY_CORRECT) != 0) {
                return true;
            }
        }
        return false;
    }

    void onTouchUpEvent(MotionEvent event) {
        boolean selectAllGotFocus = mSelectAllOnFocus && mTextView.didTouchFocusSelect();
        CharSequence text = mTextView.getText();
        if (!selectAllGotFocus && text.length() > 0) {
            // Move cursor
            final int offset = mTextView.getOffsetForPosition(event.getX(), event.getY());
            Selection.setSelection((Spannable) text, offset);
            if (mSpellChecker != null) {
                // When the cursor moves, the word that was typed may need spell check
                mSpellChecker.onSelectionChanged();
            }
            if (!extractedTextModeWillBeStarted()) {
                if (isCursorInsideEasyCorrectionSpan()) {
                    hideControllers();
                    mShowSuggestionRunnable = new Runnable() {
                        public void run() {
                            showSuggestions();
                        }
                    };
                    // removeCallbacks is performed on every touch
                    mTextView.postDelayed(mShowSuggestionRunnable,
                            ViewConfiguration.getDoubleTapTimeout());
                    return;
                } else if (hasInsertionController()) {
                    // add touch the cursor to show ActionPopup if InsertHandleView is showing
                    InsertionPointCursorController cursorController = getInsertionController();
                    if(offset == cursorController.getShowAtOffset()) {
                        hideSuggestionAndSelectionControllers();
                        cursorController.showWithActionPopup();
                    } else {
                        hideControllers();
                        cursorController.show();
                    }
                    return;
                }
            }
        }
        hideControllers();
        if (mInsertionPointCursorController != null) {
            mInsertionPointCursorController.resetShowingStatus();
        }
    }

    protected void stopSelectionActionMode() {
        if (mSelectionActionMode != null) {
            // This will hide the mSelectionModifierCursorController
            mSelectionActionMode.finish();
        } else {
            hideSelectionModifierCursorController();
        }
    }

    /**
     * @return True if this view supports insertion handles.
     */
    boolean hasInsertionController() {
        return mInsertionControllerEnabled;
    }

    /**
     * @return True if this view supports selection handles.
     */
    boolean hasSelectionController() {
        return mSelectionControllerEnabled;
    }

    InsertionPointCursorController getInsertionController() {
        if (!mInsertionControllerEnabled) {
            return null;
        }

        if (mInsertionPointCursorController == null) {
            mInsertionPointCursorController = new InsertionPointCursorController();

            final ViewTreeObserver observer = mTextView.getViewTreeObserver();
            observer.addOnTouchModeChangeListener(mInsertionPointCursorController);
        }

        return mInsertionPointCursorController;
    }

    SelectionModifierCursorController getSelectionController() {
        if (!mSelectionControllerEnabled) {
            return null;
        }

        if (mSelectionModifierCursorController == null) {
            mSelectionModifierCursorController = new SelectionModifierCursorController();

            final ViewTreeObserver observer = mTextView.getViewTreeObserver();
            observer.addOnTouchModeChangeListener(mSelectionModifierCursorController);
        }

        return mSelectionModifierCursorController;
    }

    private void updateCursorPosition(int cursorIndex, int top, int bottom, float horizontal) {
        if (mCursorDrawable[cursorIndex] == null)
            mCursorDrawable[cursorIndex] = mTextView.getResources().getDrawable(
                    mTextView.mCursorDrawableRes);

        if (mTempRect == null) mTempRect = new Rect();
        mCursorDrawable[cursorIndex].getPadding(mTempRect);
        final int width = mCursorDrawable[cursorIndex].getIntrinsicWidth();
        horizontal = Math.max(0.5f, horizontal - 0.5f);
        final int left = (int) (horizontal) - mTempRect.left;
        mCursorDrawable[cursorIndex].setBounds(left, top - mTempRect.top, left + width,
                bottom + mTempRect.bottom);
    }

    /**
     * Called by the framework in response to a text auto-correction (such as fixing a typo using a
     * a dictionnary) from the current input method, provided by it calling
     * {@link InputConnection#commitCorrection} InputConnection.commitCorrection()}. The default
     * implementation flashes the background of the corrected word to provide feedback to the user.
     *
     * @param info The auto correct info about the text that was corrected.
     */
    public void onCommitCorrection(CorrectionInfo info) {
        if (mCorrectionHighlighter == null) {
            mCorrectionHighlighter = new CorrectionHighlighter();
        } else {
            mCorrectionHighlighter.invalidate(false);
        }

        mCorrectionHighlighter.highlight(info);
    }

    void showSuggestions() {
        if (mSuggestionsPopupWindow == null) {
            mSuggestionsPopupWindow = new SuggestionsPopupWindow();
        }
        hideControllers();
        mSuggestionsPopupWindow.show();
    }

    boolean areSuggestionsShown() {
        return mSuggestionsPopupWindow != null && mSuggestionsPopupWindow.isShowing();
    }

    void showQuickSnippets() {
        if (mQuickSnippetPopupWindow == null) {
            mQuickSnippetPopupWindow = new QuickSnippetPopupWindow();
        }
        hideControllers();
        mQuickSnippetPopupWindow.show();
    }

    void updateQuickSnippets() {
        if (mQuickSnippetPopupWindow != null) {
            mQuickSnippetPopupWindow.handleConfigurationChange();
        }
    }

    void onScrollChanged() {
        if (mPositionListener != null) {
            mPositionListener.onScrollChanged();
        }
    }

    /**
     * @return True when the TextView isFocused and has a valid zero-length selection (cursor).
     */
    private boolean shouldBlink() {
        if (!isCursorVisible() || !mTextView.isFocused()) return false;

        final int start = mTextView.getSelectionStart();
        if (start < 0) return false;

        final int end = mTextView.getSelectionEnd();
        if (end < 0) return false;

        return start == end;
    }

    void makeBlink() {
        if (shouldBlink()) {
            mShowCursor = SystemClock.uptimeMillis();
            if (mBlink == null) mBlink = new Blink();
            mBlink.removeCallbacks(mBlink);
            mBlink.postAtTime(mBlink, mShowCursor + BLINK);
        } else {
            if (mBlink != null) mBlink.removeCallbacks(mBlink);
        }
    }

    private class Blink extends Handler implements Runnable {
        private boolean mCancelled;

        public void run() {
            if (mCancelled) {
                return;
            }

            removeCallbacks(Blink.this);

            if (shouldBlink()) {
                // Check WindowVisbility() here to avoid no blink runs.
                // Sometimes, the view's windowVisbility is GONE when setText()
                // and onFocusChanged().
                if (mTextView.getWindowVisibility() != View.VISIBLE)
                    return;
                if (mTextView.getLayout() != null) {
                    mTextView.invalidateCursorPath();
                }
                postAtTime(this, SystemClock.uptimeMillis() + BLINK);
            }
        }

        void cancel() {
            if (!mCancelled) {
                removeCallbacks(Blink.this);
                mCancelled = true;
            }
        }

        void uncancel() {
            mCancelled = false;
        }
    }

    private DragShadowBuilder getTextThumbnailBuilder(CharSequence text) {
        TextView shadowView = (TextView) View.inflate(mTextView.getContext(),
                com.android.internal.R.layout.text_drag_thumbnail, null);

        if (shadowView == null) {
            throw new IllegalArgumentException("Unable to inflate text drag thumbnail");
        }

        if (text.length() > DRAG_SHADOW_MAX_TEXT_LENGTH) {
            text = text.subSequence(0, DRAG_SHADOW_MAX_TEXT_LENGTH);
        }
        shadowView.setText(text);
        shadowView.setTextColor(mTextView.getTextColors());

        shadowView.setTextAppearance(mTextView.getContext(), R.styleable.Theme_textAppearanceLarge);
        shadowView.setGravity(Gravity.CENTER);

        shadowView.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final int size = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        shadowView.measure(size, size);

        shadowView.layout(0, 0, shadowView.getMeasuredWidth(), shadowView.getMeasuredHeight());
        shadowView.invalidate();
        return new DragShadowBuilder(shadowView);
    }

    private static class DragLocalState {
        public TextView sourceTextView;
        public int start, end;

        public DragLocalState(TextView sourceTextView, int start, int end) {
            this.sourceTextView = sourceTextView;
            this.start = start;
            this.end = end;
        }
    }

    void onDrop(DragEvent event) {
        StringBuilder content = new StringBuilder("");
        ClipData clipData = event.getClipData();
        final int itemCount = clipData.getItemCount();
        for (int i=0; i < itemCount; i++) {
            Item item = clipData.getItemAt(i);
            content.append(item.coerceToStyledText(mTextView.getContext()));
        }

        final int offset = mTextView.getOffsetForPosition(event.getX(), event.getY());

        Object localState = event.getLocalState();
        DragLocalState dragLocalState = null;
        if (localState instanceof DragLocalState) {
            dragLocalState = (DragLocalState) localState;
        }
        boolean dragDropIntoItself = dragLocalState != null &&
                dragLocalState.sourceTextView == mTextView;

        if (dragDropIntoItself) {
            if (offset >= dragLocalState.start && offset < dragLocalState.end) {
                // A drop inside the original selection discards the drop.
                return;
            }
        }

        final int originalLength = mTextView.getText().length();
        long minMax = mTextView.prepareSpacesAroundPaste(offset, offset, content);
        int min = TextUtils.unpackRangeStartFromLong(minMax);
        int max = TextUtils.unpackRangeEndFromLong(minMax);

        Selection.setSelection((Spannable) mTextView.getText(), max);
        mTextView.replaceText_internal(min, max, content);

        if (dragDropIntoItself) {
            int dragSourceStart = dragLocalState.start;
            int dragSourceEnd = dragLocalState.end;
            if (max <= dragSourceStart) {
                // Inserting text before selection has shifted positions
                final int shift = mTextView.getText().length() - originalLength;
                dragSourceStart += shift;
                dragSourceEnd += shift;
            }

            // Delete original selection
            mTextView.deleteText_internal(dragSourceStart, dragSourceEnd);

            // Make sure we do not leave two adjacent spaces.
            final int prevCharIdx = Math.max(0,  dragSourceStart - 1);
            final int nextCharIdx = Math.min(mTextView.getText().length(), dragSourceStart + 1);
            if (nextCharIdx > prevCharIdx + 1) {
                CharSequence t = mTextView.getTransformedText(prevCharIdx, nextCharIdx);
                if (Character.isSpaceChar(t.charAt(0)) && Character.isSpaceChar(t.charAt(1))) {
                    mTextView.deleteText_internal(prevCharIdx, prevCharIdx + 1);
                }
            }
        }
    }

    public void addSpanWatchers(Spannable text) {
        final int textLength = text.length();

        if (mKeyListener != null) {
            text.setSpan(mKeyListener, 0, textLength, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }

        if (mSpanController == null) {
            mSpanController = new SpanController();
        }
        text.setSpan(mSpanController, 0, textLength, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
    }

    /**
     * Controls the {@link EasyEditSpan} monitoring when it is added, and when the related
     * pop-up should be displayed.
     * Also monitors {@link Selection} to call back to the attached input method.
     */
    class SpanController implements SpanWatcher {

        private static final int DISPLAY_TIMEOUT_MS = 3000; // 3 secs

        private EasyEditPopupWindow mPopupWindow;

        private Runnable mHidePopup;

        // This function is pure but inner classes can't have static functions
        private boolean isNonIntermediateSelectionSpan(final Spannable text,
                final Object span) {
            return (Selection.SELECTION_START == span || Selection.SELECTION_END == span)
                    && (text.getSpanFlags(span) & Spanned.SPAN_INTERMEDIATE) == 0;
        }

        @Override
        public void onSpanAdded(Spannable text, Object span, int start, int end) {
            if (isNonIntermediateSelectionSpan(text, span)) {
                sendUpdateSelection();
            } else if (span instanceof EasyEditSpan) {
                if (mPopupWindow == null) {
                    mPopupWindow = new EasyEditPopupWindow();
                    mHidePopup = new Runnable() {
                        @Override
                        public void run() {
                            hide();
                        }
                    };
                }

                // Make sure there is only at most one EasyEditSpan in the text
                if (mPopupWindow.mEasyEditSpan != null) {
                    mPopupWindow.mEasyEditSpan.setDeleteEnabled(false);
                }

                mPopupWindow.setEasyEditSpan((EasyEditSpan) span);
                mPopupWindow.setOnDeleteListener(new EasyEditDeleteListener() {
                    @Override
                    public void onDeleteClick(EasyEditSpan span) {
                        Editable editable = (Editable) mTextView.getText();
                        int start = editable.getSpanStart(span);
                        int end = editable.getSpanEnd(span);
                        if (start >= 0 && end >= 0) {
                            sendEasySpanNotification(EasyEditSpan.TEXT_DELETED, span);
                            mTextView.deleteText_internal(start, end);
                        }
                        editable.removeSpan(span);
                    }
                });

                if (mTextView.getWindowVisibility() != View.VISIBLE) {
                    // The window is not visible yet, ignore the text change.
                    return;
                }

                if (mTextView.getLayout() == null) {
                    // The view has not been laid out yet, ignore the text change
                    return;
                }

                if (extractedTextModeWillBeStarted()) {
                    // The input is in extract mode. Do not handle the easy edit in
                    // the original TextView, as the ExtractEditText will do
                    return;
                }

                mPopupWindow.show();
                mTextView.removeCallbacks(mHidePopup);
                mTextView.postDelayed(mHidePopup, DISPLAY_TIMEOUT_MS);
            }
        }

        @Override
        public void onSpanRemoved(Spannable text, Object span, int start, int end) {
            if (isNonIntermediateSelectionSpan(text, span)) {
                sendUpdateSelection();
            } else if (mPopupWindow != null && span == mPopupWindow.mEasyEditSpan) {
                hide();
            }
        }

        @Override
        public void onSpanChanged(Spannable text, Object span, int previousStart, int previousEnd,
                int newStart, int newEnd) {
            if (isNonIntermediateSelectionSpan(text, span)) {
                sendUpdateSelection();
            } else if (mPopupWindow != null && span instanceof EasyEditSpan) {
                EasyEditSpan easyEditSpan = (EasyEditSpan) span;
                sendEasySpanNotification(EasyEditSpan.TEXT_MODIFIED, easyEditSpan);
                text.removeSpan(easyEditSpan);
            }
        }

        public void hide() {
            if (mPopupWindow != null) {
                mPopupWindow.hide();
                mTextView.removeCallbacks(mHidePopup);
            }
        }

        private void sendEasySpanNotification(int textChangedType, EasyEditSpan span) {
            try {
                PendingIntent pendingIntent = span.getPendingIntent();
                if (pendingIntent != null) {
                    Intent intent = new Intent();
                    intent.putExtra(EasyEditSpan.EXTRA_TEXT_CHANGED_TYPE, textChangedType);
                    pendingIntent.send(mTextView.getContext(), 0, intent);
                }
            } catch (CanceledException e) {
                // This should not happen, as we should try to send the intent only once.
                Log.w(TAG, "PendingIntent for notification cannot be sent", e);
            }
        }
    }

    /**
     * Listens for the delete event triggered by {@link EasyEditPopupWindow}.
     */
    private interface EasyEditDeleteListener {

        /**
         * Clicks the delete pop-up.
         */
        void onDeleteClick(EasyEditSpan span);
    }

    /**
     * Displays the actions associated to an {@link EasyEditSpan}. The pop-up is controlled
     * by {@link SpanController}.
     */
    private class EasyEditPopupWindow extends PinnedPopupWindow
            implements OnClickListener {
        private static final int POPUP_TEXT_LAYOUT =
                com.android.internal.R.layout.text_edit_action_popup_text;
        private TextView mDeleteTextView;
        private EasyEditSpan mEasyEditSpan;
        private EasyEditDeleteListener mOnDeleteListener;

        @Override
        protected void createPopupWindow() {
            mPopupWindow = new PopupWindow(mTextView.getContext(), null,
                    com.android.internal.R.attr.textSelectHandleWindowStyle);
            mPopupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
            mPopupWindow.setClippingEnabled(true);
        }

        @Override
        protected void initContentView() {
            LinearLayout linearLayout = new LinearLayout(mTextView.getContext());
            linearLayout.setOrientation(LinearLayout.HORIZONTAL);
            mContentView = linearLayout;
            mContentView.setBackgroundResource(
                    com.android.internal.R.drawable.text_edit_side_paste_window);

            LayoutInflater inflater = (LayoutInflater)mTextView.getContext().
                    getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            LayoutParams wrapContent = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

            mDeleteTextView = (TextView) inflater.inflate(POPUP_TEXT_LAYOUT, null);
            mDeleteTextView.setLayoutParams(wrapContent);
            mDeleteTextView.setText(com.android.internal.R.string.delete);
            mDeleteTextView.setOnClickListener(this);
            mContentView.addView(mDeleteTextView);
        }

        public void setEasyEditSpan(EasyEditSpan easyEditSpan) {
            mEasyEditSpan = easyEditSpan;
        }

        private void setOnDeleteListener(EasyEditDeleteListener listener) {
            mOnDeleteListener = listener;
        }

        @Override
        public void onClick(View view) {
            if (view == mDeleteTextView
                    && mEasyEditSpan != null && mEasyEditSpan.isDeleteEnabled()
                    && mOnDeleteListener != null) {
                mOnDeleteListener.onDeleteClick(mEasyEditSpan);
            }
        }

        @Override
        public void hide() {
            if (mEasyEditSpan != null) {
                mEasyEditSpan.setDeleteEnabled(false);
            }
            mOnDeleteListener = null;
            super.hide();
        }

        @Override
        protected int getTextOffset() {
            // Place the pop-up at the end of the span
            Editable editable = (Editable) mTextView.getText();
            return editable.getSpanEnd(mEasyEditSpan);
        }

        @Override
        protected int getVerticalLocalPosition(int line) {
            return mTextView.getLayout().getLineBottom(line);
        }

        @Override
        protected int clipVertically(int positionY, int parentPositionY, int offset) {
            // As we display the pop-up below the span, no vertical clipping is required.
            return positionY;
        }
    }

    private class PositionListener implements ViewTreeObserver.OnPreDrawListener {
        // 3 handles
        // 3 ActionPopup [replace, suggestion, easyedit] (suggestionsPopup first hides the others)
        private final int MAXIMUM_NUMBER_OF_LISTENERS = 6;
        private TextViewPositionListener[] mPositionListeners =
                new TextViewPositionListener[MAXIMUM_NUMBER_OF_LISTENERS];
        private boolean mCanMove[] = new boolean[MAXIMUM_NUMBER_OF_LISTENERS];
        private boolean mPositionHasChanged = true;
        // Absolute position of the TextView with respect to its parent window
        private int mPositionX, mPositionY;
        // TextView's window vertical offset on screen;
        private int mWindowVerticalOffsetY;
        private int mNumberOfListeners;
        private boolean mScrollHasChanged;
        final int[] mTempCoords = new int[2];

        public void addSubscriber(TextViewPositionListener positionListener, boolean canMove) {
            if (mNumberOfListeners == 0) {
                updatePosition();
                ViewTreeObserver vto = mTextView.getViewTreeObserver();
                vto.addOnPreDrawListener(this);
            }

            int emptySlotIndex = -1;
            for (int i = 0; i < MAXIMUM_NUMBER_OF_LISTENERS; i++) {
                TextViewPositionListener listener = mPositionListeners[i];
                if (listener == positionListener) {
                    return;
                } else if (emptySlotIndex < 0 && listener == null) {
                    emptySlotIndex = i;
                }
            }

            mPositionListeners[emptySlotIndex] = positionListener;
            mCanMove[emptySlotIndex] = canMove;
            mNumberOfListeners++;
        }

        public void removeSubscriber(TextViewPositionListener positionListener) {
            for (int i = 0; i < MAXIMUM_NUMBER_OF_LISTENERS; i++) {
                if (mPositionListeners[i] == positionListener) {
                    mPositionListeners[i] = null;
                    mNumberOfListeners--;
                    break;
                }
            }

            if (mNumberOfListeners == 0) {
                ViewTreeObserver vto = mTextView.getViewTreeObserver();
                vto.removeOnPreDrawListener(this);
            }
        }

        public int getPositionX() {
            return mPositionX;
        }

        public int getPositionY() {
            return mPositionY;
        }

        public int getWindowVerticalOffset() {
            return mWindowVerticalOffsetY;
        }

        @Override
        public boolean onPreDraw() {
            updatePosition();

            for (int i = 0; i < MAXIMUM_NUMBER_OF_LISTENERS; i++) {
                if (mPositionHasChanged || mScrollHasChanged || mCanMove[i]) {
                    TextViewPositionListener positionListener = mPositionListeners[i];
                    if (positionListener != null) {
                        positionListener.updatePosition(mPositionX, mPositionY,
                                mPositionHasChanged, mScrollHasChanged);
                    }
                }
            }

            mScrollHasChanged = false;
            return true;
        }

        private void updatePosition() {
            mTextView.getLocationInWindow(mTempCoords);

            mPositionHasChanged = mTempCoords[0] != mPositionX || mTempCoords[1] != mPositionY;

            mPositionX = mTempCoords[0];
            mPositionY = mTempCoords[1];

            mTextView.getLocationOnScreen(mTempCoords);

            mWindowVerticalOffsetY = mTempCoords[1] - mPositionY;
        }

        public void onScrollChanged() {
            mScrollHasChanged = true;
        }
    }

    private abstract class PinnedPopupWindow implements TextViewPositionListener {
        private static final int POPUP_WINDOW_BOTTOM_ARROW =
                com.android.internal.R.drawable.smartisan_text_edit_bottom_arrow;
        private static final int POPUP_WINDOW_TOP_ARROW =
                com.android.internal.R.drawable.smartisan_text_edit_top_arrow;
        private static final int SHOW_DELAY_TIME = 300;
        protected PopupWindow mPopupWindow;
        protected ViewGroup mContentView;
        protected boolean mShouldShowArrow = true;
        private int mPositionX, mPositionY;
        protected ImageView mTopArrowView;
        protected ImageView mBottomArrowView;
        private Drawable mTopArrowDrawable;
        private Drawable mBottomArrowDrawable;
        private int mArrowDrawablePaddingOffset=0;
        private Runnable mDelayShower;

        protected abstract void createPopupWindow();
        protected abstract void initContentView();
        protected abstract int getTextOffset();
        protected abstract int getVerticalLocalPosition(int line);
        protected abstract int clipVertically(int positionY, int parentPositionY, int offset);

        public PinnedPopupWindow() {
            createPopupWindow();

            mPopupWindow.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
            mPopupWindow.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
            mPopupWindow.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            mArrowDrawablePaddingOffset = mTextView.getContext().getResources()
                    .getDimensionPixelSize(com.android.internal.R.dimen.arrow_drawable_padding_offset);
            initContentView();
            initArrowView();
        }

        protected void initArrowView() {
            LayoutParams wrapContent = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            mContentView.setLayoutParams(wrapContent);

            FrameLayout frameLayout = new FrameLayout(mTextView.getContext());
            frameLayout.addView(mContentView);

            mBottomArrowDrawable = mTextView.getContext().getResources().getDrawable(
                    POPUP_WINDOW_BOTTOM_ARROW);
            mTopArrowDrawable = mTextView.getContext().getResources().getDrawable(
                    POPUP_WINDOW_TOP_ARROW);
            mContentView.setPadding(0, mTopArrowDrawable.getIntrinsicHeight()-mArrowDrawablePaddingOffset,
                    0, mBottomArrowDrawable.getIntrinsicHeight()-mArrowDrawablePaddingOffset);
            mTopArrowView = new ImageView(mTextView.getContext());
            mTopArrowView.setImageDrawable(mTopArrowDrawable);
            mTopArrowView.setScaleType(ScaleType.CENTER);
            FrameLayout.LayoutParams topArrowParams = new
                    FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            frameLayout.addView(mTopArrowView, topArrowParams);

            mBottomArrowView = new ImageView(mTextView.getContext());
            mBottomArrowView.setImageDrawable(mBottomArrowDrawable);
            mBottomArrowView.setScaleType(ScaleType.CENTER);
            FrameLayout.LayoutParams bottomArrowParams = new
                    FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            frameLayout.addView(mBottomArrowView, bottomArrowParams);
            mContentView = frameLayout;
            mPopupWindow.setContentView(mContentView);
        }

        public void show() {
            getPositionListener().addSubscriber(this, false /* offset is fixed */);

            final int offset = getTextOffset();
            if(isOffsetVisible(offset)) {
                computeLocalPosition(offset);
                final PositionListener positionListener = getPositionListener();
                updatePosition(positionListener.getPositionX(), positionListener.getPositionY(), offset);
            } else {
                showAfterDelay();
            }
        }

        protected void measureContent() {
            final DisplayMetrics displayMetrics = mTextView.getResources().getDisplayMetrics();
            mContentView.measure(
                    View.MeasureSpec.makeMeasureSpec(displayMetrics.widthPixels,
                            View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(displayMetrics.heightPixels,
                            View.MeasureSpec.AT_MOST));
        }

        /* The popup window will be horizontally centered on the getTextOffset() and vertically
         * positioned according to viewportToContentHorizontalOffset.
         *
         * This method assumes that mContentView has properly been measured from its content. */
        private void computeLocalPosition(int offset) {
            measureContent();
            final int width = mContentView.getMeasuredWidth();
            mPositionX = (int) (mTextView.getLayout().getPrimaryHorizontal(offset) - width / 2.0f);
            mPositionX += mTextView.viewportToContentHorizontalOffset();

            final int line = mTextView.getLayout().getLineForOffset(offset);
            mPositionY = getVerticalLocalPosition(line);
            mPositionY += mTextView.viewportToContentVerticalOffset();
        }

        private void updatePosition(int parentPositionX, int parentPositionY, int offset) {
            int positionX = parentPositionX + mPositionX;
            int positionY = parentPositionY + mPositionY;

            // Horizontal clipping
            final DisplayMetrics displayMetrics = mTextView.getResources().getDisplayMetrics();
            final int width = mContentView.getMeasuredWidth();
            positionX = Math.min(displayMetrics.widthPixels - width, positionX);
            positionX = Math.max(0, positionX);

            // Place arrow at the proper position
            if (mShouldShowArrow) {
                final int oriArrowPositionX = positionX + width / 2;
                int newArrowPositionX = (int) getCursorPositionX(parentPositionX, offset);
                newArrowPositionX = Math.max(newArrowPositionX, ARROW_HORIZONTAL_OFFSET);
                newArrowPositionX = Math.min(newArrowPositionX,
                        displayMetrics.widthPixels - ARROW_HORIZONTAL_OFFSET);
                if (positionY < 0) {
                    mTopArrowView.setVisibility(View.VISIBLE);
                    mTopArrowView.setTranslationX(newArrowPositionX - oriArrowPositionX);
                    mBottomArrowView.setVisibility(View.GONE);
                } else {
                    mTopArrowView.setVisibility(View.GONE);
                    mBottomArrowView.setVisibility(View.VISIBLE);
                    mBottomArrowView.setTranslationX(newArrowPositionX - oriArrowPositionX);
                }
            }

            positionY = clipVertically(positionY, parentPositionY, offset);

            if (isShowing()) {
                mPopupWindow.update(positionX, positionY, -1, -1);
            } else {
                mPopupWindow.showAtLocation(mTextView, Gravity.NO_GRAVITY,
                        positionX, positionY);
            }
        }

        public void hide() {
            mPopupWindow.dismiss();
            getPositionListener().removeSubscriber(this);
            removeShowerCallBack();
        }

        @Override
        public void updatePosition(int parentPositionX, int parentPositionY,
                boolean parentPositionChanged, boolean parentScrolled) {
            if (parentPositionChanged && isShowing()) {
                mPopupWindow.dismiss();
            }
            showAfterDelay();
        }

        public boolean isShowing() {
            return mPopupWindow.isShowing();
        }

        public void showAfterDelay() {
            if(mDelayShower == null) {
                mDelayShower = new Runnable() {
                    @Override
                    public void run() {
                        final PositionListener positionListener = getPositionListener();
                        final int parentPositionX = positionListener.getPositionX();
                        final int parentPositionY = positionListener.getPositionY();
                        int offset = getTextOffset();
                        if (isOffsetVisible(offset)) {
                            computeLocalPosition(offset);
                            updatePosition(parentPositionX, parentPositionY, offset);
                        } else {
                            Layout layout = mTextView.getLayout();
                            if (layout != null) {
                                Rect r = new Rect();
                                mTextView.getGlobalVisibleRect(r);
                                final int positionX = (r.left + r.right) / 2
                                        - mTextView.viewportToContentHorizontalOffset()
                                        - parentPositionX;
                                final int positionY = (r.top + r.bottom) / 2
                                        - mTextView.viewportToContentVerticalOffset()
                                        - parentPositionY;
                                if (layout.getLineTop(0) > positionY
                                        || layout.getLineTop(layout.getLineCount()) < positionY) {
                                    return;
                                }
                                final int line = layout.getLineForVertical(positionY);
                                offset = layout.getOffsetForHorizontal(line, (float) positionX);
                                if (offset >= mTextView.getSelectionStart()
                                        && offset <= mTextView.getSelectionEnd()) {
                                    computeLocalPosition(offset);
                                    updatePosition(parentPositionX, parentPositionY, offset);
                                }
                            }
                        }
                    }
                };
            } else {
                removeShowerCallBack();
            }
            mTextView.postDelayed(mDelayShower, SHOW_DELAY_TIME);
        }

        private void removeShowerCallBack() {
            if(mDelayShower != null) {
                mTextView.removeCallbacks(mDelayShower);
            }
        }

        private float getCursorPositionX(int parentPositionX, int offset) {
            final float offsetX = mTextView.getLayout().getPrimaryHorizontal(offset);
            float positionX = offsetX + parentPositionX;
            // Take TextView's padding and scroll into account.
            positionX += mTextView.viewportToContentHorizontalOffset();
            return positionX;
        }
    }

    private class QuickSnippetPopupWindow extends QuickSnippetView {

        public QuickSnippetPopupWindow() {
            super(mTextView.getContext());
            disableAdd(mTextView instanceof ExtractEditText);
        }

        @Override
        public void insertSnippet(String snippet) {
            ((Editable) mTextView.getText()).insert(mTextView.getSelectionEnd(), snippet);
        }
    }

    private class SuggestionsPopupWindow extends PinnedPopupWindow implements OnItemClickListener {
        private static final int MAX_NUMBER_SUGGESTIONS = SuggestionSpan.SUGGESTIONS_MAX_SIZE;
        private static final int ADD_TO_DICTIONARY = -1;
        private SuggestionInfo[] mSuggestionInfos;
        private int mNumberOfSuggestions;
        private boolean mCursorWasVisibleBeforeSuggestions;
        private boolean mIsShowingUp = false;
        private SuggestionAdapter mSuggestionsAdapter;
        private final Comparator<SuggestionSpan> mSuggestionSpanComparator;
        private final HashMap<SuggestionSpan, Integer> mSpansLengths;

        private class CustomPopupWindow extends PopupWindow {
            public CustomPopupWindow(Context context, int defStyle) {
                super(context, null, defStyle);
            }

            @Override
            public void dismiss() {
                super.dismiss();

                getPositionListener().removeSubscriber(SuggestionsPopupWindow.this);

                // Safe cast since show() checks that mTextView.getText() is an Editable
                ((Spannable) mTextView.getText()).removeSpan(mSuggestionRangeSpan);

                mTextView.setCursorVisible(mCursorWasVisibleBeforeSuggestions);
            }
        }

        public SuggestionsPopupWindow() {
            mCursorWasVisibleBeforeSuggestions = mCursorVisible;
            mSuggestionSpanComparator = new SuggestionSpanComparator();
            mSpansLengths = new HashMap<SuggestionSpan, Integer>();
        }

        @Override
        protected void createPopupWindow() {
            mPopupWindow = new CustomPopupWindow(getUiContext(),
                com.android.internal.R.attr.textSuggestionsWindowStyle);
            mPopupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
            mPopupWindow.setFocusable(true);
            mPopupWindow.setClippingEnabled(false);
        }

        @Override
        protected void initContentView() {
            ListView listView = new ListView(getUiContext());
            mSuggestionsAdapter = new SuggestionAdapter();
            listView.setAdapter(mSuggestionsAdapter);
            listView.setOnItemClickListener(this);
            mContentView = listView;

            // Inflate the suggestion items once and for all. +1 for add to dictionary
            mSuggestionInfos = new SuggestionInfo[MAX_NUMBER_SUGGESTIONS + 1];
            for (int i = 0; i < mSuggestionInfos.length; i++) {
                mSuggestionInfos[i] = new SuggestionInfo();
            }
        }

        @Override
        protected void initArrowView() {
            mShouldShowArrow = false;
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            mContentView.setLayoutParams(lp);
            mPopupWindow.setContentView(mContentView);
        }

        public boolean isShowingUp() {
            return mIsShowingUp;
        }

        public void onParentLostFocus() {
            mIsShowingUp = false;
        }

        private class SuggestionInfo {
            int suggestionStart, suggestionEnd; // range of actual suggestion within text
            SuggestionSpan suggestionSpan; // the SuggestionSpan that this TextView represents
            int suggestionIndex; // the index of this suggestion inside suggestionSpan
            SpannableStringBuilder text = new SpannableStringBuilder();
            TextAppearanceSpan highlightSpan = new TextAppearanceSpan(getUiContext(),
                    android.R.style.TextAppearance_SuggestionHighlight);
        }

        private class SuggestionAdapter extends BaseAdapter {
            private LayoutInflater mInflater = (LayoutInflater) getUiContext().
                    getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            @Override
            public int getCount() {
                return mNumberOfSuggestions;
            }

            @Override
            public Object getItem(int position) {
                return mSuggestionInfos[position];
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView textView = (TextView) convertView;

                if (textView == null) {
                    textView = (TextView) mInflater.inflate(mTextView.mTextEditSuggestionItemLayout,
                            parent, false);
                }

                final SuggestionInfo suggestionInfo = mSuggestionInfos[position];
                textView.setText(suggestionInfo.text);

                return textView;
            }
        }

        private class SuggestionSpanComparator implements Comparator<SuggestionSpan> {
            public int compare(SuggestionSpan span1, SuggestionSpan span2) {
                final int flag1 = span1.getFlags();
                final int flag2 = span2.getFlags();
                if (flag1 != flag2) {
                    // The order here should match what is used in updateDrawState
                    final boolean easy1 = (flag1 & SuggestionSpan.FLAG_EASY_CORRECT) != 0;
                    final boolean easy2 = (flag2 & SuggestionSpan.FLAG_EASY_CORRECT) != 0;
                    final boolean misspelled1 = (flag1 & SuggestionSpan.FLAG_MISSPELLED) != 0;
                    final boolean misspelled2 = (flag2 & SuggestionSpan.FLAG_MISSPELLED) != 0;
                    if (easy1 && !misspelled1) return -1;
                    if (easy2 && !misspelled2) return 1;
                    if (misspelled1) return -1;
                    if (misspelled2) return 1;
                }

                return mSpansLengths.get(span1).intValue() - mSpansLengths.get(span2).intValue();
            }
        }

        /**
         * Returns the suggestion spans that cover the current cursor position. The suggestion
         * spans are sorted according to the length of text that they are attached to.
         */
        private SuggestionSpan[] getSuggestionSpans() {
            int pos = mTextView.getSelectionStart();
            Spannable spannable = (Spannable) mTextView.getText();
            SuggestionSpan[] suggestionSpans = spannable.getSpans(pos, pos, SuggestionSpan.class);

            mSpansLengths.clear();
            for (SuggestionSpan suggestionSpan : suggestionSpans) {
                int start = spannable.getSpanStart(suggestionSpan);
                int end = spannable.getSpanEnd(suggestionSpan);
                mSpansLengths.put(suggestionSpan, Integer.valueOf(end - start));
            }

            // The suggestions are sorted according to their types (easy correction first, then
            // misspelled) and to the length of the text that they cover (shorter first).
            Arrays.sort(suggestionSpans, mSuggestionSpanComparator);
            return suggestionSpans;
        }

        @Override
        public void show() {
            if (!(mTextView.getText() instanceof Editable)) return;

            if (updateSuggestions()) {
                mCursorWasVisibleBeforeSuggestions = mCursorVisible;
                mTextView.setCursorVisible(false);
                mIsShowingUp = true;
                super.show();
            }
        }

        @Override
        protected void measureContent() {
            final DisplayMetrics displayMetrics = mTextView.getResources().getDisplayMetrics();
            final int horizontalMeasure = View.MeasureSpec.makeMeasureSpec(
                    displayMetrics.widthPixels, View.MeasureSpec.AT_MOST);
            final int verticalMeasure = View.MeasureSpec.makeMeasureSpec(
                    displayMetrics.heightPixels, View.MeasureSpec.AT_MOST);

            int width = 0;
            View view = null;
            for (int i = 0; i < mNumberOfSuggestions; i++) {
                view = mSuggestionsAdapter.getView(i, view, mContentView);
                view.getLayoutParams().width = LayoutParams.WRAP_CONTENT;
                view.measure(horizontalMeasure, verticalMeasure);
                width = Math.max(width, view.getMeasuredWidth());
            }

            // Enforce the width based on actual text widths
            mContentView.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    verticalMeasure);

            Drawable popupBackground = mPopupWindow.getBackground();
            if (popupBackground != null) {
                if (mTempRect == null) mTempRect = new Rect();
                popupBackground.getPadding(mTempRect);
                width += mTempRect.left + mTempRect.right;
            }
            mPopupWindow.setWidth(width);
        }

        @Override
        protected int getTextOffset() {
            return mTextView.getSelectionStart();
        }

        @Override
        protected int getVerticalLocalPosition(int line) {
            return mTextView.getLayout().getClippedLineBottom(line);
        }

        @Override
        protected int clipVertically(int positionY, int parentPositionY, int offset) {
            final int height = mContentView.getMeasuredHeight();
            final DisplayMetrics displayMetrics = mTextView.getResources().getDisplayMetrics();
            return Math.min(positionY, displayMetrics.heightPixels - height);
        }

        @Override
        public void hide() {
            super.hide();
        }

        private boolean updateSuggestions() {
            Spannable spannable = (Spannable) mTextView.getText();
            SuggestionSpan[] suggestionSpans = getSuggestionSpans();

            final int nbSpans = suggestionSpans.length;
            // Suggestions are shown after a delay: the underlying spans may have been removed
            if (nbSpans == 0) return false;

            mNumberOfSuggestions = 0;
            int spanUnionStart = mTextView.getText().length();
            int spanUnionEnd = 0;

            SuggestionSpan misspelledSpan = null;
            int underlineColor = 0;

            for (int spanIndex = 0; spanIndex < nbSpans; spanIndex++) {
                SuggestionSpan suggestionSpan = suggestionSpans[spanIndex];
                final int spanStart = spannable.getSpanStart(suggestionSpan);
                final int spanEnd = spannable.getSpanEnd(suggestionSpan);
                spanUnionStart = Math.min(spanStart, spanUnionStart);
                spanUnionEnd = Math.max(spanEnd, spanUnionEnd);

                if ((suggestionSpan.getFlags() & SuggestionSpan.FLAG_MISSPELLED) != 0) {
                    misspelledSpan = suggestionSpan;
                }

                // The first span dictates the background color of the highlighted text
                if (spanIndex == 0) underlineColor = suggestionSpan.getUnderlineColor();

                String[] suggestions = suggestionSpan.getSuggestions();
                int nbSuggestions = suggestions.length;
                for (int suggestionIndex = 0; suggestionIndex < nbSuggestions; suggestionIndex++) {
                    String suggestion = suggestions[suggestionIndex];

                    boolean suggestionIsDuplicate = false;
                    for (int i = 0; i < mNumberOfSuggestions; i++) {
                        if (mSuggestionInfos[i].text.toString().equals(suggestion)) {
                            SuggestionSpan otherSuggestionSpan = mSuggestionInfos[i].suggestionSpan;
                            final int otherSpanStart = spannable.getSpanStart(otherSuggestionSpan);
                            final int otherSpanEnd = spannable.getSpanEnd(otherSuggestionSpan);
                            if (spanStart == otherSpanStart && spanEnd == otherSpanEnd) {
                                suggestionIsDuplicate = true;
                                break;
                            }
                        }
                    }

                    if (!suggestionIsDuplicate) {
                        SuggestionInfo suggestionInfo = mSuggestionInfos[mNumberOfSuggestions];
                        suggestionInfo.suggestionSpan = suggestionSpan;
                        suggestionInfo.suggestionIndex = suggestionIndex;
                        suggestionInfo.text.replace(0, suggestionInfo.text.length(), suggestion);

                        mNumberOfSuggestions++;

                        if (mNumberOfSuggestions == MAX_NUMBER_SUGGESTIONS) {
                            // Also end outer for loop
                            spanIndex = nbSpans;
                            break;
                        }
                    }
                }
            }

            for (int i = 0; i < mNumberOfSuggestions; i++) {
                highlightTextDifferences(mSuggestionInfos[i], spanUnionStart, spanUnionEnd);
            }

            // Add "Add to dictionary" item if there is a span with the misspelled flag
            if (misspelledSpan != null) {
                final int misspelledStart = spannable.getSpanStart(misspelledSpan);
                final int misspelledEnd = spannable.getSpanEnd(misspelledSpan);
                if (misspelledStart >= 0 && misspelledEnd > misspelledStart) {
                    SuggestionInfo suggestionInfo = mSuggestionInfos[mNumberOfSuggestions];
                    suggestionInfo.suggestionSpan = misspelledSpan;
                    suggestionInfo.suggestionIndex = ADD_TO_DICTIONARY;
                    suggestionInfo.text.replace(0, suggestionInfo.text.length(), mTextView.
                            getContext().getString(com.android.internal.R.string.addToDictionary));
                    suggestionInfo.text.setSpan(suggestionInfo.highlightSpan, 0, 0,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                    mNumberOfSuggestions++;
                }
            }

            if (mSuggestionRangeSpan == null) mSuggestionRangeSpan = new SuggestionRangeSpan();
            if (underlineColor == 0) {
                // Fallback on the default highlight color when the first span does not provide one
                mSuggestionRangeSpan.setBackgroundColor(mTextView.mHighlightColor);
            } else {
                final float BACKGROUND_TRANSPARENCY = 0.4f;
                final int newAlpha = (int) (Color.alpha(underlineColor) * BACKGROUND_TRANSPARENCY);
                mSuggestionRangeSpan.setBackgroundColor(
                        (underlineColor & 0x00FFFFFF) + (newAlpha << 24));
            }
            spannable.setSpan(mSuggestionRangeSpan, spanUnionStart, spanUnionEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            mSuggestionsAdapter.notifyDataSetChanged();
            return true;
        }

        private void highlightTextDifferences(SuggestionInfo suggestionInfo, int unionStart,
                int unionEnd) {
            final Spannable text = (Spannable) mTextView.getText();
            final int spanStart = text.getSpanStart(suggestionInfo.suggestionSpan);
            final int spanEnd = text.getSpanEnd(suggestionInfo.suggestionSpan);

            // Adjust the start/end of the suggestion span
            suggestionInfo.suggestionStart = spanStart - unionStart;
            suggestionInfo.suggestionEnd = suggestionInfo.suggestionStart
                    + suggestionInfo.text.length();

            // Add the text before and after the span.
            final String textAsString = text.toString();
            suggestionInfo.text.insert(0, textAsString.substring(unionStart, spanStart));
            suggestionInfo.text.append(textAsString.substring(spanEnd, unionEnd));
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Editable editable = (Editable) mTextView.getText();
            SuggestionInfo suggestionInfo = mSuggestionInfos[position];

            final int spanStart = editable.getSpanStart(suggestionInfo.suggestionSpan);
            final int spanEnd = editable.getSpanEnd(suggestionInfo.suggestionSpan);
            if (spanStart < 0 || spanEnd <= spanStart) {
                // Span has been removed
                hide();
                return;
            }

            final String originalText = editable.toString().substring(spanStart, spanEnd);

            if (suggestionInfo.suggestionIndex == ADD_TO_DICTIONARY) {
                Intent intent = new Intent(Settings.ACTION_USER_DICTIONARY_INSERT);
                intent.putExtra("word", originalText);
                intent.putExtra("locale", mTextView.getTextServicesLocale().toString());
                // Put a listener to replace the original text with a word which the user
                // modified in a user dictionary dialog.
                intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK);
                mTextView.getContext().startActivity(intent);
                // There is no way to know if the word was indeed added. Re-check.
                // TODO The ExtractEditText should remove the span in the original text instead
                editable.removeSpan(suggestionInfo.suggestionSpan);
                Selection.setSelection(editable, spanEnd);
                updateSpellCheckSpans(spanStart, spanEnd, false);
            } else {
                // SuggestionSpans are removed by replace: save them before
                SuggestionSpan[] suggestionSpans = editable.getSpans(spanStart, spanEnd,
                        SuggestionSpan.class);
                final int length = suggestionSpans.length;
                int[] suggestionSpansStarts = new int[length];
                int[] suggestionSpansEnds = new int[length];
                int[] suggestionSpansFlags = new int[length];
                for (int i = 0; i < length; i++) {
                    final SuggestionSpan suggestionSpan = suggestionSpans[i];
                    suggestionSpansStarts[i] = editable.getSpanStart(suggestionSpan);
                    suggestionSpansEnds[i] = editable.getSpanEnd(suggestionSpan);
                    suggestionSpansFlags[i] = editable.getSpanFlags(suggestionSpan);

                    // Remove potential misspelled flags
                    int suggestionSpanFlags = suggestionSpan.getFlags();
                    if ((suggestionSpanFlags & SuggestionSpan.FLAG_MISSPELLED) > 0) {
                        suggestionSpanFlags &= ~SuggestionSpan.FLAG_MISSPELLED;
                        suggestionSpanFlags &= ~SuggestionSpan.FLAG_EASY_CORRECT;
                        suggestionSpan.setFlags(suggestionSpanFlags);
                    }
                }

                final int suggestionStart = suggestionInfo.suggestionStart;
                final int suggestionEnd = suggestionInfo.suggestionEnd;
                final String suggestion = suggestionInfo.text.subSequence(
                        suggestionStart, suggestionEnd).toString();
                mTextView.replaceText_internal(spanStart, spanEnd, suggestion);

                // Notify source IME of the suggestion pick. Do this before
                // swaping texts.
                suggestionInfo.suggestionSpan.notifySelection(
                        mTextView.getContext(), originalText, suggestionInfo.suggestionIndex);

                // Swap text content between actual text and Suggestion span
                String[] suggestions = suggestionInfo.suggestionSpan.getSuggestions();
                suggestions[suggestionInfo.suggestionIndex] = originalText;

                // Restore previous SuggestionSpans
                final int textLength = mTextView.getText().toString().length();
                final int realSuggestionLength = Math.min(suggestion.length(), textLength);
                final int lengthDifference = realSuggestionLength - (spanEnd - spanStart);
                for (int i = 0; i < length; i++) {
                    // Only spans that include the modified region make sense after replacement
                    // Spans partially included in the replaced region are removed, there is no
                    // way to assign them a valid range after replacement
                    if (suggestionSpansStarts[i] <= spanStart &&
                            suggestionSpansEnds[i] >= spanEnd) {
                        if (suggestionSpansEnds[i] + lengthDifference > textLength) {
                            Log.e(TAG, "setSpan of range (" + suggestionSpansStarts[i]
                                    + "," + (suggestionSpansEnds[i] + lengthDifference)
                                    + ") ends beyond length " + textLength
                                    + ". mText=" + mTextView.getText()
                                    + " suggestion=" + suggestion
                                    + " spanStart=" + spanStart + " spanEnd=" + spanEnd);
                        } else {
                            mTextView.setSpan_internal(suggestionSpans[i], suggestionSpansStarts[i],
                                suggestionSpansEnds[i] + lengthDifference, suggestionSpansFlags[i]);
                        }
                    }
                }

                // Move cursor at the end of the replaced word
                final int newCursorPosition = spanEnd + lengthDifference;
                mTextView.setCursorPosition_internal(newCursorPosition, newCursorPosition);
            }

            hide();
        }
    }

    /**
     * An ActionMode Callback class that is used to provide actions while in text selection mode.
     *
     * The default callback provides a subset of Select All, Cut, Copy and Paste actions, depending
     * on which of these this TextView supports.
     */
    private class SelectionActionModeCallback implements ActionMode.Callback {

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            TypedArray styledAttributes = mTextView.getContext().obtainStyledAttributes(
                    com.android.internal.R.styleable.SelectionModeDrawables);

            mode.setTitle(mTextView.getContext().getString(
                    com.android.internal.R.string.textSelectionCABTitle));
            mode.setSubtitle(null);
            mode.setTitleOptionalHint(true);

            menu.add(0, TextView.ID_SELECT_ALL, 0, com.android.internal.R.string.selectAll).
                    setIcon(styledAttributes.getResourceId(
                            R.styleable.SelectionModeDrawables_actionModeSelectAllDrawable, 0)).
                    setAlphabeticShortcut('a').
                    setShowAsAction(
                            MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);

            if (mTextView.canCut()) {
                menu.add(0, TextView.ID_CUT, 0, com.android.internal.R.string.cut).
                    setIcon(styledAttributes.getResourceId(
                            R.styleable.SelectionModeDrawables_actionModeCutDrawable, 0)).
                    setAlphabeticShortcut('x').
                    setShowAsAction(
                            MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
            }

            if (mTextView.canCopy()) {
                menu.add(0, TextView.ID_COPY, 0, com.android.internal.R.string.copy).
                    setIcon(styledAttributes.getResourceId(
                            R.styleable.SelectionModeDrawables_actionModeCopyDrawable, 0)).
                    setAlphabeticShortcut('c').
                    setShowAsAction(
                            MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
            }

            if (mTextView.canPaste()) {
                menu.add(0, TextView.ID_PASTE, 0, com.android.internal.R.string.paste).
                        setIcon(styledAttributes.getResourceId(
                                R.styleable.SelectionModeDrawables_actionModePasteDrawable, 0)).
                        setAlphabeticShortcut('v').
                        setShowAsAction(
                                MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
            }

            styledAttributes.recycle();

            if (mCustomSelectionActionModeCallback != null) {
                if (!mCustomSelectionActionModeCallback.onCreateActionMode(mode, menu)) {
                    // The custom mode can choose to cancel the action mode
                    return false;
                }
            }

            if ((menu.hasVisibleItems() || mode.getCustomView() != null)
                    && hasSelectionController()) {
                getSelectionController().show();
                mTextView.setHasTransientState(true);
                return true;
            } else {
                return false;
            }
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            if (mCustomSelectionActionModeCallback != null) {
                return mCustomSelectionActionModeCallback.onPrepareActionMode(mode, menu);
            }
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (mCustomSelectionActionModeCallback != null &&
                 mCustomSelectionActionModeCallback.onActionItemClicked(mode, item)) {
                return true;
            }
            return mTextView.onTextContextMenuItem(item.getItemId());
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            if (mCustomSelectionActionModeCallback != null) {
                mCustomSelectionActionModeCallback.onDestroyActionMode(mode);
            }

            /*
             * If we're ending this mode because we're detaching from a window,
             * we still have selection state to preserve. Don't clear it, we'll
             * bring back the selection mode when (if) we get reattached.
             */
            if (!mPreserveDetachedSelection) {
                Selection.setSelection((Spannable) mTextView.getText(),
                        mTextView.getSelectionEnd());
                mTextView.setHasTransientState(false);
            }

            if (mSelectionModifierCursorController != null) {
                mSelectionModifierCursorController.hide();
            }

            mSelectionActionMode = null;
        }
    }

    private class ActionPopupWindow extends PinnedPopupWindow implements OnClickListener {
        private static final int POPUP_TEXT_LAYOUT =
                com.android.internal.R.layout.text_edit_action_popup_text;
        private static final int SEARCH_MAX_WORDS_NUMBER = 50;

        private LayoutInflater mInflater;
        private LinearLayout.LayoutParams mWrapContent;
        private LinearLayout mMenu;

        private TextView mSelectTextView;
        private TextView mSelectAllTextView;
        private TextView mCutTextView;
        private TextView mCopyTextView;
        private TextView mSearchTextView;
        private TextView mShareTextView;
        private TextView mDictTextView;
        private TextView mPasteTextView;
        private TextView mUndoTextView;
        private TextView mRedoTextView;
        private TextView mQuickSnippetTextView;

        private String mStringUndo;
        private String mStringUndoInput;
        private String mStringUndoCut;
        private String mStringUndoDelete;
        private String mStringUndoPaste;
        private String mStringRedo;
        private String mStringRedoInput;
        private String mStringRedoCut;
        private String mStringRedoDelete;
        private String mStringRedoPaste;

        private String mYoudaoDictPackageName;
        private String mYoudaoDictClassName;
        private int mYoudaoDictMaxQueryWordsNumber;

        private int mHandleViewHeight;

        private class CustomHorizontalScrollView extends HorizontalScrollView {
            public CustomHorizontalScrollView(Context context) {
                super(context);
            }

            @Override
            protected void onOverScrolled(int scrollX, int scrollY,
                    boolean clampedX, boolean clampedY) {
                int arrowLeftBound;
                if (mTopArrowView.getVisibility() == View.VISIBLE) {
                    arrowLeftBound = Math.max(0, mTopArrowView.getLeft()
                            + (int) mTopArrowView.getTranslationX()
                            - mTopArrowView.getWidth() / 2);
                } else {
                    arrowLeftBound = Math.max(0, mBottomArrowView.getLeft()
                            + (int) mBottomArrowView.getTranslationX()
                            - mBottomArrowView.getWidth() / 2);
                }
                if (scrollX + arrowLeftBound < 0) {
                    scrollX = -arrowLeftBound;
                }

                super.onOverScrolled(scrollX, scrollY, clampedX, clampedY);
            }

        }

        public ActionPopupWindow(int handleViewHeight) {
            super();
            mHandleViewHeight = handleViewHeight;
            Context context = mTextView.getContext();
            mYoudaoDictPackageName = context.getString(
                    com.android.internal.R.string.yddict_package_name);
            mYoudaoDictClassName = context.getString(
                    com.android.internal.R.string.yddict_class_name);
            mYoudaoDictMaxQueryWordsNumber = context.getResources().getInteger(
                    com.android.internal.R.integer.yddict_max_query_words_number);
        }

        @Override
        protected void createPopupWindow() {
            mPopupWindow = new PopupWindow(mTextView.getContext(), null,
                    com.android.internal.R.attr.textSelectHandleWindowStyle);
            mPopupWindow.setClippingEnabled(true);
        }

        @Override
        protected void initContentView() {
            LayoutInflater inflater = (LayoutInflater) mTextView.getContext().
                    getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mInflater = inflater;

            LayoutParams wrapContent = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.FILL_PARENT);
            mMenu = new LinearLayout(mTextView.getContext());
            mMenu.setOrientation(LinearLayout.HORIZONTAL);
            mMenu.setLayoutParams(wrapContent);
            mMenu.setBackgroundResource(com.android.internal.R.drawable.smartisan_text_edit_popup_window);

            mWrapContent = new LinearLayout.LayoutParams(wrapContent);
            mWrapContent.gravity = Gravity.CENTER;

            CustomHorizontalScrollView scrollView = new CustomHorizontalScrollView(mTextView.getContext());
            scrollView.addView(mMenu);
            scrollView.setHorizontalScrollBarEnabled(false);
            mContentView = scrollView;

            mSelectTextView = (TextView) inflater.inflate(POPUP_TEXT_LAYOUT, null);
            mSelectTextView.setLayoutParams(mWrapContent);
            mSelectTextView.setText(com.android.internal.R.string.select);
            mSelectTextView.setOnClickListener(this);

            mSelectAllTextView = (TextView) inflater.inflate(POPUP_TEXT_LAYOUT, null);
            mSelectAllTextView.setLayoutParams(mWrapContent);
            mSelectAllTextView.setText(com.android.internal.R.string.selectAll);
            mSelectAllTextView.setOnClickListener(this);

            mCutTextView = (TextView) inflater.inflate(POPUP_TEXT_LAYOUT, null);
            mCutTextView.setLayoutParams(mWrapContent);
            mCutTextView.setText(com.android.internal.R.string.cut);
            mCutTextView.setOnClickListener(this);

            mCopyTextView = (TextView) inflater.inflate(POPUP_TEXT_LAYOUT, null);
            mCopyTextView.setLayoutParams(mWrapContent);
            mCopyTextView.setText(com.android.internal.R.string.copy);
            mCopyTextView.setOnClickListener(this);

            mSearchTextView = (TextView) inflater.inflate(POPUP_TEXT_LAYOUT, null);
            mSearchTextView.setLayoutParams(mWrapContent);
            mSearchTextView.setText(com.android.internal.R.string.ime_action_search);
            mSearchTextView.setOnClickListener(this);

            mShareTextView = (TextView) inflater.inflate(POPUP_TEXT_LAYOUT, null);
            mShareTextView.setLayoutParams(mWrapContent);
            mShareTextView.setText(com.android.internal.R.string.share);
            mShareTextView.setOnClickListener(this);

            mDictTextView = (TextView) inflater.inflate(POPUP_TEXT_LAYOUT, null);
            mDictTextView.setLayoutParams(mWrapContent);
            mDictTextView.setText(com.android.internal.R.string.dictionary);
            mDictTextView.setOnClickListener(this);

            mPasteTextView = (TextView) inflater.inflate(POPUP_TEXT_LAYOUT, null);
            mPasteTextView.setLayoutParams(mWrapContent);
            mPasteTextView.setText(com.android.internal.R.string.paste);
            mPasteTextView.setOnClickListener(this);

            mUndoTextView = (TextView) inflater.inflate(POPUP_TEXT_LAYOUT, null);
            mUndoTextView.setLayoutParams(mWrapContent);
            mUndoTextView.setText(com.android.internal.R.string.undo);
            mUndoTextView.setOnClickListener(this);

            mRedoTextView = (TextView) inflater.inflate(POPUP_TEXT_LAYOUT, null);
            mRedoTextView.setLayoutParams(mWrapContent);
            mRedoTextView.setText(com.android.internal.R.string.redo);
            mRedoTextView.setOnClickListener(this);

            mQuickSnippetTextView = (TextView) inflater.inflate(POPUP_TEXT_LAYOUT, null);
            mQuickSnippetTextView.setLayoutParams(mWrapContent);
            mQuickSnippetTextView.setText(com.android.internal.R.string.quick_snippet);
            mQuickSnippetTextView.setOnClickListener(this);

            Context context = mTextView.getContext();
            mStringUndo = context.getString(com.android.internal.R.string.undo);
            mStringUndoInput = context.getString(com.android.internal.R.string.undo_input);
            mStringUndoCut = context.getString(com.android.internal.R.string.undo_cut);
            mStringUndoDelete = context.getString(com.android.internal.R.string.undo_delete);
            mStringUndoPaste = context.getString(com.android.internal.R.string.undo_paste);
            mStringRedo = context.getString(com.android.internal.R.string.redo);
            mStringRedoInput = context.getString(com.android.internal.R.string.redo_input);
            mStringRedoCut = context.getString(com.android.internal.R.string.redo_cut);
            mStringRedoDelete = context.getString(com.android.internal.R.string.redo_delete);
            mStringRedoPaste = context.getString(com.android.internal.R.string.redo_paste);
        }

        private View createDivider() {
            if (mInflater == null) {
                mInflater = (LayoutInflater) mTextView.getContext().
                        getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            }

            if (mWrapContent == null) {
                mWrapContent = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER);
            }

            ImageView v = new ImageView(mTextView.getContext());
            v.setImageResource(com.android.internal.R.drawable.smartisan_text_edit_divider);
            v.setScaleType(ScaleType.CENTER);
            v.setLayoutParams(mWrapContent);
            return v;
        }

        private boolean isDictSearchAvailable() {
            if ((mHiddenContextMenuItem & ActionMode.MENU_NO_DICT) != 0) {
                return false;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setClassName(mYoudaoDictPackageName, mYoudaoDictClassName);
            return mTextView.getContext().getPackageManager()
                    .queryIntentActivities(intent, 0).size() > 0;
        }

        @Override
        public void show() {
            boolean canSelect = canSelectCurrentWord() && !mTextView.hasSelection();
            boolean canSelectAll = canSelectText() && !mTextView.hasSelection();
            if (!canSelectAll && !mInsertionControllerEnabled) {
                canSelectAll = mTextView.length() > mTextView.getSelectionEnd()
                        - mTextView.getSelectionStart();
            }
            boolean canCut = mTextView.canCut();
            boolean canCopy = mTextView.canCopy();
            boolean canSearch = mTextView.hasSelection()
                    && !(mTextView.getTransformationMethod() instanceof PasswordTransformationMethod);
            // Copy and Undo/Redo never show at the same time.
            boolean canUndo = mTextView.canUndo() && !canCopy;
            boolean canRedo = mTextView.canRedo() && !canCopy;
            boolean canPaste = mTextView.canPaste();
            boolean canInsert = mTextView.canInsert();

            if (!canSelect && !canSelectAll && !canCut && !canCopy && !canSearch
                    && !canUndo && !canRedo && !canPaste && !canInsert) {
                return;
            }

            mMenu.removeAllViews();

            boolean isFirstView = true;
            if (canSelect) {
                if (!isFirstView) mMenu.addView(createDivider());
                mMenu.addView(mSelectTextView);
                isFirstView = false;
            }

            if (canSelectAll) {
                if (!isFirstView) mMenu.addView(createDivider());
                mMenu.addView(mSelectAllTextView);
                isFirstView = false;
            }

            if (canCut) {
                if (!isFirstView) mMenu.addView(createDivider());
                mMenu.addView(mCutTextView);
                isFirstView = false;
            }

            if (canPaste) {
                if (!isFirstView) mMenu.addView(createDivider());
                mMenu.addView(mPasteTextView);
                isFirstView = false;
            }

            if (canCopy) {
                if (!isFirstView) mMenu.addView(createDivider());
                mMenu.addView(mCopyTextView);
                isFirstView = false;
            }

            if (canSearch && isDictSearchAvailable()) {
                if (!isFirstView) mMenu.addView(createDivider());
                mMenu.addView(mDictTextView);
                isFirstView = false;
            }

            if (canSearch && (mHiddenContextMenuItem & ActionMode.MENU_NO_SEARCH) == 0) {
                if (!isFirstView) mMenu.addView(createDivider());
                mMenu.addView(mSearchTextView);
                isFirstView = false;
            }

            if (canSearch && (mHiddenContextMenuItem & ActionMode.MENU_NO_SHARE) == 0
                    && (mTextView.getContext() instanceof Activity)) {
                if (!isFirstView) mMenu.addView(createDivider());
                mMenu.addView(mShareTextView);
                isFirstView = false;
            }

            if (canUndo) {
                if (!isFirstView) mMenu.addView(createDivider());
                mMenu.addView(mUndoTextView);
                isFirstView = false;

                mUndoTextView.setText(getUndoText());
            }

            if (canRedo) {
                if (!isFirstView) mMenu.addView(createDivider());
                mMenu.addView(mRedoTextView);
                isFirstView = false;

                mRedoTextView.setText(getRedoText());
            }

            if (canInsert) {
                if (!isFirstView) mMenu.addView(createDivider());
                mMenu.addView(mQuickSnippetTextView);
                isFirstView = false;
            }

            super.show();
        }

        private String getUndoText() {
            switch (mTextView.getNextUndoType()) {
                case INPUT:
                    return mStringUndoInput;
                case DELETE:
                    return mStringUndoDelete;
                case CUT:
                    return mStringUndoCut;
                case PASTE:
                    return mStringUndoPaste;
                default:
                    return mStringUndo;
            }
        }

        private String getRedoText() {
            switch (mTextView.getNextRedoType()) {
                case INPUT:
                    return mStringRedoInput;
                case DELETE:
                    return mStringRedoDelete;
                case CUT:
                    return mStringRedoCut;
                case PASTE:
                    return mStringRedoPaste;
                default:
                    return mStringRedo;
            }
        }

        @Override
        public void onClick(View view) {
            if (view == mPasteTextView && mTextView.canPaste()) {
                mTextView.onTextContextMenuItem(TextView.ID_PASTE);
                /*
                 * some app override onTextContextMenuItem(int id) cause
                 * TextViewmLayout is null we use onPreDraw() get new mLayout
                 */
                if (mTextView.getLayout() == null) {
                    mTextView.onPreDraw();
                }
            } else if (view == mUndoTextView) {
                mTextView.performUndo();
                if (hasInsertionController() && isCursorVisible()) {
                    getInsertionController().showWithActionPopup();
                }
            } else if (view == mRedoTextView) {
                mTextView.performRedo();
                if (hasInsertionController() && isCursorVisible()) {
                    getInsertionController().showWithActionPopup();
                }
            } else if (view == mSelectTextView) {
                if (selectCurrentWord()) {
                    if (mTextView.getLayout() == null) {
                        mTextView.onPreDraw();
                    }
                    getSelectionController().show();
                }
            } else if (view == mSelectAllTextView) {
                mTextView.onTextContextMenuItem(TextView.ID_SELECT_ALL);
                if (mTextView.getLayout() == null) {
                    mTextView.onPreDraw();
                }
                mHideHandleWhenSelectAll = mIsSmartisanUrlInputView;
                if (hasSelectionController()) {
                    getSelectionController().show();
                }
            } else if (view == mCutTextView) {
                mTextView.onTextContextMenuItem(TextView.ID_CUT);
                if (mTextView.getLayout() == null) {
                    mTextView.onPreDraw();
                }
            } else if (view == mCopyTextView) {
                mTextView.onTextContextMenuItem(TextView.ID_COPY);
                if (mTextView.getLayout() == null) {
                    mTextView.onPreDraw();
                }
            } else if (view == mSearchTextView) {
                int min = 0;
                int max = mTextView.getText().length();

                if (mTextView.isFocused()) {
                    final int selStart = mTextView.getSelectionStart();
                    final int selEnd = mTextView.getSelectionEnd();

                    min = Math.max(0, Math.min(selStart, selEnd));
                    max = Math.max(0, Math.max(selStart, selEnd));
                }
                max = Math.min(max, min + SEARCH_MAX_WORDS_NUMBER);
                if (hasSelectionController()) {
                    getSelectionController().hide();
                }

                String selectedText = mTextView.getTransformedText(min, max).toString();
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(selectedText));
                intent.putExtra("smartisan_search", true);
                intent.putExtra(Browser.EXTRA_CREATE_NEW_TAB, true);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setClassName(PackageManager.DEFAULT_BROWSER_NAME,
                        PackageManager.DEFAULT_BROWSER_NAME + ".BrowserActivity");
                mTextView.getContext().startActivity(intent);
            } else if (view == mShareTextView) {
                InputMethodManager imm = InputMethodManager.peekInstance();
                if (imm != null && imm.isActive(mTextView)) {
                    imm.hideSoftInputFromWindow(mTextView.getWindowToken(), 0);
                }
                int min = 0;
                int max = mTextView.getText().length();

                if (mTextView.isFocused()) {
                    final int selStart = mTextView.getSelectionStart();
                    final int selEnd = mTextView.getSelectionEnd();

                    min = Math.max(0, Math.min(selStart, selEnd));
                    max = Math.max(0, Math.max(selStart, selEnd));
                }
                if (hasSelectionController()) {
                    getSelectionController().hide();
                }

                String selectedText = mTextView.getTransformedText(min, max).toString();
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, selectedText);
                Intent i = Intent.createChooser(send, null);
                i.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
                mTextView.getContext().startActivity(i);
            } else if (view == mDictTextView) {
                int min = 0;
                int max = mTextView.getText().length();

                if (mTextView.isFocused()) {
                    final int selStart = mTextView.getSelectionStart();
                    final int selEnd = mTextView.getSelectionEnd();
                    min = Math.max(0, Math.min(selStart, selEnd));
                    max = Math.max(0, Math.max(selStart, selEnd));
                }
                max = Math.min(max, min + mYoudaoDictMaxQueryWordsNumber);
                if (hasSelectionController()) {
                    getSelectionController().hide();
                }

                String queryWord = mTextView.getTransformedText(min, max)
                        .toString();
                Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("yddictsmartisan://dict/query?q=" + queryWord));
                intent.setClassName(mYoudaoDictPackageName, mYoudaoDictClassName);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Context context = mTextView.getContext();
                context.startActivity(intent,
                        ActivityOptions.makeCustomAnimation(context,
                                com.smartisanos.internal.R.anim.pop_up_in,
                                com.smartisanos.internal.R.anim.fake_anim).toBundle());
            } else if (view == mQuickSnippetTextView) {
                showQuickSnippets();
            }
        }

        @Override
        protected int getTextOffset() {
            int start = mTextView.getSelectionStart();
            int end = mTextView.getSelectionEnd();
            if(start == end) return start;
            Layout layout = mTextView.getLayout();
            if(layout != null) {
                int startLine = layout.getLineForOffset(start);
                int endLine = layout.getLineForOffset(end);
                if(startLine == endLine) {
                    int startVis = isOffsetVisible(start) ? 1 : 0;
                    int endVis = isOffsetVisible(end) ? 1 : 0;
                    if(startVis + endVis == 2 || startVis + endVis == 0) {
                        return (start + end) / 2;
                    } else if (startVis == 1) {
                        return start;
                    } else {
                        return end;
                    }
                }
                if(isOffsetVisible(start) || !isOffsetVisible(end)) {
                    int lineStart = layout.getLineStart(startLine);
                    int lineEnd = layout.getLineEnd(startLine);
                    return (lineStart + lineEnd) / 2;
                } else {
                    int lineStart = layout.getLineStart(endLine);
                    int lineEnd = layout.getLineEnd(endLine);
                    return (lineStart + lineEnd) / 2;
                }
            } else {
                return (start + end) / 2;
            }
        }

        @Override
        protected int getVerticalLocalPosition(int line) {
            return mTextView.getLayout().getLineTop(line) - mContentView.getMeasuredHeight();
        }

        @Override
        protected int clipVertically(int positionY, int parentPositionY, int offset) {
            if (positionY < 0) {
                final Layout layout = mTextView.getLayout();
                final int line = layout.getLineForOffset(offset);
                positionY += layout.getLineBottom(line) - layout.getLineTop(line);
                final int contentViewHeight = mContentView.getMeasuredHeight();
                positionY += contentViewHeight;

                if (!mHideHandleWhenSelectAll) positionY += mHandleViewHeight;
                final int endLine = layout.getLineForOffset(mTextView.getSelectionEnd());
                final int endLinePositionY = layout.getLineBottom(endLine) + parentPositionY
                        + mTextView.viewportToContentVerticalOffset();
                if(positionY + contentViewHeight > endLinePositionY &&
                        positionY < endLinePositionY + mHandleViewHeight) {
                    positionY += layout.getLineBottom(endLine) - layout.getLineBottom(line);
                }
            }
            return positionY;
        }
    }

    private abstract class HandleView extends View implements TextViewPositionListener {
        protected Drawable mDrawable;
        protected Drawable mDrawableLtr;
        protected Drawable mDrawableRtl;
        protected boolean mReversed;
        private final PopupWindow mContainer;
        // Position with respect to the parent TextView
        private int mPositionX, mPositionY;
        private boolean mIsDragging;
        // Offset from touch position to mPosition
        private float mTouchToWindowOffsetX, mTouchToWindowOffsetY;
        protected int mHotspotX;
        // Offsets the hotspot point up, so that cursor is not hidden by the finger when moving up
        private float mTouchOffsetY;
        // Where the touch position should be on the handle to ensure a maximum cursor visibility
        private float mIdealVerticalOffset;
        // Parent's (TextView) previous position in window
        private int mLastParentX, mLastParentY;
        // Transient action popup window for Paste and Replace actions
        protected ActionPopupWindow mActionPopupWindow;
        // Previous text character offset
        protected int mPreviousOffset = -1;
        // Previous text character offset
        private boolean mPositionHasChanged = true;
        // Used to delay the appearance of the action popup window
        private Runnable mActionPopupShower;

        public HandleView(Drawable drawableLtr, Drawable drawableRtl) {
            super(mTextView.getContext());
            mContainer = new PopupWindow(mTextView.getContext(), null,
                    com.android.internal.R.attr.textSelectHandleWindowStyle);
            mContainer.setSplitTouchEnabled(true);
            mContainer.setClippingEnabled(false);
            mContainer.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
            mContainer.setContentView(this);

            mDrawableLtr = drawableLtr;
            mDrawableRtl = drawableRtl;

            updateDrawable();

            final int handleHeight = mDrawable.getIntrinsicHeight();
            mTouchOffsetY = -0.3f * handleHeight;
            mIdealVerticalOffset = 0.7f * handleHeight;
        }

        protected void updateDrawable() {
            final int offset = getCurrentCursorOffset();
            final boolean isRtlCharAtOffset = mTextView.getLayout().isRtlCharAt(offset) ^ mReversed;
            mDrawable = isRtlCharAtOffset ? mDrawableRtl : mDrawableLtr;
            mHotspotX = getHotspotX(mDrawable, isRtlCharAtOffset);
        }

        protected abstract int getHotspotX(Drawable drawable, boolean isRtlRun);

        // Touch-up filter: number of previous positions remembered
        private static final int HISTORY_SIZE = 5;
        private static final int TOUCH_UP_FILTER_DELAY_AFTER = 150;
        private static final int TOUCH_UP_FILTER_DELAY_BEFORE = 350;
        private final long[] mPreviousOffsetsTimes = new long[HISTORY_SIZE];
        private final int[] mPreviousOffsets = new int[HISTORY_SIZE];
        private int mPreviousOffsetIndex = 0;
        private int mNumberPreviousOffsets = 0;

        private void startTouchUpFilter(int offset) {
            mNumberPreviousOffsets = 0;
            addPositionToTouchUpFilter(offset);
        }

        private void addPositionToTouchUpFilter(int offset) {
            mPreviousOffsetIndex = (mPreviousOffsetIndex + 1) % HISTORY_SIZE;
            mPreviousOffsets[mPreviousOffsetIndex] = offset;
            mPreviousOffsetsTimes[mPreviousOffsetIndex] = SystemClock.uptimeMillis();
            mNumberPreviousOffsets++;
        }

        private void filterOnTouchUp() {
            final long now = SystemClock.uptimeMillis();
            int i = 0;
            int index = mPreviousOffsetIndex;
            final int iMax = Math.min(mNumberPreviousOffsets, HISTORY_SIZE);
            while (i < iMax && (now - mPreviousOffsetsTimes[index]) < TOUCH_UP_FILTER_DELAY_AFTER) {
                i++;
                index = (mPreviousOffsetIndex - i + HISTORY_SIZE) % HISTORY_SIZE;
            }

            if (i > 0 && i < iMax &&
                    (now - mPreviousOffsetsTimes[index]) > TOUCH_UP_FILTER_DELAY_BEFORE) {
                positionAtCursorOffset(mPreviousOffsets[index], false);
            }
        }

        public boolean offsetHasBeenChanged() {
            return mNumberPreviousOffsets > 1;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(mDrawable.getIntrinsicWidth(), mDrawable.getIntrinsicHeight());
        }

        public void show() {
            if (isShowing()) return;

            getPositionListener().addSubscriber(this, true /* local position may change */);

            // Make sure the offset is always considered new, even when focusing at same position
            mPreviousOffset = -1;
            positionAtCursorOffset(getCurrentCursorOffset(), false);

            hideActionPopupWindow();
        }

        public int getShowAtOffset() {
            return isShowing() ? mPreviousOffset : -1;
        }

        protected void dismiss() {
            mIsDragging = false;
            mReversed = false;
            mContainer.dismiss();
            onDetached();
        }

        public void hide() {
            dismiss();

            getPositionListener().removeSubscriber(this);
        }

        void showActionPopupWindow(int delay) {
            if (mActionPopupWindow == null) {
                if(mDrawable != null) {
                    mActionPopupWindow = new ActionPopupWindow(mDrawable.getIntrinsicHeight());
                } else {
                    mActionPopupWindow = new ActionPopupWindow(0);
                }
            }
            if (mActionPopupShower == null) {
                mActionPopupShower = new Runnable() {
                    public void run() {
                        mActionPopupWindow.show();
                    }
                };
            } else {
                mTextView.removeCallbacks(mActionPopupShower);
            }
            mTextView.postDelayed(mActionPopupShower, delay);
        }

        protected void hideActionPopupWindow() {
            if (mActionPopupShower != null) {
                mTextView.removeCallbacks(mActionPopupShower);
            }
            if (mActionPopupWindow != null) {
                mActionPopupWindow.hide();
            }
        }

        public boolean isShowing() {
            return mContainer.isShowing();
        }

        private boolean isVisible() {
            // Always show a dragging handle.
            if (mIsDragging) {
                return true;
            }

            if (mTextView.isInBatchEditMode()) {
                return false;
            }

            return isPositionVisible(mPositionX + mHotspotX, mPositionY);
        }

        public abstract int getCurrentCursorOffset();

        protected abstract boolean updateSelection(int offset);

        public abstract void updatePosition(float x, float y);

        /**
         * @return whether update HandleView position
         */
        protected boolean positionAtCursorOffset(int offset, boolean parentScrolled) {
            // Some MovementMethod (such as LinkMovementMethod) would remove selection
            // spans onTouch which may cause we get a -1 offset when getting selection.
            // In this case, the handle view and action popup should be closed than be
            // positioned at a wrong place.
            if (offset < 0) {
                return false;
            }
            // A HandleView relies on the layout, which may be nulled by external methods
            Layout layout = mTextView.getLayout();
            if (layout == null) {
                // Will update controllers' state, hiding them and stopping selection mode if needed
                prepareCursorControllers();
                return true;
            }

            boolean offsetChanged = offset != mPreviousOffset;
            if (offsetChanged || parentScrolled) {
                if (offsetChanged) {
                    if (!updateSelection(offset)) {
                        return false;
                    }
                    addPositionToTouchUpFilter(offset);
                }
                final int line = layout.getLineForOffset(offset);
                final float lineheight=mTextView.getLineHeight();
                final float linespacemulti=mTextView.getLineSpacingMultiplier();
                final float linespaceextra=mTextView.getLineSpacingExtra();
                final float linespace=(linespacemulti-1)*lineheight+linespaceextra;
                mPositionX = (int) (getPrimaryHorizontal(layout,
                        mTextView.getHintLayout(), offset, false) - mHotspotX);
                mPositionY = layout.getLineBottom(line);

                // Take TextView's padding and scroll into account.
                mPositionX += mTextView.viewportToContentHorizontalOffset();
                mPositionY += mTextView.viewportToContentVerticalOffset();
                mPositionY-=linespace;
                mPreviousOffset = offset;
                mPositionHasChanged = true;
            }
            return true;
        }

        public void updatePosition(int parentPositionX, int parentPositionY,
                boolean parentPositionChanged, boolean parentScrolled) {
            if(!positionAtCursorOffset(getCurrentCursorOffset(), parentScrolled)) {
                hide();
                return;
            }
            if (parentPositionChanged || mPositionHasChanged) {
                if (mIsDragging) {
                    // Update touchToWindow offset in case of parent scrolling while dragging
                    if (parentPositionX != mLastParentX || parentPositionY != mLastParentY) {
                        mTouchToWindowOffsetX += parentPositionX - mLastParentX;
                        mTouchToWindowOffsetY += parentPositionY - mLastParentY;
                        mLastParentX = parentPositionX;
                        mLastParentY = parentPositionY;
                    }

                    onHandleMoved();
                }

                if (!mHideHandleWhenSelectAll && isVisible()) {
                    final int positionX = parentPositionX + mPositionX;
                    final int positionY = parentPositionY + mPositionY;
                    if (isShowing()) {
                        mContainer.update(positionX, positionY, -1, -1);
                    } else {
                        mContainer.showAtLocation(mTextView, Gravity.NO_GRAVITY,
                                positionX, positionY);
                        if(mTextView.hasSelection()){
                            showActionPopupWindow(0);
                        }
                    }
                } else {
                    if (isShowing()) {
                        mIsDragging = false;
                        mContainer.dismiss();
                    }
                }

                mPositionHasChanged = false;
            } else if (isShowing() && !mTextView.isShown()) {
                dismiss();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            mDrawable.setBounds(0, 0, mRight - mLeft, mBottom - mTop);
            mDrawable.draw(c);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    startTouchUpFilter(getCurrentCursorOffset());
                    final PositionListener positionListener = getPositionListener();
                    mLastParentX = positionListener.getPositionX();
                    mLastParentY = positionListener.getPositionY();

                    mTouchToWindowOffsetX = ev.getRawX() - mPositionX;
                    mTouchToWindowOffsetY = ev.getRawY() - positionListener.getWindowVerticalOffset() - mPositionY;
                    mIsDragging = true;
                    break;
                }

                case MotionEvent.ACTION_MOVE: {
                    final float rawX = ev.getRawX();
                    final float rawY = ev.getRawY();

                    // Vertical hysteresis: vertical down movement tends to snap to ideal offset
                    final int windowVerticalOffset = getPositionListener().getWindowVerticalOffset();
                    final float previousVerticalOffset = mTouchToWindowOffsetY - mLastParentY;
                    final float currentVerticalOffset = rawY - windowVerticalOffset - mPositionY - mLastParentY;
                    float newVerticalOffset;
                    if (previousVerticalOffset < mIdealVerticalOffset) {
                        newVerticalOffset = Math.min(currentVerticalOffset, mIdealVerticalOffset);
                        newVerticalOffset = Math.max(newVerticalOffset, previousVerticalOffset);
                    } else {
                        newVerticalOffset = Math.max(currentVerticalOffset, mIdealVerticalOffset);
                        newVerticalOffset = Math.min(newVerticalOffset, previousVerticalOffset);
                    }

                    mTouchToWindowOffsetY = newVerticalOffset + mLastParentY;

                    final float newPosX = rawX - mTouchToWindowOffsetX + mDrawable.getIntrinsicWidth() / 2;
                    final float newPosY = rawY - windowVerticalOffset - mTouchToWindowOffsetY + mTouchOffsetY;

                    updatePosition(newPosX, newPosY);
                    break;
                }

                case MotionEvent.ACTION_UP:
                    filterOnTouchUp();
                    mIsDragging = false;
                    break;

                case MotionEvent.ACTION_CANCEL:
                    mIsDragging = false;
                    break;
            }
            return true;
        }

        public boolean isDragging() {
            return mIsDragging;
        }

        void onHandleMoved() {
            hideActionPopupWindow();
        }

        public void onDetached() {
            hideActionPopupWindow();
        }
    }

    private class InsertionHandleView extends HandleView {
        private static final int DELAY_BEFORE_HANDLE_FADES_OUT = 4000;
        private static final int RECENT_CUT_COPY_DURATION = 15 * 1000; // seconds

        private static final int NONE_SHOWING = 0;
        private static final int HANDLE_SHOWING = 1;
        private static final int POPUP_SHOWING = 2;

        // Used to detect taps on the insertion handle, which will affect the ActionPopupWindow
        private float mDownPositionX, mDownPositionY;
        private Runnable mHider;
        private int mShowingStatus;

        public InsertionHandleView(Drawable drawable) {
            super(drawable, drawable);
        }

        @Override
        public void show() {
            mHideHandleWhenSelectAll = false;
            super.show();

            final long durationSinceCutOrCopy =
                    SystemClock.uptimeMillis() - TextView.LAST_CUT_OR_COPY_TIME;
            if (durationSinceCutOrCopy < RECENT_CUT_COPY_DURATION) {
                showActionPopupWindow(0);
            }

            hideAfterDelay();
        }

        public void showWithActionPopup() {
            show();
            showActionPopupWindow(0);
        }

        public void saveShowingStatus() {
            if (mActionPopupWindow != null && mActionPopupWindow.isShowing()) {
                mShowingStatus = POPUP_SHOWING;
            } else if (isShowing()) {
                mShowingStatus = HANDLE_SHOWING;
            } else {
                mShowingStatus = NONE_SHOWING;
            }
        }

        public void restoreShowingStatus() {
            if (mShowingStatus == POPUP_SHOWING) {
                showWithActionPopup();
            } else if (mShowingStatus == HANDLE_SHOWING) {
                show();
            }
            resetShowingStatus();
        }

        public void resetShowingStatus() {
            mShowingStatus = NONE_SHOWING;
        }

        private void hideAfterDelay() {
            if (mHider == null) {
                mHider = new Runnable() {
                    public void run() {
                        hide();
                    }
                };
            } else {
                removeHiderCallback();
            }
            mTextView.postDelayed(mHider, DELAY_BEFORE_HANDLE_FADES_OUT);
        }

        private void removeHiderCallback() {
            if (mHider != null) {
                mTextView.removeCallbacks(mHider);
            }
        }

        @Override
        protected int getHotspotX(Drawable drawable, boolean isRtlRun) {
            return drawable.getIntrinsicWidth() / 2;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            final boolean result = super.onTouchEvent(ev);

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownPositionX = ev.getRawX();
                    mDownPositionY = ev.getRawY();
                    saveShowingStatus();
                    break;

                case MotionEvent.ACTION_UP:
                    if (!offsetHasBeenChanged()) {
                        final float deltaX = mDownPositionX - ev.getRawX();
                        final float deltaY = mDownPositionY - ev.getRawY();
                        final float distanceSquared = deltaX * deltaX + deltaY * deltaY;

                        final ViewConfiguration viewConfiguration = ViewConfiguration.get(
                                mTextView.getContext());
                        final int touchSlop = viewConfiguration.getScaledTouchSlop();

                        if (distanceSquared < touchSlop * touchSlop) {
                            if (mActionPopupWindow != null && mActionPopupWindow.isShowing()) {
                                // Tapping on the handle dismisses the displayed action popup
                                mActionPopupWindow.hide();
                            } else {
                                showWithActionPopup();
                            }
                        }
                    } else {
                        restoreShowingStatus();
                    }

                    hideAfterDelay();
                    break;

                case MotionEvent.ACTION_CANCEL:
                    hideAfterDelay();
                    break;

                default:
                    break;
            }

            return result;
        }

        @Override
        public int getCurrentCursorOffset() {
            return mTextView.getSelectionStart();
        }

        @Override
        public boolean updateSelection(int offset) {
            Selection.setSelection((Spannable) mTextView.getText(), offset);

            // The movements of InertionHanleView cause the cursor of
            // TextView moves. So the SelectionController also need to
            // update. Otherwise when user try to select text, the
            // selection range would be wrong.
            if (hasSelectionController()) {
                getSelectionController().updateMinAndMaxOffset(offset);
            }
            return true;
        }

        @Override
        public void updatePosition(float x, float y) {
            positionAtCursorOffset(mTextView.getOffsetForPosition(x, y), false);
        }

        @Override
        void onHandleMoved() {
            super.onHandleMoved();
            removeHiderCallback();
        }

        @Override
        public void onDetached() {
            super.onDetached();
            removeHiderCallback();
        }
    }

    private class SelectionStartHandleView extends HandleView {

        public SelectionStartHandleView(Drawable drawableLtr, Drawable drawableRtl) {
            super(drawableLtr, drawableRtl);
        }

        @Override
        protected int getHotspotX(Drawable drawable, boolean isRtlRun) {
            if (isRtlRun) {
                return drawable.getIntrinsicWidth() / 7;
            } else {
                return (drawable.getIntrinsicWidth() * 6) / 7;
            }
        }

        @Override
        public int getCurrentCursorOffset() {
            if (mTextView.hasSelection()) {
                return mReversed ? mTextView.getSelectionEnd() : mTextView.getSelectionStart();
            } else {
                return mPreviousOffset;
            }
        }

        @Override
        public boolean updateSelection(int offset) {
            final int selEnd = mTextView.getSelectionEnd();
            if (selEnd < 0) {
                Log.e(TAG, "SelectionStartHandleView fails updateSelection:"
                        + "(" + offset + "," + selEnd
                        + "), selection of mTextView=" + mTextView
                        + " has been removed");
                return false;
            }
            if (mReversed) {
                final int selStart = mTextView.getSelectionStart();
                if (offset < selStart) {
                    Selection.setSelection((Spannable) mTextView.getText(), offset, selStart);
                    mSelectionModifierCursorController.setReversed();
                } else {
                    Selection.setSelection((Spannable) mTextView.getText(), selStart, offset);
                    updateDrawable();
                }
            } else {
                if (offset > selEnd) {
                    Selection.setSelection((Spannable) mTextView.getText(), selEnd, offset);
                    mSelectionModifierCursorController.setReversed();
                } else {
                    Selection.setSelection((Spannable) mTextView.getText(), offset, selEnd);
                    updateDrawable();
                }
            }
            return true;
        }

        @Override
        public void updatePosition(float x, float y) {
            // Handles can not cross and selection is at least one character
            final int offset = mTextView.getOffsetForPosition(x, y);
            final int oldSelEnd = mReversed ? mTextView.getSelectionStart() : mTextView.getSelectionEnd();
            if (offset == oldSelEnd) {
                return;
            }

            positionAtCursorOffset(offset, false);
        }

        public ActionPopupWindow getActionPopupWindow() {
            return mActionPopupWindow;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            final boolean result = super.onTouchEvent(ev);

            if (ev.getAction() == MotionEvent.ACTION_UP) {
                showActionPopupWindow(0);
            }

            return result;
        }
    }

    private class SelectionEndHandleView extends HandleView {

        public SelectionEndHandleView(Drawable drawableLtr, Drawable drawableRtl) {
            super(drawableLtr, drawableRtl);
        }

        @Override
        protected int getHotspotX(Drawable drawable, boolean isRtlRun) {
            if (isRtlRun) {
                return (drawable.getIntrinsicWidth() * 6) / 7;
            } else {
                return drawable.getIntrinsicWidth() / 7;
            }
        }

        @Override
        public int getCurrentCursorOffset() {
            if (mTextView.hasSelection()) {
                return mReversed ? mTextView.getSelectionStart() : mTextView.getSelectionEnd();
            } else {
                return mPreviousOffset;
            }
        }

        @Override
        public boolean updateSelection(int offset) {
            final int selStart = mTextView.getSelectionStart();
            if (selStart < 0) {
                Log.e(TAG, "SelectionEndHandleView fails updateSelection:"
                        + "(" + selStart + "," + offset
                        + "), selection of mTextView=" + mTextView
                        + " has been removed");
                return false;
            }
            if (mReversed) {
                final int selEnd = mTextView.getSelectionEnd();
                if (offset > selEnd) {
                    Selection.setSelection((Spannable) mTextView.getText(), selEnd, offset);
                    mSelectionModifierCursorController.setReversed();
                } else {
                    Selection.setSelection((Spannable) mTextView.getText(), offset, selEnd);
                    updateDrawable();
                }
            } else {
                if (offset < selStart) {
                    Selection.setSelection((Spannable) mTextView.getText(), offset, selStart);
                    mSelectionModifierCursorController.setReversed();
                } else {
                    Selection.setSelection((Spannable) mTextView.getText(), selStart, offset);
                    updateDrawable();
                }
            }
            return true;
        }

        @Override
        public void updatePosition(float x, float y) {
            // Handles can not cross and selection is at least one character
            final int offset = mTextView.getOffsetForPosition(x, y);
            final int oldSelStart = mReversed ? mTextView.getSelectionEnd() : mTextView.getSelectionStart();
            if (offset == oldSelStart) {
                return;
            }

            positionAtCursorOffset(offset, false);
        }

        public void setActionPopupWindow(ActionPopupWindow actionPopupWindow) {
            mActionPopupWindow = actionPopupWindow;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            final boolean result = super.onTouchEvent(ev);

            if (ev.getAction() == MotionEvent.ACTION_UP) {
                showActionPopupWindow(0);
            }

            return result;
        }
    }

    /**
     * A CursorController instance can be used to control a cursor in the text.
     */
    private interface CursorController extends ViewTreeObserver.OnTouchModeChangeListener {
        /**
         * Makes the cursor controller visible on screen.
         * See also {@link #hide()}.
         */
        public void show();

        /**
         * Hide the cursor controller from screen.
         * See also {@link #show()}.
         */
        public void hide();

        /**
         * Called when the view is detached from window. Perform house keeping task, such as
         * stopping Runnable thread that would otherwise keep a reference on the context, thus
         * preventing the activity from being recycled.
         */
        public void onDetached();
    }

    private class InsertionPointCursorController implements CursorController {
        private InsertionHandleView mHandle;

        public void show() {
            getHandle().show();
        }

        public int getShowAtOffset() {
            return getHandle().getShowAtOffset();
        }

        public void showWithActionPopup() {
            getHandle().showWithActionPopup();
        }

        public void hide() {
            if (mHandle != null) {
                mHandle.hide();
            }
        }

        public void onTouchModeChanged(boolean isInTouchMode) {
            if (!isInTouchMode) {
                hide();
            }
        }

        private InsertionHandleView getHandle() {
            if (mSelectHandleCenter == null) {
                mSelectHandleCenter = mTextView.getResources().getDrawable(
                        mTextView.mTextSelectHandleRes);
            }
            if (mHandle == null) {
                mHandle = new InsertionHandleView(mSelectHandleCenter);
            }
            return mHandle;
        }

        public void resetShowingStatus() {
            if (mHandle != null) {
                mHandle.resetShowingStatus();
            }
        }

        public void saveShowingStatus() {
            if (mHandle != null) {
                mHandle.saveShowingStatus();
            }
        }

        public void restoreShowingStatus() {
            if (mHandle != null) {
                mHandle.restoreShowingStatus();
            }
        }

        @Override
        public void onDetached() {
            final ViewTreeObserver observer = mTextView.getViewTreeObserver();
            observer.removeOnTouchModeChangeListener(this);

            if (mHandle != null) mHandle.onDetached();
        }
    }

    class SelectionModifierCursorController implements CursorController {
        private static final int DELAY_BEFORE_REPLACE_ACTION = 200; // milliseconds
        // The cursor controller handles, lazily created when shown.
        private SelectionStartHandleView mStartHandle;
        private SelectionEndHandleView mEndHandle;
        // The offsets of that last touch down event. Remembered to start selection there.
        private int mMinTouchOffset, mMaxTouchOffset;

        // Double tap detection
        private long mPreviousTapUpTime = 0;
        private float mDownPositionX, mDownPositionY;
        private boolean mGestureStayedInTapRegion;
        private boolean mHasHidden = true;

        SelectionModifierCursorController() {
            resetTouchOffsets();
        }

        private boolean checkImeOptions() {
            return mInputContentType == null ? false
                    : mInputContentType.imeOptions != EditorInfo.IME_NULL;
        }

        public void show() {
            if (mTextView.isInBatchEditMode()) {
                return;
            }
            mHasHidden = false;
            if (mTextView.isFocused() && isCursorVisible() && checkImeOptions()) {
                InputMethodManager imm = InputMethodManager.peekInstance();
                imm.showSoftInput(mTextView, 0);
            }
            initDrawables();
            initHandles();
            hideInsertionPointCursorController();
        }

        private void initDrawables() {
            if (mSelectHandleLeft == null) {
                mSelectHandleLeft = mTextView.getContext().getResources().getDrawable(
                        mTextView.mTextSelectHandleLeftRes);
            }
            if (mSelectHandleRight == null) {
                mSelectHandleRight = mTextView.getContext().getResources().getDrawable(
                        mTextView.mTextSelectHandleRightRes);
            }
        }

        private void initHandles() {
            // Lazy object creation has to be done before updatePosition() is called.
            if (mStartHandle == null) {
                mStartHandle = new SelectionStartHandleView(mSelectHandleLeft, mSelectHandleRight);
            }
            if (mEndHandle == null) {
                mEndHandle = new SelectionEndHandleView(mSelectHandleRight, mSelectHandleLeft);
            }

            mStartHandle.show();
            mEndHandle.show();

            // Make sure both left and right handles share the same ActionPopupWindow (so that
            // moving any of the handles hides the action popup).
            mStartHandle.showActionPopupWindow(DELAY_BEFORE_REPLACE_ACTION);
            mEndHandle.setActionPopupWindow(mStartHandle.getActionPopupWindow());

            hideInsertionPointCursorController();
        }

        public void hide() {
            if (mStartHandle != null) mStartHandle.hide();
            if (mEndHandle != null) mEndHandle.hide();
            if (!mHasHidden && mTextView.hasSelection()) {
                Selection.setSelection((Spannable) mTextView.getText(),
                        mTextView.getSelectionEnd());
            }
            mHasHidden = true;
        }

        public void onTouchEvent(MotionEvent event) {
            // This is done even when the View does not have focus, so that long presses can start
            // selection and tap can move cursor from this tap position.
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    final float x = event.getX();
                    final float y = event.getY();

                    // Remember finger down position, to be able to start selection from there
                    mMinTouchOffset = mMaxTouchOffset = mTextView.getOffsetForPosition(x, y);

                    // Double tap detection
                    if (mGestureStayedInTapRegion) {
                        long duration = SystemClock.uptimeMillis() - mPreviousTapUpTime;
                        if (duration <= ViewConfiguration.getDoubleTapTimeout()) {
                            final float deltaX = x - mDownPositionX;
                            final float deltaY = y - mDownPositionY;
                            final float distanceSquared = deltaX * deltaX + deltaY * deltaY;

                            ViewConfiguration viewConfiguration = ViewConfiguration.get(
                                    mTextView.getContext());
                            int doubleTapSlop = viewConfiguration.getScaledDoubleTapSlop();
                            boolean stayedInArea = distanceSquared < doubleTapSlop * doubleTapSlop;

                            if (stayedInArea && isPositionOnText(x, y)) {
                                if (selectCurrentWord()) {
                                    getSelectionController().show();
                                }

                                mDiscardNextActionUp = true;
                            }
                        }
                    }

                    mDownPositionX = x;
                    mDownPositionY = y;
                    mGestureStayedInTapRegion = true;
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                    // Handle multi-point gestures. Keep min and max offset positions.
                    // Only activated for devices that correctly handle multi-touch.
                    if (mTextView.getContext().getPackageManager().hasSystemFeature(
                            PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT)) {
                        updateMinAndMaxOffsets(event);
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (mGestureStayedInTapRegion) {
                        final float deltaX = event.getX() - mDownPositionX;
                        final float deltaY = event.getY() - mDownPositionY;
                        final float distanceSquared = deltaX * deltaX + deltaY * deltaY;

                        final ViewConfiguration viewConfiguration = ViewConfiguration.get(
                                mTextView.getContext());
                        int doubleTapTouchSlop = viewConfiguration.getScaledDoubleTapTouchSlop();

                        if (distanceSquared > doubleTapTouchSlop * doubleTapTouchSlop) {
                            mGestureStayedInTapRegion = false;
                        }
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    mPreviousTapUpTime = SystemClock.uptimeMillis();
                    break;
            }
        }

        /**
         * @param event
         */
        private void updateMinAndMaxOffsets(MotionEvent event) {
            int pointerCount = event.getPointerCount();
            for (int index = 0; index < pointerCount; index++) {
                int offset = mTextView.getOffsetForPosition(event.getX(index), event.getY(index));
                if (offset < mMinTouchOffset) mMinTouchOffset = offset;
                if (offset > mMaxTouchOffset) mMaxTouchOffset = offset;
            }
        }

        public void updateMinAndMaxOffset(int offset) {
            mMinTouchOffset = mMaxTouchOffset = offset;

        }

        public int getMinTouchOffset() {
            return mMinTouchOffset;
        }

        public int getMaxTouchOffset() {
            return mMaxTouchOffset;
        }

        public void resetTouchOffsets() {
            mMinTouchOffset = mMaxTouchOffset = -1;
        }

        /**
         * @return true if this controller is currently used to move the selection start.
         */
        public boolean isSelectionStartDragged() {
            return mStartHandle != null && (mStartHandle.mReversed ? mEndHandle
                    .isDragging() : mStartHandle.isDragging());
        }

        public void onTouchModeChanged(boolean isInTouchMode) {
            if (!isInTouchMode) {
                hide();
            }
        }

        @Override
        public void onDetached() {
            final ViewTreeObserver observer = mTextView.getViewTreeObserver();
            observer.removeOnTouchModeChangeListener(this);

            this.hide();
        }

        public void setReversed() {
            mStartHandle.mReversed = !mStartHandle.mReversed;
            mStartHandle.updateDrawable();
            mStartHandle.mPreviousOffset = -1;
            mStartHandle.invalidate();
            mEndHandle.mReversed = !mEndHandle.mReversed;
            mEndHandle.updateDrawable();
            mEndHandle.invalidate();
            mEndHandle.mPreviousOffset = -1;
        }
    }

    private class CorrectionHighlighter {
        private final Path mPath = new Path();
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int mStart, mEnd;
        private long mFadingStartTime;
        private RectF mTempRectF;
        private final static int FADE_OUT_DURATION = 400;

        public CorrectionHighlighter() {
            mPaint.setCompatibilityScaling(mTextView.getResources().getCompatibilityInfo().
                    applicationScale);
            mPaint.setStyle(Paint.Style.FILL);
        }

        public void highlight(CorrectionInfo info) {
            mStart = info.getOffset();
            mEnd = mStart + info.getNewText().length();
            mFadingStartTime = SystemClock.uptimeMillis();

            if (mStart < 0 || mEnd < 0) {
                stopAnimation();
            }
        }

        public void draw(Canvas canvas, int cursorOffsetVertical) {
            if (updatePath() && updatePaint()) {
                if (cursorOffsetVertical != 0) {
                    canvas.translate(0, cursorOffsetVertical);
                }

                canvas.drawPath(mPath, mPaint);

                if (cursorOffsetVertical != 0) {
                    canvas.translate(0, -cursorOffsetVertical);
                }
                invalidate(true); // TODO invalidate cursor region only
            } else {
                stopAnimation();
                invalidate(false); // TODO invalidate cursor region only
            }
        }

        private boolean updatePaint() {
            final long duration = SystemClock.uptimeMillis() - mFadingStartTime;
            if (duration > FADE_OUT_DURATION) return false;

            final float coef = 1.0f - (float) duration / FADE_OUT_DURATION;
            final int highlightColorAlpha = Color.alpha(mTextView.mHighlightColor);
            final int color = (mTextView.mHighlightColor & 0x00FFFFFF) +
                    ((int) (highlightColorAlpha * coef) << 24);
            mPaint.setColor(color);
            return true;
        }

        private boolean updatePath() {
            final Layout layout = mTextView.getLayout();
            if (layout == null) return false;

            // Update in case text is edited while the animation is run
            final int length = mTextView.getText().length();
            int start = Math.min(length, mStart);
            int end = Math.min(length, mEnd);

            mPath.reset();
            layout.getSelectionPath(start, end, mPath);
            return true;
        }

        private void invalidate(boolean delayed) {
            if (mTextView.getLayout() == null) return;

            if (mTempRectF == null) mTempRectF = new RectF();
            mPath.computeBounds(mTempRectF, false);

            int left = mTextView.getCompoundPaddingLeft();
            int top = mTextView.getExtendedPaddingTop() + mTextView.getVerticalOffset(true);

            if (delayed) {
                mTextView.postInvalidateOnAnimation(
                        left + (int) mTempRectF.left, top + (int) mTempRectF.top,
                        left + (int) mTempRectF.right, top + (int) mTempRectF.bottom);
            } else {
                mTextView.postInvalidate((int) mTempRectF.left, (int) mTempRectF.top,
                        (int) mTempRectF.right, (int) mTempRectF.bottom);
            }
        }

        private void stopAnimation() {
            Editor.this.mCorrectionHighlighter = null;
        }
    }

    private static class ErrorPopup extends PopupWindow {
        private boolean mAbove = false;
        private final TextView mView;
        private int mPopupInlineErrorBackgroundId = 0;
        private int mPopupInlineErrorAboveBackgroundId = 0;

        ErrorPopup(TextView v, int width, int height) {
            super(v, width, height);
            mView = v;
            // Make sure the TextView has a background set as it will be used the first time it is
            // shown and positioned. Initialized with below background, which should have
            // dimensions identical to the above version for this to work (and is more likely).
            mPopupInlineErrorBackgroundId = getResourceId(mPopupInlineErrorBackgroundId,
                    com.android.internal.R.styleable.Theme_errorMessageBackground);
            mView.setBackgroundResource(mPopupInlineErrorBackgroundId);
        }

        void fixDirection(boolean above) {
            mAbove = above;

            if (above) {
                mPopupInlineErrorAboveBackgroundId =
                    getResourceId(mPopupInlineErrorAboveBackgroundId,
                            com.android.internal.R.styleable.Theme_errorMessageAboveBackground);
            } else {
                mPopupInlineErrorBackgroundId = getResourceId(mPopupInlineErrorBackgroundId,
                        com.android.internal.R.styleable.Theme_errorMessageBackground);
            }

            mView.setBackgroundResource(above ? mPopupInlineErrorAboveBackgroundId :
                mPopupInlineErrorBackgroundId);
        }

        private int getResourceId(int currentId, int index) {
            if (currentId == 0) {
                TypedArray styledAttributes = mView.getContext().obtainStyledAttributes(
                        R.styleable.Theme);
                currentId = styledAttributes.getResourceId(index, 0);
                styledAttributes.recycle();
            }
            return currentId;
        }

        @Override
        public void update(int x, int y, int w, int h, boolean force) {
            super.update(x, y, w, h, force);

            boolean above = isAboveAnchor();
            if (above != mAbove) {
                fixDirection(above);
            }
        }
    }

    static class InputContentType {
        int imeOptions = EditorInfo.IME_NULL;
        String privateImeOptions;
        CharSequence imeActionLabel;
        int imeActionId;
        Bundle extras;
        OnEditorActionListener onEditorActionListener;
        boolean enterDown;
    }

    static class InputMethodState {
        Rect mCursorRectInWindow = new Rect();
        RectF mTmpRectF = new RectF();
        float[] mTmpOffset = new float[2];
        ExtractedTextRequest mExtractedTextRequest;
        final ExtractedText mExtractedText = new ExtractedText();
        int mBatchEditNesting;
        boolean mCursorChanged;
        boolean mSelectionModeChanged;
        boolean mContentChanged;
        int mChangedStart, mChangedEnd, mChangedDelta;
    }

    public static class UndoInputFilter implements InputFilter {
        final Editor mEditor;

        public UndoInputFilter(Editor editor) {
            mEditor = editor;
        }

        @Override
        public CharSequence filter(CharSequence source, int start, int end,
                Spanned dest, int dstart, int dend) {
            if (DEBUG_UNDO) {
                Log.d(TAG, "filter: source=" + source + " (" + start + "-" + end + ")");
                Log.d(TAG, "filter: dest=" + dest + " (" + dstart + "-" + dend + ")");
            }
            final UndoManager um = mEditor.mUndoManager;
            if (um.isInUndo()) {
                if (DEBUG_UNDO) Log.d(TAG, "*** skipping, currently performing undo/redo");
                return null;
            }

            um.beginUpdate("Edit text");
            TextModifyOperation op = um.getLastOperation(
                    TextModifyOperation.class, mEditor.mUndoOwner, UndoManager.MERGE_MODE_UNIQUE);
            if (op != null) {
                if (DEBUG_UNDO) Log.d(TAG, "Last op: range=(" + op.mRangeStart + "-" + op.mRangeEnd
                        + "), oldText=" + op.mOldText);
                // See if we can continue modifying this operation.
                if (op.mOldText == null) {
                    // The current operation is an add...  are we adding more?  We are adding
                    // more if we are either appending new text to the end of the last edit or
                    // completely replacing some or all of the last edit.
                    if (start < end && ((dstart >= op.mRangeStart && dend <= op.mRangeEnd)
                            || (dstart == op.mRangeEnd && dend == op.mRangeEnd))) {
                        op.mRangeEnd = dstart + (end-start);
                        um.endUpdate();
                        if (DEBUG_UNDO) Log.d(TAG, "*** merging with last op, mRangeEnd="
                                + op.mRangeEnd);
                        return null;
                    }
                } else {
                    // The current operation is a delete...  can we delete more?
                    if (start == end && dend == op.mRangeStart-1) {
                        SpannableStringBuilder str;
                        if (op.mOldText instanceof SpannableString) {
                            str = (SpannableStringBuilder)op.mOldText;
                        } else {
                            str = new SpannableStringBuilder(op.mOldText);
                        }
                        str.insert(0, dest, dstart, dend);
                        op.mRangeStart = dstart;
                        op.mOldText = str;
                        um.endUpdate();
                        if (DEBUG_UNDO) Log.d(TAG, "*** merging with last op, range=("
                                + op.mRangeStart + "-" + op.mRangeEnd
                                + "), oldText=" + op.mOldText);
                        return null;
                    }
                }

                // Couldn't add to the current undo operation, need to start a new
                // undo state for a new undo operation.
                um.commitState(null);
                um.setUndoLabel("Edit text");
            }

            // Create a new undo state reflecting the operation being performed.
            op = new TextModifyOperation(mEditor.mUndoOwner);
            op.mRangeStart = dstart;
            if (start < end) {
                op.mRangeEnd = dstart + (end-start);
            } else {
                op.mRangeEnd = dstart;
            }
            if (dstart < dend) {
                op.mOldText = dest.subSequence(dstart, dend);
            }
            if (DEBUG_UNDO) Log.d(TAG, "*** adding new op, range=(" + op.mRangeStart
                    + "-" + op.mRangeEnd + "), oldText=" + op.mOldText);
            um.addOperation(op, UndoManager.MERGE_MODE_NONE);
            um.endUpdate();
            return null;
        }
    }

    public static class TextModifyOperation extends UndoOperation<TextView> {
        int mRangeStart, mRangeEnd;
        CharSequence mOldText;

        public TextModifyOperation(UndoOwner owner) {
            super(owner);
        }

        public TextModifyOperation(Parcel src, ClassLoader loader) {
            super(src, loader);
            mRangeStart = src.readInt();
            mRangeEnd = src.readInt();
            mOldText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(src);
        }

        @Override
        public void commit() {
        }

        @Override
        public void undo() {
            swapText();
        }

        @Override
        public void redo() {
            swapText();
        }

        private void swapText() {
            // Both undo and redo involves swapping the contents of the range
            // in the text view with our local text.
            TextView tv = getOwnerData();
            Editable editable = (Editable)tv.getText();
            CharSequence curText;
            if (mRangeStart >= mRangeEnd) {
                curText = null;
            } else {
                curText = editable.subSequence(mRangeStart, mRangeEnd);
            }
            if (DEBUG_UNDO) {
                Log.d(TAG, "Swap: range=(" + mRangeStart + "-" + mRangeEnd
                        + "), oldText=" + mOldText);
                Log.d(TAG, "Swap: curText=" + curText);
            }
            if (mOldText == null) {
                editable.delete(mRangeStart, mRangeEnd);
                mRangeEnd = mRangeStart;
            } else {
                editable.replace(mRangeStart, mRangeEnd, mOldText);
                mRangeEnd = mRangeStart + mOldText.length();
            }
            mOldText = curText;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mRangeStart);
            dest.writeInt(mRangeEnd);
            TextUtils.writeToParcel(mOldText, dest, flags);
        }

        public static final Parcelable.ClassLoaderCreator<TextModifyOperation> CREATOR
                = new Parcelable.ClassLoaderCreator<TextModifyOperation>() {
            public TextModifyOperation createFromParcel(Parcel in) {
                return new TextModifyOperation(in, null);
            }

            public TextModifyOperation createFromParcel(Parcel in, ClassLoader loader) {
                return new TextModifyOperation(in, loader);
            }

            public TextModifyOperation[] newArray(int size) {
                return new TextModifyOperation[size];
            }
        };
    }
}
