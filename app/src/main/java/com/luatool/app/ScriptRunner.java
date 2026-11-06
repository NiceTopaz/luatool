package com.luatool.app;

import java.io.File;
import java.util.Arrays;

/**
 * Picks the right engine for a script file by looking at its first bytes (never by decoding it as text):
 *  - Lua source                -> native Lua 5.3 (LuaJ fallback if the native library is missing)
 *  - Lua 5.3 bytecode (0x53)   -> native Lua 5.3
 *  - Lua 5.2 bytecode (0x52)   -> LuaJ (Lua 5.2); real Lua 5.3 cannot read 5.2 bytecode
 */
final class ScriptRunner {
    private ScriptRunner() {}

    static final int TEXT = 0, BC51 = 1, BC52 = 2, BC53 = 3, BC54 = 4, BC_BAD = 5;

    static boolean isBytecodeStart(byte[] d) {
        return d.length >= 4 && d[0] == 0x1B && d[1] == 'L' && d[2] == 'u' && d[3] == 'a';
    }

    private static final byte[] TAIL = {0x19, (byte) 0x93, 0x0D, 0x0A, 0x1A, 0x0A};

    static int kind(byte[] d) {
        if (!isBytecodeStart(d)) return TEXT;
        if (d.length < 12) return BC_BAD;
        switch (d[4] & 0xFF) {
            case 0x51: return BC51;
            case 0x52:
                // 5.2 header: ver, format(0), endian, sizeof int/size_t/instr/number, integral, tail
                if (d.length < 18 || d[5] != 0 || !Arrays.equals(Arrays.copyOfRange(d, 12, 18), TAIL)) return BC_BAD;
                return BC52;
            case 0x53:
                if (d.length < 12 || d[5] != 0 || !Arrays.equals(Arrays.copyOfRange(d, 6, 12), TAIL)) return BC_BAD;
                return BC53;
            case 0x54: return BC54;
            default: return BC_BAD;
        }
    }

    /** Removes a UTF-8 BOM and turns a leading "#!" line into a comment (keeps line numbers). */
    static byte[] cleanText(byte[] d) {
        int off = 0;
        if (d.length >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF) off = 3;
        boolean shebang = d.length > off && d[off] == '#';
        if (off == 0 && !shebang) return d;
        byte[] out = new byte[d.length - off + (shebang ? 2 : 0)];
        int p = 0;
        if (shebang) { out[p++] = '-'; out[p++] = '-'; }
        System.arraycopy(d, off, out, p, d.length - off);
        return out;
    }

    /** @return null on success, otherwise the error message. */
    static String run(File f) throws Exception {
        byte[] data = Session.readBytes(f);
        if (data.length == 0) return "the script file is empty";
        boolean nativeOk = LuaVM.available();
        int k = kind(data);
        switch (k) {
            case BC51:
                return "this is Lua 5.1 bytecode. LuaTool runs Lua 5.3 source/bytecode and Lua 5.2 bytecode. "
                        + "Recompile the script (or ask its author for a 5.3 build).";
            case BC54:
                return "this is Lua 5.4 bytecode. LuaTool runs Lua 5.3 source/bytecode and Lua 5.2 bytecode.";
            case BC_BAD:
                return "the file starts like Lua bytecode, but its header is damaged. This usually happens when a binary "
                        + ".lua file is copied/pasted/uploaded as text (zero bytes get dropped and other bytes replaced "
                        + "by U+FFFD). Re-copy the file as a binary (e.g. put it in a .zip) and import it again.";
            case BC52:
                AppLog.add("Lua 5.2 bytecode detected -> running it with the Lua 5.2 engine (LuaJ).");
                return LuaJEngine.run(f, data);
            case BC53:
                if (!nativeOk) return "Lua 5.3 bytecode needs the native engine, which failed to load: " + LuaVM.loadError();
                AppLog.add("Engine: " + LuaVM.version() + " (bytecode)");
                return LuaVM.run(f, data);
            default:
                if (nativeOk) {
                    AppLog.add("Engine: " + LuaVM.version());
                    return LuaVM.run(f, cleanText(data));
                }
                AppLog.add("Native Lua 5.3 engine unavailable (" + LuaVM.loadError() + ") -> using the Lua 5.2 engine (LuaJ).");
                return LuaJEngine.run(f, cleanText(data));
        }
    }
}
