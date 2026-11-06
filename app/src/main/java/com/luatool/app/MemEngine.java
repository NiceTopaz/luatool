package com.luatool.app;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Root-free memory access for processes of the SAME uid, through /proc/PID/mem.
 * Pure Java (no native code), so it works on any ABI.
 */
public final class MemEngine {
    public static final int T_BYTE = 1, T_WORD = 2, T_DWORD = 4, T_XOR = 8, T_FLOAT = 16, T_QWORD = 32, T_DOUBLE = 64;

    public static final int R_C_HEAP = 1, R_JAVA_HEAP = 2, R_C_ALLOC = 4, R_C_DATA = 8, R_C_BSS = 16,
            R_ANON = 32, R_STACK = 64, R_OTHER_BIT = 128, R_CODE_APP = 16384, R_JAVA = 65536,
            R_ASHMEM = 524288;
    public static final int R_OTHER = -2080896;
    public static final int R_ALL = -1;
    public static final int DEFAULT_RANGES = R_C_HEAP | R_JAVA_HEAP | R_C_ALLOC | R_C_DATA | R_C_BSS | R_ANON | R_STACK | R_ASHMEM;

    public static final int SIGN_EQUAL = 536870912, SIGN_NOT_EQUAL = 1073741824, SIGN_GREATER_OR_EQUAL = 1, SIGN_LESS_OR_EQUAL = 2;

    static final int MAX_RESULTS = 2000000;
    static final int CHUNK = 256 * 1024;

    public static final class Cancelled extends RuntimeException {
        Cancelled() { super("cancelled"); }
    }

    public static final class Region {
        public long start, end;
        public String perms, path;
        public int cls;
    }

    private static final class Frozen {
        long addr; int type; double d; long l;
    }

    public final int pid;
    public volatile boolean cancel;
    public int ranges = DEFAULT_RANGES;
    private RandomAccessFile mem;
    private boolean closed;

    private long[] addrs = new long[1024];
    private byte[] types = new byte[1024];
    private int count;

    private final ArrayList<Frozen> frozen = new ArrayList<>();
    private Thread freezer;

    public MemEngine(int pid) { this.pid = pid; }

    // ---------- lifecycle ----------

    /** @return null if OK, otherwise a human readable error. */
    public synchronized String open() {
        try {
            mem = new RandomAccessFile("/proc/" + pid + "/mem", "rw");
        } catch (Throwable e1) {
            try {
                mem = new RandomAccessFile("/proc/" + pid + "/mem", "r");
                AppLog.add("Note: memory opened READ-ONLY (writes will fail).");
            } catch (Throwable e2) {
                return "Cannot open /proc/" + pid + "/mem: " + e2.getMessage();
            }
        }
        List<Region> rs = regions(R_ALL, 0, Long.MAX_VALUE, true);
        if (rs.isEmpty()) return "Opened, but could not read /proc/" + pid + "/maps";
        byte[] b = new byte[8];
        for (int i = 0; i < rs.size() && i < 8; i++) {
            if (readAt(rs.get(i).start, b, 8) == 8) return null;
        }
        return "Opened, but reading its memory is blocked on this device (SELinux/ROM).";
    }

    public synchronized void close() {
        closed = true;
        clearFreeze();
        try { if (mem != null) mem.close(); } catch (IOException ignored) {}
        mem = null;
    }

    // ---------- regions ----------

    static int classify(String path) {
        if (path.contains("[heap]")) return R_C_HEAP;
        if (path.contains("[stack")) return R_STACK;
        if (path.contains("dalvik-main space") || path.contains("dalvik-alloc space")
                || path.contains("dalvik-large object") || path.contains("dalvik-zygote")
                || path.contains("dalvik-non moving") || path.contains("dalvik-free list"))
            return R_JAVA_HEAP;
        if (path.contains("libc_malloc") || path.contains("scudo") || path.contains("jemalloc")) return R_C_ALLOC;
        if (path.contains(".bss")) return R_C_BSS;
        if (path.startsWith("/dev/ashmem")) return R_ASHMEM;
        if (path.isEmpty()) return R_ANON;
        if (path.contains(".so")) return R_C_DATA;
        return R_OTHER_BIT;
    }

