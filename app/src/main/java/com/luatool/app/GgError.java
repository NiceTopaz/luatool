package com.luatool.app;

/** Error raised to the running Lua script (becomes a normal Lua error with this message). */
public class GgError extends RuntimeException {
    public GgError(String msg) { super(msg); }
}
