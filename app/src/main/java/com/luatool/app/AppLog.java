package com.luatool.app;

import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tiny in-memory log shown in the panel. */
public final class AppLog {
    public interface Listener { void onChanged(); }

    private static final ArrayList<String> lines = new ArrayList<>();
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private AppLog() {}

    public static void add(String s) {
        synchronized (lines) {
            lines.add(s);
            while (lines.size() > 150) lines.remove(0);
        }
        for (Listener l : listeners) l.onChanged();
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (lines) {
            for (String s : lines) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static void addListener(Listener l) { listeners.addIfAbsent(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }
}
