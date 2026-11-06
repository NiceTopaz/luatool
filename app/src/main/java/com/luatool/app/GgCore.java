package com.luatool.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Environment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.luatool.app.Wire.LuaTable;

/**
 * The GameGuardian-style "gg" API, independent of the Lua engine. Both the native Lua 5.3 engine and the
 * LuaJ (Lua 5.2 bytecode) fallback call into this class with plain Java values (see {@link Wire}).
 */
final class GgCore {
    private GgCore() {}

    static final Object[] NONE = new Object[0];

    interface Fn { Object[] call(Args a) throws Exception; }

    /** Public gg.* functions, in registration order (the index is the id used by the engines). */
    static final LinkedHashMap<String, Fn> FNS = new LinkedHashMap<>();
    /** Internal helpers handed to the prelude only (not visible as gg.* to scripts). */
    static final LinkedHashMap<String, Fn> INTERNAL = new LinkedHashMap<>();

    static volatile File currentScript;
    /** Items of the GG "saved list" (addListItems / getListItems). */
    private static final ArrayList<LuaTable> saved = new ArrayList<>();

    // ------------------------------------------------------------------ args

    static final class Args {
        final Object[] a;
        Args(Object[] a) { this.a = a == null ? NONE : a; }

        int n() { return a.length; }
        Object get(int i) { return i >= 1 && i <= a.length ? a[i - 1] : null; }
        boolean isNil(int i) { return get(i) == null; }
        boolean has(int i) { return get(i) != null; }

        String typeName(int i) {
            Object o = get(i);
            if (o == null) return "no value";
            if (o instanceof Boolean) return "boolean";
            if (o instanceof Number) return "number";
            if (o instanceof String) return "string";
            return "table";
        }

        Number num(int i) {
            Object o = get(i);
            Number n = toNumber(o);
            if (n == null) throw new GgError("bad argument #" + i + " (number expected, got " + typeName(i) + ")");
            return n;
        }

        long lng(int i) { Number n = num(i); return n instanceof Double ? (long) n.doubleValue() : n.longValue(); }
        int integer(int i) { return (int) lng(i); }
        long optLong(int i, long def) { return isNil(i) ? def : lng(i); }
        int optInt(int i, int def) { return isNil(i) ? def : integer(i); }
        boolean bool(int i) { Object o = get(i); return o != null && !Boolean.FALSE.equals(o); }

        String str(int i) {
            Object o = get(i);
            if (o == null) throw new GgError("bad argument #" + i + " (string expected, got " + typeName(i) + ")");
            return luaToString(o);
        }

        String optStr(int i, String def) { return isNil(i) ? def : str(i); }

        LuaTable tbl(int i) {
            Object o = get(i);
            if (o instanceof LuaTable) return (LuaTable) o;
            throw new GgError("bad argument #" + i + " (table expected, got " + typeName(i) + ")");
        }

        boolean isTable(int i) { return get(i) instanceof LuaTable; }
    }

