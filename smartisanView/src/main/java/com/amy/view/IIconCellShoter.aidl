package android.view;

import android.graphics.Bitmap;
import android.content.ComponentName;
import android.view.IconCellShotInfo;
import android.view.Surface;

/**
*@hide
*/
//hbt, todo move added API to addon
interface IIconCellShoter {
    IconCellShotInfo getIconCellShot(in ComponentName compName, in Surface surf, in boolean isEnter);
    boolean          getThemeResource(in Surface surf);
    void             unlockTouch();
}
