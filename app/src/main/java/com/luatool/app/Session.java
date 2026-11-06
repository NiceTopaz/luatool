package com.luatool.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/** Global state: selected process, running script, stop flag. */
public final class Session {
    private Session() {}

    public static volatile ProcessInfo target;
    public static volatile MemEngine engine;
    public static volatile boolean running;
    public static volatile boolean stopRequested;
    public static volatile File scriptFile;
    public static volatile Context app;

    // floating-button "click" support for scripts that poll gg.isClickedUiButton()
    public static volatile long lastClickPoll;
    public static volatile boolean clicked;

    private static Thread thread;

    /** @return null on success, else an error message. */
    public static synchronized String select(ProcessInfo p) {
        MemEngine old = engine;
        engine = null;
        if (old != null) old.close();
        target = p;
        MemEngine e = new MemEngine(p.pid);
        String err = e.open();
        if (err != null) {
            e.close();
            return err;
        }
        engine = e;
        return null;
    }

    public static void check() {
        if (stopRequested) throw new GgError("script stopped");
    }

    /** Called by os.exit() inside a script: ends the script, not the app. */
    public static void requestStopFromScript() {
        stopRequested = true;
        LuaVM.stop();
    }

    public static synchronized void run(final File f) {
        if (running) {
            AppLog.add("A script is already running - press Stop first.");
            return;
        }
        final ProcessInfo t = target;
        final MemEngine eng = engine;
        if (t != null && eng != null && !new File("/proc/" + t.pid).exists()) {
            AppLog.add("That process has ended. Refresh the list and select it again.");
            return;
        }
        if (t == null || eng == null) {
            AppLog.add("No process selected: memory functions (gg.searchNumber, ...) will fail, everything else works.");
        }
        running = true;
        stopRequested = false;
        clicked = false;
        if (eng != null) eng.cancel = false;
        scriptFile = f;
        // big stack: obfuscated scripts recurse deeply
        thread = new Thread(null, new Runnable() {
            @Override public void run() {
                AppLog.add("Running " + f.getName() + (t != null ? " on " + t.name : ""));
                try {
                    String err = ScriptRunner.run(f);
                    if (err == null) {
                        AppLog.add("Script finished." + (eng != null && eng.frozenCount() > 0
                                ? " (" + eng.frozenCount() + " values frozen - Stop clears them)" : ""));
                    } else if (stopRequested || err.endsWith("script stopped")) {
                        AppLog.add("Script stopped.");
                    } else {
                        AppLog.add("Lua error: " + err);
                    }
                } catch (Throwable e) {
                    if (stopRequested || e instanceof MemEngine.Cancelled) AppLog.add("Script stopped.");
                    else AppLog.add("Error: " + e);
                } finally {
                    running = false;
                }
            }
        }, "lua-script", 32L * 1024 * 1024);
        thread.setDaemon(true);
        thread.start();
    }

    public static void stop() {
        MemEngine e = engine;
        if (e != null) {
            e.cancel = true;
            e.clearFreeze();
        }
        if (running) {
            stopRequested = true;
            LuaVM.stop();
            Thread t = thread;
            if (t != null) t.interrupt();
            AppLog.add("Stopping...");
        } else {
            AppLog.add("Stopped (frozen values cleared).");
        }
    }

    static byte[] readBytes(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }
}
