package com.indusy.leanback.simple.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The whole app grid is a single View: no child views, no adapters, no layout
 * pass per tile. Tiles are drawn directly, so recycling is free and the view
 * hierarchy stays at eight views for the entire screen.
 */
public final class AppGridView extends View {

    public interface Listener {
        void onLaunch(AppEntry app);

        void onFocusSettings();

        void onOrderChanged();

        void onMenuAction(int action, AppEntry app);
    }

    static final int ACTION_OPEN = 0;
    static final int ACTION_MOVE = 1;
    static final int ACTION_INFO = 2;
    static final int ACTION_UNINSTALL = 3;

    private static final int COLS = 5;
    private static final long SCROLL_MS = 190L;
    private static final long FOCUS_MS = 130L;
    private static final long HOLD_MS = 480L;
    private static final float FOCUS_GROW = 0.08f;
    private static final int DIM_ALPHA = 102;
    private static final int GHOST_FILL = 0x1A4C8DFF;
    private static final int GHOST_STROKE = 0x8C4C8DFF;
    private static final int TILE_STROKE = 0x0EFFFFFF;
    private static final int MENU_SCRIM = 0xB3000000;
    private static final int MENU_BG = 0xFF1B1D23;
    private static final int MENU_UNINSTALL = 0xFFFF7A7A;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelFocusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint menuTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint menuRowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final RectF iconRect = new RectF();
    private final DashPathEffect dash;

    private final float density;
    private final float scaled;

    private final int colorSurface;
    private final int colorFocus;
    private final int colorTextDim;
    private final int colorPlaceholder;
    private final int colorHint;

    private final CharSequence holdHint;
    private final CharSequence emptyHint;
    private final float hintAscent;
    private final float hintDescent;

    private Listener listener;
    private IconCache icons;
    private List<AppEntry> apps = Collections.emptyList();

    private float gap;
    private float tileW;
    private float tileH;
    private float radius;
    private float iconSize;
    private float padL;
    private float padR;
    private float padT;
    private float padB;
    private float iconTop;
    private float labelBaseline;
    private float labelMaxW;
    private int rowH;
    private int maxScroll;

    private int focus;
    private int scroll;
    private int animFrom;
    private int animTo;
    private long scrollStart;
    private long focusStart;
    private float focusScale = 1f;
    private boolean moving;
    private int moveFrom;
    private int pressed = -1;
    private boolean suppressClick;
    private boolean labelsDirty = true;
    private boolean loaded;
    private boolean animPosted;
    private CharSequence moveHint;
    private CharSequence menuTitle;
    private CharSequence[] menuLabels;
    private int menuIndex = -1;
    private int menuSelection;
    private float menuTitleAscent;
    private float menuTitleDescent;
    private float menuRowAscent;
    private float menuRowDescent;

    private final Runnable holdRunnable = new Runnable() {
        @Override
        public void run() {
            suppressClick = true;
            openMenu();
        }
    };

    private final Runnable anim = new Runnable() {
        @Override
        public void run() {
            animPosted = false;
            long now = SystemClock.uptimeMillis();
            boolean more = false;
            if (scroll != animTo) {
                float t = Math.min(1f, (now - scrollStart) / (float) SCROLL_MS);
                float e = 1f - (1f - t) * (1f - t);
                scroll = animFrom + Math.round((animTo - animFrom) * e);
                if (t >= 1f) {
                    scroll = animTo;
                }
                more = t < 1f;
            }
            if (focusScale < 1f + FOCUS_GROW) {
                float t = Math.min(1f, (now - focusStart) / (float) FOCUS_MS);
                float e = 1f - (1f - t) * (1f - t);
                focusScale = 1f + FOCUS_GROW * e;
                if (t < 1f) {
                    more = true;
                }
            }
            invalidate();
            if (more) {
                animPosted = true;
                postOnAnimation(this);
            }
        }
    };

    public AppGridView(Context context) {
        this(context, null);
    }

    public AppGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        scaled = context.getResources().getDisplayMetrics().scaledDensity;

