package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.List;

/**
 * A print's camera pictures full screen: pinch or double tap to zoom, drag to move when zoomed, − and + for the same, and
 * ‹ › to step to the picture before or after (the zoom stays, so one spot can be followed through the print).
 */
final class PictureViewer {
    /** What to call a picture in the title, e.g. "2:40 into the print · layer 80". */
    interface Caption { String of(int index); }

    static void show(Activity activity, List<File> pictures, int start, Caption caption) {
        if (pictures.isEmpty()) return;
        float density = activity.getResources().getDisplayMetrics().density;
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout stage = new FrameLayout(activity); stage.setBackgroundColor(Color.BLACK);
        ZoomImageView view = new ZoomImageView(activity);
        stage.addView(view, new FrameLayout.LayoutParams(-1, -1));
        TextView title = new TextView(activity); title.setTextColor(Color.WHITE); title.setTextSize(15); title.setBackgroundColor(0x99000000);
        int pad = Math.round(12 * density); title.setPadding(pad, pad, pad, pad);
        stage.addView(title, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        LinearLayout bar = new LinearLayout(activity); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setBackgroundColor(0x99000000);
        bar.setPadding(Math.round(8 * density), Math.round(8 * density), Math.round(8 * density), Math.round(8 * density));
        int[] at = {Math.max(0, Math.min(start, pictures.size() - 1))};
        Bitmap[] shown = {null};
        Runnable load = () -> {
            File file = pictures.get(at[0]);
            Bitmap full = BitmapFactory.decodeFile(file.getPath());   // full size: zooming in should show the camera's own detail
            if (full != null) { view.setImageBitmap(full); if (shown[0] != null && shown[0] != full) shown[0].recycle(); shown[0] = full; }
            String words = caption.of(at[0]) + "   ·   " + (at[0] + 1) + " of " + pictures.size();
            title.setText(words); view.setContentDescription("Picture at " + words + ". Pinch or double tap to zoom");
        };
        Button previous = button(activity, bar, "‹", density, () -> { if (at[0] > 0) { at[0]--; load.run(); } }); previous.setContentDescription("Previous picture");
        button(activity, bar, "−", density, () -> view.zoomBy(1 / 1.5f)).setContentDescription("Zoom out");
        button(activity, bar, "+", density, () -> view.zoomBy(1.5f)).setContentDescription("Zoom in");
        Button next = button(activity, bar, "›", density, () -> { if (at[0] < pictures.size() - 1) { at[0]++; load.run(); } }); next.setContentDescription("Next picture");
        button(activity, bar, "Close", density, dialog::dismiss);
        stage.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        dialog.setContentView(stage);
        dialog.setOnDismissListener(d -> { if (shown[0] != null) shown[0].recycle(); shown[0] = null; });
        load.run();
        dialog.show();
    }

    private static Button button(Activity activity, LinearLayout bar, String text, float density, Runnable action) {
        Button b = new Button(activity); b.setText(text); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(text.length() == 1 ? 22 : 15); b.setBackground(null);
        int min = Math.round(48 * density); b.setMinHeight(min); b.setMinimumHeight(min); b.setMinWidth(min); b.setMinimumWidth(min);
        b.setOnClickListener(v -> action.run());
        bar.addView(b, new LinearLayout.LayoutParams(0, -2, 1)); return b;
    }

    private PictureViewer() { }
}
