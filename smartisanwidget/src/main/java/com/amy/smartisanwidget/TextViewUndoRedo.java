package android.widget;

/*
 * THIS CLASS IS PROVIDED TO THE PUBLIC DOMAIN FOR FREE WITHOUT ANY
 * RESTRICTIONS OR ANY WARRANTY.
 */

import java.util.LinkedList;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Selection;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.widget.TextView;

/**
 * A generic undo/redo implementation for TextViews.
 * 
 * @hide
 */
public class TextViewUndoRedo {

    static private final String TAG = "TextViewUndoRedo";
    static private final long RECENT_CUT_PASTE_DURATION = 200;

    static public enum EDIT_TYPE {
        UNKNOW(0), INPUT(1), DELETE(2), CUT(3), PASTE(4);

        public static EDIT_TYPE fromInt(int value) {
            switch (value) {
                case 1:
                    return INPUT;
                case 2:
                    return DELETE;
                case 3:
                    return CUT;
                case 4:
                    return PASTE;
                default:
                    return UNKNOW;
            }
        }

        private int mValue;

        EDIT_TYPE(int i) {
            mValue = i;
        }

        public int toInt() {
            return mValue;
        }
    };

    /**
     * Is undo/redo being performed? This member signals if an undo/redo
     * operation is currently being performed. Changes in the text during
     * undo/redo are not recorded because it would mess up the undo history.
     */
    private boolean mIsUndoOrRedo = false;

    /**
     * The edit history.
     */
    private EditHistory mEditHistory;

    /**
     * The change listener.
     */
    private EditTextChangeListener mChangeListener;

    /**
     * The edit text.
     */
    private TextView mTextView;

    private ClipboardManager mClipBoard;

    // =================================================================== //

    /**
     * Create a new TextViewUndoRedo and attach it to the specified TextView.
     * 
     * @param textView The text view for which the undo/redo is implemented.
     */
    public TextViewUndoRedo(TextView textView) {
        mTextView = textView;
        mEditHistory = new EditHistory();
        mChangeListener = new EditTextChangeListener();
        mTextView.addTextChangedListener(mChangeListener);
        mClipBoard = (ClipboardManager) textView.getContext().
                getSystemService(Context.CLIPBOARD_SERVICE);
    }

    // =================================================================== //

    /**
     * Disconnect this undo/redo from the text view.
     */
    public void disconnect() {
        mTextView.removeTextChangedListener(mChangeListener);
    }

    /**
     * Set the maximum history size. If size is negative, then history size is
     * only limited by the device memory.
     */
    public void setMaxHistorySize(int maxHistorySize) {
        mEditHistory.setMaxHistorySize(maxHistorySize);
    }

    /**
     * Clear history.
     */
    public void clearHistory() {
        mEditHistory.clear();
    }

    public void dump() {
        Log.d(TAG, "Edit history dump:");
        mEditHistory.dump();
    }

    /**
     * Can undo be performed?
     */
    public boolean getCanUndo() {
        return (mEditHistory.mmPosition > 0);
    }

    public EDIT_TYPE getNextUndoType() {
        return mEditHistory.getNextUndoType();
    }

    public EDIT_TYPE getNextRedoType() {
        return mEditHistory.getNextRedoType();
    }

    /**
     * Perform undo.
     */
    public void undo() {
        EditItem edit = mEditHistory.getPrevious();
        if (edit == null) {
            return;
        }

        Editable text = mTextView.getEditableText();
        int start = edit.mmStart;
        int end = start + (edit.mmAfter != null ? edit.mmAfter.length() : 0);

        if (end > text.length()) {
            // If TextView set character filter, it may cause undo() option fails.
            // So we don't perform undo() here.
            return;
        }
        mIsUndoOrRedo = true;
        text.replace(start, end, edit.mmBefore);
        mIsUndoOrRedo = false;

        // This will get rid of underlines inserted when editor tries to come
        // up with a suggestion.
        for (Object o : text.getSpans(0, text.length(), UnderlineSpan.class)) {
            text.removeSpan(o);
        }

        Selection.setSelection(text, edit.mmBefore == null ? start
                : (start + edit.mmBefore.length()));
    }

    /**
     * Can redo be performed?
     */
    public boolean getCanRedo() {
        return (mEditHistory.mmPosition < mEditHistory.mmHistory.size());
    }