    static Number toNumber(Object o) {
        if (o instanceof Number) return (Number) o;
        if (o instanceof String) {
            String s = ((String) o).trim();
            if (s.isEmpty()) return null;
            try {
                if (s.startsWith("0x") || s.startsWith("0X")) return Long.parseLong(s.substring(2), 16);
                if (s.startsWith("-0x") || s.startsWith("-0X")) return -Long.parseLong(s.substring(3), 16);
                try { return Long.parseLong(s); } catch (NumberFormatException e) { return Double.parseDouble(s); }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static String luaToString(Object o) {
        if (o == null) return "nil";
        if (o instanceof Double) {
            double d = (Double) o;
            if (Double.isNaN(d)) return "nan";
            if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d) + ".0";
            String s = new java.math.BigDecimal(d).round(new java.math.MathContext(14)).stripTrailingZeros().toString();
            return s;
        }
        if (o instanceof LuaTable) return "table";
        return String.valueOf(o);
    }

    static Object[] one(Object v) { return new Object[]{v}; }

    // ------------------------------------------------------------------ helpers

    private static MemEngine eng() {
        MemEngine e = Session.engine;
        if (e == null) throw new GgError("no process selected (pick a process in the LuaTool panel first)");
        return e;
    }

    private static boolean writeVal(MemEngine e, long addr, int type, Object v) {
        Number n = toNumber(v);
        if (n == null) throw new GgError("value is not a number");
        double d = n.doubleValue();
        long l = n instanceof Double ? (long) d : n.longValue();
        return e.writeNumber(addr, type, d, l);
    }

    private static LuaTable row(long addr, Object value, int flags) {
        LuaTable r = new LuaTable();
        r.put("address", addr);
        if (value != null) r.put("value", value);
        r.put("flags", (long) flags);
        return r;
    }

    private static LuaTable resultRow(MemEngine e, long addr, int type) {
        Object v = e.readValue(addr, type);
        if (v == null) return null;
        return row(addr, v, type);
    }

    private static void doSearch(Args a, boolean refine) {
        MemEngine e = eng();
        String text = a.str(1);
        int type = a.optInt(2, MemEngine.T_DWORD);
        int sign = a.optInt(4, MemEngine.SIGN_EQUAL);
        int n;
        if (refine) {
            n = e.refine(text, type, sign);
        } else {
            long from = a.optLong(5, 0);
            long to = a.optLong(6, -1);
            int limit = a.optInt(7, 0);
            long hi = to < 0 ? Long.MAX_VALUE : to;
            if (text.indexOf(';') >= 0) n = e.searchGroup(text, type, from, hi, limit);
            else n = e.search(text, type, sign, from, hi, limit);
        }
        AppLog.add((refine ? "refine '" : "search '") + text + "': " + n + " results");
    }

    private static String[] strList(LuaTable t) {
        int n = t.length();
        String[] out = new String[n];
        for (int i = 1; i <= n; i++) out[i - 1] = luaToString(t.at(i));
        return out;
    }

    private static void reg(String name, Fn f) { FNS.put(name, f); }

    private static void unsupported(final String name) {
        FNS.put(name, new Fn() { public Object[] call(Args a) {
            throw new GgError("gg." + name + " is not supported by LuaTool");
        }});
    }

    // ------------------------------------------------------------------ dispatch

    /** Names in registration order. */
    static String[] publicNames() { return FNS.keySet().toArray(new String[0]); }
    static String[] internalNames() { return INTERNAL.keySet().toArray(new String[0]); }

    private static Fn[] table;
    private static String[] tableNames;

    private static synchronized void buildTable() {
        if (table != null) return;
        ArrayList<Fn> fs = new ArrayList<>();
        ArrayList<String> ns = new ArrayList<>();
        for (Map.Entry<String, Fn> e : FNS.entrySet()) { ns.add(e.getKey()); fs.add(e.getValue()); }
        for (Map.Entry<String, Fn> e : INTERNAL.entrySet()) { ns.add("(internal) " + e.getKey()); fs.add(e.getValue()); }
        table = fs.toArray(new Fn[0]);
        tableNames = ns.toArray(new String[0]);
    }

    /**
     * Calls function {@code id} (public functions first, then internal ones).
     * @throws GgError with the message that should reach the Lua script.
     */
    static Object[] call(int id, Object[] args) {
        buildTable();
        if (id < 0 || id >= table.length) throw new GgError("bad function id " + id);
        String name = tableNames[id];
        try {
            Session.check();
            Object[] r = table[id].call(new Args(args));
            return r == null ? NONE : r;
        } catch (GgError e) {
            throw e;
        } catch (MemEngine.Cancelled e) {
            throw new GgError("script stopped");
        } catch (InterruptedException e) {
            throw new GgError("script stopped");
        } catch (Throwable e) {
            if (Session.stopRequested) throw new GgError("script stopped");
            String m = e.getMessage();
            throw new GgError(name + ": " + (m != null ? m : e.toString()));
        }
    }

    /** Constants + metadata table for the "gg" global. */
    static LuaTable constants(File script) {
        LuaTable gg = new LuaTable();
        currentScript = script;
        saved.clear();

        gg.put("TYPE_BYTE", (long) MemEngine.T_BYTE);
        gg.put("TYPE_WORD", (long) MemEngine.T_WORD);
        gg.put("TYPE_DWORD", (long) MemEngine.T_DWORD);
        gg.put("TYPE_XOR", (long) MemEngine.T_XOR);
        gg.put("TYPE_FLOAT", (long) MemEngine.T_FLOAT);
        gg.put("TYPE_QWORD", (long) MemEngine.T_QWORD);
        gg.put("TYPE_DOUBLE", (long) MemEngine.T_DOUBLE);
        gg.put("TYPE_AUTO", 127L);
        gg.put("REGION_C_HEAP", (long) MemEngine.R_C_HEAP);
        gg.put("REGION_JAVA_HEAP", (long) MemEngine.R_JAVA_HEAP);
        gg.put("REGION_C_ALLOC", (long) MemEngine.R_C_ALLOC);
        gg.put("REGION_C_DATA", (long) MemEngine.R_C_DATA);
        gg.put("REGION_C_BSS", (long) MemEngine.R_C_BSS);
        gg.put("REGION_ANONYMOUS", (long) MemEngine.R_ANON);
        gg.put("REGION_STACK", (long) MemEngine.R_STACK);
        gg.put("REGION_ASHMEM", (long) MemEngine.R_ASHMEM);
        gg.put("REGION_JAVA", (long) MemEngine.R_JAVA);
        gg.put("REGION_CODE_APP", (long) MemEngine.R_CODE_APP);
        gg.put("REGION_OTHER", (long) MemEngine.R_OTHER);
        gg.put("REGION_BAD", 0x20000L);
        gg.put("REGION_VIDEO", 0x100000L);
        gg.put("REGION_CODE_SYS", 0x8000L);
        gg.put("REGION_PPSSPP", 0x40000L);
        gg.put("REGION_ALL", (long) MemEngine.R_ALL);
        gg.put("SIGN_EQUAL", (long) MemEngine.SIGN_EQUAL);
        gg.put("SIGN_NOT_EQUAL", (long) MemEngine.SIGN_NOT_EQUAL);
        gg.put("SIGN_GREATER_OR_EQUAL", (long) MemEngine.SIGN_GREATER_OR_EQUAL);
        gg.put("SIGN_LESS_OR_EQUAL", (long) MemEngine.SIGN_LESS_OR_EQUAL);
        gg.put("SIGN_FUZZY_EQUAL", 4L);
        gg.put("SIGN_FUZZY_NOT_EQUAL", 8L);
        gg.put("SIGN_FUZZY_GREATER", 16L);
        gg.put("SIGN_FUZZY_LESS", 32L);
        gg.put("LOAD_APPEND", 1L);
        gg.put("LOAD_VALUES", 2L);
        gg.put("LOAD_VALUES_FREEZE", 3L);
        gg.put("SAVE_AS_TEXT", 1L);
        gg.put("PROT_READ", 1L);
        gg.put("PROT_WRITE", 2L);
        gg.put("PROT_EXEC", 4L);
        gg.put("ASM_ARM", 1L);
        gg.put("ASM_THUMB", 2L);
        gg.put("ASM_ARM64", 3L);

        // Version values are chosen so scripts that check "GG is new enough" run; LUATOOL tells scripts what this is.
        gg.put("VERSION", "101.1");
        gg.put("VERSION_INT", 10101L);
        gg.put("BUILD", 16142L);
        gg.put("LUATOOL", "1.1");

        Context app = Session.app;
        if (app != null) {
            gg.put("PACKAGE", app.getPackageName());
            gg.put("FILES_DIR", app.getFilesDir().getAbsolutePath());
            gg.put("TMP_PATH", app.getCacheDir().getAbsolutePath());
            File ext = app.getExternalFilesDir(null);
            if (ext != null) gg.put("EXT_FILES_DIR", ext.getAbsolutePath());
            File extc = app.getExternalCacheDir();
            if (extc != null) gg.put("EXT_CACHE_DIR", extc.getAbsolutePath());
        }
        gg.put("EXT_STORAGE", Environment.getExternalStorageDirectory().getAbsolutePath());
        return gg;
    }

    // ------------------------------------------------------------------ the API

    static {
        // ---------- UI ----------
        reg("alert", new Fn() { public Object[] call(Args a) {
            String b1 = a.isNil(2) ? null : a.str(2);
            String b2 = a.isNil(3) ? null : a.str(3);
            String b3 = a.isNil(4) ? null : a.str(4);
            return one((long) UiBridge.alert(a.str(1), b1, b2, b3));
        }});

        reg("toast", new Fn() { public Object[] call(Args a) {
            UiBridge.toast(a.str(1));
            return NONE;
        }});

        reg("choice", new Fn() { public Object[] call(Args a) {
            String[] items = strList(a.tbl(1));
            int sel = a.optInt(2, 0) - 1;
            String msg = a.isNil(3) ? null : a.str(3);
            int r = UiBridge.choice(items, sel, msg);
            return one(r < 0 ? null : (Object) (long) (r + 1));
        }});

        reg("multiChoice", new Fn() { public Object[] call(Args a) {
            String[] items = strList(a.tbl(1));
            boolean[] checked = new boolean[items.length];
            if (a.isTable(2)) {
                LuaTable sel = a.tbl(2);
                for (int i = 0; i < items.length; i++) {
                    Object o = sel.at(i + 1);
                    checked[i] = o != null && !Boolean.FALSE.equals(o);
                }
            }
            String msg = a.isNil(3) ? null : a.str(3);
            boolean[] r = UiBridge.multiChoice(items, checked, msg);
            if (r == null) return one(null);
            LuaTable out = new LuaTable();
            for (int i = 0; i < r.length; i++) if (r[i]) out.put((long) (i + 1), Boolean.TRUE);
            return one(out);
        }});

        reg("prompt", new Fn() { public Object[] call(Args a) {
            LuaTable prompts = a.tbl(1);
            LuaTable defs = a.isTable(2) ? a.tbl(2) : new LuaTable();
            LuaTable types = a.isTable(3) ? a.tbl(3) : new LuaTable();
            // Keep GG's key order: numeric keys ascending first, then the rest in insertion order.
            List<Object> keys = new ArrayList<>();
            for (int i = 1; prompts.containsKey((long) i); i++) keys.add((long) i);
            for (Object k : prompts.keySet()) if (!keys.contains(k)) keys.add(k);
            int n = keys.size();
            String[] labels = new String[n], dv = new String[n], ty = new String[n];
            for (int i = 0; i < n; i++) {
                Object k = keys.get(i);
                labels[i] = luaToString(prompts.get(k));
                Object d = defs.get(k);
                dv[i] = d == null ? "" : luaToString(d);
                Object t = types.get(k);
                ty[i] = t == null ? "text" : luaToString(t);
            }
            Object[] r = UiBridge.prompt(labels, dv, ty);
            if (r == null) return one(null);
            LuaTable out = new LuaTable();
            for (int i = 0; i < n; i++) {
                Object v;
                if (r[i] instanceof Boolean) {
                    v = r[i];
                } else if ("number".equals(ty[i])) {
                    Number num = toNumber(r[i]);
                    v = num;
                } else {
                    v = r[i];
                }
                if (v != null) out.put(keys.get(i), v);
            }
            return one(out);
        }});

        reg("sleep", new Fn() { public Object[] call(Args a) throws Exception {
            long end = System.currentTimeMillis() + a.optLong(1, 0);
            while (true) {
                Session.check();
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                Thread.sleep(Math.min(left, 50));
            }
            return NONE;
        }});

        reg("isVisible", new Fn() { public Object[] call(Args a) {
            return one(OverlayService.panelVisible());
        }});
        reg("setVisible", new Fn() { public Object[] call(Args a) {
            OverlayService.setPanelVisible(a.bool(1));
            return NONE;
        }});
        reg("showUiButton", new Fn() { public Object[] call(Args a) {
            OverlayService.setBubbleVisible(true);
            return NONE;
        }});
        reg("hideUiButton", new Fn() { public Object[] call(Args a) {
            OverlayService.setBubbleVisible(false);
            return NONE;
        }});
        reg("isClickedUiButton", new Fn() { public Object[] call(Args a) {
            Session.lastClickPoll = System.currentTimeMillis();
            boolean c = Session.clicked;
            Session.clicked = false;
            return one(c);
        }});
        reg("copyText", new Fn() { public Object[] call(final Args a) {
            final Context c = Session.app;
            final String text = a.str(1);
            if (c != null) UiBridge.main.post(new Runnable() { public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("lua", text));
                } catch (Throwable ignored) {}
            }});
            return one(true);
        }});
        reg("getFile", new Fn() { public Object[] call(Args a) {
            File f = currentScript;
            return one(f == null ? null : f.getAbsolutePath());
        }});
        reg("skipRestoreState", new Fn() { public Object[] call(Args a) { return NONE; } });
        reg("require", new Fn() { public Object[] call(Args a) { return NONE; } });
        reg("getLocale", new Fn() { public Object[] call(Args a) { return one(Locale.getDefault().toString()); } });
        reg("numberFromLocale", new Fn() { public Object[] call(Args a) throws Exception {
            Number n = NumberFormat.getInstance().parse(a.str(1));
            return one(n instanceof Long ? n : (Object) n.doubleValue());
        }});
        reg("numberToLocale", new Fn() { public Object[] call(Args a) {
            return one(NumberFormat.getInstance().format(a.num(1)));
        }});
        reg("bytes", new Fn() { public Object[] call(Args a) {
            Charset cs;
            try { cs = Charset.forName(a.optStr(2, "UTF-8")); }
            catch (Exception e) { throw new GgError("unknown charset"); }
            byte[] b = a.str(1).getBytes(cs);
            LuaTable out = new LuaTable();
            for (int i = 0; i < b.length; i++) out.put((long) (i + 1), (long) (b[i] & 0xFF));
            return one(out);
        }});
        reg("isPackageInstalled", new Fn() { public Object[] call(Args a) {
            Context c = Session.app;
            if (c == null) return one(false);
            try {
                c.getPackageManager().getPackageInfo(a.str(1), 0);
                return one(true);
            } catch (Throwable t) {
                return one(false);
            }
        }});

