package com.webary.mediadownloader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements JobStore.Listener {
    private static final int REQ_TREE = 44;
    private static final int REQ_NOTIFY = 45;
    private static final int REQ_STORAGE = 46;

    private static final String[] MODES = {"Video", "Audio", "Captions"};
    private static final int[] MODE_ICONS = {R.drawable.ic_video, R.drawable.ic_audio, R.drawable.ic_captions};
    private static final String[] QUALITIES = {"Best available", "2160p", "1440p", "1080p", "720p", "480p", "360p"};
    private static final String[][] FORMATS = {{"MP4", "MKV", "WEBM"}, {"MP3", "M4A", "WAV", "FLAC"}, {"VTT", "SRT"}};

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    // Main screen
    private FrameLayout root;
    private LinearLayout header;
    private View headerLine;
    private LinearLayout listBox;
    private LinearLayout bottomBar;
    private final Map<String, CardHolder> holders = new HashMap<>();

    // Sheet
    private View scrim;
    private SheetLayout sheet;
    private EditText urlInput;
    private LinearLayout previewBox;
    private ImageView previewThumb;
    private TextView previewTitle, previewMeta;
    private final LinearLayout[] modeTiles = new LinearLayout[3];
    private View qualityRow, embedRow;
    private TextView qualityValue, formatValue, folderValue;
    private Switch embedSwitch, subfolderSwitch;

    private int modeIndex, qualityIndex;
    private final int[] formatIndex = new int[3];

    // Link preview
    private final Runnable previewRunnable = this::loadPreview;
    private int previewGen = 0;
    private String previewUrl, previewTitleText, previewThumbUrl, previewChannel;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("media_downloader", MODE_PRIVATE);
        JobStore.load(this);
        loadChoices();
        setContentView(buildUi());
        configureWindow();
        setMode(modeIndex);
        renderList();
        handleSharedText(getIntent());
        // Warm up yt-dlp (and refresh it if stale) so the first download starts fast.
        bg.execute(() -> { try { Engine.ensureReady(this); } catch (Exception ignored) {} });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedText(intent);
    }

    @Override protected void onStart() {
        super.onStart();
        JobStore.addListener(this);
        renderList();
    }

    @Override protected void onStop() {
        JobStore.removeListener(this);
        super.onStop();
    }

    @Override public void onBackPressed() {
        if (sheet.getVisibility() == View.VISIBLE) closeSheet();
        else super.onBackPressed();
    }

    // ---------- Window / insets ----------

    private void configureWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light, light);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    /** Content draws edge to edge; the fixed header pads itself below the status bar. */
    private WindowInsets applyInsets(View v, WindowInsets in) {
        int top, nav, ime;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            top = bars.top;
            nav = bars.bottom;
            ime = in.getInsets(WindowInsets.Type.ime()).bottom;
        } else {
            top = in.getSystemWindowInsetTop();
            ime = in.getSystemWindowInsetBottom();
            nav = Math.min(ime, in.getStableInsetBottom());
        }
        header.setPadding(dp(20), top + dp(12), dp(12), dp(12));
        bottomBar.setPadding(dp(20), dp(12), dp(20), nav + dp(16));
        sheet.setPadding(dp(20), dp(10), dp(20), Math.max(nav, ime) + dp(16));
        sheet.topGap = top + dp(24);
        sheet.requestLayout();
        return in;
    }

    // ---------- Main screen ----------

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener(this::applyInsets);

        LinearLayout column = vbox();
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        header = hbox();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Ui.BG);
        TextView title = text("Downloads", 28, Ui.INK, true);
        title.setLetterSpacing(-0.02f);
        header.addView(title, lp(0, -2, 1f));
        ImageView settings = iconButton(R.drawable.ic_settings, "Settings", Ui.SOFT, Ui.INK, 44, 14);
        settings.setOnClickListener(v -> showSettings());
        header.addView(settings, lp(dp(44), dp(44)));
        column.addView(header, lp(-1, -2));

        headerLine = new View(this);
        headerLine.setBackgroundColor(Ui.LINE);
        headerLine.setAlpha(0f);
        column.addView(headerLine, lp(-1, dp(1)));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> headerLine.setAlpha(y > 0 ? 1f : 0f));
        listBox = vbox();
        listBox.setPadding(dp(20), dp(4), dp(20), dp(16));
        scroll.addView(listBox, new FrameLayout.LayoutParams(-1, -2));
        column.addView(scroll, lp(-1, 0, 1f));

        bottomBar = vbox();
        View newButton = primaryButton("New download", R.drawable.ic_plus);
        newButton.setOnClickListener(v -> openSheet());
        bottomBar.addView(newButton, lp(-1, dp(56)));
        column.addView(bottomBar, lp(-1, -2));

        scrim = new View(this);
        scrim.setBackgroundColor(Ui.SCRIM);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closeSheet());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        sheet = buildSheet();
        sheet.setVisibility(View.GONE);
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return root;
    }

    @Override public void onJobsChanged(Job job, boolean structural) {
        CardHolder h = job == null ? null : holders.get(job.id);
        if (structural || h == null || h.state != job.state) {
            renderList();
        } else {
            bindProgress(h, job);
        }
    }

    private void renderList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        holders.clear();
        List<Job> jobs = JobStore.snapshot();

        if (jobs.isEmpty()) {
            listBox.addView(emptyState(), lp(-1, -2));
            return;
        }

        boolean anyFinished = false;
        for (Job j : jobs) if (j.isFinished()) { anyFinished = true; break; }
        if (anyFinished) {
            LinearLayout bar = hbox();
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.addView(text(jobs.size() == 1 ? "1 download" : jobs.size() + " downloads", 13, Ui.MUTED, false), lp(0, -2, 1f));
            TextView clear = text("Clear finished", 13, Ui.ACCENT, true);
            clear.setGravity(Gravity.CENTER);
            clear.setPadding(dp(10), 0, dp(4), 0);
            clear.setMinHeight(dp(44));
            clear.setOnClickListener(v -> JobStore.clearFinished());
            bar.addView(clear, lp(-2, dp(44)));
            listBox.addView(bar, lp(-1, -2));
        }

        for (int i = 0; i < jobs.size(); i++) {
            LinearLayout.LayoutParams p = lp(-1, -2);
            if (i > 0) p.topMargin = dp(10);
            listBox.addView(jobCard(jobs.get(i)), p);
        }
    }

    private View emptyState() {
        LinearLayout box = vbox();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(48), dp(24), dp(24));
        FrameLayout circle = new FrameLayout(this);
        circle.setBackground(Ui.round(this, Ui.SOFT, 999));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_download);
        icon.setImageTintList(ColorStateList.valueOf(Ui.MUTED));
        circle.addView(icon, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        box.addView(circle, lp(dp(64), dp(64)));
        box.addView(space(16));
        TextView t = text("No downloads yet", 17, Ui.INK, true);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        box.addView(space(6));
        TextView s = text("Tap New download, or share a link to this app from YouTube or your browser.", 14, Ui.MUTED, false);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(0, 1.15f);
        box.addView(s);
        return box;
    }

    private View jobCard(Job job) {
        CardHolder h = new CardHolder();
        h.state = job.state;

        LinearLayout card = hbox();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(Ui.pressable(this, Ui.CARD, 18));

        FrameLayout cover = new FrameLayout(this);
        cover.setBackgroundColor(job.state == Job.FAILED && TextUtils.isEmpty(job.thumb) ? Ui.ERR_BG : Ui.SOFT);
        Ui.clipRound(cover, 10);
        ImageView modeIcon = new ImageView(this);
        modeIcon.setImageResource(job.state == Job.FAILED && TextUtils.isEmpty(job.thumb) ? R.drawable.ic_alert : MODE_ICONS[modeIndexOf(job.mode)]);
        modeIcon.setImageTintList(ColorStateList.valueOf(job.state == Job.FAILED && TextUtils.isEmpty(job.thumb) ? Ui.ACCENT : Ui.MUTED));
        cover.addView(modeIcon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
        Thumbs.load(thumb, job.thumb);
        card.addView(cover, lp(dp(96), dp(54)));

        LinearLayout middle = vbox();
        TextView title = text(job.displayTitle(), 14, Ui.INK, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        middle.addView(title);
        if (job.state == Job.RUNNING || job.state == Job.QUEUED) {
            h.bar = new Bar(this);
            LinearLayout.LayoutParams bp = lp(-1, dp(6));
            bp.topMargin = dp(8);
            middle.addView(h.bar, bp);
        }
        h.meta = text("", 12, job.state == Job.FAILED ? Ui.ERR : Ui.MUTED, false);
        h.meta.setMaxLines(2);
        h.meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mp = lp(-1, -2);
        mp.topMargin = dp(4);
        middle.addView(h.meta, mp);
        LinearLayout.LayoutParams midP = lp(0, -2, 1f);
        midP.setMargins(dp(12), 0, dp(8), 0);
        card.addView(middle, midP);

        View action = null;
        if (job.state == Job.RUNNING || job.state == Job.QUEUED) {
            action = iconButton(R.drawable.ic_close, "Cancel download", Ui.FIELD, Ui.INK, 40, 12);
            action.setOnClickListener(v -> cancelJob(job));
        } else if (job.state == Job.FAILED || job.state == Job.CANCELLED) {
            TextView retry = pill("Retry", Ui.INK, Color.WHITE);
            retry.setOnClickListener(v -> retryJob(job));
            action = retry;
        } else if (job.state == Job.DONE) {
            action = iconButton(R.drawable.ic_play, "Open file", Ui.FIELD, Ui.INK, 40, 12);
            action.setOnClickListener(v -> openFile(job));
        }
        if (action != null) card.addView(action, action instanceof TextView ? lp(-2, dp(40)) : lp(dp(40), dp(40)));

        card.setOnClickListener(v -> {
            if (job.state == Job.DONE) openFile(job);
            else if (job.state == Job.FAILED) showError(job);
        });
        card.setOnLongClickListener(v -> {
            if (!job.isFinished()) return false;
            new AlertDialog.Builder(this)
                    .setMessage("Remove this item from the list? The file stays on your phone.")
                    .setPositiveButton("Remove", (d, w) -> JobStore.remove(job))
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });

        bindProgress(h, job);
        holders.put(job.id, h);
        return card;
    }

    private void bindProgress(CardHolder h, Job job) {
        if (h.bar != null) h.bar.setProgress(job.state == Job.QUEUED ? 0 : job.progress);
        String meta;
        switch (job.state) {
            case Job.QUEUED: meta = "Waiting · " + job.spec(); break;
            case Job.RUNNING:
                meta = job.status + " · " + job.progress + "%" + (TextUtils.isEmpty(job.detail) ? "" : " · " + job.detail);
                break;
            case Job.DONE:
                meta = job.spec() + " · " + new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(new Date(job.time));
                break;
            case Job.FAILED: meta = job.error; break;
            default: meta = "Cancelled · " + job.spec();
        }
        h.meta.setText(meta);
    }

    // ---------- Sheet ----------

    private SheetLayout buildSheet() {
        SheetLayout s = new SheetLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setBackground(sheetBackground());
        s.setClickable(true); // swallow taps so they don't reach the scrim

        View handle = new View(this);
        handle.setBackground(Ui.round(this, Color.parseColor("#D6CFC5"), 999));
        LinearLayout.LayoutParams hp = lp(dp(40), dp(4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        s.addView(handle, hp);

        LinearLayout titleRow = hbox();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text("New download", 20, Ui.INK, true), lp(0, -2, 1f));
        ImageView close = iconButton(R.drawable.ic_close, "Close", Ui.FIELD, Ui.INK, 40, 12);
        close.setOnClickListener(v -> closeSheet());
        titleRow.addView(close, lp(dp(40), dp(40)));
        LinearLayout.LayoutParams tp = lp(-1, -2);
        tp.setMargins(0, dp(12), 0, dp(12));
        s.addView(titleRow, tp);

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = vbox();
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        s.addView(scroll, lp(-1, 0, 1f));

        // Link + Paste
        TextView linkLabel = text("Link", 13, Ui.MUTED, true);
        body.addView(linkLabel);
        LinearLayout linkRow = hbox();
        linkRow.setGravity(Gravity.CENTER_VERTICAL);
        urlInput = new EditText(this);
        urlInput.setId(View.generateViewId());
        linkLabel.setLabelFor(urlInput.getId());
        urlInput.setHint("Paste or type a link");
        urlInput.setHintTextColor(Color.parseColor("#8A8178"));
        urlInput.setTextColor(Ui.INK);
        urlInput.setTextSize(15);
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        urlInput.setBackground(Ui.outline(this, Color.WHITE, Ui.INK, 14, 1.5f));
        urlInput.setPadding(dp(14), 0, dp(14), 0);
        urlInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                ui.removeCallbacks(previewRunnable);
                ui.postDelayed(previewRunnable, 500);
            }
        });
        linkRow.addView(urlInput, lp(0, dp(48), 1f));
        TextView paste = pill("Paste", Ui.INK, Color.WHITE);
        paste.setCompoundDrawablesRelative(tinted(R.drawable.ic_paste, Color.WHITE, 18), null, null, null);
        paste.setCompoundDrawablePadding(dp(6));
        paste.setOnClickListener(v -> pasteLink());
        LinearLayout.LayoutParams pp = lp(-2, dp(48));
        pp.leftMargin = dp(8);
        linkRow.addView(paste, pp);
        LinearLayout.LayoutParams lrp = lp(-1, -2);
        lrp.topMargin = dp(6);
        body.addView(linkRow, lrp);

        // Preview
        previewBox = hbox();
        previewBox.setGravity(Gravity.CENTER_VERTICAL);
        previewBox.setPadding(dp(10), dp(10), dp(12), dp(10));
        previewBox.setBackground(Ui.round(this, Ui.FIELD, 16));
        previewBox.setVisibility(View.GONE);
        FrameLayout pcover = new FrameLayout(this);
        pcover.setBackgroundColor(Ui.SOFT);
        Ui.clipRound(pcover, 10);
        previewThumb = new ImageView(this);
        previewThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pcover.addView(previewThumb, new FrameLayout.LayoutParams(-1, -1));
        previewBox.addView(pcover, lp(dp(112), dp(63)));
        LinearLayout ptext = vbox();
        previewTitle = text("", 14, Ui.INK, true);
        previewTitle.setMaxLines(2);
        previewTitle.setEllipsize(TextUtils.TruncateAt.END);
        previewMeta = text("", 12, Ui.MUTED, false);
        previewMeta.setMaxLines(1);
        previewMeta.setEllipsize(TextUtils.TruncateAt.END);
        ptext.addView(previewTitle);
        ptext.addView(space(3));
        ptext.addView(previewMeta);
        LinearLayout.LayoutParams ptp = lp(0, -2, 1f);
        ptp.leftMargin = dp(12);
        previewBox.addView(ptext, ptp);
        LinearLayout.LayoutParams pbp = lp(-1, -2);
        pbp.topMargin = dp(12);
        body.addView(previewBox, pbp);

        // Mode tiles
        LinearLayout tiles = hbox();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            LinearLayout tile = vbox();
            tile.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(this);
            icon.setImageResource(MODE_ICONS[i]);
            tile.addView(icon, lp(dp(22), dp(22)));
            TextView label = text(MODES[i], 13, Ui.INK, true);
            LinearLayout.LayoutParams labp = lp(-2, -2);
            labp.topMargin = dp(6);
            tile.addView(label, labp);
            tile.setOnClickListener(v -> setMode(index));
            modeTiles[i] = tile;
            LinearLayout.LayoutParams tlp = lp(0, dp(72), 1f);
            if (i > 0) tlp.leftMargin = dp(8);
            tiles.addView(tile, tlp);
        }
        LinearLayout.LayoutParams tilesP = lp(-1, -2);
        tilesP.topMargin = dp(16);
        body.addView(tiles, tilesP);

        // Options
        LinearLayout group = vbox();
        group.setBackground(Ui.round(this, Ui.FIELD, 16));
        Ui.clipRound(group, 16);

        qualityValue = valueText();
        qualityRow = optionRow("Quality", qualityValue, true);
        qualityRow.setOnClickListener(v -> Dropdown.show(qualityRow, QUALITIES, qualityIndex, i -> {
            qualityIndex = i;
            prefs.edit().putInt("quality", i).apply();
            qualityValue.setText(QUALITIES[i]);
        }));
        group.addView(qualityRow, lp(-1, dp(52)));

        formatValue = valueText();
        View formatRow = optionRow("Format", formatValue, true);
        formatRow.setOnClickListener(v -> Dropdown.show(formatRow, FORMATS[modeIndex], formatIndex[modeIndex], i -> {
            formatIndex[modeIndex] = i;
            prefs.edit().putInt("fmt_" + MODES[modeIndex], i).apply();
            formatValue.setText(FORMATS[modeIndex][i]);
        }));
        group.addView(divider());
        group.addView(formatRow, lp(-1, dp(52)));

        embedSwitch = styledSwitch(prefs.getBoolean("embed", true));
        embedSwitch.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("embed", on).apply());
        embedRow = switchRow("Embed subtitles", embedSwitch);
        group.addView(divider());
        group.addView(embedRow, lp(-1, dp(52)));

        subfolderSwitch = styledSwitch(prefs.getBoolean("subfolder", false));
        subfolderSwitch.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("subfolder", on).apply());
        group.addView(divider());
        group.addView(switchRow("Own folder per download", subfolderSwitch), lp(-1, dp(52)));

        folderValue = valueText();
        View folderRow = optionRow("Save to", folderValue, false);
        folderRow.setOnClickListener(v -> chooseFolder());
        group.addView(divider());
        group.addView(folderRow, lp(-1, dp(52)));
        refreshFolderLabel();

        LinearLayout.LayoutParams gp = lp(-1, -2);
        gp.topMargin = dp(16);
        body.addView(group, gp);

        View add = primaryButton("Add to downloads", 0);
        add.setOnClickListener(v -> addDownload());
        LinearLayout.LayoutParams ap = lp(-1, dp(56));
        ap.topMargin = dp(16);
        s.addView(add, ap);
        return s;
    }

    private android.graphics.drawable.GradientDrawable sheetBackground() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Color.WHITE);
        float r = dp(28);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return g;
    }

    private void openSheet() {
        if (sheet.getVisibility() == View.VISIBLE) return;
        scrim.setAlpha(0f);
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(200).start();
        sheet.setVisibility(View.INVISIBLE);
        sheet.post(() -> {
            sheet.setTranslationY(sheet.getHeight());
            sheet.setVisibility(View.VISIBLE);
            sheet.animate().translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator(2f)).start();
        });
    }

    private void closeSheet() {
        hideKeyboard();
        scrim.animate().alpha(0f).setDuration(180).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        sheet.animate().translationY(sheet.getHeight()).setDuration(200)
                .withEndAction(() -> sheet.setVisibility(View.GONE)).start();
    }

    private void setMode(int index) {
        modeIndex = index;
        prefs.edit().putInt("mode", index).apply();
        for (int i = 0; i < 3; i++) {
            boolean on = i == index;
            LinearLayout tile = modeTiles[i];
            tile.setBackground(Ui.pressable(this, on ? Ui.INK : Ui.FIELD, 16));
            ((ImageView) tile.getChildAt(0)).setImageTintList(ColorStateList.valueOf(on ? Color.WHITE : Ui.INK));
            TextView label = (TextView) tile.getChildAt(1);
            label.setTextColor(on ? Color.WHITE : Ui.INK);
            tile.setSelected(on);
            tile.setContentDescription(MODES[i] + (on ? ", selected" : ""));
        }
        boolean video = index == 0;
        qualityRow.setVisibility(video ? View.VISIBLE : View.GONE);
        ((View) embedRow).setVisibility(video ? View.VISIBLE : View.GONE);
        // Dividers sit before each row; hide the one above a hidden row.
        ViewGroup group = (ViewGroup) qualityRow.getParent();
        int embedPos = group.indexOfChild(embedRow);
        group.getChildAt(embedPos - 1).setVisibility(video ? View.VISIBLE : View.GONE);
        group.getChildAt(1).setVisibility(video ? View.VISIBLE : View.GONE);

        qualityValue.setText(QUALITIES[qualityIndex]);
        formatValue.setText(FORMATS[index][formatIndex[index]]);
    }

    private void loadChoices() {
        modeIndex = clamp(prefs.getInt("mode", 0), 3);
        qualityIndex = clamp(prefs.getInt("quality", 0), QUALITIES.length);
        for (int i = 0; i < 3; i++) formatIndex[i] = clamp(prefs.getInt("fmt_" + MODES[i], 0), FORMATS[i].length);
    }

    private static int clamp(int v, int size) { return v < 0 || v >= size ? 0 : v; }

    private void pasteLink() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
        String link = text == null ? null : extractLink(text.toString());
        if (link == null) {
            toast("No link on the clipboard");
            return;
        }
        urlInput.setText(link);
        urlInput.setSelection(link.length());
    }

    private void loadPreview() {
        String url = extractLink(urlInput.getText().toString());
        if (url == null) {
            previewUrl = null;
            previewBox.setVisibility(View.GONE);
            return;
        }
        if (url.equals(previewUrl)) return;
        previewUrl = url;
        previewTitleText = previewThumbUrl = previewChannel = null;
        int gen = ++previewGen;

        previewBox.setVisibility(View.VISIBLE);
        Thumbs.load(previewThumb, null);
        previewTitle.setText("Loading video details…");
        previewMeta.setText(Uri.parse(url).getHost());

        bg.execute(() -> {
            try {
                Engine.ensureReady(this);
                YoutubeDLRequest request = new YoutubeDLRequest(url);
                request.addOption("--no-playlist");
                VideoInfo info = YoutubeDL.getInstance().getInfo(request);
                ui.post(() -> {
                    if (gen != previewGen) return;
                    previewTitleText = info.getTitle();
                    previewThumbUrl = info.getThumbnail();
                    previewChannel = info.getUploader();
                    previewTitle.setText(TextUtils.isEmpty(previewTitleText) ? url : previewTitleText);
                    String meta = TextUtils.isEmpty(previewChannel) ? Uri.parse(url).getHost() : previewChannel;
                    if (info.getDuration() > 0) meta += " · " + duration(info.getDuration());
                    previewMeta.setText(meta);
                    Thumbs.load(previewThumb, previewThumbUrl);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (gen != previewGen) return;
                    previewTitle.setText("Couldn't load a preview");
                    previewMeta.setText("You can still add the download");
                });
            }
        });
    }

    private void addDownload() {
        String url = extractLink(urlInput.getText().toString());
        if (url == null) {
            toast(urlInput.length() == 0 ? "Paste a link first" : "That doesn't look like a web link");
            return;
        }
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                && TextUtils.isEmpty(prefs.getString("tree_uri", ""))) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            toast("Allow storage, then tap Add again");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }

        Job job = new Job();
        job.url = url;
        job.mode = MODES[modeIndex];
        job.quality = QUALITIES[qualityIndex];
        job.format = FORMATS[modeIndex][formatIndex[modeIndex]];
        job.treeUri = prefs.getString("tree_uri", "");
        job.subfolder = subfolderSwitch.isChecked();
        job.embedSubs = modeIndex == 0 && embedSwitch.isChecked();
        if (url.equals(previewUrl)) {
            job.title = previewTitleText;
            job.thumb = previewThumbUrl;
            job.channel = previewChannel;
        }
        JobStore.add(job);
        startDownloads();

        urlInput.setText("");
        previewUrl = null;
        previewBox.setVisibility(View.GONE);
        closeSheet();
    }

    private void startDownloads() {
        Intent i = new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void cancelJob(Job job) {
        if (job.state == Job.QUEUED) {
            job.state = Job.CANCELLED;
            job.status = "Cancelled";
            JobStore.changed(job, true);
        } else if (job.state == Job.RUNNING) {
            startService(new Intent(this, DownloadService.class)
                    .setAction(DownloadService.ACTION_CANCEL)
                    .putExtra(DownloadService.EXTRA_JOB_ID, job.id));
        }
    }

    private void retryJob(Job job) {
        JobStore.requeue(job);
        startDownloads();
    }

    private void openFile(Job job) {
        if (TextUtils.isEmpty(job.fileUri)) return;
        Uri uri = Uri.parse(job.fileUri);
        if ("file".equals(uri.getScheme())) {
            // Android 9 and older saves to a plain path; allow handing it to a player.
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
        }
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, job.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            toast("No app can open this file");
        } catch (Exception e) {
            toast("The file couldn't be opened — it may have been moved or deleted");
        }
    }

    private void showError(Job job) {
        new AlertDialog.Builder(this)
                .setTitle("Download failed")
                .setMessage(job.error + "\n\nDetails:\n" + Errors.keyLine(job.rawError == null ? "" : job.rawError))
                .setPositiveButton("Retry", (d, w) -> retryJob(job))
                .setNeutralButton("Copy details", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Download error", job.rawError));
                    toast("Copied");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showSettings() {
        String message = "Download engine: yt-dlp " + Engine.version(this)
                + "\nIt updates itself automatically.\n\nSaves to: " + StorageHelper.displayFolder(this, prefs.getString("tree_uri", ""))
                + "\n\nOnly download media you have permission to save.";
        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setMessage(message)
                .setPositiveButton("Update engine", (d, w) -> {
                    toast("Checking for updates…");
                    bg.execute(() -> {
                        String result = Engine.updateNow(this);
                        ui.post(() -> toast(result));
                    });
                })
                .setNeutralButton("Reset folder", (d, w) -> {
                    prefs.edit().remove("tree_uri").apply();
                    refreshFolderLabel();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_TREE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) {}
            prefs.edit().putString("tree_uri", uri.toString()).apply();
            refreshFolderLabel();
        }
    }

    private void refreshFolderLabel() {
        if (folderValue != null) folderValue.setText(StorageHelper.displayFolder(this, prefs.getString("tree_uri", "")));
    }

    private void handleSharedText(Intent intent) {
        if (intent == null || urlInput == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            String link = text == null ? null : extractLink(text.toString());
            if (link != null) {
                urlInput.setText(link);
                urlInput.setSelection(link.length());
                openSheet();
            }
            intent.setAction(null); // don't reopen on rotation/recreate
        }
    }

    private static String extractLink(String s) {
        if (s == null) return null;
        int h = s.indexOf("http://");
        if (h < 0) h = s.indexOf("https://");
        if (h < 0) return null;
        String candidate = s.substring(h).trim().split("\\s")[0];
        return candidate.length() > 10 ? candidate : null;
    }

    private static String duration(int seconds) {
        int h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%d:%02d", m, s);
    }

    private static int modeIndexOf(String mode) {
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(mode)) return i;
        return 0;
    }

    // ---------- View helpers ----------

    private View optionRow(String label, TextView value, boolean dropdown) {
        LinearLayout row = hbox();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(12), 0);
        row.setBackground(Ui.pressable(this, Ui.FIELD, 0));
        row.addView(text(label, 15, Ui.INK, false), lp(-2, -2));
        LinearLayout.LayoutParams vp = lp(0, -2, 1f);
        vp.leftMargin = dp(12);
        value.setGravity(Gravity.END);
        row.addView(value, vp);
        ImageView chev = new ImageView(this);
        chev.setImageResource(dropdown ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_right);
        chev.setImageTintList(ColorStateList.valueOf(Ui.MUTED));
        LinearLayout.LayoutParams cp = lp(dp(16), dp(16));
        cp.leftMargin = dp(6);
        row.addView(chev, cp);
        return row;
    }

    private View switchRow(String label, Switch sw) {
        LinearLayout row = hbox();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(12), 0);
        row.setBackground(Ui.pressable(this, Ui.FIELD, 0));
        row.addView(text(label, 15, Ui.INK, false), lp(0, -2, 1f));
        row.addView(sw, lp(-2, -2));
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    private Switch styledSwitch(boolean checked) {
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{Ui.ACCENT, Color.WHITE}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{Ui.ACCENT, Color.parseColor("#B8AFA3")}));
        return sw;
    }

    private TextView valueText() {
        TextView t = text("", 15, Ui.INK, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams p = lp(-1, dp(1));
        p.setMargins(dp(16), 0, dp(16), 0);
        v.setLayoutParams(p);
        return v;
    }

    /** Accent button with an optional leading icon, both centered together. */
    private View primaryButton(String label, int iconRes) {
        LinearLayout b = hbox();
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.pressable(this, Ui.ACCENT, 16));
        if (iconRes != 0) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(iconRes);
            icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            LinearLayout.LayoutParams ip = lp(dp(20), dp(20));
            ip.rightMargin = dp(8);
            b.addView(icon, ip);
        }
        b.addView(text(label, 16, Color.WHITE, true));
        b.setContentDescription(label);
        b.setFocusable(true);
        b.setClickable(true);
        return b;
    }

    private TextView pill(String label, int fill, int color) {
        TextView t = text(label, 14, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setBackground(Ui.pressable(this, fill, 12));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private ImageView iconButton(int res, String description, int fill, int tint, int sizeDp, int radiusDp) {
        ImageView b = new ImageView(this);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(tint));
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(sizeDp >= 44 ? 12 : 11);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(Ui.pressable(this, fill, radiusDp));
        b.setContentDescription(description);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private android.graphics.drawable.Drawable tinted(int res, int color, int sizeDp) {
        android.graphics.drawable.Drawable d = getDrawable(res).mutate();
        d.setTint(color);
        d.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
        return d;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private LinearLayout vbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout hbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private View space(int h) { View v = new View(this); v.setLayoutParams(lp(1, dp(h))); return v; }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private LinearLayout.LayoutParams lp(int w, int h, float weight) { return new LinearLayout.LayoutParams(w, h, weight); }
    private int dp(float v) { return Ui.dp(this, v); }

    private void hideKeyboard() {
        View v = getCurrentFocus();
        if (v != null) ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0);
        if (urlInput != null) urlInput.clearFocus();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------- Small custom views ----------

    private static final class CardHolder {
        int state;
        Bar bar;
        TextView meta;
    }

    /** Bottom sheet that never grows taller than the screen minus the status bar. */
    private static final class SheetLayout extends LinearLayout {
        int topGap;
        SheetLayout(Context c) { super(c); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int available = MeasureSpec.getSize(heightSpec) - topGap;
            if (available > 0) heightSpec = MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST);
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /** Thin rounded progress bar. */
    private static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int progress;

        Bar(Context c) {
            super(c);
            track.setColor(Ui.TRACK);
            fill.setColor(Ui.ACCENT);
        }

        void setProgress(int p) {
            if (p == progress) return;
            progress = p;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float r = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, r, r, track);
            if (progress > 0) {
                rect.right = Math.max(getHeight(), getWidth() * progress / 100f);
                canvas.drawRoundRect(rect, r, r, fill);
            }
        }
    }
}