    /**
     * Perform redo.
     */
    public void redo() {
        EditItem edit = mEditHistory.getNext();
        if (edit == null) {
            return;
        }

        Editable text = mTextView.getEditableText();
        int start = edit.mmStart;
        int end = start + (edit.mmBefore != null ? edit.mmBefore.length() : 0);

        if (end > text.length()) {
            // If TextView set character filter, it may cause redo() option fails.
            // So we don't perform redo() here.
            return;
        }
        mIsUndoOrRedo = true;
        text.replace(start, end, edit.mmAfter);
        mIsUndoOrRedo = false;

        // This will get rid of underlines inserted when editor tries to come
        // up with a suggestion.
        for (Object o : text.getSpans(0, text.length(), UnderlineSpan.class)) {
            text.removeSpan(o);
        }

        Selection.setSelection(text, edit.mmAfter == null ? start
                : (start + edit.mmAfter.length()));
    }

    /**
     * Store preferences.
     */
    public void storePersistentState(Editor editor, String prefix) {
        // Store hash code of text in the editor so that we can check if the
        // editor contents has changed.
        editor.putString(prefix + ".hash",
                String.valueOf(mTextView.getText().toString().hashCode()));
        editor.putInt(prefix + ".maxSize", mEditHistory.mmMaxHistorySize);
        editor.putInt(prefix + ".position", mEditHistory.mmPosition);
        editor.putInt(prefix + ".size", mEditHistory.mmHistory.size());

        int i = 0;
        for (EditItem ei : mEditHistory.mmHistory) {
            String pre = prefix + "." + i;

            editor.putInt(pre + ".start", ei.mmStart);
            editor.putString(pre + ".before", ei.mmBefore.toString());
            editor.putString(pre + ".after", ei.mmAfter.toString());
            editor.putInt(pre + ".type", ei.mmEditType.toInt());

            i++;
        }
    }

    /**
     * Restore preferences.
     * 
     * @param prefix The preference key prefix used when state was stored.
     * @return did restore succeed? If this is false, the undo history will be
     *         empty.
     */
    public boolean restorePersistentState(SharedPreferences sp, String prefix)
            throws IllegalStateException {

        boolean ok = doRestorePersistentState(sp, prefix);
        if (!ok) {
            mEditHistory.clear();
        }

        return ok;
    }

    private boolean doRestorePersistentState(SharedPreferences sp, String prefix) {

        String hash = sp.getString(prefix + ".hash", null);
        if (hash == null) {
            // No state to be restored.
            return true;
        }

        if (Integer.valueOf(hash) != mTextView.getText().toString().hashCode()) {
            return false;
        }

        mEditHistory.clear();
        mEditHistory.mmMaxHistorySize = sp.getInt(prefix + ".maxSize", -1);

        int count = sp.getInt(prefix + ".size", -1);
        if (count == -1) {
            return false;
        }

        for (int i = 0; i < count; i++) {
            String pre = prefix + "." + i;

            int start = sp.getInt(pre + ".start", -1);
            String before = sp.getString(pre + ".before", null);
            String after = sp.getString(pre + ".after", null);
            int type = sp.getInt(pre + ".type", 0);

            if (start == -1 || before == null || after == null) {
                return false;
            }

            mEditHistory.add(new EditItem(start, before, after, EDIT_TYPE.fromInt(type)));
        }

        mEditHistory.mmPosition = sp.getInt(prefix + ".position", -1);
        if (mEditHistory.mmPosition == -1) {
            return false;
        }

        return true;
    }

    // =================================================================== //

    /**
     * Keeps track of all the edit history of a text.
     */
    private final class EditHistory {

        /**
         * The position from which an EditItem will be retrieved when getNext()
         * is called. If getPrevious() has not been called, this has the same
         * value as mmHistory.size().
         */
        private int mmPosition = 0;

        /**
         * Maximum undo history size.
         */
        private int mmMaxHistorySize = -1;

        /**
         * The list of edits in chronological order.
         */
        private final LinkedList<EditItem> mmHistory = new LinkedList<EditItem>();

        /**
         * Clear history.
         */
        private void clear() {
            mmPosition = 0;
            mmHistory.clear();
        }

        /**
         * Adds a new edit operation to the history at the current position. If
         * executed after a call to getPrevious() removes all the future history
         * (elements with positions >= current history position).
         */
        private void add(EditItem item) {
            while (mmHistory.size() > mmPosition) {
                mmHistory.removeLast();
            }

            mmHistory.add(item);
            mmPosition++;

            if (mmMaxHistorySize >= 0) {
                trimHistory();
            }
        }

        private void dump() {
            for (int i = 0; i < mmHistory.size(); i++ ) {
                Log.d(TAG, "History " + i + ": " + mmHistory.get(i).toString());
            }
        }

        /**
         * Set the maximum history size. If size is negative, then history size
         * is only limited by the device memory.
         */
        private void setMaxHistorySize(int maxHistorySize) {
            mmMaxHistorySize = maxHistorySize;
            if (mmMaxHistorySize >= 0) {
                trimHistory();
            }
        }