    public List<Region> regions(int mask, long from, long to, boolean scan) {
        ArrayList<Region> out = new ArrayList<>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/" + pid + "/maps"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\\s+", 6);
                if (p.length < 5) continue;
                String perms = p[1];
                if (scan && (perms.length() < 2 || perms.charAt(0) != 'r' || perms.charAt(1) != 'w')) continue;
                String path = p.length > 5 ? p[5].trim() : "";
                if (scan) {
                    if (path.startsWith("/dev/") && !path.startsWith("/dev/ashmem")) continue;
                    if (path.startsWith("/sys/") || path.startsWith("/proc/")) continue;
                    if (path.equals("[vvar]") || path.equals("[vectors]") || path.equals("[vsyscall]")) continue;
                }
                int dash = p[0].indexOf('-');
                if (dash < 0) continue;
                long s, e;
                try {
                    s = Long.parseLong(p[0].substring(0, dash), 16);
                    e = Long.parseLong(p[0].substring(dash + 1), 16);
                } catch (NumberFormatException ex) { continue; }
                int cls = classify(path);
                if ((mask & cls) == 0) continue;
                if (e <= from || s >= to) continue;
                Region r = new Region();
                r.start = Math.max(s, from);
                r.end = Math.min(e, to);
                r.perms = perms;
                r.path = path;
                r.cls = cls;
                out.add(r);
            }
        } catch (IOException ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (IOException ignored) {}
        }
        return out;
    }

    // ---------- raw access ----------

    public synchronized int readAt(long addr, byte[] buf, int len) {
        if (mem == null) return -1;
        try {
            mem.seek(addr);
            int off = 0;
            while (off < len) {
                int n = mem.read(buf, off, len - off);
                if (n <= 0) break;
                off += n;
            }
            return off;
        } catch (Throwable e) {
            return -1;
        }
    }

    public synchronized boolean writeAt(long addr, byte[] data) {
        if (mem == null) return false;
        try {
            mem.seek(addr);
            mem.write(data, 0, data.length);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static int sizeOf(int type) {
        switch (type) {
            case T_BYTE: return 1;
            case T_WORD: return 2;
            case T_DWORD: case T_FLOAT: return 4;
            case T_QWORD: case T_DOUBLE: return 8;
            default: throw new IllegalArgumentException("unsupported type " + type + " (use BYTE, WORD, DWORD, FLOAT, QWORD or DOUBLE)");
        }
    }

    /** @return Long, Double, or null if unreadable. */
    public Object readValue(long addr, int type) {
        int size = sizeOf(type);
        byte[] b = new byte[8];
        if (readAt(addr, b, size) != size) return null;
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        switch (type) {
            case T_BYTE: return (long) bb.get(0);
            case T_WORD: return (long) bb.getShort(0);
            case T_DWORD: return (long) bb.getInt(0);
            case T_QWORD: return bb.getLong(0);
            case T_FLOAT: return (double) bb.getFloat(0);
            default: return bb.getDouble(0);
        }
    }

    public boolean writeNumber(long addr, int type, double d, long l) {
        int size = sizeOf(type);
        byte[] b = new byte[size];
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        switch (type) {
            case T_BYTE: bb.put((byte) l); break;
            case T_WORD: bb.putShort((short) l); break;
            case T_DWORD: bb.putInt((int) l); break;
            case T_QWORD: bb.putLong(l); break;
            case T_FLOAT: bb.putFloat((float) d); break;
            default: bb.putDouble(d); break;
        }
        return writeAt(addr, b);
    }

    // ---------- results ----------

    public int count() { return count; }
    public long addrAt(int i) { return addrs[i]; }
    public int typeAt(int i) { return types[i]; }

    public void clearResults() { count = 0; }

    private void add(long a, int type) {
        if (count == addrs.length) {
            int n = Math.min(MAX_RESULTS, addrs.length * 2);
            addrs = java.util.Arrays.copyOf(addrs, n);
            types = java.util.Arrays.copyOf(types, n);
        }
        addrs[count] = a;
        types[count] = (byte) type;
        count++;
    }

    // ---------- search ----------

    private static final class Matcher {
        final int type, sign;
        long llo, lhi;
        double dlo, dhi;
        boolean unsigned;

        Matcher(String text, int type, int sign) {
            this.type = type;
            this.sign = sign;
            sizeOf(type);
            text = text.trim();
            boolean fl = type == T_FLOAT || type == T_DOUBLE;
            String s1 = text, s2 = text;
            int t = text.indexOf('~');
            if (t > 0) { s1 = text.substring(0, t).trim(); s2 = text.substring(t + 1).trim(); }
            try {
                if (fl) {
                    dlo = Double.parseDouble(s1);
                    dhi = Double.parseDouble(s2);
                    if (dlo > dhi) { double x = dlo; dlo = dhi; dhi = x; }
                    if (type == T_FLOAT) { dlo = (float) dlo; dhi = (float) dhi; }
                } else {
                    llo = parseLong(s1);
                    lhi = parseLong(s2);
                    if (llo > lhi) { long x = llo; llo = lhi; lhi = x; }
                    unsigned = (type == T_BYTE || type == T_WORD) && llo >= 0;
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a number: " + text);
            }
            if (sign != SIGN_EQUAL && sign != SIGN_NOT_EQUAL && sign != SIGN_GREATER_OR_EQUAL && sign != SIGN_LESS_OR_EQUAL)
                throw new IllegalArgumentException("unsupported sign (fuzzy search is not implemented)");
        }

        static long parseLong(String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return (long) Double.parseDouble(s); }
        }

        boolean ti(long v) {
            switch (sign) {
                case SIGN_NOT_EQUAL: return v < llo || v > lhi;
                case SIGN_GREATER_OR_EQUAL: return v >= llo;
                case SIGN_LESS_OR_EQUAL: return v <= lhi;
                default: return v >= llo && v <= lhi;
            }
        }

        boolean td(double v) {
            switch (sign) {
                case SIGN_NOT_EQUAL: return v < dlo || v > dhi;
                case SIGN_GREATER_OR_EQUAL: return v >= dlo;
                case SIGN_LESS_OR_EQUAL: return v <= dhi;
                default: return v >= dlo && v <= dhi;
            }
        }

        boolean match(ByteBuffer bb, int off) {
            switch (type) {
                case T_DWORD: return ti(bb.getInt(off));
                case T_QWORD: return ti(bb.getLong(off));
                case T_FLOAT: return td(bb.getFloat(off));
                case T_DOUBLE: return td(bb.getDouble(off));
                case T_WORD: { short v = bb.getShort(off); return ti(unsigned ? (v & 0xFFFF) : v); }
                default: { byte v = bb.get(off); return ti(unsigned ? (v & 0xFF) : v); }
            }
        }
    }

    /** New search over memory. Returns number of results. */
    public int search(String text, int type, int sign, long from, long to, int limit) {
        Matcher m = new Matcher(text, type, sign);
        int size = sizeOf(type);
        int cap = limit > 0 ? Math.min(limit, MAX_RESULTS) : MAX_RESULTS;
        count = 0;
        byte[] buf = new byte[CHUNK];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        for (Region r : regions(ranges, from, to, true)) {
            long a = r.start;
            while (a < r.end) {
                if (cancel) throw new Cancelled();
                int want = (int) Math.min((long) CHUNK, r.end - a);
                int got = readAt(a, buf, want);
                if (got == want) {
                    if (scan(bb, want, a, m, type, size, cap)) return count;
                } else {
                    for (int off = 0; off < want; off += 4096) {
                        int len = Math.min(4096, want - off);
                        if (readAt(a + off, buf, len) == len) {
                            if (scan(bb, len, a + off, m, type, size, cap)) return count;
                        }
                    }
                }
                a += want;
            }
        }
        return count;
    }

    private boolean scan(ByteBuffer bb, int len, long base, Matcher m, int type, int size, int cap) {
        for (int off = 0; off + size <= len; off += size) {
            if (m.match(bb, off)) {
                add(base + off, type);
                if (count >= cap) return true;
            }
        }
        return false;
    }

    /** Keep only current results that match the new condition. */
    public int refine(String text, int type, int sign) {
        Matcher m = new Matcher(text, type, sign);
        int size = sizeOf(type);
        byte[] pg = new byte[4096 + 8];
        ByteBuffer bb = ByteBuffer.wrap(pg).order(ByteOrder.LITTLE_ENDIAN);
        long pageBase = -1;
        int pageLen = 0;
        int n = 0;
        for (int i = 0; i < count; i++) {
            if ((i & 0x3FFF) == 0 && cancel) throw new Cancelled();
            long a = addrs[i];
            long pb = a & ~4095L;
            int rel = (int) (a - pb);
            if (pb != pageBase || rel + size > pageLen) {
                pageBase = pb;
                pageLen = Math.max(0, readAt(pb, pg, 4096 + 8));
            }
            if (rel + size > pageLen) continue;
            if (m.match(bb, rel)) {
                addrs[n] = a;
                types[n] = (byte) type;
                n++;
            }
        }
        count = n;
        return count;
    }

    // ---------- group search ("a;b;c" in GameGuardian syntax) ----------

    private static final class Group {
        Matcher[] ms;
        int[] offs;
        int length;

        static int typeFromLetter(char c) {
            switch (Character.toUpperCase(c)) {
                case 'B': return T_BYTE;
                case 'W': return T_WORD;
                case 'D': return T_DWORD;
                case 'F': return T_FLOAT;
                case 'Q': return T_QWORD;
                case 'E': return T_DOUBLE;
                default: return -1;
            }
        }

        static Group parse(String spec, int defType) {
            if (spec.contains("::"))
                throw new IllegalArgumentException("'::distance' is not supported; a group is matched as one packed sequence");
            String[] parts = spec.split(";");
            ArrayList<Matcher> ms = new ArrayList<>();
            ArrayList<Integer> offs = new ArrayList<>();
            int off = 0;
            for (String part : parts) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                int type = defType;
                int lt = typeFromLetter(t.charAt(t.length() - 1));
                if (lt > 0 && t.length() > 1) {
                    type = lt;
                    t = t.substring(0, t.length() - 1).trim();
                }
                ms.add(new Matcher(t, type, SIGN_EQUAL));
                offs.add(off);
                off += sizeOf(type);
            }
            if (ms.isEmpty()) throw new IllegalArgumentException("empty group");
            Group g = new Group();
            g.ms = ms.toArray(new Matcher[0]);
            g.offs = new int[offs.size()];
            for (int i = 0; i < g.offs.length; i++) g.offs[i] = offs.get(i);
            g.length = off;
            return g;
        }

        boolean matchAt(ByteBuffer bb, int base) {
            for (int i = 0; i < ms.length; i++) {
                if (!ms[i].match(bb, base + offs[i])) return false;
            }
            return true;
        }
    }

    /** Group search: all values must follow each other directly, in the given order. */
    public int searchGroup(String spec, int defType, long from, long to, int limit) {
        Group g = Group.parse(spec, defType);
        int L = g.length;
        int cap = limit > 0 ? Math.min(limit, MAX_RESULTS) : MAX_RESULTS;
        int firstType = g.ms[0].type;
        count = 0;
        byte[] buf = new byte[CHUNK + 4096 + 64];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        for (Region r : regions(ranges, from, to, true)) {
            long a = r.start;
            while (a + L <= r.end) {
                if (cancel) throw new Cancelled();
                int want = (int) Math.min((long) CHUNK, r.end - a);
                int got = readAt(a, buf, want);
                if (got == want) {
                    if (scanGroup(bb, want, a, g, want - L + 1, firstType, cap)) return count;
                } else {
                    for (int off = 0; off < want; off += 4096) {
                        int len = Math.min(4096 + L - 1, want - off);
                        if (readAt(a + off, buf, len) != len) {
                            len = Math.min(4096, want - off);
                            if (readAt(a + off, buf, len) != len) continue;
                        }
                        int starts = Math.min(4096, len - L + 1);
                        if (starts > 0 && scanGroup(bb, len, a + off, g, starts, firstType, cap)) return count;
                    }
                }
                if (a + want >= r.end) break;
                a += want - (L - 1);
            }
        }
        return count;
    }

    private boolean scanGroup(ByteBuffer bb, int len, long base, Group g, int starts, int type, int cap) {
        for (int off = 0; off < starts; off++) {
            if (off + g.length > len) break;
            if (g.matchAt(bb, off)) {
                add(base + off, type);
                if (count >= cap) return true;
            }
        }
        return false;
    }

    // ---------- freezing ----------

    public void freeze(long addr, int type, double d, long l) {
        synchronized (frozen) {
            for (Frozen f : frozen) {
                if (f.addr == addr) { f.type = type; f.d = d; f.l = l; return; }
            }
            Frozen f = new Frozen();
            f.addr = addr; f.type = type; f.d = d; f.l = l;
            frozen.add(f);
        }
        writeNumber(addr, type, d, l);
        startFreezer();
    }

    public void unfreeze(long addr) {
        synchronized (frozen) {
            for (int i = 0; i < frozen.size(); i++) {
                if (frozen.get(i).addr == addr) { frozen.remove(i); break; }
            }
        }
    }

    public void clearFreeze() {
        synchronized (frozen) { frozen.clear(); }
    }

    /** Snapshot of frozen items as {address, type, double value, long value}. */
    public java.util.List<Object[]> frozenItems() {
        synchronized (frozen) {
            java.util.ArrayList<Object[]> out = new java.util.ArrayList<>();
            for (Frozen f : frozen) out.add(new Object[]{f.addr, f.type, f.d, f.l});
            return out;
        }
    }

    public int frozenCount() {
        synchronized (frozen) { return frozen.size(); }
    }

    private synchronized void startFreezer() {
        if (freezer != null) return;
        freezer = new Thread(new Runnable() {
            @Override public void run() {
                while (!closed) {
                    ArrayList<Frozen> snap;
                    synchronized (frozen) { snap = new ArrayList<>(frozen); }
                    for (Frozen f : snap) writeNumber(f.addr, f.type, f.d, f.l);
                    try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                }
            }
        }, "freezer");
        freezer.setDaemon(true);
        freezer.start();
    }
}
