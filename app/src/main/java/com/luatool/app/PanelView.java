package com.luatool.app;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** The main control panel. Used both inside the overlay window and inside the activity. */
public class PanelView extends LinearLayout {
    private static final int BG = 0xF0202124, SEL = 0xFF3949AB, TXT = 0xFFFFFFFF, DIM = 0xFFB0B0B0;

    private final Context ctx;
    private final TextView logView;
    private final ScrollView logScroll;
    private final SelAdapter procAdapter = new SelAdapter();
    private final SelAdapter scriptAdapter = new SelAdapter();
    private List<ProcessInfo> procs = new ArrayList<>();
    private List<File> scripts = new ArrayList<>();
    private Runnable onClose;

    private final AppLog.Listener logListener = new AppLog.Listener() {
        @Override public void onChanged() {
            UiBridge.main.post(new Runnable() {
                @Override public void run() { updateLog(); }
            });
        }
    };

    private static final class SelAdapter extends BaseAdapter {
        List<String> items = new ArrayList<>();
        int selected = -1;

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            TextView tv = (v instanceof TextView) ? (TextView) v : new TextView(parent.getContext());
            int p = Ui.dp(parent.getContext(), 8);
            tv.setPadding(p, p, p, p);
            tv.setTextSize(13);
            tv.setTextColor(TXT);
            tv.setText(items.get(i));
            tv.setBackgroundColor(i == selected ? SEL : Color.TRANSPARENT);
            return tv;
        }
    }

    public PanelView(Context context) {
        super(context);
        this.ctx = context;
        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        int pad = Ui.dp(context, 8);
        setPadding(pad, pad, pad, pad);

        // header
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("LuaTool", 16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        Button close = button("X", new OnClickListener() {
            @Override public void onClick(View v) { if (onClose != null) onClose.run(); }
        });
        header.addView(close, new LayoutParams(Ui.dp(context, 48), Ui.dp(context, 40)));
        addView(header);

        // processes
        addView(sectionRow("1. Pick a process", "Refresh", new OnClickListener() {
            @Override public void onClick(View v) { refreshProcesses(); }
        }));
        ListView pl = new ListView(context);
        pl.setAdapter(procAdapter);
        pl.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int i, long id) { selectProcess(i); }
        });
        addView(pl, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // scripts
        addView(sectionRow("2. Pick a .lua script", "Refresh", new OnClickListener() {
            @Override public void onClick(View v) { refreshScripts(); }
        }));
        ListView sl = new ListView(context);
        sl.setAdapter(scriptAdapter);
        sl.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int i, long id) {
                scriptAdapter.selected = i;
                scriptAdapter.notifyDataSetChanged();
            }
        });
        addView(sl, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // run / stop
        LinearLayout row = new LinearLayout(context);
        row.addView(button("Run", new OnClickListener() {
            @Override public void onClick(View v) { runSelected(); }
        }), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.addView(button("Stop", new OnClickListener() {
            @Override public void onClick(View v) { Session.stop(); }
        }), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        addView(row);

        // log
        logView = new TextView(context);
        logView.setTextSize(11);
        logView.setTextColor(DIM);
        logScroll = new ScrollView(context);
        logScroll.addView(logView);
        addView(logScroll, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(context, 90)));
    }

    public void setOnClose(Runnable r) { onClose = r; }

    private TextView label(String s, int sp) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(TXT);
        return t;
    }

    private Button button(String text, OnClickListener l) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private View sectionRow(String title, String btn, OnClickListener l) {
        LinearLayout r = new LinearLayout(ctx);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = label(title, 13);
        t.setTextColor(DIM);
        r.addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        r.addView(button(btn, l), new LayoutParams(LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 40)));
        return r;
    }

    // ---------- actions ----------

    public void refreshAll() {
        refreshProcesses();
        refreshScripts();
        updateLog();
    }

    public void refreshProcesses() {
        int keepPid = -1;
        if (procAdapter.selected >= 0 && procAdapter.selected < procs.size()) keepPid = procs.get(procAdapter.selected).pid;
        procs = ProcessInfo.list();
        procAdapter.items = new ArrayList<>();
        procAdapter.selected = -1;
        for (int i = 0; i < procs.size(); i++) {
            procAdapter.items.add(procs.get(i).label());
            if (procs.get(i).pid == keepPid) procAdapter.selected = i;
        }
        procAdapter.notifyDataSetChanged();
        if (procs.isEmpty()) AppLog.add("No processes visible. Run this inside the virtual environment next to the app you want to edit.");
    }

    public void refreshScripts() {
        File keep = (scriptAdapter.selected >= 0 && scriptAdapter.selected < scripts.size()) ? scripts.get(scriptAdapter.selected) : null;
        scripts = ScriptStore.list(ctx);
        scriptAdapter.items = new ArrayList<>();
        scriptAdapter.selected = -1;
        for (int i = 0; i < scripts.size(); i++) {
            scriptAdapter.items.add(scripts.get(i).getName());
            if (keep != null && scripts.get(i).equals(keep)) scriptAdapter.selected = i;
        }
        scriptAdapter.notifyDataSetChanged();
        if (scripts.isEmpty()) scriptAdapter.items.add("(no scripts yet - use Import or Paste in the app)");
        scriptAdapter.notifyDataSetChanged();
    }

    private void selectProcess(int i) {
        if (i < 0 || i >= procs.size()) return;
        procAdapter.selected = i;
        procAdapter.notifyDataSetChanged();
        ProcessInfo p = procs.get(i);
        String err = Session.select(p);
        if (err == null) AppLog.add("Attached to " + p.name + " (pid " + p.pid + ") - memory is accessible.");
        else AppLog.add("Cannot use " + p.name + ": " + err);
    }

    private void runSelected() {
        int i = scriptAdapter.selected;
        if (i < 0 || i >= scripts.size()) {
            AppLog.add("Pick a script first.");
            return;
        }
        Session.run(scripts.get(i));
    }

    private void updateLog() {
        logView.setText(AppLog.dump());
        logScroll.post(new Runnable() {
            @Override public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        AppLog.addListener(logListener);
        updateLog();
    }

    @Override protected void onDetachedFromWindow() {
        AppLog.removeListener(logListener);
        super.onDetachedFromWindow();
    }
}
