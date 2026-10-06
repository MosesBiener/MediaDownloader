package com.webary.mediadownloader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads video cover images (remote URLs or local first-frame files) into ImageViews with a memory cache. */
final class Thumbs {
    private static final int TARGET_WIDTH = 480;
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private static final ExecutorService pool = Executors.newFixedThreadPool(3);

    private Thumbs() {}

    static void load(ImageView view, String source) {
        view.setTag(R.id.thumb_source, source);
        if (source == null || source.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = cache.get(source);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap bmp = fetch(source);
            if (bmp == null) return;
            cache.put(source, bmp);
            view.post(() -> {
                if (source.equals(view.getTag(R.id.thumb_source))) view.setImageBitmap(bmp);
            });
        });
    }

    private static Bitmap fetch(String source) {
        try {
            if (source.startsWith("/")) {
                try (InputStream in = new FileInputStream(source)) { return decode(readAll(in)); }
            }
            HttpURLConnection c = (HttpURLConnection) new URL(source).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            try (InputStream in = c.getInputStream()) {
                return decode(readAll(in));
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static Bitmap decode(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= TARGET_WIDTH) opts.inSampleSize *= 2;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }
}
