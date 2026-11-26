package com.galerie.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Environment;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Choix d'un dossier de destination, en plein écran :
 * navigation dossier par dossier, recherche dans tous les dossiers, création de dossier,
 * bouton « Choisir » directement sur chaque ligne.
 */
public class FolderPicker {

    public interface Callback { void onPicked(File dir); }

    private static class Entry {
        File dir;
        String sub;
        boolean up;
    }

    private final Activity act;
    private final Callback cb;
    private final String title;
    private final File root = Environment.getExternalStorageDirectory();
    private File current;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private volatile List<File> allDirs = new ArrayList<>();
    private String query = "";

    private Dialog dialog;
    private EditText searchEdit;
    private TextView pathView, emptyView, hereBtn;
    private BaseAdapter adapter;

    public static void show(Activity a, File start, Callback cb) {
        show(a, start, "Déplacer vers…", cb);
    }

    public static void show(Activity a, File start, String title, Callback cb) {
        new FolderPicker(a, start, title, cb).open();
    }

    private FolderPicker(Activity a, File start, String title, Callback cb) {
        this.act = a;
        this.cb = cb;
        this.title = title;
        this.current = start != null && start.isDirectory() ? start : root;
        for (MediaRepo.Album al : MediaRepo.get().albums) counts.put(al.path, al.items.size());
    }

    private int dp(int v) { return Ui.dp(act, v); }

    // ------------------------------------------------------------------ interface