        /**
         * Trim history when it exceeds max history size.
         */
        private void trimHistory() {
            while (mmHistory.size() > mmMaxHistorySize) {
                mmHistory.removeFirst();
                mmPosition--;
            }

            if (mmPosition < 0) {
                mmPosition = 0;
            }
        }

        /**
         * Traverses the history backward by one position, returns and item at
         * that position.
         */
        private EditItem getPrevious() {
            if (mmPosition == 0) {
                return null;
            }
            mmPosition--;
            return mmHistory.get(mmPosition);
        }

        /**
         * Traverses the history forward by one position, returns and item at
         * that position.
         */
        private EditItem getNext() {
            if (mmPosition >= mmHistory.size()) {
                return null;
            }

            EditItem item = mmHistory.get(mmPosition);
            mmPosition++;
            return item;
        }

        private EDIT_TYPE getNextUndoType() {
            if (mmPosition == 0) {
                return EDIT_TYPE.UNKNOW;
            }

            return mmHistory.get(mmPosition - 1).mmEditType;
        }

        private EDIT_TYPE getNextRedoType() {
            if (mmPosition >= mmHistory.size()) {
                return EDIT_TYPE.UNKNOW;
            }

            return mmHistory.get(mmPosition).mmEditType;
        }

        private int getNextStart() {
            if (mmPosition == 0) {
                return -1;
            }

            return mmHistory.get(mmPosition - 1).mmStart;
        }
    }

    /**
     * Represents the changes performed by a single edit operation.
     */
    private final class EditItem {
        private final int mmStart;
        private final CharSequence mmBefore;
        private final CharSequence mmAfter;
        private EDIT_TYPE mmEditType;

        /**
         * Constructs EditItem of a modification that was applied at position
         * start and replaced CharSequence before with CharSequence after.
         */
        public EditItem(int start, CharSequence before, CharSequence after, EDIT_TYPE editType) {
            mmStart = start;
            mmBefore = before;
            mmAfter = after;
            mmEditType = editType;
        }

        @Override
        public String toString() {
            return "EditItem, mmStart = " + mmStart + ", (" + mmBefore + ", " + mmAfter + ");";
        }
    }

    /**
     * Class that listens to changes in the text.
     */
    private final class EditTextChangeListener implements TextWatcher {

        /**
         * The text that will be removed by the change event.
         */
        private CharSequence mBeforeChange;

        /**
         * The text that was inserted by the change event.
         */
        private CharSequence mAfterChange;

        public void beforeTextChanged(CharSequence s, int start, int count,
                int after) {
            if (mIsUndoOrRedo || (after <= 0 && count <= 0)) {
                return;
            }

            mBeforeChange = s.subSequence(start, start + count);
        }

        public void onTextChanged(CharSequence s, int start, int before,
                int count) {
            EDIT_TYPE editType = EDIT_TYPE.UNKNOW;
            CharSequence clipText = null;
            long now = SystemClock.uptimeMillis();

            if (mIsUndoOrRedo || (before <= 0 && count <= 0)) {
                return;
            }

            mAfterChange = s.subSequence(start, start + count);
            if (mBeforeChange.toString().equals(mAfterChange.toString())) {
                // should return if there is no real change
                return;
            }

            ClipData clipData = mClipBoard.getPrimaryClip();
            if (clipData != null && clipData.getItemCount() > 0) {
                clipText = clipData.getItemAt(0).getText();
            }

            if (count == 0) {
                if (TextUtils.equals(mBeforeChange, clipText)) {
                    if (clipText.length() == 1
                            && now - TextView.LAST_CUT_OR_COPY_TIME > RECENT_CUT_PASTE_DURATION) {
                        editType = EDIT_TYPE.INPUT;
                    } else {
                        editType = EDIT_TYPE.CUT;

                    }
                } else {
                    editType = EDIT_TYPE.DELETE;
                    if (mBeforeChange != null
                            && mEditHistory.getNextUndoType() == EDIT_TYPE.DELETE
                            && mEditHistory.getNextStart() == start + mBeforeChange.length()) {
                        EditItem prevItem = mEditHistory.getPrevious();
                        mBeforeChange = (new StringBuilder(mBeforeChange).append(prevItem.mmBefore));
                    }
                }
            } else {
                if (TextUtils.equals(mAfterChange, clipText)) {
                    if (clipText.length() == 1
                            && now - TextView.LAST_PASTE_TIME > RECENT_CUT_PASTE_DURATION) {
                        editType = EDIT_TYPE.INPUT;
                    } else {
                        editType = EDIT_TYPE.PASTE;
                    }
                } else {
                    editType = EDIT_TYPE.INPUT;
                }
            }

            mEditHistory.add(new EditItem(start, mBeforeChange, mAfterChange, editType));
        }

        public void afterTextChanged(Editable s) {
        }
    }
}
