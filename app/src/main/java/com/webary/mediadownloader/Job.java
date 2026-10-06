package com.webary.mediadownloader;

import org.json.JSONObject;

import java.util.UUID;

/** One download in the queue. Mutated by the service thread, read by the UI thread. */
final class Job {
    static final int QUEUED = 0, RUNNING = 1, DONE = 2, FAILED = 3, CANCELLED = 4;

    String id = UUID.randomUUID().toString();
    String url, mode, quality, format, treeUri;
    boolean subfolder, embedSubs;

    // Filled from the link preview (or at download time).
    String title, thumb, channel;

    volatile int state = QUEUED;
    volatile int progress = 0;
    volatile String status = "Waiting", detail = "";
    String error, rawError;
    String fileName, fileUri, mime;
    long time = System.currentTimeMillis();

    boolean isFinished() { return state == DONE || state == FAILED || state == CANCELLED; }

    /** Short "1080p · MP4" style summary of what was requested. */
    String spec() {
        if ("Video".equals(mode)) {
            String q = quality == null || quality.startsWith("Best") ? "Best" : quality;
            return q + " · " + format;
        }
        return format;
    }

    String displayTitle() {
        if (title != null && !title.isEmpty()) return title;
        if (fileName != null && !fileName.isEmpty()) return fileName;
        return url;
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id).put("url", url).put("mode", mode).put("quality", quality).put("format", format)
                .put("treeUri", treeUri).put("subfolder", subfolder).put("embedSubs", embedSubs)
                .put("title", title).put("thumb", thumb).put("channel", channel)
                .put("state", state).put("progress", progress).put("error", error).put("rawError", rawError)
                .put("fileName", fileName).put("fileUri", fileUri).put("mime", mime).put("time", time);
        return o;
    }

    static Job fromJson(JSONObject o) {
        Job j = new Job();
        j.id = o.optString("id", j.id);
        j.url = str(o, "url");
        j.mode = o.optString("mode", "Video");
        j.quality = str(o, "quality");
        j.format = o.optString("format", "MP4");
        j.treeUri = str(o, "treeUri");
        j.subfolder = o.optBoolean("subfolder", false);
        j.embedSubs = o.optBoolean("embedSubs", true);
        j.title = str(o, "title");
        j.thumb = str(o, "thumb");
        j.channel = str(o, "channel");
        j.state = o.optInt("state", DONE);
        j.progress = o.optInt("progress", 0);
        j.error = str(o, "error");
        j.rawError = str(o, "rawError");
        j.fileName = str(o, "fileName");
        j.fileUri = str(o, "fileUri");
        j.mime = str(o, "mime");
        j.time = o.optLong("time", 0);
        return j;
    }

    private static String str(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key, null);
    }
}
