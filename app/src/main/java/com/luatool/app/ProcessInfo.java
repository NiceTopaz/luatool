package com.luatool.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** A process visible to this app (Android only shows processes of the same UID, which is what we want). */
public final class ProcessInfo {
    public final int pid;
    public final String name;
    public final long rssKb;

    public ProcessInfo(int pid, String name, long rssKb) {
        this.pid = pid;
        this.name = name;
        this.rssKb = rssKb;
    }

    public String label() {
        String self = pid == android.os.Process.myPid() ? "   (this tool)" : "";
        return String.format(Locale.US, "%s\npid %d  -  %.1f MB%s", name, pid, rssKb / 1024.0, self);
    }

    static String readText(String path, int max) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0 && bo.size() < max) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean isNum(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    public static List<ProcessInfo> list() {
        ArrayList<ProcessInfo> out = new ArrayList<>();
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            String n = d.getName();
            if (!isNum(n)) continue;
            int pid;
            try { pid = Integer.parseInt(n); } catch (NumberFormatException e) { continue; }
            String cmd = readText("/proc/" + pid + "/cmdline", 512);
            if (cmd == null) continue;
            int z = cmd.indexOf('\0');
            if (z >= 0) cmd = cmd.substring(0, z);
            cmd = cmd.trim();
            if (cmd.isEmpty()) continue;
            long rss = 0;
            String st = readText("/proc/" + pid + "/status", 4096);
            if (st != null) {
                int i = st.indexOf("VmRSS:");
                if (i >= 0) {
                    i += 6;
                    while (i < st.length() && st.charAt(i) == ' ' || i < st.length() && st.charAt(i) == '\t') i++;
                    int j = i;
                    while (j < st.length() && st.charAt(j) >= '0' && st.charAt(j) <= '9') j++;
                    if (j > i) {
                        try { rss = Long.parseLong(st.substring(i, j)); } catch (NumberFormatException ignored) {}
                    }
                }
            }
            out.add(new ProcessInfo(pid, cmd, rss));
        }
        Collections.sort(out, new Comparator<ProcessInfo>() {
            @Override public int compare(ProcessInfo a, ProcessInfo b) {
                return a.rssKb == b.rssKb ? 0 : (a.rssKb < b.rssKb ? 1 : -1);
            }
        });
        return out;
    }
}
