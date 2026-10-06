package com.webary.mediadownloader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory download queue (newest first). Finished jobs are persisted so history survives restarts. */
final class JobStore {
    interface Listener {
        /** structural = jobs added/removed or a job changed state; otherwise only progress moved. */
        void onJobsChanged(Job job, boolean structural);
    }

    private static final int MAX_HISTORY = 40;
    private static final List<Job> jobs = new ArrayList<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean loaded = false;
    private static Context app;

    private JobStore() {}

    static synchronized void load(Context context) {
        if (loaded) return;
        app = context.getApplicationContext();
        loaded = true;
        try {
            JSONArray arr = new JSONArray(prefs().getString("jobs", "[]"));
            for (int i = 0; i < arr.length(); i++) jobs.add(Job.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
    }

    static synchronized List<Job> snapshot() { return new ArrayList<>(jobs); }

    static synchronized Job find(String id) {
        for (Job j : jobs) if (j.id.equals(id)) return j;
        return null;
    }

    static synchronized boolean hasRunning() {
        for (Job j : jobs) if (j.state == Job.RUNNING) return true;
        return false;
    }

    /** Oldest job still waiting, so the queue runs in the order downloads were added. */
    static synchronized Job nextQueued() {
        for (int i = jobs.size() - 1; i >= 0; i--) if (jobs.get(i).state == Job.QUEUED) return jobs.get(i);
        return null;
    }

    static void add(Job job) {
        synchronized (JobStore.class) { jobs.add(0, job); }
        changed(job, true);
    }

    static void remove(Job job) {
        synchronized (JobStore.class) { jobs.remove(job); }
        changed(job, true);
    }

    static void clearFinished() {
        synchronized (JobStore.class) {
            List<Job> keep = new ArrayList<>();
            for (Job j : jobs) if (!j.isFinished()) keep.add(j);
            jobs.clear();
            jobs.addAll(keep);
        }
        changed(null, true);
    }

    /** Moves a job back to the end of the queue (as if just added). */
    static void requeue(Job job) {
        synchronized (JobStore.class) {
            jobs.remove(job);
            job.state = Job.QUEUED;
            job.progress = 0;
            job.status = "Waiting";
            job.detail = "";
            job.error = null;
            job.rawError = null;
            job.time = System.currentTimeMillis();
            jobs.add(0, job);
        }
        changed(job, true);
    }

    static void changed(Job job, boolean structural) {
        if (structural) persist();
        main.post(() -> {
            for (Listener l : listeners) l.onJobsChanged(job, structural);
        });
    }

    static void addListener(Listener l) { listeners.add(l); }
    static void removeListener(Listener l) { listeners.remove(l); }

    private static synchronized void persist() {
        if (app == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Job j : jobs) {
                if (!j.isFinished()) continue;
                arr.put(j.toJson());
                if (arr.length() >= MAX_HISTORY) break;
            }
            prefs().edit().putString("jobs", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static SharedPreferences prefs() {
        return app.getSharedPreferences("media_downloader", Context.MODE_PRIVATE);
    }
}
