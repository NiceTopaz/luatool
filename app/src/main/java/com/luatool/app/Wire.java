package com.luatool.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny binary codec used to move Lua values across the JNI boundary (and from LuaJ).
 *
 * Java side types: null (nil), Boolean, Long (Lua integer), Double (Lua float), String (UTF-8),
 * LuaTable (Map), List (written as an array table 1..n), Map (written as a table).
 *
 * Wire format (little endian):
 *   0 nil | 1 false | 2 true | 3 int64 | 4 double | 5 string(int32 len, bytes) | 6 table(int32 n, n * (key, value))
 */
public final class Wire {
    private Wire() {}

    static final Charset UTF8 = Charset.forName("UTF-8");
    static final int MAX_DEPTH = 24;

    /** A decoded Lua table. Keys are Long / Double / String / Boolean. */
    public static final class LuaTable extends LinkedHashMap<Object, Object> {
        /** Lua length operator for sequences (border starting from 1). */
        public int length() {
            int n = 0;
            while (containsKey((long) (n + 1))) n++;
            return n;
        }

        public Object at(int i) { return get((long) i); }
    }

    // ---------------------------------------------------------------- encode

    public static byte[] encode(Object... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        w32(out, values == null ? 0 : values.length);
        if (values != null) for (Object v : values) put(out, v, 0);
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void put(ByteArrayOutputStream o, Object v, int depth) {
        if (v == null) { o.write(0); return; }
        if (v instanceof Boolean) { o.write(((Boolean) v) ? 2 : 1); return; }
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            o.write(3);
            w64(o, ((Number) v).longValue());
            return;
        }
        if (v instanceof Double || v instanceof Float) {
            o.write(4);
            w64(o, Double.doubleToRawLongBits(((Number) v).doubleValue()));
            return;
        }
        if (v instanceof CharSequence) {
            byte[] b = v.toString().getBytes(UTF8);
            o.write(5);
            w32(o, b.length);
            o.write(b, 0, b.length);
            return;
        }
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            o.write(5);
            w32(o, b.length);
            o.write(b, 0, b.length);
            return;
        }
        if (depth >= MAX_DEPTH) { o.write(0); return; }
        if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            o.write(6);
            w32(o, l.size());
            for (int i = 0; i < l.size(); i++) {
                put(o, (long) (i + 1), depth + 1);
                put(o, l.get(i), depth + 1);
            }
            return;
        }
        if (v instanceof Object[]) {
            Object[] l = (Object[]) v;
            o.write(6);
            w32(o, l.length);
            for (int i = 0; i < l.length; i++) {
                put(o, (long) (i + 1), depth + 1);
                put(o, l[i], depth + 1);
            }
            return;
        }
        if (v instanceof Map) {
            Map<Object, Object> m = (Map<Object, Object>) v;
            o.write(6);
            w32(o, m.size());
            for (Map.Entry<Object, Object> e : m.entrySet()) {
                put(o, e.getKey(), depth + 1);
                put(o, e.getValue(), depth + 1);
            }
            return;
        }
        // unknown object: pass its string form
        byte[] b = String.valueOf(v).getBytes(UTF8);
        o.write(5);
        w32(o, b.length);
        o.write(b, 0, b.length);
    }

    private static void w32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >>> 24) & 0xFF);
    }

    private static void w64(ByteArrayOutputStream o, long v) {
        for (int i = 0; i < 8; i++) o.write((int) ((v >>> (8 * i)) & 0xFF));
    }

    // ---------------------------------------------------------------- decode

    private static final class Cur {
        final byte[] b;
        int p;
        Cur(byte[] b) { this.b = b; }
        int r32() {
            if (p + 4 > b.length) throw new IllegalArgumentException("wire: truncated");
            int v = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
            p += 4;
            return v;
        }
        long r64() {
            if (p + 8 > b.length) throw new IllegalArgumentException("wire: truncated");
            long v = 0;
            for (int i = 7; i >= 0; i--) v = (v << 8) | (b[p + i] & 0xFF);
            p += 8;
            return v;
        }
    }

    public static Object[] decode(byte[] data) {
        if (data == null || data.length == 0) return new Object[0];
        Cur c = new Cur(data);
        int n = c.r32();
        if (n < 0 || n > 1 << 20) throw new IllegalArgumentException("wire: bad count");
        Object[] out = new Object[n];
        for (int i = 0; i < n; i++) out[i] = get(c, 0);
        return out;
    }

    private static Object get(Cur c, int depth) {
        if (c.p >= c.b.length) throw new IllegalArgumentException("wire: truncated");
        int tag = c.b[c.p++];
        switch (tag) {
            case 0: return null;
            case 1: return Boolean.FALSE;
            case 2: return Boolean.TRUE;
            case 3: return c.r64();
            case 4: return Double.longBitsToDouble(c.r64());
            case 5: {
                int len = c.r32();
                if (len < 0 || c.p + len > c.b.length) throw new IllegalArgumentException("wire: bad string");
                String s = new String(c.b, c.p, len, UTF8);
                c.p += len;
                return s;
            }
            case 6: {
                int n = c.r32();
                if (n < 0) throw new IllegalArgumentException("wire: bad table");
                LuaTable t = new LuaTable();
                for (int i = 0; i < n; i++) {
                    Object k = get(c, depth + 1);
                    Object v = get(c, depth + 1);
                    if (k != null && v != null) t.put(normKey(k), v);
                }
                return t;
            }
            default:
                throw new IllegalArgumentException("wire: bad tag " + tag);
        }
    }

    /** Float keys with an integer value are the same key in Lua (2.0 == 2). */
    static Object normKey(Object k) {
        if (k instanceof Double) {
            double d = (Double) k;
            if (d == Math.rint(d) && Math.abs(d) < 9.0e18) return (long) d;
        }
        return k;
    }

    // ---------------------------------------------------------------- helpers

    public static List<Object> list(Object... items) {
        ArrayList<Object> l = new ArrayList<>();
        for (Object o : items) l.add(o);
        return l;
    }
}
