package android.widget;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.Animator.AnimatorListener;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.database.Cursor;
import android.graphics.Point;
import android.net.Uri;
import android.provider.QuickPhrases;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.LongSparseArray;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Animation.AnimationListener;
import android.widget.AbsListView.OnScrollListener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.android.internal.R;

/** @hide **/
public abstract class QuickSnippetView {
    private static final int MAX_SNIPPET_LENGHT = 100;
    private static final int MAX_ITEM_NUMBER = 10;
    private static final int MAX_DIALOG_TEXT_LINES = 8;

    private static final int TOKEN_QUERY = 0;
    private static final int TOKEN_INSERT = 1;
    private static final int TOKEN_UPDATE = 2;
    private static final int TOKEN_DELETE = 3;

    private static final int REMOVE_ICON_ANIM_DELAY = 100;
    private static final int REMOVE_ICON_ANIM_VIS_DURATION = 200;
    private static final int REMOVE_ICON_ANIM_INVIS_DURATION = 150;
    private static final int TEXT_ITEM_ANIM_DURATION = 300;

    private final ArrayList<QuickSnippet> mQuickSnippets = new ArrayList<QuickSnippet>();
    private final QuickSnippetAdapter mQuickSnippetAdapter = new QuickSnippetAdapter();
    private final ModeChanger mModeChanger = new ModeChanger();
    private final QueryHandler mQueryHandler;
    private final Dialog mQuickSnippetDialog;
    private final ContextThemeWrapper mUiContext;
    private final LayoutInflater mInflater;
    private final int mTextItemOffset;

    private boolean mIsShowingUp = false;
    private boolean mHasShown = false;
    private boolean mIsEditMode = false;
    private boolean mScrollToBottom = false;
    private boolean mDisableAdd = false;
    private boolean mAddVisible;
    private ListView mSnippetListView;
    private View mContentView;
    private AlertDialog mEditQuickSnippetDialog;

    private class QuickSnippet {
        public String snippet;
        public int id;

        public QuickSnippet(String _snippet, int _id) {
            snippet = _snippet;
            id = _id;
        }
    }

    public QuickSnippetView(Context context) {
        mUiContext = new ContextThemeWrapper(context,
                android.R.style.Theme_DeviceDefault_Light);
        mInflater = (LayoutInflater) mUiContext
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mQueryHandler = new QueryHandler(mUiContext.getContentResolver());
        mQuickSnippetDialog = new Dialog(mUiContext,
                R.style.Theme_Smartisanos_Resolver);
        mTextItemOffset = mUiContext.getResources().getDimensionPixelOffset(
                R.dimen.quick_snippet_text_item_offset);
    }

    public abstract void insertSnippet(String snippet);

    public void show() {
        asyncQueryPhrase();
    }

    public void hide() {
        if (mQuickSnippetDialog != null) {
            mQuickSnippetDialog.dismiss();
        }
    }

    public boolean isShowingUp() {
        return mIsShowingUp;
    }

    public void onParentLostFocus() {
        mIsShowingUp = false;
    }

    public void handleConfigurationChange() {
        if (mHasShown) {
            hide();
            show();
        }
    }

    public void disableAdd(boolean disable) {
        mDisableAdd = disable;
    }

