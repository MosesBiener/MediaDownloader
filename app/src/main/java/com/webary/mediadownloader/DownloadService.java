package com.webary.mediadownloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs queued {@link Job}s one at a time in the foreground. */
public class DownloadService extends Service {
    static final String ACTION_START = "start";
    static final String ACTION_CANCEL = "cancel";
    static final String EXTRA_JOB_ID = "job_id";

    private static final String CHANNEL_ID = "downloads";
    private static final int NOTIFICATION_ID = 7301;
    private static final String TAG = "MediaDownloader";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<String> activeProcessIds = ConcurrentHashMap.newKeySet();
    private boolean draining = false;
    private volatile Job current;
    private volatile boolean cancelled = false;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        JobStore.load(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            String id = intent.getStringExtra(EXTRA_JOB_ID);
            Job running = current;
            if (running != null && (id == null || id.equals(running.id))) cancelCurrent();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(0, "Preparing download…", "", true));
        synchronized (this) {
            if (!draining) {
                draining = true;
                executor.execute(this::drain);
            }
        }
        return START_NOT_STICKY;
    }

    private void drain() {
        Job last = null;
        while (true) {
            Job job;
            synchronized (this) {
                job = JobStore.nextQueued();
                if (job == null) {
                    draining = false;
                    break;
                }
            }
            run(job);
            last = job;
        }
        stopForeground(STOP_FOREGROUND_DETACH);
        if (last != null && last.state == Job.DONE) updateNotification(100, "Download complete", last.displayTitle(), false);
        else if (last != null && last.state == Job.FAILED) updateNotification(last.progress, "Download failed", last.error, false);
        else ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        stopSelf();
    }

    private void run(Job job) {
        current = job;
        cancelled = false;
        job.state = Job.RUNNING;
        setProgress(job, 1, "Preparing…", "Checking the download engine");
        JobStore.changed(job, true);

        File work = new File(getCacheDir(), "job_" + UUID.randomUUID());
        try {
            if (!work.mkdirs()) throw new IllegalStateException("Could not create a temporary folder");
            Engine.ensureReady(this, true);
            ensureNotCancelled();

            if (TextUtils.isEmpty(job.title) || TextUtils.isEmpty(job.thumb)) {
                setProgress(job, 3, "Reading link…", "Getting video details");
                fillInfo(job);
            }

            String outExt = job.format.toLowerCase(Locale.US);
            File finalFile;
            String fileTitle;

            if ("Video".equals(job.mode)) {
                File v = downloadOne(job, work, "%(title)s.__video__.%(ext)s",
                        buildVideoSelector(job.format, heightFilter(job.quality)), 5, 48, "Downloading video");
                fileTitle = titleFromMarker(v.getName(), ".__video__.");
                ensureNotCancelled();
                File a = downloadOne(job, work, "%(title)s.__audio__.%(ext)s",
                        buildAudioSelectorForVideo(job.format), 48, 78, "Downloading audio");
                ensureNotCancelled();

                File subtitle = null;
                if (job.embedSubs) {
                    try {
                        setProgress(job, 79, "Getting subtitles…", "Looking for captions");
                        subtitle = downloadSubtitle(job, work);
                    } catch (Exception e) {
                        Log.w(TAG, "No subtitle available", e);
                    }
                }

                finalFile = new File(work, "final." + outExt);
                setProgress(job, 84, "Finishing…", "Combining audio and video");
                mergeVideo(v, a, subtitle, finalFile, job.format);
                if (TextUtils.isEmpty(job.thumb)) job.thumb = firstFrame(finalFile, job.id);
            } else if ("Audio".equals(job.mode)) {
                File a = downloadOne(job, work, "%(title)s.__audio__.%(ext)s", "bestaudio/best", 5, 78, "Downloading audio");
                fileTitle = titleFromMarker(a.getName(), ".__audio__.");
                finalFile = new File(work, "final." + outExt);
                setProgress(job, 84, "Converting…", "Creating " + job.format);
                convertAudio(a, finalFile, job.format);
            } else {
                setProgress(job, 8, "Getting captions…", "Looking for caption tracks");
                File sub = downloadSubtitle(job, work);
                if (sub == null) throw new IllegalStateException("No captions are available for this video");
                fileTitle = titleFromMarker(sub.getName(), ".__sub__.");
                if ("SRT".equalsIgnoreCase(job.format)) {
                    finalFile = new File(work, "final.srt");
                    FFmpegSession s = ffmpeg("-y", "-i", sub.getAbsolutePath(), finalFile.getAbsolutePath());
                    if (!ReturnCode.isSuccess(s.getReturnCode())) throw ffmpegFailure("Could not convert captions to SRT", s);
                } else {
                    finalFile = new File(work, "final.vtt");
                    copyFile(sub, finalFile);
                }
            }

            ensureNotCancelled();
            setProgress(job, 95, "Saving…", "Writing to your folder");
            String safeTitle = StorageHelper.sanitizeFileName(TextUtils.isEmpty(job.title) ? fileTitle : job.title);
            String displayName = safeTitle + "." + outExt;
            job.mime = mimeFor(job.mode, job.format);
            Uri saved = StorageHelper.exportFile(this, finalFile, displayName, job.mime, job.treeUri, job.subfolder, safeTitle);
            if (TextUtils.isEmpty(job.title)) job.title = safeTitle;
            job.fileName = displayName;
            job.fileUri = saved.toString();
            job.state = Job.DONE;
            setProgress(job, 100, "Saved", displayName);
            Log.i(TAG, "Saved to " + saved);
        } catch (CancelledException | YoutubeDL.CanceledException e) {
            job.state = Job.CANCELLED;
            job.status = "Cancelled";
        } catch (Throwable e) {
            if (cancelled) {
                job.state = Job.CANCELLED;
                job.status = "Cancelled";
            } else {
                Log.e(TAG, "Download failed", e);
                job.rawError = rawMessage(e);
                job.error = Errors.friendly(job.rawError);
                job.state = Job.FAILED;
                job.status = "Failed";
            }
        } finally {
            current = null;
            activeProcessIds.clear();
            deleteRecursive(work);
            job.time = System.currentTimeMillis();
            JobStore.changed(job, true);
        }
    }

    /** Title, channel and cover image, for jobs added before the preview finished loading. */
    private void fillInfo(Job job) {
        try {
            YoutubeDLRequest request = new YoutubeDLRequest(job.url);
            request.addOption("--no-playlist");
            VideoInfo info = YoutubeDL.getInstance().getInfo(request);
            if (TextUtils.isEmpty(job.title)) job.title = info.getTitle();
            if (TextUtils.isEmpty(job.thumb)) job.thumb = info.getThumbnail();
            if (TextUtils.isEmpty(job.channel)) job.channel = info.getUploader();
            JobStore.changed(job, true);
        } catch (Exception e) {
            Log.w(TAG, "Could not read video details", e);
        }
    }

    private File downloadOne(Job job, File work, String template, String formatSelector,
                             int startProgress, int endProgress, String label) throws Exception {
        YoutubeDLRequest request = new YoutubeDLRequest(job.url);
        request.addOption("--no-playlist");
        request.addOption("--no-part");
        request.addOption("--no-mtime");
        request.addOption("--newline");
        // yt-dlp's own fixups need its ffmpeg binary, which this app doesn't ship; FFmpegKit remuxes afterwards.
        request.addOption("--fixup", "never");
        request.addOption("-f", formatSelector);
        request.addOption("-o", new File(work, template).getAbsolutePath());

        String pid = "job_" + job.id + "_" + startProgress;
        activeProcessIds.add(pid);
        try {
            YoutubeDL.getInstance().execute(request, pid, (progress, eta, line) -> {
                int mapped = startProgress + Math.round((endProgress - startProgress) * Math.max(0f, Math.min(100f, progress)) / 100f);
                setProgress(job, mapped, label, eta > 0 ? "ETA " + prettyEta(eta) : "");
                return kotlin.Unit.INSTANCE;
            });
        } finally {
            activeProcessIds.remove(pid);
        }

        ensureNotCancelled();
        String marker = template.contains("__video__") ? ".__video__." : ".__audio__.";
        File result = findMarkerFile(work, marker);
        if (result == null) throw new IllegalStateException("The site did not return the requested media stream");
        return result;
    }

    /** Prefers English captions; falls back to any language. Returns null when there are none. */
    private File downloadSubtitle(Job job, File work) throws Exception {
        File f = downloadSubtitlePass(job, work, "en.*,en");
        if (f == null) f = downloadSubtitlePass(job, work, "all");
        return f;
    }

    private File downloadSubtitlePass(Job job, File work, String languages) throws Exception {
        File[] old = work.listFiles((dir, name) -> name.contains(".__sub__."));
        if (old != null) for (File x : old) x.delete();

        YoutubeDLRequest request = new YoutubeDLRequest(job.url);
        request.addOption("--no-playlist");
        request.addOption("--skip-download");
        request.addOption("--write-subs");
        request.addOption("--write-auto-subs");
        request.addOption("--sub-langs", languages);
        request.addOption("--sub-format", "vtt/best");
        request.addOption("-o", new File(work, "%(title)s.__sub__.%(language)s.%(ext)s").getAbsolutePath());
        String pid = "job_" + job.id + "_sub_" + languages.hashCode();
        activeProcessIds.add(pid);
        try {
            YoutubeDL.getInstance().execute(request, pid, null);
        } catch (YoutubeDL.CanceledException e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "Subtitle pass failed", e);
        } finally {
            activeProcessIds.remove(pid);
        }
        ensureNotCancelled();
        return findMarkerFile(work, ".__sub__.");
    }

    private void mergeVideo(File video, File audio, File subtitle, File output, String format) throws Exception {
        boolean mp4 = "MP4".equalsIgnoreCase(format);
        boolean canEmbed = subtitle != null && !"WEBM".equalsIgnoreCase(format);
        List<String> args = new ArrayList<>(Arrays.asList("-y", "-i", video.getAbsolutePath(), "-i", audio.getAbsolutePath()));
        if (canEmbed) args.addAll(Arrays.asList("-i", subtitle.getAbsolutePath()));
        args.addAll(Arrays.asList("-map", "0:v:0", "-map", "1:a:0"));
        if (canEmbed) args.addAll(Arrays.asList("-map", "2:0"));
        args.addAll(Arrays.asList("-c:v", "copy", "-c:a", "copy"));
        if (canEmbed) args.addAll(Arrays.asList("-c:s", mp4 ? "mov_text" : "srt"));
        if (mp4) args.addAll(Arrays.asList("-movflags", "+faststart"));
        args.add(output.getAbsolutePath());

        FFmpegSession session = ffmpeg(args.toArray(new String[0]));
        if (ReturnCode.isSuccess(session.getReturnCode())) return;
        ensureNotCancelled();

        // MP4 fallback for sites which only expose codecs that cannot be stream-copied into MP4.
        if (mp4) {
            FFmpegSession second = ffmpeg("-y", "-i", video.getAbsolutePath(), "-i", audio.getAbsolutePath(),
                    "-map", "0:v:0", "-map", "1:a:0", "-c:v", "mpeg4", "-q:v", "3", "-c:a", "aac", "-b:a", "192k",
                    "-movflags", "+faststart", output.getAbsolutePath());
            if (ReturnCode.isSuccess(second.getReturnCode())) return;
            session = second;
        }
        throw ffmpegFailure("FFmpeg could not combine the downloaded streams", session);
    }

    private void convertAudio(File input, File output, String format) throws Exception {
        String[] codec;
        if ("MP3".equalsIgnoreCase(format)) codec = new String[]{"-c:a", "libmp3lame", "-b:a", "192k"};
        else if ("M4A".equalsIgnoreCase(format)) codec = new String[]{"-c:a", "aac", "-b:a", "192k"};
        else if ("WAV".equalsIgnoreCase(format)) codec = new String[]{"-c:a", "pcm_s16le"};
        else if ("FLAC".equalsIgnoreCase(format)) codec = new String[]{"-c:a", "flac"};
        else codec = new String[]{"-c:a", "copy"};

        List<String> args = new ArrayList<>(Arrays.asList("-y", "-i", input.getAbsolutePath(), "-vn"));
        args.addAll(Arrays.asList(codec));
        args.add(output.getAbsolutePath());
        FFmpegSession session = ffmpeg(args.toArray(new String[0]));
        if (!ReturnCode.isSuccess(session.getReturnCode())) throw ffmpegFailure("FFmpeg could not create " + format, session);
    }

    /** Runs FFmpeg with discrete arguments — no string quoting, so titles with quotes or spaces are safe. */
    private static FFmpegSession ffmpeg(String... args) {
        return FFmpegKit.executeWithArguments(args);
    }

    /** Error carrying the tail of FFmpeg's output, so "Copy full details" shows the real cause. */
    private static IllegalStateException ffmpegFailure(String message, FFmpegSession session) {
        String out = session.getOutput();
        if (out == null) out = "";
        out = out.trim();
        if (out.length() > 1500) out = out.substring(out.length() - 1500);
        Log.e(TAG, message + "\n" + out);
        return new IllegalStateException(message + "\n" + out);
    }

    /** Saves the first frame as the cover when the site gave no thumbnail. */
    private String firstFrame(File video, String jobId) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(video.getAbsolutePath());
            Bitmap frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            File dir = new File(getFilesDir(), "covers");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File out = new File(dir, jobId + ".jpg");
            try (FileOutputStream os = new FileOutputStream(out)) {
                frame.compress(Bitmap.CompressFormat.JPEG, 85, os);
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private String buildVideoSelector(String format, String height) {
        String h = TextUtils.isEmpty(height) ? "" : "[height<=" + height + "]";
        if ("MP4".equalsIgnoreCase(format)) return "bestvideo[ext=mp4]" + h + "/bestvideo" + h + "/best[ext=mp4]" + h + "/best" + h + "/best";
        if ("WEBM".equalsIgnoreCase(format)) return "bestvideo[ext=webm]" + h + "/bestvideo" + h + "/best" + h + "/best";
        return "bestvideo" + h + "/best" + h + "/best";
    }

    /** Falls back to the combined stream so sites that only offer one file still work. */
    private String buildAudioSelectorForVideo(String format) {
        if ("MP4".equalsIgnoreCase(format)) return "bestaudio[ext=m4a]/bestaudio/best";
        if ("WEBM".equalsIgnoreCase(format)) return "bestaudio[ext=webm]/bestaudio/best";
        return "bestaudio/best";
    }

    private String heightFilter(String quality) {
        if (quality == null || quality.startsWith("Best")) return "";
        return quality.replaceAll("[^0-9]", "");
    }

    private File findMarkerFile(File dir, String marker) {
        File[] files = dir.listFiles((d, name) -> name.contains(marker) && !name.endsWith(".part") && !name.endsWith(".ytdl"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files[0];
    }

    private String titleFromMarker(String name, String marker) {
        int i = name.indexOf(marker);
        return StorageHelper.sanitizeFileName(i > 0 ? name.substring(0, i) : name);
    }


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

    private void setProgress(Job job, int progress, String status, String detail) {
        int p = Math.max(0, Math.min(100, progress));
        boolean statusChanged = !TextUtils.equals(status, job.status);
        if (p == job.progress && !statusChanged && TextUtils.equals(detail, job.detail)) return;
        boolean notify = p != job.progress || statusChanged;
        job.progress = p;
        job.status = status;
        job.detail = detail;
        JobStore.changed(job, false);
        if (notify && job.state == Job.RUNNING) {
            updateNotification(p, job.displayTitle(), status + " · " + p + "%", true);
        }
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
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancelIntent = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent cancel = PendingIntent.getService(this, 1, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(detail)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);
        if (ongoing) {
            b.setProgress(100, Math.max(0, Math.min(100, progress)), progress <= 2);
            b.addAction(new Notification.Action.Builder(null, "Cancel", cancel).build());
        } else {
            b.setAutoCancel(true);
        }
        return b.build();
    }

    private void updateNotification(int progress, String title, String detail, boolean ongoing) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(progress, title, detail, ongoing));
    }

    private void cancelCurrent() {
        cancelled = true;
        for (String pid : activeProcessIds) {
            try { YoutubeDL.getInstance().destroyProcessById(pid); } catch (Exception ignored) {}
        }
        try { FFmpegKit.cancel(); } catch (Exception ignored) {}
    }

    private void ensureNotCancelled() throws CancelledException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new CancelledException();
    }

    private static String rawMessage(Throwable e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m.trim();
    }

    private static String prettyEta(long sec) {
        if (sec < 60) return sec + "s";
        return (sec / 60) + "m " + (sec % 60) + "s";
    }

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
