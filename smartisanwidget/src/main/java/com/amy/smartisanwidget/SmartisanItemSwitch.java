
package android.widget;

import com.android.internal.R;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.util.Log;

/**
@hide
*/
public class SmartisanItemSwitch extends LinearLayout {

    private OnCheckedChangeListener mOnCheckedChangeListener;

    private SmartisanSwitchEx mSwitch;

    private TextView summary;

    private static final String TAG="SmartisanItemSwitch";
    
    public SmartisanItemSwitch(Context context) {
        super(context);
    }

    public SmartisanItemSwitch(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SmartisanItemSwitch(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final View container = LayoutInflater.from(context).inflate(
                R.layout.smartisan_item_switch_layout, this, true);

        ImageView icon = (ImageView)container.findViewById(R.id.smartisan_item_switch_icon);
        TextView title = (TextView)container.findViewById(R.id.smartisan_item_switch_title);
        summary = (TextView)container.findViewById(R.id.samrtisan_item_switch_summary);
        mSwitch = (SmartisanSwitchEx)container.findViewById(R.id.smartisan_item_switch);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SmartisanItemSwitch,
                defStyle, 0);

        Drawable drawable = a.getDrawable(R.styleable.SmartisanItemSwitch_icon);

        if (drawable == null) {
            icon.setVisibility(View.GONE);
        } else {
            icon.setImageDrawable(drawable);
        }
        int titleSize = a.getDimensionPixelSize(R.styleable.SmartisanItemSwitch_smartisan_item_switch_title_size, -1);
        if (titleSize > 0) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, titleSize);
        }
        int summarySize = a.getDimensionPixelSize(R.styleable.SmartisanItemSwitch_smartisan_item_switch_summary_size, -1);
        if (summarySize > 0) {
            summary.setTextSize(TypedValue.COMPLEX_UNIT_PX, summarySize);
        }

        title.setText(a.getText(R.styleable.SmartisanItemSwitch_smartisan_item_switch_title));
        CharSequence strSummary = a.getText(R.styleable.SmartisanItemSwitch_smartisan_item_switch_summary);
        if (strSummary != null) {
            summary.setVisibility(View.VISIBLE);
            summary.setText(strSummary);
        }
        mSwitch.setChecked(a.getBoolean(R.styleable.SmartisanItemSwitch_smartisan_item_switch_isEnable, false));

        a.recycle();
        mSwitch.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton ex, boolean state) {
                if (mOnCheckedChangeListener != null) {
                    ex.setTag(SmartisanItemSwitch.this.getId());
                    mOnCheckedChangeListener.onCheckedChanged(ex, state);
                }
            }
        });
    }

    public void setButtonDrawable(int id) {
        mSwitch.setButtonDrawable(id);
    }

    public void setSummary(CharSequence s) {
        summary.setVisibility(View.VISIBLE);
        summary.setText(s);
    }

    public void setSummary(int s) {
        summary.setVisibility(View.VISIBLE);
        summary.setText(s);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        mOnCheckedChangeListener = listener;
    }

    public void setChecked(boolean isChecked) {
        mSwitch.setChecked(isChecked);
    }

    public boolean isChecked() {
        return mSwitch.isChecked();
    }

    public SmartisanSwitchEx getSwitch() {
        return mSwitch;
    }
}