        colorSurface = context.getColor(R.color.surface);
        colorFocus = context.getColor(R.color.surface_focus);
        colorTextDim = context.getColor(R.color.text_dim);
        colorPlaceholder = context.getColor(R.color.icon_placeholder);
        colorHint = context.getColor(R.color.hint);

        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1f);

        labelPaint.setColor(colorTextDim);
        labelFocusPaint.setColor(0xFF0B0C0F);
        labelFocusPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        hintPaint.setColor(colorHint);
        hintPaint.setTextAlign(Paint.Align.RIGHT);
        emptyPaint.setColor(colorTextDim);
        emptyPaint.setTextAlign(Paint.Align.CENTER);

        Paint.FontMetrics hintMetrics = hintPaint.getFontMetrics();
        hintAscent = hintMetrics.ascent;
        hintDescent = hintMetrics.descent;

        dash = new DashPathEffect(new float[]{dp(8), dp(6)}, 0f);

        holdHint = context.getString(R.string.hold_for_menu);
        emptyHint = context.getString(R.string.empty);
        menuLabels = new CharSequence[]{
                context.getString(R.string.open),
                context.getString(R.string.move),
                context.getString(R.string.app_info),
                context.getString(R.string.uninstall)
        };
        menuTitlePaint.setColor(0xFFF4F5F7);
        menuTitlePaint.setTextSize(sp(22));
        menuRowPaint.setTextSize(sp(20));
        Paint.FontMetrics titleMetrics = menuTitlePaint.getFontMetrics();
        menuTitleAscent = titleMetrics.ascent;
        menuTitleDescent = titleMetrics.descent;
        Paint.FontMetrics rowMetrics = menuRowPaint.getFontMetrics();
        menuRowAscent = rowMetrics.ascent;
        menuRowDescent = rowMetrics.descent;

        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setIconCache(IconCache cache) {
        this.icons = cache;
    }

    List<AppEntry> getApps() {
        return apps;
    }

    void setApps(List<AppEntry> list) {
        apps = list == null ? Collections.<AppEntry>emptyList() : new ArrayList<>(list);
        loaded = true;
        if (focus >= apps.size()) {
            focus = Math.max(0, apps.size() - 1);
        }
        labelsDirty = true;
        maxScroll = computeMaxScroll();
        if (scroll > maxScroll) {
            scroll = maxScroll;
            animTo = maxScroll;
        }
        ensureVisible(false);
        updateContentDescription();
        invalidate();
    }

    private int rowCount() {
        return apps.isEmpty() ? 0 : (apps.size() + COLS - 1) / COLS;
    }

    private int computeMaxScroll() {
        int viewH = getHeight() - (int) padT - (int) padB;
        if (rowH == 0 || viewH <= 0) {
            return 0;
        }
        int contentH = rowCount() * rowH - (int) gap;
        return Math.max(0, contentH - viewH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        padL = dp(64);
        padR = dp(64);
        padT = dp(24);
        padB = dp(36);
        gap = dp(20);
        tileH = dp(144);
        radius = dp(14);
        iconSize = dp(64);

        float available = w - padL - padR - gap * (COLS - 1);
        tileW = Math.max(dp(80), available / COLS);
        rowH = Math.round(tileH + gap);

        labelPaint.setTextSize(sp(18));
        labelFocusPaint.setTextSize(sp(18));
        emptyPaint.setTextSize(sp(20));
        hintPaint.setTextSize(sp(15));

        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        float labelH = metrics.descent - metrics.ascent;
        float contentH = iconSize + dp(12) + labelH;
        iconTop = Math.round((tileH - contentH) / 2f);
        labelBaseline = iconTop + iconSize + dp(12) - metrics.ascent;
        labelMaxW = Math.max(dp(40), tileW - dp(16));

        labelsDirty = true;
        maxScroll = computeMaxScroll();
        if (scroll > maxScroll) {
            scroll = maxScroll;
            animTo = maxScroll;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (labelsDirty && !apps.isEmpty()) {
            rebuildLabels();
        }
        int w = getWidth();
        int h = getHeight();

        if (apps.isEmpty()) {
            if (loaded) {
                canvas.drawText(emptyHint, 0, emptyHint.length(), w / 2f, h / 2f, emptyPaint);
            }
            return;
        }

        int viewH = h - (int) padT - (int) padB;
        int first = Math.max(0, scroll / rowH);
        int last = Math.min(rowCount() - 1, (scroll + viewH) / rowH);

        for (int row = first; row <= last; row++) {
            float y = padT + row * rowH - scroll;
            for (int col = 0; col < COLS; col++) {
                int index = row * COLS + col;
                if (index >= apps.size()) {
                    break;
                }
                float x = padL + col * (tileW + gap);
                drawTile(canvas, apps.get(index), index, x, y);
            }
        }

        drawHints(canvas, w, h);

        if (menuIndex >= 0) {
            drawMenu(canvas, w, h);
        }
    }

    private void drawMenu(Canvas canvas, int w, int h) {
        canvas.drawColor(MENU_SCRIM);

        float panelW = Math.min(dp(560), w - padL * 2);
        float rowH = dp(60);
        float headerH = dp(76);
        float padV = dp(16);
        float panelH = headerH + menuLabels.length * rowH + padV;
        float left = (w - panelW) / 2f;
        float top = (h - panelH) / 2f;

        rect.set(left, top, left + panelW, top + panelH);
        fill.setColor(MENU_BG);
        fill.setAlpha(255);
        canvas.drawRoundRect(rect, dp(20), dp(20), fill);
        stroke.setColor(0x1AFFFFFF);
        stroke.setStrokeWidth(1f);
        stroke.setAlpha(255);
        canvas.drawRoundRect(rect, dp(20), dp(20), stroke);

        menuTitlePaint.setColor(0xFFF4F5F7);
        float titleBaseline = top + headerH / 2f - (menuTitleAscent + menuTitleDescent) / 2f;
        canvas.drawText(menuTitle, 0, menuTitle.length(), left + dp(28), titleBaseline, menuTitlePaint);

        for (int i = 0; i < menuLabels.length; i++) {
            float rowTop = top + headerH + i * rowH;
            boolean selected = i == menuSelection;
            if (selected) {
                rect.set(left + dp(8), rowTop, left + panelW - dp(8), rowTop + rowH - dp(4));
                fill.setColor(colorFocus);
                fill.setAlpha(255);
                canvas.drawRoundRect(rect, dp(12), dp(12), fill);
            }
            menuRowPaint.setColor(selected ? 0xFF0B0C0F
                    : (i == ACTION_UNINSTALL ? MENU_UNINSTALL : colorTextDim));
            float baseline = rowTop + (rowH - dp(4)) / 2f
                    - (menuRowAscent + menuRowDescent) / 2f;
            canvas.drawText(menuLabels[i], 0, menuLabels[i].length(),
                    left + dp(32), baseline, menuRowPaint);
        }
    }

    private void drawTile(Canvas canvas, AppEntry app, int index, float x, float y) {
        boolean focused = !moving && isFocused() && index == focus;
        boolean dragged = moving && index == focus;
        boolean ghost = moving && index == moveFrom && moveFrom != focus;

        float scale = focused ? focusScale : (dragged ? 1.07f : 1f);
        int save = -1;
        if (scale != 1f) {
            save = canvas.save();
            canvas.scale(scale, scale, x + tileW / 2f, y + tileH / 2f);
        }

        rect.set(x, y, x + tileW, y + tileH);
        int alpha = (moving && !dragged) ? DIM_ALPHA : 255;

        if (ghost) {
            fill.setColor(GHOST_FILL);
            canvas.drawRoundRect(rect, radius, radius, fill);
            stroke.setColor(GHOST_STROKE);
            stroke.setStrokeWidth(dp(2));
            stroke.setPathEffect(dash);
            canvas.drawRoundRect(rect, radius, radius, stroke);
            stroke.setPathEffect(null);
            stroke.setStrokeWidth(1f);
        } else {
            boolean lit = focused || dragged;
            fill.setColor(lit ? colorFocus : colorSurface);
            fill.setAlpha(alpha);
            canvas.drawRoundRect(rect, radius, radius, fill);

            stroke.setColor(lit ? 0xFFFFFFFF : TILE_STROKE);
            stroke.setStrokeWidth(1f);
            stroke.setAlpha(alpha);
            canvas.drawRoundRect(rect, radius, radius, stroke);

            Bitmap bitmap = icons == null ? null : icons.get(app);
            iconRect.set(x + (tileW - iconSize) / 2f, y + iconTop,
                    x + (tileW + iconSize) / 2f, y + iconTop + iconSize);
            if (bitmap != null) {
                iconPaint.setAlpha(alpha);
                canvas.drawBitmap(bitmap, null, iconRect, iconPaint);
            } else {
                fill.setColor(colorPlaceholder);
                fill.setAlpha(alpha);
                canvas.drawRoundRect(iconRect, dp(16), dp(16), fill);
            }

            Paint label = lit ? labelFocusPaint : labelPaint;
            label.setAlpha(alpha);
            String text = app.shortLabel != null ? app.shortLabel : app.label;
            float width = label.measureText(text);
            canvas.drawText(text, x + (tileW - width) / 2f, y + labelBaseline, label);
        }

        if (save >= 0) {
            canvas.restoreToCount(save);
        }
    }

    private void drawHints(Canvas canvas, int w, int h) {
        if (moving && moveHint != null) {
            float textWidth = hintPaint.measureText(moveHint, 0, moveHint.length());
            float padX = dp(22);
            float height = dp(44);
            float bottom = h - dp(44);
            rect.set(w / 2f - textWidth / 2f - padX, bottom - height,
                    w / 2f + textWidth / 2f + padX, bottom);

            hintBgPaint.setColor(0xEE16181E);
            canvas.drawRoundRect(rect, height / 2f, height / 2f, hintBgPaint);
            stroke.setColor(0x18FFFFFF);
            stroke.setStrokeWidth(1f);
            stroke.setAlpha(255);
            canvas.drawRoundRect(rect, height / 2f, height / 2f, stroke);

            hintPaint.setTextAlign(Paint.Align.CENTER);
            hintPaint.setColor(0xFFD6D9E0);
            float baseline = bottom - height / 2f - (hintAscent + hintDescent) / 2f;
            canvas.drawText(moveHint, 0, moveHint.length(), w / 2f, baseline, hintPaint);
        } else if (!apps.isEmpty()) {
            hintPaint.setTextAlign(Paint.Align.RIGHT);
            hintPaint.setColor(colorHint);
            canvas.drawText(holdHint, 0, holdHint.length(), w - padR, h - dp(16), hintPaint);
        }
    }

    private void rebuildLabels() {
        labelsDirty = false;
        for (int i = 0; i < apps.size(); i++) {
            AppEntry entry = apps.get(i);
            entry.shortLabel = TextUtils.ellipsize(
                    entry.label, labelPaint, labelMaxW, TextUtils.TruncateAt.END).toString();
        }
    }

    private void updateContentDescription() {
        if (menuIndex >= 0) {
            setContentDescription(menuTitle);
        } else if (moving) {
            setContentDescription(getContext().getString(R.string.move));
        } else if (focus >= 0 && focus < apps.size()) {
            setContentDescription(apps.get(focus).label);
        } else {
            setContentDescription(null);
        }
    }

    private void kickAnim() {
        if (!animPosted) {
            animPosted = true;
            postOnAnimation(anim);
        }
    }

    private void setFocus(int index) {
        if (index < 0 || index >= apps.size() || index == focus) {
            return;
        }
        focus = index;
        focusScale = 1f;
        focusStart = SystemClock.uptimeMillis();
        ensureVisible(true);
        updateContentDescription();
        kickAnim();
        invalidate();
    }

    private void ensureVisible(boolean animate) {
        if (apps.isEmpty() || rowH == 0) {
            return;
        }
        int viewH = getHeight() - (int) padT - (int) padB;
        int row = focus / COLS;
        int top = row * rowH - (int) dp(8);
        int bottom = row * rowH + (int) tileH + (int) dp(8);
        int target = scroll;
        if (top < scroll) {
            target = top;
        } else if (bottom > scroll + viewH) {
            target = bottom - viewH;
        }
        target = Math.max(0, Math.min(maxScroll, target));
        if (target == scroll) {
            return;
        }
        if (animate) {
            animFrom = scroll;
            animTo = target;
            scrollStart = SystemClock.uptimeMillis();
            kickAnim();
        } else {
            scroll = target;
            animTo = target;
        }
    }

    private void openMenu() {
        if (apps.isEmpty() || moving) {
            return;
        }
        menuIndex = focus;
        menuSelection = 0;
        float maxWidth = Math.min(dp(560), getWidth() - padL * 2) - dp(56);
        menuTitle = TextUtils.ellipsize(apps.get(focus).label, menuTitlePaint,
                maxWidth, TextUtils.TruncateAt.END);
        updateContentDescription();
        invalidate();
    }

    private void closeMenu() {
        if (menuIndex < 0) {
            return;
        }
        menuIndex = -1;
        updateContentDescription();
        invalidate();
    }

    private void executeMenu() {
        if (menuIndex < 0) {
            return;
        }
        suppressClick = true;
        int action = menuSelection;
        AppEntry app = apps.get(menuIndex);
        menuIndex = -1;
        updateContentDescription();
        invalidate();
        if (listener != null) {
            listener.onMenuAction(action, app);
        }
    }

    void startMove() {
        enterMove();
    }

    private void enterMove() {
        if (apps.isEmpty() || moving) {
            return;
        }
        moving = true;
        menuIndex = -1;
        moveFrom = focus;
        moveHint = getContext().getString(R.string.move) + "   ·   OK "
                + getContext().getString(R.string.drop) + "   ·   BACK "
                + getContext().getString(R.string.cancel);
        updateContentDescription();
        invalidate();
    }

    private void cancelMove() {
        if (!moving) {
            return;
        }
        moving = false;
        moveHint = null;
        updateContentDescription();
        invalidate();
    }

    private void commitMove() {
        if (!moving) {
            return;
        }
        suppressClick = true;
        int from = moveFrom;
        int to = focus;
        moving = false;
        moveHint = null;
        if (from != to && from < apps.size() && to < apps.size()) {
            AppEntry entry = apps.remove(from);
            apps.add(to, entry);
            if (listener != null) {
                listener.onOrderChanged();
            }
        }
        updateContentDescription();
        invalidate();
    }

    private void launchFocused() {
        if (listener != null && focus >= 0 && focus < apps.size()) {
            listener.onLaunch(apps.get(focus));
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (apps.isEmpty()) {
            return super.onKeyDown(keyCode, event);
        }
        if (menuIndex >= 0) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    menuSelection = (menuSelection + menuLabels.length - 1) % menuLabels.length;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    menuSelection = (menuSelection + 1) % menuLabels.length;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (event.getRepeatCount() == 0) {
                        executeMenu();
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    closeMenu();
                    return true;
                default:
                    return true;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (focus % COLS > 0) {
                    setFocus(focus - 1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focus % COLS < COLS - 1 && focus + 1 < apps.size()) {
                    setFocus(focus + 1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focus >= COLS) {
                    setFocus(Math.min(focus - COLS, apps.size() - 1));
                } else if (!moving && listener != null) {
                    listener.onFocusSettings();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focus / COLS < rowCount() - 1) {
                    setFocus(Math.min(focus + COLS, apps.size() - 1));
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0) {
                    if (moving) {
                        commitMove();
                    } else {
                        removeCallbacks(holdRunnable);
                        postDelayed(holdRunnable, HOLD_MS);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (moving) {
                    cancelMove();
                }
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            removeCallbacks(holdRunnable);
            if (!suppressClick && !moving) {
                launchFocused();
            }
            suppressClick = false;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hitTest(event.getX(), event.getY());
                if (pressed >= 0) {
                    setFocus(pressed);
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP: {
                int index = hitTest(event.getX(), event.getY());
                boolean same = index >= 0 && index == pressed;
                pressed = -1;
                if (same) {
                    if (moving) {
                        commitMove();
                    } else {
                        launchFocused();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private int hitTest(float x, float y) {
        if (apps.isEmpty() || rowH == 0) {
            return -1;
        }
        float localX = x - padL;
        float localY = y - padT + scroll;
        if (localX < 0 || localY < 0) {
            return -1;
        }
        int col = (int) (localX / (tileW + gap));
        int row = (int) (localY / rowH);
        if (col >= COLS) {
            return -1;
        }
        if (localX - col * (tileW + gap) > tileW) {
            return -1;
        }
        if (localY - row * rowH > tileH) {
            return -1;
        }
        int index = row * COLS + col;
        return index < apps.size() ? index : -1;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (!gainFocus) {
            removeCallbacks(holdRunnable);
            suppressClick = false;
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(holdRunnable);
        removeCallbacks(anim);
        animPosted = false;
        super.onDetachedFromWindow();
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return value * scaled;
    }
}
