package com.luatool.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** Draws a draggable floating button and the control panel above other apps. */
public class OverlayService extends Service {
    static volatile OverlayService instance;

    Context themed;
    private WindowManager wm;
    private TextView bubble;
    private PanelView panel;
    private WindowManager.LayoutParams bubbleLp, panelLp;
    private boolean bubbleShown, panelShown;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Session.app = getApplicationContext();
        if (bubble == null) setup();
        return START_STICKY;
    }

    private void setup() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        themed = new ContextThemeWrapper(this, android.R.style.Theme_Material);
        startForegroundSafely();

        bubble = new TextView(themed);
        bubble.setText("Lua");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(13);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC3949AB);
        bg.setStroke(Ui.dp(this, 2), 0xFFFFFFFF);
        bubble.setBackground(bg);

        bubbleLp = new WindowManager.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52), UiBridge.overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = Ui.dp(this, 8);
        bubbleLp.y = Ui.dp(this, 160);

        bubble.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy;
            float tx, ty;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = bubbleLp.x; sy = bubbleLp.y;
                        tx = e.getRawX(); ty = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - tx, dy = e.getRawY() - ty;
                        if (Math.abs(dx) > Ui.dp(OverlayService.this, 6) || Math.abs(dy) > Ui.dp(OverlayService.this, 6)) moved = true;
                        if (moved) {
                            bubbleLp.x = sx + (int) dx;
                            bubbleLp.y = sy + (int) dy;
                            try { wm.updateViewLayout(bubble, bubbleLp); } catch (Throwable ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) onBubbleTap();
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(bubble, bubbleLp);
            bubbleShown = true;
            AppLog.add("Floating button is on. Tap it to open the panel.");
        } catch (Throwable t) {
            AppLog.add("Overlay failed: " + t.getMessage()
                    + "  -> allow 'Display over other apps' for this app (or for the host app, e.g. BlackBox).");
            instance = null;
            stopSelf();
        }
    }

    private void onBubbleTap() {
        // A script that polls gg.isClickedUiButton() gets the click instead of the panel opening.
        if (Session.running && System.currentTimeMillis() - Session.lastClickPoll < 2000) {
            Session.clicked = true;
            return;
        }
        if (panelShown) hidePanelNow(); else showPanelNow();
    }

    private void showPanelNow() {
        if (panelShown) return;
        try {
            if (panel == null) {
                panel = new PanelView(themed);
                panel.setOnClose(new Runnable() { @Override public void run() { hidePanelNow(); } });
                DisplayMetrics dm = getResources().getDisplayMetrics();
                int w = Math.min((int) (dm.widthPixels * 0.94f), Ui.dp(this, 460));
                int h = (int) (dm.heightPixels * 0.72f);
                panelLp = new WindowManager.LayoutParams(w, h, UiBridge.overlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
                panelLp.gravity = Gravity.CENTER;
            }
            panel.refreshAll();
            wm.addView(panel, panelLp);
            panelShown = true;
        } catch (Throwable t) {
            AppLog.add("Cannot open panel: " + t.getMessage());
        }
    }

    private void hidePanelNow() {
        if (!panelShown) return;
        try { wm.removeView(panel); } catch (Throwable ignored) {}
        panelShown = false;
    }

    private void setBubbleNow(boolean show) {
        if (bubble == null) return;
        try {
            if (show && !bubbleShown) { wm.addView(bubble, bubbleLp); bubbleShown = true; }
            else if (!show && bubbleShown) { wm.removeView(bubble); bubbleShown = false; }
        } catch (Throwable ignored) {}
    }

    // ---------- called from the Lua thread ----------

    static boolean panelVisible() {
        OverlayService s = instance;
        return s != null && s.panelShown;
    }

    static void setPanelVisible(final boolean v) {
        final OverlayService s = instance;
        if (s == null) return;
        UiBridge.main.post(new Runnable() {
            @Override public void run() { if (v) s.showPanelNow(); else s.hidePanelNow(); }
        });
    }

    static void setBubbleVisible(final boolean v) {
        final OverlayService s = instance;
        if (s == null) return;
        UiBridge.main.post(new Runnable() {
            @Override public void run() { s.setBubbleNow(v); }
        });
    }

    private void startForegroundSafely() {
        try {
            Notification.Builder nb;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.createNotificationChannel(new NotificationChannel("luatool", "LuaTool", NotificationManager.IMPORTANCE_MIN));
                nb = new Notification.Builder(this, "luatool");
            } else {
                nb = new Notification.Builder(this);
            }
            nb.setContentTitle("LuaTool floating menu is running")
              .setSmallIcon(android.R.drawable.ic_menu_edit);
            startForeground(1, nb.build());
        } catch (Throwable ignored) {
            // some virtual environments cannot post notifications; the overlay still works
        }
    }

    @Override public void onDestroy() {
        hidePanelNow();
        setBubbleNow(false);
        instance = null;
        super.onDestroy();
    }
}
