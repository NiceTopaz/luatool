package com.luatool.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Loads assets/prelude.lua: the Lua side of the compatibility layer (print, io.write, getfenv, ...). */
final class Prelude {
    private Prelude() {}

    /** Optional override (used by desktop tests). */
    static volatile byte[] override;

    static byte[] source() {
        byte[] o = override;
        if (o != null) return o;
        Context c = Session.app;
        if (c == null) throw new IllegalStateException("no Context to read prelude.lua");
        try {
            InputStream in = c.getAssets().open("prelude.lua");
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                return bo.toByteArray();
            } finally {
                in.close();
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read prelude.lua: " + e.getMessage());
        }
    }
}
