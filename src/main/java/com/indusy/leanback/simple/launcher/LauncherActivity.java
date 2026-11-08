package com.indusy.leanback.simple.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

public final class LauncherActivity extends Activity implements AppGridView.Listener {

    private static final String TV_SETTINGS_PACKAGE = "com.android.tv.settings";
    private static final String TV_SETTINGS_CLASS = "com.android.tv.settings.MainSettings";
    private static final String PHONE_SETTINGS_PACKAGE = "com.android.settings";
    private static final String PHONE_SETTINGS_CLASS = "com.android.settings.Settings";

    private AppGridView grid;
    private View settingsButton;
    private TextView clockTime;
    private TextView clockDate;
    private IconCache icons;
    private AppRepository repository;

    private BroadcastReceiver timeReceiver;
    private BroadcastReceiver packageReceiver;

    private String timePattern;
    private String datePattern;
    private String weekdayPattern;
    private boolean chinese;

    private final Runnable focusGrid = new Runnable() {
        @Override
        public void run() {
            grid.requestFocus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        grid = findViewById(R.id.grid);
        settingsButton = findViewById(R.id.settings);
        clockTime = findViewById(R.id.clock_time);
        clockDate = findViewById(R.id.clock_date);

        icons = new IconCache(this, grid);
        grid.setIconCache(icons);
        grid.setListener(this);

        repository = new AppRepository(this);

        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSystemSettings();
            }
        });
        settingsButton.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    grid.requestFocus();
                    return true;
                }
                return false;
            }
        });

        buildClockFormats();
        hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceivers();
        updateClock();
        reload();
        grid.post(focusGrid);
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        unregisterReceivers();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_MODERATE) {
            icons.trim();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        icons.trim();
    }

    @Override
    public void onBackPressed() {
        // This is the home screen; Back must not leave it.
    }

    // ---------------------------------------------------------------- listener

    @Override
    public void onLaunch(AppEntry app) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(app.pkg, app.cls))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (Exception e) {
            reload();
        }
    }

    @Override
    public void onFocusSettings() {
        settingsButton.requestFocus();
    }

    @Override
    public void onOrderChanged() {
        repository.saveOrder(grid.getApps());
    }

    // ----------------------------------------------------------------- internals

    private void reload() {
        repository.load(new AppRepository.Callback() {
            @Override
            public void onApps(List<AppEntry> apps) {
                grid.setApps(apps);
            }
        });
    }

    private void openSystemSettings() {
        if (start(new Intent(Settings.ACTION_SETTINGS))) {
            return;
        }
        if (start(new Intent().setComponent(
                new ComponentName(TV_SETTINGS_PACKAGE, TV_SETTINGS_CLASS)))) {
            return;
        }
        start(new Intent().setComponent(
                new ComponentName(PHONE_SETTINGS_PACKAGE, PHONE_SETTINGS_CLASS)));
    }

    private boolean start(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void buildClockFormats() {
        Configuration configuration = getResources().getConfiguration();
        Locale locale = configuration.getLocales().get(0);
        boolean hour24 = DateFormat.is24HourFormat(this);
        timePattern = DateFormat.getBestDateTimePattern(locale, hour24 ? "Hm" : "hm");
        datePattern = DateFormat.getBestDateTimePattern(locale, "MMMd");
        weekdayPattern = DateFormat.getBestDateTimePattern(locale, "EEE");
        chinese = "zh".equals(locale.getLanguage());
    }

    private void updateClock() {
        long now = System.currentTimeMillis();
        clockTime.setText(DateFormat.format(timePattern, now));
        String date = DateFormat.format(datePattern, now).toString();
        String weekday = DateFormat.format(weekdayPattern, now).toString();
        clockDate.setText(chinese ? date + " " + weekday : weekday + ", " + date);
    }

    private void registerReceivers() {
        if (timeReceiver == null) {
            timeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateClock();
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            registerReceiver(timeReceiver, filter);
        }
        if (packageReceiver == null) {
            packageReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    reload();
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            filter.addDataScheme("package");
            registerReceiver(packageReceiver, filter);
        }
    }

    private void unregisterReceivers() {
        if (timeReceiver != null) {
            unregisterReceiver(timeReceiver);
            timeReceiver = null;
        }
        if (packageReceiver != null) {
            unregisterReceiver(packageReceiver);
            packageReceiver = null;
        }
    }
}