    private void open() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Ui.BG);
        box.setFitsSystemWindows(true);

        // barre du haut
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.BAR);
        bar.addView(Ui.iconButton(act, R.drawable.ic_close, v -> dialog.dismiss()), new LinearLayout.LayoutParams(dp(48), dp(56)));
        TextView t = new TextView(act);
        t.setText(title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(19);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(dp(8), 0, 0, 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView newBtn = new TextView(act);
        newBtn.setText("+ Nouveau dossier");
        newBtn.setTextColor(Ui.ACCENT);
        newBtn.setTextSize(14);
        newBtn.setTypeface(null, Typeface.BOLD);
        newBtn.setPadding(dp(12), dp(12), dp(16), dp(12));
        newBtn.setOnClickListener(v -> newFolder());
        bar.addView(newBtn);
        box.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));

        // recherche
        searchEdit = new EditText(act);
        searchEdit.setHint("Rechercher un dossier");
        searchEdit.setHintTextColor(Ui.MUTED);
        searchEdit.setTextColor(Ui.TEXT);
        searchEdit.setTextSize(16);
        searchEdit.setSingleLine(true);
        searchEdit.setBackground(Ui.rect(act, Ui.CARD, 24));
        searchEdit.setPadding(dp(20), dp(10), dp(20), dp(10));
        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable e) {
                query = e.toString().trim();
                refresh();
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(dp(12), dp(10), dp(12), dp(4));
        box.addView(searchEdit, sp);

        // chemin courant
        pathView = new TextView(act);
        pathView.setTextColor(Ui.MUTED);
        pathView.setTextSize(12);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.START);
        pathView.setPadding(dp(20), dp(8), dp(20), dp(6));
        box.addView(pathView, new LinearLayout.LayoutParams(-1, -2));

        // liste
        FrameLayout listBox = new FrameLayout(act);
        ListView lv = new ListView(act);
        lv.setDivider(null);
        lv.setSelector(android.R.color.transparent);
        adapter = new EntryAdapter();
        lv.setAdapter(adapter);
        listBox.addView(lv, new FrameLayout.LayoutParams(-1, -1));
        emptyView = new TextView(act);
        emptyView.setText("Aucun dossier trouvé");
        emptyView.setTextColor(Ui.MUTED);
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        listBox.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
        box.addView(listBox, new LinearLayout.LayoutParams(-1, 0, 1));

        // bouton du bas : choisir le dossier affiché
        hereBtn = new Button(act);
        hereBtn.setAllCaps(false);
        hereBtn.setTextColor(Color.BLACK);
        hereBtn.setTextSize(16);
        hereBtn.setTypeface(null, Typeface.BOLD);
        hereBtn.setBackground(Ui.rect(act, Ui.ACCENT, 28));
        hereBtn.setOnClickListener(v -> pick(current));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, dp(52));
        hp.setMargins(dp(16), dp(8), dp(16), dp(12));
        box.addView(hereBtn, hp);

        dialog = new Dialog(act, android.R.style.Theme_Material_NoActionBar);
        dialog.setContentView(box);
        dialog.setOnKeyListener((d, code, ev) -> {
            if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) {
                if (!query.isEmpty()) { searchEdit.setText(""); return true; }
                if (!current.equals(root) && current.getParentFile() != null) {
                    current = current.getParentFile();
                    refresh();
                    return true;
                }
            }
            return false;
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialog.getWindow().setStatusBarColor(Ui.BAR);
            dialog.getWindow().setNavigationBarColor(Ui.BG);
        }
        refresh();
        scanAllDirs();
    }

    // ------------------------------------------------------------------ données

    /** Liste tous les dossiers du stockage en arrière-plan (pour la recherche). */
    private void scanAllDirs() {
        new Thread(() -> {
            List<File> out = new ArrayList<>();
            walk(root, 0, out);
            allDirs = out;
            act.runOnUiThread(() -> { if (dialog.isShowing() && !query.isEmpty()) refresh(); });
        }, "galerie-dirs").start();
    }

    private void walk(File dir, int depth, List<File> out) {
        if (depth > 7 || out.size() > 30000) return;
        File[] l = dir.listFiles();
        if (l == null) return;
        for (File f : l) {
            if (!f.isDirectory() || f.getName().startsWith(".")) continue;
            if (depth == 0 && f.getName().equals("Android")) continue;
            out.add(f);
            walk(f, depth + 1, out);
        }
    }

    private String shortPath(File f) {
        String p = f.getAbsolutePath();
        String r = root.getAbsolutePath();
        return p.startsWith(r) ? "Stockage" + p.substring(r.length()) : p;
    }

    private String subtitle(File d) {
        Integer n = counts.get(d.getAbsolutePath());
        return n == null || n == 0 ? "" : n + (n > 1 ? " éléments" : " élément");
    }

    private void refresh() {
        entries.clear();
        if (query.isEmpty()) {
            File parent = current.getParentFile();
            if (!current.equals(root) && parent != null) {
                Entry up = new Entry();
                up.dir = parent;
                up.up = true;
                up.sub = "Dossier parent";
                entries.add(up);
            }
            File[] l = current.listFiles();
            List<File> sub = new ArrayList<>();
            if (l != null) for (File f : l) if (f.isDirectory() && !f.getName().startsWith(".")) sub.add(f);
            FileUtil.sort(sub);
            for (File f : sub) {
                Entry e = new Entry();
                e.dir = f;
                e.sub = subtitle(f);
                entries.add(e);
            }
            pathView.setText(shortPath(current));
            hereBtn.setText("Choisir « " + (current.equals(root) ? "Stockage" : current.getName()) + " »");
            hereBtn.setVisibility(View.VISIBLE);
            emptyView.setText("Ce dossier ne contient aucun sous-dossier");
            emptyView.setVisibility(sub.isEmpty() && entries.isEmpty() ? View.VISIBLE : View.GONE);
        } else {
            String q = query.toLowerCase(Locale.ROOT);
            List<File> found = new ArrayList<>();
            for (File f : allDirs) {
                if (f.getName().toLowerCase(Locale.ROOT).contains(q)) {
                    found.add(f);
                    if (found.size() >= 300) break;
                }
            }
            Collections.sort(found, (a, b) -> FileUtil.naturalCompare(a.getName(), b.getName()));
            for (File f : found) {
                Entry e = new Entry();
                e.dir = f;
                String c = subtitle(f);
                File par = f.getParentFile();
                e.sub = (c.isEmpty() ? "" : c + " · ") + (par != null ? shortPath(par) : "");
                entries.add(e);
            }
            pathView.setText(found.size() + (found.size() > 1 ? " dossiers trouvés" : " dossier trouvé"));
            hereBtn.setVisibility(View.GONE);
            emptyView.setText("Aucun dossier trouvé");
            emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        }
        adapter.notifyDataSetChanged();
    }

    private void pick(File dir) {
        dialog.dismiss();
        cb.onPicked(dir);
    }

    private void open(File dir) {
        current = dir;
        if (!query.isEmpty()) searchEdit.setText(""); // le watcher relance refresh()
        else refresh();
    }

    // ------------------------------------------------------------------ lignes

    private class EntryAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int i) { return entries.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            final Entry e = entries.get(position);
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(12), dp(10));
            row.setOnClickListener(v -> open(e.dir));

            ImageView ic = new ImageView(act);
            ic.setImageResource(e.up ? R.drawable.ic_back : R.drawable.ic_folder);
            if (e.up) ic.setColorFilter(Ui.MUTED);
            ic.setPadding(dp(8), dp(8), dp(8), dp(8));
            ic.setBackground(Ui.rect(act, Ui.CARD, 12));
            row.addView(ic, new LinearLayout.LayoutParams(dp(48), dp(48)));

            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(14), 0, dp(8), 0);
            TextView name = new TextView(act);
            name.setText(e.up ? ".." : e.dir.getName());
            name.setTextColor(Ui.TEXT);
            name.setTextSize(16);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(name);
            if (e.sub != null && !e.sub.isEmpty()) {
                TextView sub = new TextView(act);
                sub.setText(e.sub);
                sub.setTextColor(Ui.MUTED);
                sub.setTextSize(12);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.START);
                col.addView(sub);
            }
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

            if (!e.up) {
                TextView choose = new TextView(act);
                choose.setText("Choisir");
                choose.setTextColor(Ui.ACCENT);
                choose.setTextSize(13);
                choose.setTypeface(null, Typeface.BOLD);
                choose.setPadding(dp(14), dp(8), dp(14), dp(8));
                GradientHolder.outline(choose, act);
                choose.setOnClickListener(v -> pick(e.dir));
                row.addView(choose);
            }
            return row;
        }
    }

    private static final class GradientHolder {
        static void outline(TextView v, Activity a) {
            android.graphics.drawable.GradientDrawable g = Ui.rect(a, 0x00000000, 16);
            g.setStroke(Ui.dp(a, 1), Ui.ACCENT);
            v.setBackground(g);
        }
    }

    // ------------------------------------------------------------------ nouveau dossier

    private void newFolder() {
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setHint("Nom du dossier");
        FrameLayout box = new FrameLayout(act);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et);
        AlertDialog d = new AlertDialog.Builder(act)
                .setTitle("Nouveau dossier dans « " + (current.equals(root) ? "Stockage" : current.getName()) + " »")
                .setView(box)
                .setPositiveButton("Créer", (dd, w) -> {
                    String n = et.getText().toString().trim();
                    if (!FileUtil.isValidName(n)) {
                        Toast.makeText(act, "Nom invalide", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    File nd = new File(current, n);
                    if (nd.isDirectory() || nd.mkdir()) {
                        MediaRepo.addEmptyAlbum(act, nd.getAbsolutePath());
                        List<File> upd = new ArrayList<>(allDirs);
                        upd.add(nd);
                        allDirs = upd;
                        open(nd);
                    } else Toast.makeText(act, "Impossible de créer le dossier", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }
}
