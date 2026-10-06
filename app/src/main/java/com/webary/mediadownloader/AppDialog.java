package com.webary.mediadownloader;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Dialog styled like the rest of the app: rounded white card, warm palette, pill buttons. */
final class AppDialog {
    static final int SECONDARY = 0, PRIMARY = 1, DANGER = 2;

    private final Activity activity;
    private final Dialog dialog;
    private final LinearLayout body;
    private final LinearLayout buttons;

    AppDialog(Activity activity, String title, CharSequence message) {
        this.activity = activity;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(24), dp(24), dp(20));
        card.setBackground(Ui.round(activity, Color.WHITE, 24));

        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextSize(20);
        t.setTextColor(Ui.INK);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(t);

        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(10);
        card.addView(body, bp);

        if (message != null) {
            TextView m = new TextView(activity);
            m.setText(message);
            m.setTextSize(15);
            m.setTextColor(Ui.MUTED);
            m.setLineSpacing(0, 1.2f);
            body.addView(m);
        }

        buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams btp = new LinearLayout.LayoutParams(-1, -2);
        btp.topMargin = dp(22);
        card.addView(buttons, btp);

        dialog.setContentView(card);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels - dp(40), dp(420));
            w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.45f);
        }
    }

    /** Adds custom content below the message. */
    AppDialog add(View view, int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(topMarginDp);
        body.addView(view, p);
        return this;
    }

    AppDialog button(String label, int style, Runnable action) {
        TextView b = new TextView(activity);
        b.setText(label);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setMinWidth(dp(88));
        int fill = style == PRIMARY ? Ui.ACCENT : style == DANGER ? Ui.ERR : Ui.FIELD;
        b.setTextColor(style == SECONDARY ? Ui.INK : Color.WHITE);
        b.setBackground(Ui.pressable(activity, fill, 14));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(v -> {
            dialog.dismiss();
            if (action != null) action.run();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(44));
        if (buttons.getChildCount() > 0) p.leftMargin = dp(8);
        buttons.addView(b, p);
        return this;
    }

    void show() { dialog.show(); }

    void dismiss() { dialog.dismiss(); }

    private int dp(float v) { return Ui.dp(activity, v); }
}