        reg("makeRequest", new Fn() { public Object[] call(Args a) {
            HttpURLConnection c = null;
            try {
                String url = a.str(1);
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                if (a.isTable(2)) {
                    for (Map.Entry<Object, Object> e : a.tbl(2).entrySet())
                        c.setRequestProperty(luaToString(e.getKey()), luaToString(e.getValue()));
                }
                if (a.has(3)) {
                    byte[] body = a.str(3).getBytes(Wire.UTF8);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    OutputStream os = c.getOutputStream();
                    os.write(body);
                    os.close();
                }
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                if (in != null) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                }
                LuaTable r = new LuaTable();
                r.put("code", (long) code);
                r.put("status", c.getResponseMessage() == null ? "" : c.getResponseMessage());
                r.put("url", c.getURL().toString());
                LuaTable h = new LuaTable();
                for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                    if (e.getKey() != null && !e.getValue().isEmpty()) h.put(e.getKey(), e.getValue().get(e.getValue().size() - 1));
                }
                r.put("headers", h);
                r.put("content", new String(bo.toByteArray(), Wire.UTF8));
                return one(r);
            } catch (Exception e) {
                return one(String.valueOf(e.getMessage() != null ? e.getMessage() : e.toString()));
            } finally {
                if (c != null) c.disconnect();
            }
        }});

        // ---------- target ----------
        reg("getTargetInfo", new Fn() { public Object[] call(Args a) {
            ProcessInfo p = Session.target;
            if (p == null) return one(null);
            LuaTable t = new LuaTable();
            t.put("pid", (long) p.pid);
            t.put("processName", p.name);
            t.put("packageName", p.name);
            t.put("label", p.name);
            Context c = Session.app;
            if (c != null) {
                try {
                    String pkg = p.name.indexOf(':') > 0 ? p.name.substring(0, p.name.indexOf(':')) : p.name;
                    PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, 0);
                    t.put("packageName", pkg);
                    if (pi.versionName != null) t.put("versionName", pi.versionName);
                    t.put("versionCode", (long) pi.versionCode);
                    t.put("firstInstallTime", pi.firstInstallTime);
                    t.put("lastUpdateTime", pi.lastUpdateTime);
                    if (pi.applicationInfo != null) {
                        t.put("sourceDir", String.valueOf(pi.applicationInfo.sourceDir));
                        t.put("dataDir", String.valueOf(pi.applicationInfo.dataDir));
                        t.put("nativeLibraryDir", String.valueOf(pi.applicationInfo.nativeLibraryDir));
                        t.put("targetSdkVersion", (long) pi.applicationInfo.targetSdkVersion);
                    }
                } catch (Throwable ignored) {}
            }
            return one(t);
        }});
        reg("getTargetPackage", new Fn() { public Object[] call(Args a) {
            ProcessInfo p = Session.target;
            return one(p == null ? null : p.name);
        }});
        reg("processPause", new Fn() { public Object[] call(Args a) {
            ProcessInfo p = Session.target;
            if (p != null && p.pid != android.os.Process.myPid()) android.os.Process.sendSignal(p.pid, 19);
            return one(true);
        }});
        reg("processResume", new Fn() { public Object[] call(Args a) {
            ProcessInfo p = Session.target;
            if (p != null && p.pid != android.os.Process.myPid()) android.os.Process.sendSignal(p.pid, 18);
            return one(true);
        }});
        reg("processKill", new Fn() { public Object[] call(Args a) {
            ProcessInfo p = Session.target;
            if (p != null && p.pid != android.os.Process.myPid()) android.os.Process.sendSignal(p.pid, 9);
            return one(true);
        }});
        reg("getSpeed", new Fn() { public Object[] call(Args a) { return one(1.0); } });
        reg("setSpeed", new Fn() { public Object[] call(Args a) { return one(false); } });

        // ---------- ranges ----------
        reg("setRanges", new Fn() { public Object[] call(Args a) {
            eng().ranges = a.optInt(1, MemEngine.DEFAULT_RANGES);
            return NONE;
        }});
        reg("getRanges", new Fn() { public Object[] call(Args a) {
            return one((long) eng().ranges);
        }});
        reg("getRangesList", new Fn() { public Object[] call(Args a) {
            String filter = a.optStr(1, "");
            List<MemEngine.Region> rs = eng().regions(MemEngine.R_ALL, 0, Long.MAX_VALUE, false);
            LuaTable out = new LuaTable();
            long k = 1;
            for (MemEngine.Region r : rs) {
                if (!filter.isEmpty() && !r.path.contains(filter)) continue;
                LuaTable t = new LuaTable();
                t.put("start", r.start);
                t.put("end", r.end);
                t.put("type", r.perms);
                t.put("name", r.path);
                t.put("internalName", r.path);
                t.put("state", "O");
                out.put(k++, t);
            }
            return one(out);
        }});

        // ---------- search / results ----------
        reg("searchNumber", new Fn() { public Object[] call(Args a) { doSearch(a, false); return NONE; } });
        reg("refineNumber", new Fn() { public Object[] call(Args a) { doSearch(a, true); return NONE; } });
        reg("clearResults", new Fn() { public Object[] call(Args a) { eng().clearResults(); return NONE; } });
        reg("getResultsCount", new Fn() { public Object[] call(Args a) { return one((long) eng().count()); } });

        reg("getResults", new Fn() { public Object[] call(Args a) {
            MemEngine e = eng();
            int cnt = a.optInt(1, 100);
            int skip = a.optInt(2, 0);
            boolean hasMin = a.has(3), hasMax = a.has(4), hasType = a.has(7);
            long amin = hasMin ? a.lng(3) : 0, amax = hasMax ? a.lng(4) : Long.MAX_VALUE;
            int type = hasType ? a.integer(7) : 0;
            LuaTable out = new LuaTable();
            long k = 1;
            for (int i = skip; i < e.count() && k <= cnt; i++) {
                long addr = e.addrAt(i);
                int t = e.typeAt(i);
                if (addr < amin || addr > amax) continue;
                if (hasType && (t & type) == 0) continue;
                LuaTable row = resultRow(e, addr, t);
                if (row != null) out.put(k++, row);
            }
            return one(out);
        }});

        reg("editAll", new Fn() { public Object[] call(Args a) {
            MemEngine e = eng();
            int type = a.optInt(2, MemEngine.T_DWORD);
            Object v = a.get(1);
            int ok = 0;
            for (int i = 0; i < e.count(); i++) {
                if ((i & 0x3FF) == 0) Session.check();
                if (writeVal(e, e.addrAt(i), type, v)) ok++;
            }
            AppLog.add("editAll: wrote " + ok + " of " + e.count());
            return one(ok > 0 || e.count() == 0);
        }});

        reg("getValues", new Fn() { public Object[] call(Args a) {
            MemEngine e = eng();
            LuaTable in = a.tbl(1);
            LuaTable out = new LuaTable();
            int n = in.length();
            for (int i = 1; i <= n; i++) {
                Object it = in.at(i);
                if (!(it instanceof LuaTable)) continue;
                LuaTable t = (LuaTable) it;
                long addr = toNumber(t.get("address")).longValue();
                Number fl = toNumber(t.get("flags"));
                int type = fl == null ? MemEngine.T_DWORD : fl.intValue();
                LuaTable row = resultRow(e, addr, type);
                if (row == null) row = row(addr, null, type);
                out.put((long) i, row);
            }
            return one(out);
        }});

        reg("setValues", new Fn() { public Object[] call(Args a) {
            MemEngine e = eng();
            LuaTable in = a.tbl(1);
            int n = in.length();
            boolean all = true;
            for (int i = 1; i <= n; i++) {
                Object it = in.at(i);
                if (!(it instanceof LuaTable)) continue;
                LuaTable t = (LuaTable) it;
                long addr = toNumber(t.get("address")).longValue();
                Number fl = toNumber(t.get("flags"));
                int type = fl == null ? MemEngine.T_DWORD : fl.intValue();
                if (!writeVal(e, addr, type, t.get("value"))) all = false;
            }
            return one(all);
        }});

        // ---------- saved list (with freeze) ----------
        reg("addListItems", new Fn() { public Object[] call(Args a) {
            MemEngine e = eng();
            LuaTable in = a.tbl(1);
            int n = in.length();
            for (int i = 1; i <= n; i++) {
                Object it = in.at(i);
                if (!(it instanceof LuaTable)) continue;
                LuaTable t = (LuaTable) it;
                Number addr = toNumber(t.get("address"));
                if (addr == null) continue;
                Number fl = toNumber(t.get("flags"));
                int type = fl == null ? MemEngine.T_DWORD : fl.intValue();
                LuaTable copy = new LuaTable();
                copy.putAll(t);
                copy.put("address", addr.longValue());
                copy.put("flags", (long) type);
                synchronized (saved) {
                    for (int j = 0; j < saved.size(); j++)
                        if (((Number) saved.get(j).get("address")).longValue() == addr.longValue()) { saved.remove(j); break; }
                    saved.add(copy);
                }
                Object fz = t.get("freeze");
                Number v = toNumber(t.get("value"));
                if (fz != null && !Boolean.FALSE.equals(fz) && v != null) {
                    e.freeze(addr.longValue(), type, v.doubleValue(), v instanceof Double ? (long) v.doubleValue() : v.longValue());
                }
            }
            return one(true);
        }});
        reg("getListItems", new Fn() { public Object[] call(Args a) {
            MemEngine e = Session.engine;
            LuaTable out = new LuaTable();
            long k = 1;
            synchronized (saved) {
                for (LuaTable t : saved) {
                    LuaTable r = new LuaTable();
                    r.putAll(t);
                    if (e != null) {
                        long addr = ((Number) t.get("address")).longValue();
                        Object v = e.readValue(addr, ((Number) t.get("flags")).intValue());
                        if (v != null) r.put("value", v);
                    }
                    out.put(k++, r);
                }
            }
            return one(out);
        }});
        reg("removeListItems", new Fn() { public Object[] call(Args a) {
            MemEngine e = eng();
            LuaTable in = a.tbl(1);
            int n = in.length();
            for (int i = 1; i <= n; i++) {
                Object it = in.at(i);
                if (!(it instanceof LuaTable)) continue;
                Number addr = toNumber(((LuaTable) it).get("address"));
                if (addr == null) continue;
                e.unfreeze(addr.longValue());
                synchronized (saved) {
                    for (int j = 0; j < saved.size(); j++)
                        if (((Number) saved.get(j).get("address")).longValue() == addr.longValue()) { saved.remove(j); break; }
                }
            }
            return one(true);
        }});
        reg("clearList", new Fn() { public Object[] call(Args a) {
            eng().clearFreeze();
            synchronized (saved) { saved.clear(); }
            return NONE;
        }});

        // ---------- present in GG but not implemented here: fail with a clear message ----------
        String[] nope = {"searchFuzzy", "startFuzzy", "continueFuzzy", "searchAddress", "refineAddress", "gotoAddress",
                "loadResults", "saveList", "loadList", "getSelectedElements", "getSelectedListItems",
                "getSelectedResults", "dumpMemory", "copyMemory", "allocatePage", "getValuesRange", "disasm",
                "unrandomizer", "timeJump", "isProcessPaused", "setHardwareBreakpoint", "removeBreakpoint"};
        for (String n : nope) if (!FNS.containsKey(n)) unsupported(n);

        // ---------- internal helpers for the prelude ----------
        INTERNAL.put("log", new Fn() { public Object[] call(Args a) {
            AppLog.add(a.n() == 0 ? "" : luaToString(a.get(1)));
            return NONE;
        }});
        INTERNAL.put("exit", new Fn() { public Object[] call(Args a) {
            Session.requestStopFromScript();
            return NONE;
        }});
    }
}
