package com.webary.mediadownloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadService extends Service {
    public static final String ACTION_PROGRESS = "com.webary.mediadownloader.PROGRESS";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_FORMAT = "format";
    public static final String EXTRA_TREE_URI = "tree_uri";
    public static final String EXTRA_SUBFOLDER = "subfolder";
    public static final String EXTRA_EMBED_SUBS = "embed_subs";

    public static volatile boolean running = false;
    public static volatile int lastProgress = 0;
    public static volatile String lastStatus = "Ready";
    public static volatile String lastDetail = "";
    public static volatile String lastFile = "";

    private static final String CHANNEL_ID = "downloads";
    private static final int NOTIFICATION_ID = 7301;
    private static final String TAG = "MediaDownloader";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled = false;
    private String processId;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        try {
            YoutubeDL.getInstance().init(getApplication());
        } catch (Exception e) {
            Log.e(TAG, "yt-dlp init failed", e);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if ("cancel".equals(intent.getAction())) {
            cancelCurrent();
            return START_NOT_STICKY;
        }
        if (running) {
            broadcast(lastProgress, "A download is already running", lastDetail, false, false);
            return START_NOT_STICKY;
        }

        String url = intent.getStringExtra(EXTRA_URL);
        String mode = nvl(intent.getStringExtra(EXTRA_MODE), "Video");
        String quality = nvl(intent.getStringExtra(EXTRA_QUALITY), "Best available");
        String format = nvl(intent.getStringExtra(EXTRA_FORMAT), "MP4");
        String treeUri = intent.getStringExtra(EXTRA_TREE_URI);
        boolean subfolder = intent.getBooleanExtra(EXTRA_SUBFOLDER, true);
        boolean embedSubs = intent.getBooleanExtra(EXTRA_EMBED_SUBS, false);

        running = true;
        cancelled = false;
        lastProgress = 0;
        lastStatus = "Starting…";
        lastDetail = "Preparing downloader";
        startForeground(NOTIFICATION_ID, buildNotification(0, lastStatus, lastDetail, true));

        executor.execute(() -> runDownload(url, mode, quality, format, treeUri, subfolder, embedSubs));
        return START_NOT_STICKY;
    }

    private void runDownload(String url, String mode, String quality, String format, String treeUri,
                             boolean createSubfolder, boolean embedSubs) {
        File work = new File(getCacheDir(), "job_" + UUID.randomUUID());
        try {
            if (TextUtils.isEmpty(url)) throw new IllegalArgumentException("Paste a link first");
            if (!work.mkdirs()) throw new IllegalStateException("Could not create temporary folder");
            processId = "media_" + UUID.randomUUID();

            File finalFile;
            String title;
            String outExt = format.toLowerCase(Locale.US);

            if ("Video".equals(mode)) {
                broadcast(2, "Analyzing link…", "Finding the best streams", false, false);
                String q = heightFilter(quality);
                String videoFormat = buildVideoSelector(format, q);
                String audioFormat = buildAudioSelectorForVideo(format);

                File v = downloadOne(url, work, "%(title)s.__video__.%(ext)s", videoFormat, 5, 48, "Downloading video");
                title = titleFromMarker(v.getName(), ".__video__.");
                ensureNotCancelled();
                File a = downloadOne(url, work, "%(title)s.__audio__.%(ext)s", audioFormat, 48, 78, "Downloading audio");
                ensureNotCancelled();

                File subtitle = null;
                if (embedSubs) {
                    try {
                        broadcast(79, "Getting subtitles…", "Looking for captions", false, false);
                        subtitle = downloadSubtitle(url, work, title);
                    } catch (Exception e) {
                        Log.w(TAG, "No subtitle available", e);
                    }
                }

                finalFile = new File(work, "final." + outExt);
                broadcast(82, "Finishing…", "Combining audio and video", false, false);
                mergeVideo(v, a, subtitle, finalFile, format);
                broadcast(95, "Saving…", "Writing to your output folder", false, false);
            } else if ("Audio".equals(mode)) {
                File a = downloadOne(url, work, "%(title)s.__audio__.%(ext)s", "bestaudio/best", 5, 78, "Downloading audio");
                title = titleFromMarker(a.getName(), ".__audio__.");
                finalFile = new File(work, "final." + outExt);
                broadcast(82, "Converting…", "Creating " + format, false, false);
                convertAudio(a, finalFile, format);
                broadcast(95, "Saving…", "Writing to your output folder", false, false);
            } else {
                broadcast(8, "Getting captions…", "Looking for caption tracks", false, false);
                File sub = downloadSubtitle(url, work, null);
                title = titleFromMarker(sub.getName(), ".__sub__.");
                if ("SRT".equalsIgnoreCase(format)) {
                    finalFile = new File(work, "final.srt");
                    FFmpegSession s = FFmpegKit.execute("-y -i " + q(sub) + " " + q(finalFile));
                    if (!ReturnCode.isSuccess(s.getReturnCode())) {
                        throw new IllegalStateException("Could not convert captions to SRT");
                    }
                } else {
                    finalFile = new File(work, "final.vtt");
                    copyFile(sub, finalFile);
                }
                broadcast(95, "Saving…", "Writing captions to your output folder", false, false);
            }

            ensureNotCancelled();
            String safeTitle = StorageHelper.sanitizeFileName(title);
            String displayName = safeTitle + "." + outExt;
            String mime = mimeFor(mode, format);
            Uri saved = StorageHelper.exportFile(this, finalFile, displayName, mime, treeUri,
                    createSubfolder, safeTitle);
            lastFile = displayName;
            broadcast(100, "Downloaded", displayName, true, false);
            updateNotification(100, "Download complete", displayName, false);
            Log.i(TAG, "Saved to " + saved);
        } catch (CancelledException e) {
            broadcast(lastProgress, "Cancelled", "Download cancelled", true, true);
            updateNotification(lastProgress, "Download cancelled", "", false);
        } catch (Throwable e) {
            Log.e(TAG, "Download failed", e);
            String message = friendlyMessage(e);
            broadcast(lastProgress, "Download failed", message, true, true);
            updateNotification(lastProgress, "Download failed", message, false);
        } finally {
            running = false;
            processId = null;
            deleteRecursive(work);
            stopForeground(false);
            stopSelf();
        }
    }

    private File downloadOne(String url, File work, String template, String formatSelector,
                             int startProgress, int endProgress, String label) throws Exception {
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("--no-playlist");
        request.addOption("--no-part");
        request.addOption("--newline");
        request.addOption("-f", formatSelector);
        request.addOption("-o", new File(work, template).getAbsolutePath());

        YoutubeDL.getInstance().execute(request, (progress, eta) -> {
            int mapped = startProgress + Math.round((endProgress - startProgress) * Math.max(0f, Math.min(100f, progress)) / 100f);
            String detail = eta > 0 ? "ETA " + prettyEta(eta) : "Downloading…";
            broadcast(mapped, label, detail, false, false);
            updateNotification(mapped, label, detail, true);
            if (cancelled) throw new RuntimeException("cancelled");
        }, processId + "_" + startProgress);

        ensureNotCancelled();
        String marker = template.contains("__video__") ? ".__video__." : ".__audio__.";
        File result = findMarkerFile(work, marker);
        if (result == null) throw new IllegalStateException("The site did not return the requested media stream");
        return result;
    }

    private File downloadSubtitle(String url, File work, String expectedTitle) throws Exception {
        // Prefer English when present; if nothing is returned, retry all languages.
        File f = downloadSubtitlePass(url, work, "en.*,en");
        if (f == null) f = downloadSubtitlePass(url, work, "all");
        if (f == null) throw new IllegalStateException("No captions are available for this video");
        return f;
    }

    private File downloadSubtitlePass(String url, File work, String languages) throws Exception {
        // Remove any prior subtitle attempt.
        File[] old = work.listFiles((dir, name) -> name.contains(".__sub__."));
        if (old != null) for (File x : old) x.delete();

        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("--no-playlist");
        request.addOption("--skip-download");
        request.addOption("--write-subs");
        request.addOption("--write-auto-subs");
        request.addOption("--sub-langs", languages);
        request.addOption("--sub-format", "vtt/best");
        request.addOption("-o", new File(work, "%(title)s.__sub__.%(language)s.%(ext)s").getAbsolutePath());
        try {
            YoutubeDL.getInstance().execute(request, (progress, eta) -> {
                int mapped = 80 + Math.round(Math.max(0f, Math.min(100f, progress)) * 0.08f);
                broadcast(mapped, "Getting captions…", "Downloading subtitle track", false, false);
            }, processId + "_sub_" + languages.hashCode());
        } catch (Exception e) {
            Log.w(TAG, "Subtitle pass failed", e);
        }
        return findMarkerFile(work, ".__sub__.");
    }

    private void mergeVideo(File video, File audio, File subtitle, File output, String format) throws Exception {
        String ext = format.toLowerCase(Locale.US);
        StringBuilder cmd = new StringBuilder("-y -i ").append(q(video)).append(" -i ").append(q(audio));
        boolean canEmbed = subtitle != null && !"WEBM".equalsIgnoreCase(format);
        if (canEmbed) cmd.append(" -i ").append(q(subtitle));
        cmd.append(" -map 0:v:0 -map 1:a:0");
        if (canEmbed) cmd.append(" -map 2:0");
        cmd.append(" -c:v copy -c:a copy");
        if (canEmbed) {
            if ("MP4".equalsIgnoreCase(format)) cmd.append(" -c:s mov_text");
            else cmd.append(" -c:s srt");
        }
        if ("MP4".equalsIgnoreCase(format)) cmd.append(" -movflags +faststart");
        cmd.append(" ").append(q(output));

        FFmpegSession session = FFmpegKit.execute(cmd.toString());
        if (ReturnCode.isSuccess(session.getReturnCode())) return;

        // MP4 fallback for sites which only expose codecs that cannot be stream-copied into MP4.
        if ("MP4".equalsIgnoreCase(format)) {
            String fallback = "-y -i " + q(video) + " -i " + q(audio) +
                    " -map 0:v:0 -map 1:a:0 -c:v mpeg4 -q:v 3 -c:a aac -b:a 192k -movflags +faststart " + q(output);
            FFmpegSession second = FFmpegKit.execute(fallback);
            if (ReturnCode.isSuccess(second.getReturnCode())) return;
        }
        throw new IllegalStateException("FFmpeg could not combine the downloaded streams");
    }

    private void convertAudio(File input, File output, String format) throws Exception {
        String codec;
        if ("MP3".equalsIgnoreCase(format)) codec = "-vn -c:a libmp3lame -b:a 192k";
        else if ("M4A".equalsIgnoreCase(format)) codec = "-vn -c:a aac -b:a 192k";
        else if ("WAV".equalsIgnoreCase(format)) codec = "-vn -c:a pcm_s16le";
        else if ("FLAC".equalsIgnoreCase(format)) codec = "-vn -c:a flac";
        else codec = "-vn -c:a copy";

        FFmpegSession session = FFmpegKit.execute("-y -i " + q(input) + " " + codec + " " + q(output));
        if (!ReturnCode.isSuccess(session.getReturnCode())) {
            throw new IllegalStateException("FFmpeg could not create " + format);
        }
    }

    private String buildVideoSelector(String format, String height) {
        String h = TextUtils.isEmpty(height) ? "" : "[height<=" + height + "]";
        if ("MP4".equalsIgnoreCase(format)) {
            return "bestvideo[ext=mp4]" + h + "/bestvideo" + h + "/best[ext=mp4]" + h + "/best" + h;
        }
        if ("WEBM".equalsIgnoreCase(format)) {
            return "bestvideo[ext=webm]" + h + "/bestvideo" + h + "/best" + h;
        }
        return "bestvideo" + h + "/best" + h;
    }

    private String buildAudioSelectorForVideo(String format) {
        if ("MP4".equalsIgnoreCase(format)) return "bestaudio[ext=m4a]/bestaudio";
        if ("WEBM".equalsIgnoreCase(format)) return "bestaudio[ext=webm]/bestaudio[ext=opus]/bestaudio";
        return "bestaudio/best";
    }

    private String heightFilter(String quality) {
        if (quality == null || quality.startsWith("Best")) return "";
        String digits = quality.replaceAll("[^0-9]", "");
        return digits;
    }

    private File findMarkerFile(File dir, String marker) {
        File[] files = dir.listFiles((d, name) -> name.contains(marker) && !name.endsWith(".part") && !name.endsWith(".ytdl"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files[0];
    }

    private String titleFromMarker(String name, String marker) {
        int i = name.indexOf(marker);
        String title = i > 0 ? name.substring(0, i) : name;
        return StorageHelper.sanitizeFileName(title);
    }

    private static String q(File f) { return q(f.getAbsolutePath()); }
    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    private static String mimeFor(String mode, String format) {
        String f = format.toUpperCase(Locale.US);
        if ("Video".equals(mode)) {
            if ("MP4".equals(f)) return "video/mp4";
            if ("WEBM".equals(f)) return "video/webm";
            return "video/x-matroska";
        }
        if ("Audio".equals(mode)) {
            if ("MP3".equals(f)) return "audio/mpeg";
            if ("M4A".equals(f)) return "audio/mp4";
            if ("WAV".equals(f)) return "audio/wav";
            if ("FLAC".equals(f)) return "audio/flac";
        }
        return "SRT".equals(f) ? "application/x-subrip" : "text/vtt";
    }

    private void broadcast(int progress, String status, String detail, boolean done, boolean error) {
        lastProgress = Math.max(0, Math.min(100, progress));
        lastStatus = status;
        lastDetail = detail;
        Intent i = new Intent(ACTION_PROGRESS);
        i.setPackage(getPackageName());
        i.putExtra("progress", lastProgress);
        i.putExtra("status", status);
        i.putExtra("detail", detail);
        i.putExtra("done", done);
        i.putExtra("error", error);
        sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Media download progress");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(int progress, String title, String detail, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancelIntent = new Intent(this, DownloadService.class).setAction("cancel");
        PendingIntent cancel = PendingIntent.getService(this, 1, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(detail)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setProgress(100, Math.max(0, Math.min(100, progress)), progress <= 2);
        if (ongoing) b.addAction(new Notification.Action.Builder(null, "Cancel", cancel).build());
        return b.build();
    }

    private void updateNotification(int progress, String title, String detail, boolean ongoing) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(progress, title, detail, ongoing));
    }

    private void cancelCurrent() {
        cancelled = true;
        try {
            if (processId != null) {
                YoutubeDL.getInstance().destroyProcessById(processId + "_5");
                YoutubeDL.getInstance().destroyProcessById(processId + "_48");
            }
        } catch (Exception ignored) {}
        try { FFmpegKit.cancel(); } catch (Exception ignored) {}
    }

    private void ensureNotCancelled() throws CancelledException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new CancelledException();
    }

    private String friendlyMessage(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        if (m.contains("HTTP Error 403") || m.contains("403")) return "The site rejected this download (403). Try another link or update the downloader engine.";
        if (m.toLowerCase(Locale.US).contains("unsupported url")) return "This website or link is not supported by the current downloader engine.";
        if (m.length() > 180) m = m.substring(0, 180) + "…";
        return m;
    }

    private static String prettyEta(long sec) {
        if (sec < 60) return sec + "s";
        return (sec / 60) + "m " + (sec % 60) + "s";
    }

    private static String nvl(String value, String fallback) { return value == null ? fallback : value; }

    private static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] b = new byte[1024 * 128];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        try { file.delete(); } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private static class CancelledException extends Exception {}
}
