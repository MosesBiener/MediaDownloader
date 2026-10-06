package com.webary.mediadownloader;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.yausername.youtubedl_android.YoutubeDL;

/** Owns yt-dlp setup and keeps the bundled yt-dlp fresh — sites break old versions quickly. */
final class Engine {
    private static final String TAG = "MediaDownloader";
    private static final long UPDATE_INTERVAL_MS = 12L * 60 * 60 * 1000;

    private static boolean initialized = false;

    private Engine() {}

    /** Initializes yt-dlp and, if the last check is stale, updates it. Blocking; call off the main thread. */
    static void ensureReady(Context context) throws Exception {
        // Never swap the yt-dlp binary underneath a running download.
        ensureReady(context, !JobStore.hasRunning());
    }

    static synchronized void ensureReady(Context context, boolean mayUpdate) throws Exception {
        Context app = context.getApplicationContext();
        if (!initialized) {
            YoutubeDL.getInstance().init(app);
            initialized = true;
        }
        SharedPreferences p = prefs(app);
        if (mayUpdate && System.currentTimeMillis() - p.getLong("last_update_check", 0) > UPDATE_INTERVAL_MS) {
            try {
                update(app);
            } catch (Exception e) {
                Log.w(TAG, "Engine update failed; continuing with the installed version", e);
            }
        }
    }

    /** Forces an update check now. Returns a human-readable result. */
    static synchronized String updateNow(Context context) {
        Context app = context.getApplicationContext();
        try {
            if (!initialized) {
                YoutubeDL.getInstance().init(app);
                initialized = true;
            }
            YoutubeDL.UpdateStatus status = update(app);
            if (status == YoutubeDL.UpdateStatus.DONE) return "Updated to " + version(app);
            return "Already up to date (" + version(app) + ")";
        } catch (Exception e) {
            Log.w(TAG, "Engine update failed", e);
            return "Update failed — check your connection";
        }
    }

    static String version(Context context) {
        try {
            String v = YoutubeDL.getInstance().versionName(context.getApplicationContext());
            return v == null ? "unknown" : v;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static YoutubeDL.UpdateStatus update(Context app) throws Exception {
        YoutubeDL.UpdateStatus status = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE);
        prefs(app).edit().putLong("last_update_check", System.currentTimeMillis()).apply();
        Log.i(TAG, "yt-dlp update: " + status + " → " + version(app));
        return status;
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences("engine", Context.MODE_PRIVATE);
    }
}
