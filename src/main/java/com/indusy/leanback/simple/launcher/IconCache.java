package com.indusy.leanback.simple.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.view.View;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded icon cache. Icons are decoded once at the exact size they are drawn,
 * so the large source drawables are never retained. 20 slots covers one full
 * screen (5 x 4) at 96px ARGB_8888, about 720 KB; the cache is dropped on
 * low-memory callbacks.
 */
final class IconCache {

    private static final int MAX_ENTRIES = 20;

    private final PackageManager pm;
    private final int size;
    private final View target;
    private final LruCache<String, Bitmap> cache;
    private final Set<String> pending =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final ThreadPoolExecutor worker;

    IconCache(Context context, View target) {
        this.pm = context.getPackageManager();
        this.target = target;
        this.size = Math.round(context.getResources().getDisplayMetrics().density * 64f);
        this.cache = new LruCache<String, Bitmap>(MAX_ENTRIES) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return 1;
            }
        };
        this.worker = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "icons");
                        thread.setDaemon(true);
                        return thread;
                    }
                });
        this.worker.allowCoreThreadTimeOut(true);
    }

    Bitmap get(AppEntry entry) {
        Bitmap bitmap = cache.get(entry.key);
        if (bitmap == null) {
            request(entry);
        }
        return bitmap;
    }

    void trim() {
        cache.evictAll();
    }

    private void request(final AppEntry entry) {
        if (!pending.add(entry.key)) {
            return;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                try {
                    Drawable drawable = pm.getActivityIcon(new ComponentName(entry.pkg, entry.cls));
                    if (drawable != null) {
                        bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                        Canvas canvas = new Canvas(bitmap);
                        drawable.setBounds(0, 0, size, size);
                        drawable.draw(canvas);
                    }
                } catch (Throwable ignored) {
                    // Uninstalled or broken icon: leave the placeholder.
                }
                pending.remove(entry.key);
                if (bitmap != null) {
                    cache.put(entry.key, bitmap);
                    target.postInvalidateOnAnimation();
                }
            }
        });
    }
}
