package com.luatool.app;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Shows dialogs requested by Lua scripts and blocks the script thread until the user answers. */
public final class UiBridge {
    private UiBridge() {}

    public static final Handler main = new Handler(Looper.getMainLooper());

    interface Sink { void done(Object v); }
    interface Shower { Dialog show(Context c, Sink s); }

    private static final class Failure {
        final Throwable t;
        Failure(Throwable t) { this.t = t; }
    }

    static int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    static Object ask(final Shower sh) {
        final Context base;
        final boolean overlay;
        OverlayService svc = OverlayService.instance;
        if (svc != null && svc.themed != null) {
            base = svc.themed;
            overlay = true;
        } else if (MainActivity.current != null) {
            base = MainActivity.current;
            overlay = false;
        } else {
            throw new GgError("no UI available: start the floating menu or keep LuaTool open");
        }
        final ArrayBlockingQueue<Object[]> q = new ArrayBlockingQueue<>(1);
        final Dialog[] holder = new Dialog[1];
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    Dialog d = sh.show(base, new Sink() {
                        @Override public void done(Object v) { q.offer(new Object[]{v}); }
                    });
                    holder[0] = d;
                    d.setCanceledOnTouchOutside(false);
                    d.setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override public void onDismiss(DialogInterface di) { q.offer(new Object[]{null}); }
                    });
                    if (overlay) d.getWindow().setType(overlayType());
                    d.show();
                } catch (Throwable t) {
                    q.offer(new Object[]{new Failure(t)});
                }
            }
        });
        try {
            while (true) {
                Object[] r = q.poll(100, TimeUnit.MILLISECONDS);
                if (r != null) {
                    if (r[0] instanceof Failure) throw new GgError("cannot show dialog: " + ((Failure) r[0]).t);
                    return r[0];
                }
                if (Session.stopRequested) {
                    dismiss(holder);
                    throw new GgError("script stopped");
                }
            }
        } catch (InterruptedException e) {
            dismiss(holder);
            throw new GgError("script stopped");
        }
    }

    private static void dismiss(final Dialog[] holder) {
        main.post(new Runnable() {
            @Override public void run() {
                try { if (holder[0] != null) holder[0].dismiss(); } catch (Throwable ignored) {}
            }
        });
    }

    // ---------- dialogs ----------

    /** @return 1/2/3 for positive/negative/neutral button, 0 if dismissed. */
    static int alert(final String text, final String b1, final String b2, final String b3) {
        Object r = ask(new Shower() {
            @Override public Dialog show(Context c, final Sink s) {
                AlertDialog.Builder b = new AlertDialog.Builder(c);
                b.setMessage(text);
                b.setPositiveButton(b1 == null ? "OK" : b1, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { s.done(1); }
                });
                if (b2 != null) b.setNegativeButton(b2, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { s.done(2); }
                });
                if (b3 != null) b.setNeutralButton(b3, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { s.done(3); }
                });
                return b.create();
            }
        });
        return r instanceof Integer ? (Integer) r : 0;
    }

    /** @return 0-based index or -1 if cancelled. */
    static int choice(final String[] items, final int selected, final String message) {
        Object r = ask(new Shower() {
            @Override public Dialog show(Context c, final Sink s) {
                AlertDialog.Builder b = new AlertDialog.Builder(c);
                if (message != null) b.setTitle(message);
                b.setSingleChoiceItems(items, selected, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        s.done(w);
                        d.dismiss();
                    }
                });
                return b.create();
            }
        });
        return r instanceof Integer ? (Integer) r : -1;
    }

    /** @return checked flags or null if cancelled. */
    static boolean[] multiChoice(final String[] items, final boolean[] checked, final String message) {
        Object r = ask(new Shower() {
            @Override public Dialog show(Context c, final Sink s) {
                AlertDialog.Builder b = new AlertDialog.Builder(c);
                if (message != null) b.setTitle(message);
                b.setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int w, boolean isChecked) { checked[w] = isChecked; }
                });
                b.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { s.done(checked.clone()); }
                });
                b.setNegativeButton("Cancel", null);
                return b.create();
            }
        });
        return r instanceof boolean[] ? (boolean[]) r : null;
    }

    /** Values are String, or Boolean for checkboxes. @return null if cancelled. */
    static Object[] prompt(final String[] labels, final String[] defaults, final String[] types) {
        final int n = labels.length;
        Object r = ask(new Shower() {
            @Override public Dialog show(Context c, final Sink s) {
                final View[] fields = new View[n];
                LinearLayout box = new LinearLayout(c);
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = Ui.dp(c, 16);
                box.setPadding(pad, pad / 2, pad, 0);
                for (int i = 0; i < n; i++) {
                    if ("checkbox".equals(types[i])) {
                        CheckBox cb = new CheckBox(c);
                        cb.setText(labels[i]);
                        cb.setChecked("true".equals(defaults[i]));
                        box.addView(cb);
                        fields[i] = cb;
                    } else {
                        TextView tv = new TextView(c);
                        tv.setText(labels[i]);
                        box.addView(tv);
                        EditText et = new EditText(c);
                        et.setSingleLine();
                        et.setText(defaults[i]);
                        if ("number".equals(types[i]))
                            et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                        box.addView(et);
                        fields[i] = et;
                    }
                }
                ScrollView sv = new ScrollView(c);
                sv.addView(box);
                AlertDialog.Builder b = new AlertDialog.Builder(c);
                b.setView(sv);
                b.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Object[] out = new Object[n];
                        for (int i = 0; i < n; i++) {
                            if (fields[i] instanceof CheckBox) out[i] = ((CheckBox) fields[i]).isChecked();
                            else out[i] = ((EditText) fields[i]).getText().toString();
                        }
                        s.done(out);
                    }
                });
                b.setNegativeButton("Cancel", null);
                return b.create();
            }
        });
        return r instanceof Object[] ? (Object[]) r : null;
    }

    static void toast(final String text) {
        final Context c = Session.app;
        if (c == null) return;
        main.post(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(c, text, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
            }
        });
    }
}
