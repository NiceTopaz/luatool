package com.luatool.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Scripts live in the app's private files dir (so it works the same inside a virtual environment). */
final class ScriptStore {
    private ScriptStore() {}

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "scripts");
        d.mkdirs();
        return d;
    }

    static List<File> list(Context c) {
        File[] fs = dir(c).listFiles();
        ArrayList<File> out = new ArrayList<>();
        if (fs != null) {
            for (File f : fs) if (f.isFile()) out.add(f);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
        });
        return out;
    }

    static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) name = "script.lua";
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        String low = name.toLowerCase();
        if (!low.endsWith(".lua") && !low.endsWith(".txt")) name += ".lua";
        return name;
    }

    static File save(Context c, String name, InputStream in) throws Exception {
        File f = new File(dir(c), safeName(name));
        FileOutputStream out = new FileOutputStream(f);
        try {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally {
            out.close();
            in.close();
        }
        return f;
    }

    static File saveText(Context c, String name, String text) throws Exception {
        return save(c, name, new java.io.ByteArrayInputStream(text.getBytes("UTF-8")));
    }
}