    private void updateButtons(final int snippetNum) {
        final Button btnEdit = (Button) mContentView.findViewById(R.id.snippet_btn_edit);
        final Button btnCancel = (Button) mContentView.findViewById(R.id.snippet_btn_cancel);
        final Button btnDone = (Button) mContentView.findViewById(R.id.snippet_btn_done);
        if (mIsEditMode) {
            btnDone.setVisibility(View.VISIBLE);
            btnEdit.setVisibility(View.INVISIBLE);
            btnCancel.setVisibility(View.INVISIBLE);
        } else {
            btnDone.setVisibility(View.INVISIBLE);
            btnEdit.setVisibility(View.VISIBLE);
            btnCancel.setVisibility(View.VISIBLE);
        }
        btnEdit.setEnabled(snippetNum > 0);
        btnEdit.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mIsEditMode = true;
                mModeChanger.beginEdit(mSnippetListView);
                updateButtons(snippetNum);
            }
        });
        btnCancel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mQuickSnippetDialog.dismiss();
            }
        });
        btnDone.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mIsEditMode = false;
                mModeChanger.endEdit(mSnippetListView);
                updateButtons(snippetNum);
            }
        });
    }

    void showQuickSnippetListDialog() {
        mContentView = (View) mInflater.inflate(
                R.layout.quick_snippet_layout, null, false);
        final ListView listView = (ListView) mContentView.findViewById(R.id.snippets_list);
        listView.setAdapter(mQuickSnippetAdapter);
        listView.setOnScrollListener(new OnScrollListener() {

            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                mModeChanger.resetChangeMode();
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem,
                    int visibleItemCount, int totalItemCount) {
            }
        });
        final int snippetNum = mQuickSnippets.size();
        mAddVisible = snippetNum < MAX_ITEM_NUMBER;
        updateButtons(snippetNum);
        mQuickSnippetDialog.setContentView(mContentView);
        Window window = mQuickSnippetDialog.getWindow();
        window.setGravity(Gravity.BOTTOM);
        Display display = ((WindowManager) mUiContext.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        Point point = new Point();
        display.getSize(point);
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.width = point.x;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        lp.setTitle(WindowManager.LayoutParams.TITLE_QUICK_SNIPPET);
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        mQuickSnippetDialog.setOnDismissListener(new OnDismissListener() {

            @Override
            public void onDismiss(DialogInterface dialog) {
                mIsEditMode = false;
                mScrollToBottom = false;
                mHasShown = false;
                if (mEditQuickSnippetDialog != null) {
                    mEditQuickSnippetDialog.dismiss();
                }
            }
        });
        mIsShowingUp = true;
        mQuickSnippetDialog.show();
        mHasShown = true;
        mSnippetListView = listView;
    }

    private void showEditQuickSnippetDialog(final String snippet, final int id) {
        final EditText editText = new EditText(mUiContext);
        editText.setFilters(new InputFilter[] {
                new InputFilter.LengthFilter(MAX_SNIPPET_LENGHT)
        });
        editText.setMaxLines(MAX_DIALOG_TEXT_LINES);
        final boolean isEdit = id != -1;
        if (isEdit) {
            editText.setText(snippet);
            editText.selectAll();
        }
        final int padding = mUiContext.getResources().getDimensionPixelOffset(
                            R.dimen.quick_snippet_dialog_edittext_padding);
        final AlertDialog editQuickSnippetDialog = new AlertDialog.Builder(mUiContext,
                            AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                .setTitle(isEdit ? R.string.edit_quick_snippet
                        : R.string.add_quick_snippet)
                .setView(editText, padding, padding, padding, padding)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final String input = editText.getText().toString().trim();
                        if (isEdit) {
                            asyncUpdatePhrase(id, input);
                        } else {
                            mScrollToBottom = true;
                            asyncSavePhrase(input);
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                })
                .create();
        editQuickSnippetDialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        editQuickSnippetDialog.show();
        final Button positiveButton = editQuickSnippetDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (!isEdit) {
            positiveButton.setEnabled(false);
        }
        editText.addTextChangedListener(new TextWatcher() {

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count,
                    int after) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                final String input = s.toString().trim();
                positiveButton.setEnabled(input.length() > 0);
            }
        });
        mEditQuickSnippetDialog = editQuickSnippetDialog;
    }

    private ContentValues buildContentValues(String text) {
        ContentValues values = new ContentValues();
        values.put(QuickPhrases.ItemsRecord.COLUMN_PHRASE, text);
        return values;
    }

    private void asyncQueryPhrase() {
        mQueryHandler.startQuery(TOKEN_QUERY, null, QuickPhrases.CONTENT_URI, null,
                null, null, QuickPhrases.ItemsRecord.COLUMN_POS + " ASC");
    }

    private void asyncDeletePhrase(int id) {
        mQueryHandler.startDelete(TOKEN_DELETE, null, QuickPhrases.CONTENT_URI,
                "_id=" + id, null);
    }

    private void asyncUpdatePhrase(int id, String text) {
        mQueryHandler.startUpdate(TOKEN_UPDATE, null, QuickPhrases.CONTENT_URI,
                buildContentValues(text), "_id=" + id, null);
    }

    private void asyncSavePhrase(String text) {
        mQueryHandler.startInsert(TOKEN_INSERT, text, QuickPhrases.CONTENT_URI,
                buildContentValues(text));
    }

    private class QueryHandler extends AsyncQueryHandler {

        public QueryHandler(ContentResolver cr) {
            super(cr);
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, final Cursor c) {
            super.onQueryComplete(token, cookie, c);
            if (c != null) {
                mQuickSnippets.clear();
                if (c.getCount() > 0) {
                    final int id_index = c.getColumnIndex(QuickPhrases.ItemsRecord._ID);
                    final int detail_index = c.getColumnIndex(QuickPhrases.ItemsRecord.COLUMN_PHRASE);
                    while (c.moveToNext()) {
                        QuickSnippet snippet = new QuickSnippet(
                                c.getString(detail_index),
                                c.getInt(id_index));
                        mQuickSnippets.add(snippet);
                    }
                }
                c.close();
                mQuickSnippetAdapter.notifyDataSetChanged();
                if (!mHasShown) {
                    showQuickSnippetListDialog();
                    return;
                }
                final int snippetNum = mQuickSnippets.size();
                if (mScrollToBottom) {
                    mSnippetListView.setSelection(snippetNum);
                    mScrollToBottom = false;
                    if (snippetNum == 1) {
                        updateButtons(snippetNum);
                    }
                } else if (snippetNum == 0) {
                    mIsEditMode = false;
                    updateButtons(snippetNum);
                }
                if (!mIsEditMode) {
                    mAddVisible = snippetNum < MAX_ITEM_NUMBER;
                }
            }
        }

        @Override
        protected void onInsertComplete(int token, Object cookie, Uri uri) {
            super.onInsertComplete(token, cookie, uri);
            asyncQueryPhrase();
        }

        @Override
        protected void onUpdateComplete(int token, Object cookie, int result) {
            super.onUpdateComplete(token, cookie, result);
            asyncQueryPhrase();
        }

        @Override
        protected void onDeleteComplete(int token, Object cookie, int result) {
            super.onDeleteComplete(token, cookie, result);
            asyncQueryPhrase();
        }
    }

    private void animateRemoval(final AnimationListener listener, final View hintView, final int deletePosition) {
        final LongSparseArray<Integer> mItemIdTopMap = new LongSparseArray<Integer>();
        final long moveDuration = 200;
        final long delayStep = 30;

        int firstVisiblePosition = mSnippetListView.getFirstVisiblePosition();

        int childCount = mSnippetListView.getChildCount();
        for (int i = 0; i < childCount; ++i) {
            View child = mSnippetListView.getChildAt(i);
            if (child != hintView) {
                int position = firstVisiblePosition + i;
                long itemId = mQuickSnippetAdapter.getItemId(position);
                mItemIdTopMap.put(itemId, child.getTop());
            }
        }
        hintView.setLayoutParams(new AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, 1));
        final ViewTreeObserver observer = mSnippetListView.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                observer.removeOnPreDrawListener(this);
                int firstVisiblePosition = mSnippetListView.getFirstVisiblePosition();
                ViewPropertyAnimator maxDelayAnim = null;
                int count = mSnippetListView.getChildCount();
                for (int i = 0; i < count; ++i) {
                    final View child = mSnippetListView.getChildAt(i);
                    int position = firstVisiblePosition + i;
                    long itemId = mQuickSnippetAdapter.getItemId(position);
                    Integer startTop = mItemIdTopMap.get(itemId);
                    int top = child.getTop();
                    long delay = delayStep * (Math.abs(i - deletePosition));
                    int delta = 0;
                    if (startTop != null) {
                        if (startTop != top) {
                            delta = startTop - top;
                        }
                    } else {
                        // Animate new views along with the others. The catch is that they did not
                        // exist in the start state, so we must calculate their starting position
                        // based on neighboring views.
                        int childHeight = child.getHeight() + mSnippetListView.getDividerHeight();
                        startTop = top + (i > 0 ? childHeight : -childHeight);
                        delta = startTop - top;
                    }
                    if (delta != 0) {
                        child.setTranslationY(delta);
                        ViewPropertyAnimator anim = child.animate().setDuration(moveDuration)
                                .translationY(0).setStartDelay(delay);
                        anim.setInterpolator(new DecelerateInterpolator());
                        anim.setListener(null);
                        if (maxDelayAnim == null || maxDelayAnim.getStartDelay() < delay) {
                            maxDelayAnim = anim;
                        }
                    }
                }
                mItemIdTopMap.clear();
                if (maxDelayAnim != null) {
                    maxDelayAnim.setListener(new AnimatorListener() {
                        @Override
                        public void onAnimationStart(Animator animation) {

                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            onDeleteAnimationEnd(listener, hintView);
                        }

                        @Override
                        public void onAnimationCancel(Animator animation) {
                            onDeleteAnimationEnd(listener, hintView);
                        }

                        @Override
                        public void onAnimationRepeat(Animator animation) {
                        }
                    });
                } else {
                    onDeleteAnimationEnd(listener, hintView);
                }
                return true;
            }
        });
    }

    private void onDeleteAnimationEnd(AnimationListener listener, View hintView) {
        asyncDeletePhrase(((ViewHolder) hintView.getTag()).id);
    }

    private class ModeChanger {

        private boolean mIsChangingMode;
        private Animator mAnimator;

        public boolean isChangeMode() {
            return mIsChangingMode;
        }

        public void resetChangeMode() {
            if (mIsChangingMode) {
                mIsChangingMode = false;
                mAnimator.cancel();
            }
        }

        public void beginEdit(ListView list) {
            if (mIsChangingMode) {
                mAnimator.cancel();
            }
            mIsChangingMode = true;
            AnimatorSet set = new AnimatorSet();
            Animator editAnimtor = makeEditAnimator(list, mIsEditMode);
            set.play(editAnimtor);
            mAnimator = set;
            mAnimator.addListener(new AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                }

                @Override
                public void onAnimationRepeat(Animator animation) {
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    mIsChangingMode = false;
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    mIsChangingMode = false;
                }
            });
            mAnimator.start();
        }

        public void endEdit(ListView list) {
            if (mIsChangingMode) {
                mAnimator.cancel();
            }
            mIsChangingMode = true;
            AnimatorSet set = new AnimatorSet();
            Animator editAnimtor = makeEditAnimator(list, mIsEditMode);
            set.play(editAnimtor);
            mAnimator = set;
            mAnimator.addListener(new AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                }

                @Override
                public void onAnimationRepeat(Animator animation) {
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    afterEndEdit();
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    afterEndEdit();
                }
            });
            mAnimator.start();
        }

        private void afterEndEdit() {
            mIsChangingMode = false;
            final int snippetNum = mQuickSnippets.size();
            if (mAddVisible != snippetNum < MAX_ITEM_NUMBER) {
                mAddVisible = snippetNum < MAX_ITEM_NUMBER;
                mQuickSnippetAdapter.notifyDataSetChanged();
            }
        }

        private Animator makeEditAnimator(ListView list, boolean edit) {
            List<ViewHolder> childView = getVisibleChild(list);
            if (childView != null) {
                ArrayList<Animator> animatorList = new ArrayList<Animator>(childView.size());
                for (ViewHolder view : childView) {
                    animatorList.add(edit ? view.beginEdit() : view.endEdit());
                }
                AnimatorSet set = new AnimatorSet();
                set.playTogether(animatorList);
                return set;
            }
            return null;
        }

       private List<ViewHolder> getVisibleChild(ListView list) {
            if (list != null) {
                int firstVisiblePosition = list.getFirstVisiblePosition();
                int lastVisiblePosition = list.getLastVisiblePosition();
                ArrayList<Integer> positions = new ArrayList<Integer>(
                        lastVisiblePosition - firstVisiblePosition + 1);
                for (int i = firstVisiblePosition; i <= lastVisiblePosition; i++) {
                    positions.add(i);
                }
                return getChildrenFromPosition(list, positions);
            }
            return null;
        }

        private List<ViewHolder> getChildrenFromPosition(ListView list,
                Collection<Integer> positions) {
            List<ViewHolder> views = new ArrayList<ViewHolder>();
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                int pos = list.getPositionForView(child);
                if (positions.contains(pos)) {
                    if (child.getTag() instanceof ViewHolder) {
                        views.add((ViewHolder) child.getTag());
                    } else {
                        if (!mIsEditMode) {
                            child.setVisibility(View.VISIBLE);
                        }
                        if ((child instanceof RelativeLayout) && child.getTag() == null) {
                            setAddItemEnabled(child, !mIsEditMode);
                        }
                    }
                }
            }
            return views;
        }
    }

    private class ViewHolder {
        private ImageView icon;
        View textItem;
        final int id;
        private Animator mDelAnimator;

        public ViewHolder(final View view, final int position) {
            QuickSnippet snippet = mQuickSnippets.get(position);
            id = snippet.id;
            icon = (ImageView) view.findViewById(R.id.snippet_del);
            icon.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mDelAnimator = makeDelete();
                    mDelAnimator.start();
                    mDelAnimator.addListener(new AnimatorListener() {

                        @Override
                        public void onAnimationStart(Animator animation) {
                        }

                        @Override
                        public void onAnimationRepeat(Animator animation) {
                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            view.setVisibility(View.INVISIBLE);
                            animateRemoval(null, view, position);
                        }

                        @Override
                        public void onAnimationCancel(Animator animation) {
                            view.setVisibility(View.INVISIBLE);
                            asyncDeletePhrase(id);
                        }
                    });
                }
            });
            textItem = view.findViewById(R.id.snippet_text_item);
            TextView textView = (TextView) textItem.findViewById(R.id.snippet_text);
            textView.setText(snippet.snippet);
            textView.setOnClickListener(new OnClickListener() {

                @Override
                public void onClick(View v) {
                    if (mIsEditMode) {
                        showEditQuickSnippetDialog(
                                mQuickSnippets.get(position).snippet,
                                mQuickSnippets.get(position).id);
                    } else {
                        insertSnippet(mQuickSnippets.get(position).snippet);
                        mQuickSnippetDialog.dismiss();
                    }
                }
            });
        }

        public void updateEditMode(boolean isEditMode) {
            if (isEditMode) {
                icon.setAlpha(1.0f);
                icon.setScaleX(1.0f);
                icon.setScaleY(1.0f);
                textItem.scrollTo(-mTextItemOffset, textItem.getScrollY());
            } else {
                icon.setAlpha(0f);
                icon.setScaleX(0.01f);
                icon.setScaleY(0.01f);
                textItem.scrollTo(0, textItem.getScrollY());
            }
        }

        private Animator makeMoveAnimator(final View view, final boolean right) {
            final int start = view.getScrollX();
            final int end = right ? -mTextItemOffset : 0;
            ValueAnimator moveAnimator = ValueAnimator.ofInt(start, end);
            moveAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {

                @Override
                public void onAnimationUpdate(ValueAnimator valueAnimator) {
                    int value = (Integer) valueAnimator.getAnimatedValue();
                    view.scrollTo(value, view.getScrollY());
                }
            });
            moveAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
            moveAnimator.setTarget(view);
            moveAnimator.setDuration(TEXT_ITEM_ANIM_DURATION);
            return moveAnimator;
        }

        private Animator makeExpandAnimator(final View view) {
            AnimatorSet animatorSet = new AnimatorSet();
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, view.getScaleX(), 1f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, view.getScaleY(), 1f);

            animatorSet.setInterpolator(new DecelerateInterpolator(1.5f));
            animatorSet.play(scaleX).with(scaleY);
            animatorSet.setDuration(REMOVE_ICON_ANIM_VIS_DURATION);
            animatorSet.setStartDelay(REMOVE_ICON_ANIM_DELAY);
            return animatorSet;
        }

        private Animator makeShrinkAnimator(final View view, int duration) {
            AnimatorSet animatorSet = new AnimatorSet();
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, view.getScaleX(), 0.01f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, view.getScaleY(), 0.01f);

            animatorSet.setInterpolator(new DecelerateInterpolator(1.5f));
            animatorSet.setDuration(duration);
            animatorSet.play(scaleX).with(scaleY);
            return animatorSet;
        }

        private Animator makeVisibleAnimator(final View view) {
            Animator visibleAnimator = ObjectAnimator.ofFloat(view, View.ALPHA, view.getAlpha(), 1f);
            visibleAnimator.setStartDelay(REMOVE_ICON_ANIM_DELAY);
            visibleAnimator.setDuration(REMOVE_ICON_ANIM_VIS_DURATION);
            visibleAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
            return visibleAnimator;
        }

        private Animator makeGoneAnimator(final View view, int duration) {
            Animator goneAnimator = ObjectAnimator.ofFloat(view, View.ALPHA, view.getAlpha(), 0f);
            goneAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
            goneAnimator.setDuration(duration);
            return goneAnimator;
        }

        public Animator beginEdit() {
            AnimatorSet animatorSet = new AnimatorSet();
            Animator visibleAnimator = makeVisibleAnimator(icon);
            Animator scaleAnimator = makeExpandAnimator(icon);
            Animator moveAnimator = makeMoveAnimator(textItem, true);
            animatorSet.playTogether(moveAnimator, visibleAnimator, scaleAnimator);
            animatorSet.addListener(new AnimatorListener() {

                @Override
                public void onAnimationStart(Animator animation) {
                }

                @Override
                public void onAnimationRepeat(Animator animation) {
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    updateEditMode(mIsEditMode);
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                }
            });
            return animatorSet;
        }

        public Animator endEdit() {
            if (mDelAnimator != null) {
                mDelAnimator.cancel();
            }
            AnimatorSet animatorSet = new AnimatorSet();
            Animator goneAnimator = makeGoneAnimator(icon, REMOVE_ICON_ANIM_INVIS_DURATION);
            Animator scaleAnimator = makeShrinkAnimator(icon, REMOVE_ICON_ANIM_INVIS_DURATION);
            Animator moveAnimator = makeMoveAnimator(textItem, false);
            animatorSet.playTogether(moveAnimator, goneAnimator, scaleAnimator);
            animatorSet.addListener(new AnimatorListener() {

                @Override
                public void onAnimationStart(Animator animation) {
                }

                @Override
                public void onAnimationRepeat(Animator animation) {
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    updateEditMode(mIsEditMode);
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                }
            });
            return animatorSet;
        }

        private Animator makeDeleteAnimator(final View view) {
            final int fromXDelta = view.getScrollX();
            final int toXDelta = -view.getWidth();
            ValueAnimator deleteAnimator = ValueAnimator.ofInt(fromXDelta, toXDelta);
            deleteAnimator.setInterpolator(new DecelerateInterpolator(1.5F));
            deleteAnimator.setDuration(TEXT_ITEM_ANIM_DURATION);
            deleteAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {

                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                        int currentVal = (Integer) animation.getAnimatedValue();
                        view.scrollTo(currentVal, view.getScrollY());
                }
            });
            return deleteAnimator;
        }

        public Animator makeDelete() {
            AnimatorSet animatorSet = new AnimatorSet();
            Animator goneAnimator = makeGoneAnimator(icon, TEXT_ITEM_ANIM_DURATION);
            Animator scaleAnimator = makeShrinkAnimator(icon, TEXT_ITEM_ANIM_DURATION);
            Animator deleteAnimator = makeDeleteAnimator(textItem);
            animatorSet.playTogether(deleteAnimator, goneAnimator, scaleAnimator);
            return animatorSet;
        }
    }

    void setAddItemEnabled(View view, boolean enabled) {
        if (mDisableAdd) enabled = false;
        view.setEnabled(enabled);
        ((TextView) view.findViewById(R.id.snippet_add)).setEnabled(enabled);
        ((ImageView) view.findViewById(R.id.snippet_icon_add)).setEnabled(enabled);
    }

    private class QuickSnippetAdapter extends BaseAdapter {

        public int getCount() {
            final int snippetNum = mQuickSnippets.size();
            if (mAddVisible) {
                if (snippetNum == 0) {
                    return 2;
                } else {
                    return snippetNum + 1;
                }
            } else {
                return snippetNum;
            }
        }

        public Object getItem(int position) {
            return null;
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            final int snippetNum = mQuickSnippets.size();
            if (position < snippetNum) {
                RelativeLayout item;
                if ((convertView instanceof RelativeLayout)
                        && convertView.getTag() != null
                        && convertView.getVisibility() == View.VISIBLE) {
                    item = (RelativeLayout) convertView;
                } else {
                    item = (RelativeLayout) mInflater.inflate(
                            R.layout.quick_snippet_text_item,
                            parent, false);
                }
                ViewHolder holder = new ViewHolder(item, position);
                item.setTag(holder);
                holder.updateEditMode(mIsEditMode);
                return item;
            } else {
                View item;
                if (snippetNum == 0 && position == 1) {
                    if (convertView instanceof TextView) {
                        item = convertView;
                    } else {
                        item = (TextView) mInflater.inflate(
                                R.layout.quick_snippet_tips, parent, false);
                    }
                } else {
                    if ((convertView instanceof RelativeLayout) && convertView.getTag() == null) {
                        item = convertView;
                    } else {
                        item = (RelativeLayout) mInflater.inflate(
                                R.layout.quick_snippet_text_item_add, parent, false);
                    }
                    if (mAddVisible) {
                        item.setVisibility(View.VISIBLE);
                        setAddItemEnabled(item, !mIsEditMode);
                    } else {
                        item.setVisibility(View.INVISIBLE);
                    }
                    item.setOnClickListener(new OnClickListener() {

                        @Override
                        public void onClick(View v) {
                            showEditQuickSnippetDialog(null, -1);
                        }
                    });
                }
                return item;
            }
        }
    }
}
