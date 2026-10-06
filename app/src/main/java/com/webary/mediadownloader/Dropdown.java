package com.webary.mediadownloader;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Custom dropdown menu: overlays everything (own window), opens below the anchor and flips above it
 * near the screen bottom, checkmarks the selection and scrolls it into view, and closes on
 * outside tap or Back. Lists here are short, so there is no type-to-search. It is positioned once
 * on open (no scroll/resize re-anchoring), which also keeps it stable on touch devices.
 */
final class Dropdown {
    interface OnPick { void onPick(int index); }

    private static final int ROW_DP = 48;
    private static final int MAX_VISIBLE_ROWS = 6;

    private Dropdown() {}

    static PopupWindow show(View anchor, String[] items, int selected, OnPick onPick) {
        Context c = anchor.getContext();
        int pad = Ui.dp(c, 6);

        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad, pad, pad, pad);

        PopupWindow[] holder = new PopupWindow[1];
        View selectedRow = null;
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            boolean isSelected = i == selected;
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 10), 0);
            row.setBackground(Ui.pressable(c, isSelected ? Ui.FIELD : Color.WHITE, 10));
            row.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                onPick.onPick(index);
            });

            TextView label = new TextView(c);
            label.setText(items[i]);
            label.setTextSize(15);
            label.setTextColor(Ui.INK);
            label.setTypeface(Typeface.DEFAULT, isSelected ? Typeface.BOLD : Typeface.NORMAL);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            ImageView check = new ImageView(c);
            check.setImageResource(R.drawable.ic_check);
            check.setImageTintList(ColorStateList.valueOf(Ui.ACCENT));
            check.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            row.addView(check, new LinearLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18)));

            if (isSelected) {
                row.setContentDescription(items[i] + ", selected");
                selectedRow = row;
            }
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, ROW_DP)));
        }

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (Build.VERSION.SDK_INT >= 29) {
            GradientDrawable thumb = Ui.round(c, Color.parseColor("#C9C1B6"), 2);
            thumb.setSize(Ui.dp(c, 3), Ui.dp(c, 24));
            scroll.setVerticalScrollbarThumbDrawable(thumb);
            scroll.setVerticalScrollbarTrackDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        scroll.addView(list);

        FrameLayout panel = new FrameLayout(c);
        panel.setBackground(Ui.outline(c, Color.WHITE, Ui.LINE, 16, 1));
        Ui.clipRound(panel, 16);
        panel.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Size: as wide as the value column, as tall as its rows (capped).
        int width = Math.max(Ui.dp(c, 200), Math.min(anchor.getWidth(), Ui.dp(c, 280)));
        int contentHeight = items.length * Ui.dp(c, ROW_DP) + pad * 2;
        int height = Math.min(contentHeight, MAX_VISIBLE_ROWS * Ui.dp(c, ROW_DP) + Ui.dp(c, ROW_DP) / 2 + pad);

        // Position: below the anchor, or above it when the space below runs out.
        Rect frame = new Rect();
        anchor.getWindowVisibleDisplayFrame(frame);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int gap = Ui.dp(c, 4);
        int edge = Ui.dp(c, 8);
        int spaceBelow = frame.bottom - (loc[1] + anchor.getHeight()) - gap - edge;
        int spaceAbove = loc[1] - frame.top - gap - edge;
        int y;
        if (height <= spaceBelow || spaceBelow >= spaceAbove) {
            height = Math.min(height, spaceBelow);
            y = loc[1] + anchor.getHeight() + gap;
        } else {
            height = Math.min(height, spaceAbove);
            y = loc[1] - gap - height;
        }
        int x = loc[0] + anchor.getWidth() - width - Ui.dp(c, 8);
        x = Math.max(frame.left + edge, Math.min(x, frame.right - width - edge));

        PopupWindow popup = new PopupWindow(panel, width, height, true);
        holder[0] = popup;
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(Ui.dp(c, 12));
        popup.setAnimationStyle(android.R.style.Animation_Dialog);
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, x, y);

        if (selectedRow != null) {
            View target = selectedRow;
            int viewport = height;
            scroll.post(() -> scroll.scrollTo(0, Math.max(0, target.getTop() - (viewport - target.getHeight()) / 2)));
        }
        return popup;
    }
}
