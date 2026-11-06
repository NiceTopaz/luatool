package com.luatool.app;

/** Native Lua 5.3 engine (PUC-Rio Lua through JNI). One script at a time. */
public final class LuaVM {
    private LuaVM() {}

    private static boolean loaded;
    private static String loadError;

    /** @return true when the native library is available. */
    public static synchronized boolean available() {
        if (!loaded && loadError == null) {
            try {
                System.loadLibrary("lua53jni");
                loaded = true;
            } catch (Throwable t) {
                loadError = String.valueOf(t);
            }
        }
        return loaded;
    }

    public static String loadError() { return loadError; }

    private static native String nativeVersion();
    private static native String nativeLastError();
    private static native long nativeCreate(byte[] init, byte[] prelude);
    private static native byte[] nativeRun(long h, byte[] chunk, String name);
    private static native void nativeStop(long h);
    private static native void nativeClose(long h);

    private static volatile long current;

    public static String version() { return available() ? nativeVersion() : "unavailable"; }

    /** Called from C for every gg.* call: [status byte][payload]. Never throws. */
    public static byte[] invoke(int id, byte[] args) {
        try {
            Object[] a = Wire.decode(args);
            Object[] r = GgCore.call(id, a);
            byte[] enc = Wire.encode(r);
            byte[] out = new byte[enc.length + 1];
            out[0] = 0;
            System.arraycopy(enc, 0, out, 1, enc.length);
            return out;
        } catch (Throwable t) {
            String m = t instanceof GgError ? t.getMessage() : String.valueOf(t);
            if (m == null) m = "error";
            byte[] mb = m.getBytes(Wire.UTF8);
            byte[] out = new byte[mb.length + 1];
            out[0] = 1;
            System.arraycopy(mb, 0, out, 1, mb.length);
            return out;
        }
    }

    /**
     * Runs a chunk (Lua 5.3 source or Lua 5.3 bytecode) to completion on the calling thread.
     * @return null on success, otherwise the error message.
     */
    public static String run(java.io.File script, byte[] chunk) {
        if (!available()) return "native Lua engine not available: " + loadError;
        byte[] init = Wire.encode(GgCore.constants(script),
                Wire.list((Object[]) GgCore.publicNames()), Wire.list((Object[]) GgCore.internalNames()));
        long h = nativeCreate(init, Prelude.source());
        if (h == 0) return "cannot start Lua: " + nativeLastError();
        current = h;
        try {
            if (Session.stopRequested) nativeStop(h);
            byte[] err = nativeRun(h, chunk, script.getName());
            return err == null ? null : new String(err, Wire.UTF8);
        } finally {
            current = 0;
            nativeClose(h);
        }
    }

    /** Asks the running script to stop (safe from any thread). */
    public static void stop() {
        long h = current;
        if (h != 0) nativeStop(h);
    }
}
