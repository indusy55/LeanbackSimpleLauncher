package com.indusy.leanback.simple.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scans launchable activities off the UI thread and merges the user's saved
 * order. Nothing here is retained except the resulting list.
 */
final class AppRepository {

    interface Callback {
        void onApps(List<AppEntry> apps);
    }

    private static final String PREFS = "launcher";
    private static final String KEY_ORDER = "order";

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean loading = new AtomicBoolean();

    AppRepository(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void load(final Callback callback) {
        if (!loading.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                List<AppEntry> apps;
                try {
                    apps = scan();
                } catch (Throwable error) {
                    apps = Collections.emptyList();
                }
                final List<AppEntry> result = apps;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        loading.set(false);
                        callback.onApps(result);
                    }
                });
            }
        }, "app-scan");
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    void saveOrder(List<AppEntry> apps) {
        StringBuilder sb = new StringBuilder(apps.size() * 48);
        for (int i = 0; i < apps.size(); i++) {
            sb.append(apps.get(i).key).append('\n');
        }
        prefs.edit().putString(KEY_ORDER, sb.toString()).apply();
    }

    private List<AppEntry> scan() {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(intent, 0);

        ArrayList<AppEntry> list = new ArrayList<>(resolved.size());
        for (int i = 0; i < resolved.size(); i++) {
            ResolveInfo info = resolved.get(i);
            ActivityInfo activity = info.activityInfo;
            if (activity == null) {
                continue;
            }
            CharSequence label = info.loadLabel(pm);
            list.add(new AppEntry(activity.packageName, activity.name,
                    label == null ? activity.packageName : label.toString()));
        }

        Configuration config = context.getResources().getConfiguration();
        Locale locale = config.getLocales().get(0);
        final Collator collator = Collator.getInstance(locale);
        Collections.sort(list, new java.util.Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return collator.compare(a.label, b.label);
            }
        });

        return applySavedOrder(list);
    }

    private List<AppEntry> applySavedOrder(ArrayList<AppEntry> sorted) {
        String saved = prefs.getString(KEY_ORDER, null);
        if (saved == null || saved.length() == 0) {
            return sorted;
        }
        HashMap<String, AppEntry> byKey = new HashMap<>(sorted.size() * 2);
        for (int i = 0; i < sorted.size(); i++) {
            AppEntry entry = sorted.get(i);
            byKey.put(entry.key, entry);
        }
        ArrayList<AppEntry> ordered = new ArrayList<>(sorted.size());
        String[] keys = saved.split("\n");
        for (int i = 0; i < keys.length; i++) {
            AppEntry entry = byKey.remove(keys[i]);
            if (entry != null) {
                ordered.add(entry);
            }
        }
        for (int i = 0; i < sorted.size(); i++) {
            AppEntry entry = sorted.get(i);
            if (byKey.remove(entry.key) != null) {
                ordered.add(entry);
            }
        }
        return ordered;
    }
}
