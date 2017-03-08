package android.view;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

//hbt, todo move added API to addon
/**
*@hide
*/
public class IconCellShotInfo implements Parcelable {
    //public Bitmap   bitmap;
    public boolean  valid;
    public int      mode;
    public Rect     cellPos;//left and top is transalted value, centered to Orign
    //below three Rect is the Texture Coordinates of the three texture on the Surface
    public Rect     cellRect;
    public Rect     sideRect;
    public Rect     shadowRect;

    public IconCellShotInfo() {
        cellPos = new Rect();
        cellRect = new Rect();
        sideRect = new Rect();
        shadowRect = new Rect();
    }

    public int describeContents() {
        return 0;
    }
    public void writeToParcel(Parcel out, int flags) {
        //if (null != bitmap) {
        //    out.writeInt(1);
        //    bitmap.writeToParcel(out, flags);
        //} else {
        //    out.writeInt(0);
        //}
        out.writeInt(valid ? 1 : 0);
        out.writeInt(mode);
        cellPos.writeToParcel(out, 0);
        cellRect.writeToParcel(out, 0);
        sideRect.writeToParcel(out, 0);
        shadowRect.writeToParcel(out, 0);
    }

    public static final Parcelable.Creator<IconCellShotInfo> CREATOR
            = new Parcelable.Creator<IconCellShotInfo>() {
        public IconCellShotInfo createFromParcel(Parcel in) {
            return new IconCellShotInfo(in);
        }

        public IconCellShotInfo[] newArray(int size) {
            return new IconCellShotInfo[size];
        }
    };

    private IconCellShotInfo(Parcel in) {
        //int bint = in.readInt();
        //if (0 != bint) {
        //    bitmap = Bitmap.CREATOR.createFromParcel(in);
        //} else {
        //    bitmap = null;
        //}
        valid =   in.readInt() != 0;
        mode =    in.readInt();
        cellPos    = Rect.CREATOR.createFromParcel(in);
        cellRect   = Rect.CREATOR.createFromParcel(in);
        sideRect   = Rect.CREATOR.createFromParcel(in);
        shadowRect = Rect.CREATOR.createFromParcel(in);
    }
    public Rect getRectInScreen(int dw, int dh) {
        final int top = cellPos.top;
        final int left = cellPos.left;
        final int tx = dw / 2;
        final int ty = dh / 2;
        final int halfw = (int)(cellPos.width() / 2);
        final int halfh = (int)(cellPos.height() / 2);
        return new Rect(tx+(int)left-halfw, ty-(int)top-halfh, tx+(int)left+halfw, ty-(int)top+halfh);
    }
    public String toString() {
        return "mode="+mode+", cellPos="+cellPos+", cellRect="+cellRect+", sideRect="+sideRect+", shadowRect="+shadowRect;
    }
}
