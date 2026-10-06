package com.webary.mediadownloader;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;

/** Palette and drawable helpers shared by the screens. */
final class Ui {
    static final int BG = Color.parseColor("#F3EFE9");
    static final int CARD = Color.WHITE;
    static final int INK = Color.parseColor("#1C1917");
    static final int MUTED = Color.parseColor("#615A52");
    static final int SOFT = Color.parseColor("#E7E1D8");
    static final int FIELD = Color.parseColor("#F3EFE9");
    static final int TRACK = Color.parseColor("#EFE9E1");
    static final int LINE = Color.parseColor("#E2DBD1");
    static final int ACCENT = Color.parseColor("#B4380A");
    static final int ERR_BG = Color.parseColor("#FDE4D8");
    static final int ERR = Color.parseColor("#8A2A06");
    static final int OK = Color.parseColor("#2F7A3E");
    static final int SCRIM = Color.argb(97, 28, 25, 23);

    private Ui() {}

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable outline(Context c, int fill, int stroke, float radiusDp, float strokeDp) {
        GradientDrawable g = round(c, fill, radiusDp);
        g.setStroke(dp(c, strokeDp), stroke);
        return g;
    }

    /** Rounded background with a pressed ripple clipped to the same shape. */
    static RippleDrawable pressable(Context c, int fill, float radiusDp) {
        int ripple = isDark(fill) ? Color.argb(50, 255, 255, 255) : Color.argb(28, 28, 25, 23);
        return new RippleDrawable(ColorStateList.valueOf(ripple), round(c, fill, radiusDp), round(c, Color.WHITE, radiusDp));
    }

    static void clipRound(View v, float radiusDp) {
        final float r = dp(v.getContext(), radiusDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    static boolean isDark(int color) {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 < 128;
    }
}
