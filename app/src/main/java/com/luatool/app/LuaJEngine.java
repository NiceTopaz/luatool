package com.luatool.app;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Map;

/**
 * Fallback engine (LuaJ, Lua 5.2 semantics). It is only used for precompiled Lua 5.2 bytecode, which the real
 * Lua 5.3 engine cannot read, and when the native library is missing.
 * The script bytes are passed as bytes (never as text), so bytecode and non-UTF-8 strings survive.
 */
final class LuaJEngine {
    private LuaJEngine() {}

    /** @return null on success, otherwise an error message. */
    static String run(File script, byte[] data) {
        try {
            Globals g = JsePlatform.standardGlobals();
            g.STDOUT = new PrintStream(new LogOut(), true);
            g.set("luajava", LuaValue.NIL);   // no raw Java access from scripts
            g.get("os").set("exit", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    Session.requestStopFromScript();
                    throw new LuaError("script stopped");
                }
            });
            g.set("unpack", g.get("table").get("unpack"));
            if (g.get("loadstring").isnil()) g.set("loadstring", g.get("load"));
            g.set("gg", buildGg(script));
            LuaValue chunk = g.load(new ByteArrayInputStream(data), "@" + script.getName(), "bt", g);
            chunk.call();
            return null;
        } catch (LuaError e) {
            if (Session.stopRequested) return "script stopped";
            return e.getMessage();
        } catch (GgError e) {
            return e.getMessage();
        }
    }

    private static LuaTable buildGg(File script) {
        LuaTable gg = new LuaTable();
        for (Map.Entry<Object, Object> e : GgCore.constants(script).entrySet()) {
            gg.set(toLuaKey(e.getKey()), toLua(e.getValue(), 0));
        }
        String[] names = GgCore.publicNames();
        for (int i = 0; i < names.length; i++) {
            final int id = i;
            gg.set(names[i], new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    Object[] args = new Object[a.narg()];
                    for (int k = 0; k < args.length; k++) args[k] = toJava(a.arg(k + 1), 0);
                    Object[] r;
                    try {
                        r = GgCore.call(id, args);
                    } catch (GgError e) {
                        throw new LuaError(e.getMessage());
                    }
                    if (r.length == 0) return LuaValue.NONE;
                    LuaValue[] out = new LuaValue[r.length];
                    for (int k = 0; k < r.length; k++) out[k] = toLua(r[k], 0);
                    return LuaValue.varargsOf(out);
                }
            });
        }
        return gg;
    }

    private static LuaValue toLuaKey(Object k) {
        return toLua(k, 0);
    }

    static Object toJava(LuaValue v, int depth) {
        if (v.isnil()) return null;
        if (v.isboolean()) return v.toboolean();
        if (v.isnumber() && !v.isstring()) {
            double d = v.todouble();
            if (d == Math.rint(d) && Math.abs(d) < 9.0e15) return (long) d;
            return d;
        }
        if (v.isstring()) return v.tojstring();
        if (v.istable() && depth < Wire.MAX_DEPTH) {
            Wire.LuaTable t = new Wire.LuaTable();
            LuaValue k = LuaValue.NIL;
            while (true) {
                Varargs n = v.next(k);
                k = n.arg1();
                if (k.isnil()) break;
                Object jk = toJava(k, depth + 1);
                Object jv = toJava(n.arg(2), depth + 1);
                if (jk != null && jv != null) t.put(Wire.normKey(jk), jv);
            }
            return t;
        }
        return null;
    }

    static LuaValue toLua(Object o, int depth) {
        if (o == null) return LuaValue.NIL;
        if (o instanceof Boolean) return LuaValue.valueOf((Boolean) o);
        if (o instanceof Long) return LuaValue.valueOf((double) (Long) o);
        if (o instanceof Number) return LuaValue.valueOf(((Number) o).doubleValue());
        if (o instanceof String) return LuaValue.valueOf((String) o);
        if (o instanceof Map && depth < Wire.MAX_DEPTH) {
            LuaTable t = new LuaTable();
            for (Object e : ((Map<?, ?>) o).entrySet()) {
                Map.Entry<?, ?> en = (Map.Entry<?, ?>) e;
                t.set(toLua(en.getKey(), depth + 1), toLua(en.getValue(), depth + 1));
            }
            return t;
        }
        return LuaValue.NIL;
    }

    /** Routes print() to the panel log. */
    private static final class LogOut extends OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        @Override public void write(int b) {
            if (b == '\n') flushLine();
            else if (b != '\r') buf.write(b);
        }

        private void flushLine() {
            try { AppLog.add(buf.toString("UTF-8")); } catch (Exception ignored) {}
            buf.reset();
        }
    }
}
