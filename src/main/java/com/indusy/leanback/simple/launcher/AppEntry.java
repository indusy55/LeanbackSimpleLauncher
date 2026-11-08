package com.indusy.leanback.simple.launcher;

/**
 * One launchable activity. Only three strings and one cached ellipsized label
 * are kept per entry, so a 300-app list costs a few tens of KB.
 */
final class AppEntry {

    final String pkg;
    final String cls;
    final String key;
    final String label;

    /** Label pre-truncated to the tile width; null until first layout. */
    String shortLabel;

    AppEntry(String pkg, String cls, String label) {
        this.pkg = pkg;
        this.cls = cls;
        this.label = label;
        this.key = pkg + '/' + cls;
    }
}
