package com.webary.mediadownloader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_TREE = 44;
    private static final int REQ_NOTIFY = 45;
    private static final int REQ_STORAGE = 46;

    private static final int BLUE = Color.rgb(40, 103, 240);
    private static final int BLUE_DARK = Color.rgb(26, 83, 224);
    private static final int BG = Color.rgb(246, 248, 252);
    private static final int TEXT = Color.rgb(18, 24, 38);
    private static final int MUTED = Color.rgb(99, 112, 139);
    private static final int BORDER = Color.rgb(222, 228, 239);
    private static final int SOFT_BLUE = Color.rgb(235, 243, 255);
    private static final int GREEN = Color.rgb(34, 176, 92);

    private final String[] modes = {"Video", "Audio", "Captions"};
    private int modeIndex = 0;

    private EditText urlInput;
    private TextView videoTab, audioTab, captionsTab;
    private Spinner qualitySpinner, formatSpinner;
    private LinearLayout qualityBlock;
    private TextView folderPath;
    private Switch subfolderSwitch, embedSwitch;
    private LinearLayout embedRow;
    private Button downloadButton;
    private LinearLayout progressCard;
    private ProgressBar progressBar;
    private TextView progressPercent, progressTitle, progressDetail;
    private LinearLayout recentList;

    private SharedPreferences prefs;

    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int progress = intent.getIntExtra("progress", 0);
            String status = intent.getStringExtra("status");
            String detail = intent.getStringExtra("detail");
            boolean done = intent.getBooleanExtra("done", false);
            boolean error = intent.getBooleanExtra("error", false);
            updateProgress(progress, status, detail, done, error);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("media_downloader", MODE_PRIVATE);
        configureWindow();
        setContentView(buildUi());
        handleSharedText(getIntent());
        setMode(0);
        refreshFolderLabel();
        renderRecent();
        syncRunningState();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedText(intent);
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(DownloadService.ACTION_PROGRESS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(progressReceiver, filter);
        syncRunningState();
    }

    @Override protected void onStop() {
        try { unregisterReceiver(progressReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    private void configureWindow() {
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 23) w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(24));

        LinearLayout page = vbox();
        page.setPadding(dp(18), dp(16), dp(18), dp(28));
        scroll.addView(page, matchWrap());

        page.addView(buildHeader());
        page.addView(space(18));
        page.addView(buildMainCard());
        page.addView(space(14));
        page.addView(buildProgressCard());
        page.addView(space(14));
        page.addView(buildRecentCard());

        TextView foot = text("Downloads should only be used for media you have permission to save.", 11, MUTED, false);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fp = lp(-1, -2);
        fp.setMargins(dp(12), dp(18), dp(12), 0);
        page.addView(foot, fp);
        return scroll;
    }

    private View buildHeader() {
        LinearLayout row = hbox();
        row.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(round(BLUE, 14));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_download);
        icon.setPadding(dp(14), dp(14), dp(14), dp(14));
        iconBox.addView(icon, new FrameLayout.LayoutParams(dp(54), dp(54)));
        row.addView(iconBox, lp(dp(54), dp(54)));

        LinearLayout titleBox = vbox();
        LinearLayout.LayoutParams tp = lp(0, -2, 1f);
        tp.setMargins(dp(14), 0, dp(8), 0);
        TextView title = text("Media Downloader", 26, TEXT, true);
        TextView sub = text("Save media offline, clean and simple.", 14, MUTED, false);
        titleBox.addView(title);
        titleBox.addView(space(2));
        titleBox.addView(sub);
        row.addView(titleBox, tp);

        TextView settings = text("⚙", 26, Color.rgb(45, 58, 82), false);
        settings.setGravity(Gravity.CENTER);
        settings.setBackground(selectorCircle(Color.TRANSPARENT, Color.rgb(230, 234, 242)));
        settings.setOnClickListener(v -> showAbout());
        row.addView(settings, lp(dp(48), dp(48)));
        return row;
    }

    private View buildMainCard() {
        LinearLayout card = card();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));

        card.addView(text("Paste link", 16, TEXT, true));
        card.addView(space(8));

        LinearLayout urlBox = hbox();
        urlBox.setGravity(Gravity.CENTER_VERTICAL);
        urlBox.setPadding(dp(12), 0, dp(8), 0);
        urlBox.setBackground(outline(Color.rgb(249, 250, 253), BORDER, 13));
        TextView linkIcon = text("↗", 19, MUTED, true);
        linkIcon.setGravity(Gravity.CENTER);
        urlBox.addView(linkIcon, lp(dp(30), dp(54)));

        urlInput = new EditText(this);
        urlInput.setTextSize(15);
        urlInput.setTextColor(TEXT);
        urlInput.setHintTextColor(Color.rgb(145, 154, 173));
        urlInput.setHint("https://example.com/watch…");
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setBackgroundColor(Color.TRANSPARENT);
        urlInput.setPadding(dp(6), 0, dp(6), 0);
        urlBox.addView(urlInput, lp(0, dp(54), 1f));

        TextView clear = text("×", 22, Color.rgb(145, 154, 173), false);
        clear.setGravity(Gravity.CENTER);
        clear.setOnClickListener(v -> urlInput.setText(""));
        urlBox.addView(clear, lp(dp(38), dp(54)));
        card.addView(urlBox, lp(-1, dp(56)));

        card.addView(space(14));
        LinearLayout tabs = hbox();
        videoTab = tab("▶  Video");
        audioTab = tab("♪  Audio");
        captionsTab = tab("▤  Captions");
        videoTab.setOnClickListener(v -> setMode(0));
        audioTab.setOnClickListener(v -> setMode(1));
        captionsTab.setOnClickListener(v -> setMode(2));
        tabs.addView(videoTab, lp(0, dp(54), 1f));
        LinearLayout.LayoutParams ap = lp(0, dp(54), 1f); ap.setMargins(dp(8), 0, dp(8), 0);
        tabs.addView(audioTab, ap);
        tabs.addView(captionsTab, lp(0, dp(54), 1f));
        card.addView(tabs);

        card.addView(space(18));
        LinearLayout selectors = hbox();
        qualityBlock = fieldBlock("♛  Quality");
        qualitySpinner = spinner(new String[]{"Best available", "2160p", "1440p", "1080p", "720p", "480p", "360p"});
        qualityBlock.addView(qualitySpinner, lp(-1, dp(56)));
        selectors.addView(qualityBlock, lp(0, -2, 1f));

        LinearLayout formatBlock = fieldBlock("▧  Format");
        LinearLayout.LayoutParams fbp = lp(0, -2, 1f); fbp.setMargins(dp(10), 0, 0, 0);
        formatSpinner = spinner(new String[]{"MP4", "MKV", "WEBM"});
        formatBlock.addView(formatSpinner, lp(-1, dp(56)));
        selectors.addView(formatBlock, fbp);
        card.addView(selectors);

        card.addView(space(18));
        LinearLayout folder = hbox();
        folder.setGravity(Gravity.CENTER_VERTICAL);
        folder.setPadding(dp(14), dp(12), dp(10), dp(12));
        folder.setBackground(round(SOFT_BLUE, 14));
        TextView folderIcon = text("▰", 27, BLUE, true);
        folderIcon.setGravity(Gravity.CENTER);
        folder.addView(folderIcon, lp(dp(54), dp(54)));

        LinearLayout folderText = vbox();
        folderText.addView(text("Output folder", 15, TEXT, true));
        folderPath = text("Downloads / Media Downloader", 13, MUTED, false);
        folderPath.setMaxLines(2);
        folderText.addView(space(3));
        folderText.addView(folderPath);
        LinearLayout.LayoutParams ftp = lp(0, -2, 1f); ftp.setMargins(dp(4), 0, dp(8), 0);
        folder.addView(folderText, ftp);

        Button change = smallButton("Change  ›");
        change.setOnClickListener(v -> chooseFolder());
        folder.addView(change, lp(dp(108), dp(48)));
        card.addView(folder);

        card.addView(space(12));
        LinearLayout subRow = switchRow("▱", "Create subfolder", "Keep each download organized");
        subfolderSwitch = new Switch(this);
        subfolderSwitch.setChecked(true);
        subRow.addView(subfolderSwitch, lp(-2, -2));
        card.addView(subRow);

        embedRow = switchRow("CC", "Embed subtitles", "Add captions when available");
        embedSwitch = new Switch(this);
        embedSwitch.setChecked(false);
        embedRow.addView(embedSwitch, lp(-2, -2));
        card.addView(embedRow);

        card.addView(space(12));
        downloadButton = new Button(this);
        downloadButton.setText("⇩   Download");
        downloadButton.setTextSize(17);
        downloadButton.setTextColor(Color.WHITE);
        downloadButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        downloadButton.setAllCaps(false);
        downloadButton.setGravity(Gravity.CENTER);
        downloadButton.setBackground(selectorRound(BLUE, BLUE_DARK, 14));
        downloadButton.setOnClickListener(v -> startDownload());
        card.addView(downloadButton, lp(-1, dp(60)));

        return card;
    }

    private View buildProgressCard() {
        progressCard = vbox();
        progressCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        progressCard.setBackground(outline(Color.rgb(241, 247, 255), Color.rgb(187, 211, 255), 16));
        progressCard.setVisibility(View.GONE);

        LinearLayout top = hbox(); top.setGravity(Gravity.CENTER_VERTICAL);
        progressTitle = text("Downloading…", 16, TEXT, true);
        progressPercent = text("0%", 15, BLUE, true); progressPercent.setGravity(Gravity.RIGHT);
        top.addView(progressTitle, lp(0, -2, 1f));
        top.addView(progressPercent, lp(dp(60), -2));
        progressCard.addView(top);
        progressCard.addView(space(10));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        if (Build.VERSION.SDK_INT >= 21) progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
        progressCard.addView(progressBar, lp(-1, dp(8)));
        progressCard.addView(space(8));

        LinearLayout bottom = hbox(); bottom.setGravity(Gravity.CENTER_VERTICAL);
        progressDetail = text("Preparing downloader", 13, MUTED, false);
        bottom.addView(progressDetail, lp(0, -2, 1f));
        Button cancel = smallButton("Cancel");
        cancel.setOnClickListener(v -> {
            Intent i = new Intent(this, DownloadService.class).setAction("cancel");
            startService(i);
        });
        bottom.addView(cancel, lp(dp(86), dp(42)));
        progressCard.addView(bottom);
        return progressCard;
    }

    private View buildRecentCard() {
        LinearLayout card = card();
        card.setPadding(dp(18), dp(16), dp(18), dp(12));
        LinearLayout head = hbox(); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Recent downloads", 18, TEXT, true), lp(0, -2, 1f));
        TextView clear = text("Clear", 13, BLUE, true);
        clear.setPadding(dp(10), dp(8), dp(2), dp(8));
        clear.setOnClickListener(v -> {
            prefs.edit().remove("recent").apply();
            renderRecent();
        });
        head.addView(clear);
        card.addView(head);
        card.addView(space(8));
        recentList = vbox();
        card.addView(recentList);
        return card;
    }

    private void setMode(int index) {
        modeIndex = index;
        styleTab(videoTab, index == 0);
        styleTab(audioTab, index == 1);
        styleTab(captionsTab, index == 2);

        if (index == 0) {
            setSpinner(formatSpinner, new String[]{"MP4", "MKV", "WEBM"});
            qualityBlock.setVisibility(View.VISIBLE);
            embedRow.setVisibility(View.VISIBLE);
        } else if (index == 1) {
            setSpinner(formatSpinner, new String[]{"MP3", "M4A", "WAV", "FLAC"});
            qualityBlock.setVisibility(View.GONE);
            embedRow.setVisibility(View.GONE);
        } else {
            setSpinner(formatSpinner, new String[]{"VTT", "SRT"});
            qualityBlock.setVisibility(View.GONE);
            embedRow.setVisibility(View.GONE);
        }
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setTextColor(selected ? Color.WHITE : Color.rgb(69, 82, 108));
        tab.setBackground(round(selected ? BLUE : Color.rgb(242, 245, 250), 13));
        tab.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void startDownload() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            toast("Paste a link first");
            urlInput.requestFocus();
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("That doesn't look like a web link");
            return;
        }
        if (DownloadService.running) {
            toast("A download is already running");
            return;
        }
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                && TextUtils.isEmpty(prefs.getString("tree_uri", ""))) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            toast("Allow storage, then tap Download again");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }

        hideKeyboard();
        Intent i = new Intent(this, DownloadService.class);
        i.putExtra(DownloadService.EXTRA_URL, url);
        i.putExtra(DownloadService.EXTRA_MODE, modes[modeIndex]);
        i.putExtra(DownloadService.EXTRA_QUALITY, String.valueOf(qualitySpinner.getSelectedItem()));
        i.putExtra(DownloadService.EXTRA_FORMAT, String.valueOf(formatSpinner.getSelectedItem()));
        i.putExtra(DownloadService.EXTRA_TREE_URI, prefs.getString("tree_uri", ""));
        i.putExtra(DownloadService.EXTRA_SUBFOLDER, subfolderSwitch.isChecked());
        i.putExtra(DownloadService.EXTRA_EMBED_SUBS, embedSwitch.isChecked());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);

        progressCard.setVisibility(View.VISIBLE);
        updateProgress(0, "Starting…", "Preparing downloader", false, false);
        downloadButton.setEnabled(false);
        downloadButton.setAlpha(0.6f);
    }

    private void updateProgress(int progress, String status, String detail, boolean done, boolean error) {
        progressCard.setVisibility(View.VISIBLE);
        progressBar.setProgress(progress);
        progressPercent.setText(progress + "%");
        progressTitle.setText(status == null ? "Working…" : status);
        progressDetail.setText(detail == null ? "" : detail);
        progressPercent.setTextColor(error ? Color.rgb(205, 55, 55) : (done ? GREEN : BLUE));
        if (done) {
            downloadButton.setEnabled(true);
            downloadButton.setAlpha(1f);
            if (!error && !TextUtils.isEmpty(DownloadService.lastFile)) {
                addRecent(DownloadService.lastFile, modes[modeIndex]);
                renderRecent();
            }
        }
    }

    private void syncRunningState() {
        if (DownloadService.running) {
            progressCard.setVisibility(View.VISIBLE);
            updateProgress(DownloadService.lastProgress, DownloadService.lastStatus, DownloadService.lastDetail, false, false);
            downloadButton.setEnabled(false);
            downloadButton.setAlpha(0.6f);
        } else if (downloadButton != null) {
            downloadButton.setEnabled(true);
            downloadButton.setAlpha(1f);
        }
    }

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
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
        if (folderPath == null) return;
        String tree = prefs.getString("tree_uri", "");
        folderPath.setText(StorageHelper.displayFolder(this, tree));
    }

    private void handleSharedText(Intent intent) {
        if (intent == null || urlInput == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                String s = text.toString();
                int h = s.indexOf("http");
                if (h >= 0) {
                    String candidate = s.substring(h).split("\\s")[0];
                    urlInput.setText(candidate);
                    urlInput.setSelection(candidate.length());
                }
            }
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Media Downloader")
                .setMessage("Standalone downloader powered by yt-dlp and FFmpeg.\n\nDefault output: Downloads / Media Downloader\n\nUse only for media you're allowed to download.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Reset folder", (d, w) -> {
                    prefs.edit().remove("tree_uri").apply();
                    refreshFolderLabel();
                })
                .show();
    }

    private void addRecent(String file, String mode) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("recent", "[]"));
            JSONArray next = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("file", file);
            item.put("mode", mode);
            item.put("time", System.currentTimeMillis());
            next.put(item);
            for (int i = 0; i < Math.min(arr.length(), 4); i++) next.put(arr.getJSONObject(i));
            prefs.edit().putString("recent", next.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void renderRecent() {
        if (recentList == null) return;
        recentList.removeAllViews();
        try {
            JSONArray arr = new JSONArray(prefs.getString("recent", "[]"));
            if (arr.length() == 0) {
                TextView empty = text("Your completed downloads will appear here.", 13, MUTED, false);
                empty.setPadding(0, dp(10), 0, dp(14));
                recentList.addView(empty);
                return;
            }
            SimpleDateFormat sdf = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                LinearLayout row = hbox(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(10), 0, dp(10));
                TextView badge = text(item.optString("mode", "Media").startsWith("A") ? "♪" : item.optString("mode").startsWith("C") ? "CC" : "▶", 15, BLUE, true);
                badge.setGravity(Gravity.CENTER); badge.setBackground(round(SOFT_BLUE, 10));
                row.addView(badge, lp(dp(44), dp(44)));
                LinearLayout middle = vbox();
                TextView name = text(item.optString("file", "Downloaded media"), 14, TEXT, true); name.setMaxLines(1);
                TextView meta = text(item.optString("mode", "Media") + "  •  " + sdf.format(new Date(item.optLong("time", 0))), 12, MUTED, false);
                middle.addView(name); middle.addView(space(3)); middle.addView(meta);
                LinearLayout.LayoutParams mp = lp(0, -2, 1f); mp.setMargins(dp(12), 0, dp(8), 0); row.addView(middle, mp);
                TextView check = text("✓", 18, GREEN, true); check.setGravity(Gravity.CENTER); row.addView(check, lp(dp(36), dp(36)));
                recentList.addView(row);
                if (i < arr.length() - 1) {
                    View line = new View(this); line.setBackgroundColor(Color.rgb(235, 238, 244)); recentList.addView(line, lp(-1, dp(1)));
                }
            }
        } catch (Exception e) {
            prefs.edit().remove("recent").apply();
        }
    }

    // ---------- UI helpers ----------
    private LinearLayout card() {
        LinearLayout l = vbox();
        l.setBackground(round(Color.WHITE, 20));
        if (Build.VERSION.SDK_INT >= 21) { l.setElevation(dp(2)); l.setTranslationZ(dp(1)); }
        return l;
    }

    private LinearLayout fieldBlock(String label) {
        LinearLayout box = vbox();
        TextView l = text(label, 14, TEXT, true);
        LinearLayout.LayoutParams p = lp(-1, -2); p.setMargins(dp(2), 0, 0, dp(7)); box.addView(l, p);
        return box;
    }

    private Spinner spinner(String[] values) {
        Spinner s = new Spinner(this);
        s.setPadding(dp(10), 0, dp(8), 0);
        s.setBackground(outline(Color.rgb(249, 250, 253), BORDER, 12));
        setSpinner(s, values);
        return s;
    }

    private void setSpinner(Spinner s, String[] values) {
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextSize(14); v.setTextColor(TEXT); v.setPadding(dp(6), 0, dp(6), 0); return v;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
    }

    private TextView tab(String label) {
        TextView t = text(label, 14, TEXT, true); t.setGravity(Gravity.CENTER); return t;
    }

    private LinearLayout switchRow(String icon, String title, String subtitle) {
        LinearLayout row = hbox(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(2), dp(7), 0, dp(7));
        TextView i = text(icon, 14, Color.rgb(61, 76, 104), true); i.setGravity(Gravity.CENTER); row.addView(i, lp(dp(42), dp(44)));
        LinearLayout copy = vbox(); copy.addView(text(title, 14, TEXT, false)); copy.addView(space(2)); copy.addView(text(subtitle, 11, MUTED, false));
        row.addView(copy, lp(0, -2, 1f));
        return row;
    }

    private Button smallButton(String label) {
        Button b = new Button(this); b.setText(label); b.setTextSize(13); b.setTextColor(BLUE); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false); b.setPadding(dp(8), 0, dp(8), 0); b.setBackground(outline(Color.WHITE, Color.rgb(190, 210, 246), 12)); return b;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color); t.setFontFeatureSettings("kern");
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }

    private LinearLayout vbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout hbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private View space(int dp) { View v = new View(this); v.setLayoutParams(lp(dp(1), dp(dp))); return v; }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private LinearLayout.LayoutParams lp(int w, int h, float weight) { return new LinearLayout.LayoutParams(w, h, weight); }
    private LinearLayout.LayoutParams matchWrap() { return lp(-1, -2); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radiusDp)); return g;
    }
    private GradientDrawable outline(int fill, int stroke, int radiusDp) {
        GradientDrawable g = round(fill, radiusDp); g.setStroke(dp(1), stroke); return g;
    }
    private android.graphics.drawable.StateListDrawable selectorRound(int normal, int pressed, int radiusDp) {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(pressed, radiusDp));
        s.addState(new int[]{}, round(normal, radiusDp)); return s;
    }
    private android.graphics.drawable.StateListDrawable selectorCircle(int normal, int pressed) {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        GradientDrawable p = round(pressed, 999); GradientDrawable n = round(normal, 999);
        s.addState(new int[]{android.R.attr.state_pressed}, p); s.addState(new int[]{}, n); return s;
    }

    private void hideKeyboard() {
        View v = getCurrentFocus();
        if (v != null) ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0);
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
