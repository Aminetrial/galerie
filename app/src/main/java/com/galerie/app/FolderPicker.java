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
import android.widget.ImageButton;
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
        boolean up, browse;
    }

    private final Activity act;
    private final Callback cb;
    private final String title;
    private final File root = Environment.getExternalStorageDirectory();
    private File current;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private String query = "";
    private boolean home = true; // true = liste de tous les albums ; false = navigation dans les dossiers

    private Dialog dialog;
    private EditText searchEdit;
    private TextView pathView, emptyView, hereBtn;
    private BaseAdapter adapter;
    private ThumbLoader thumbs;
    private final Map<String, MediaRepo.Album> albumMap = new HashMap<>();

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
        for (MediaRepo.Album al : MediaRepo.get().albums) {
            counts.put(al.path, al.items.size());
            albumMap.put(al.path, al);
        }
        this.thumbs = new ThumbLoader(a);
    }

    private int dp(int v) { return Ui.dp(act, v); }

    // ------------------------------------------------------------------ interface

    private void open() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Ui.BG);
        box.setFitsSystemWindows(true);

        // titre centré + fermer
        FrameLayout top = new FrameLayout(act);
        top.setBackgroundColor(Ui.BAR);
        top.addView(Ui.iconButton(act, R.drawable.ic_close, v -> dialog.dismiss()),
                new FrameLayout.LayoutParams(dp(48), dp(56), Gravity.START | Gravity.CENTER_VERTICAL));
        TextView t = new TextView(act);
        t.setText(title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(19);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        top.addView(t, new FrameLayout.LayoutParams(-1, dp(56)));
        box.addView(top, new LinearLayout.LayoutParams(-1, dp(56)));

        // « Nouveau dossier » + loupe
        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(16), dp(12), dp(8), dp(4));
        TextView newBtn = new TextView(act);
        newBtn.setText("Nouveau dossier");
        newBtn.setTextColor(Color.BLACK);
        newBtn.setTextSize(15);
        newBtn.setTypeface(null, Typeface.BOLD);
        newBtn.setPadding(dp(18), dp(10), dp(18), dp(10));
        newBtn.setBackground(Ui.rect(act, Ui.ACCENT, 14));
        newBtn.setOnClickListener(v -> newFolder());
        actions.addView(newBtn, new LinearLayout.LayoutParams(-2, -2));
        actions.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1));
        ImageButton searchBtn = Ui.iconButton(act, R.drawable.ic_search, v -> toggleSearch());
        searchBtn.setContentDescription("Rechercher un dossier");
        actions.addView(searchBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        box.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        // recherche (cachée tant qu'on ne touche pas la loupe)
        searchEdit = new EditText(act);
        searchEdit.setHint("Rechercher un dossier");
        searchEdit.setHintTextColor(Ui.MUTED);
        searchEdit.setTextColor(Ui.TEXT);
        searchEdit.setTextSize(16);
        searchEdit.setSingleLine(true);
        searchEdit.setBackground(Ui.rect(act, Ui.CARD, 24));
        searchEdit.setPadding(dp(20), dp(10), dp(20), dp(10));
        searchEdit.setVisibility(View.GONE);
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
        sp.setMargins(dp(12), dp(4), dp(12), dp(4));
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
        dialog.setOnDismissListener(d -> thumbs.shutdown());
        dialog.setContentView(box);
        dialog.setOnKeyListener((d, code, ev) -> {
            if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) {
                if (searchEdit.getVisibility() == View.VISIBLE) { toggleSearch(); return true; }
                if (!home) {
                    if (!current.equals(root) && current.getParentFile() != null) current = current.getParentFile();
                    else home = true;
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
    }

    // ------------------------------------------------------------------ données

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
        if (query.isEmpty() && home) {
            Entry b = new Entry();
            b.browse = true;
            b.sub = "Explorer tous les dossiers du téléphone";
            entries.add(b);
            List<MediaRepo.Album> al = new ArrayList<>(MediaRepo.get().albums);
            for (MediaRepo.Album a : al) {
                Entry e = new Entry();
                e.dir = new File(a.path);
                e.sub = subtitle(e.dir);
                entries.add(e);
            }
            pathView.setText("Vos albums (" + al.size() + ")");
            hereBtn.setVisibility(View.GONE);
            emptyView.setVisibility(View.GONE);
        } else if (query.isEmpty()) {
            File parent = current.getParentFile();
            if (!current.equals(root) && parent != null) {
                Entry up = new Entry();
                up.dir = parent;
                up.up = true;
                up.sub = "Dossier parent";
                entries.add(up);
            } else {
                Entry up = new Entry();
                up.up = true; // dir == null : retour à la liste des albums
                up.sub = "Retour à la liste des albums";
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
            for (MediaRepo.Album al : MediaRepo.get().albums) {
                if (al.name.toLowerCase(Locale.ROOT).contains(q)) found.add(new File(al.path));
            }
            for (File f : found) {
                Entry e = new Entry();
                e.dir = f;
                e.sub = subtitle(f);
                entries.add(e);
            }
            pathView.setText(found.size() + (found.size() > 1 ? " albums trouvés" : " album trouvé"));
            hereBtn.setVisibility(View.GONE);
            emptyView.setText("Aucun dossier trouvé");
            emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        }
        adapter.notifyDataSetChanged();
    }

    private void toggleSearch() {
        boolean show = searchEdit.getVisibility() != View.VISIBLE;
        searchEdit.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            searchEdit.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) act.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) searchEdit.post(() -> imm.showSoftInput(searchEdit, 0));
        } else {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) act.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(searchEdit.getWindowToken(), 0);
            searchEdit.setText("");
        }
    }

    private void pick(File dir) {
        dialog.dismiss();
        cb.onPicked(dir);
    }

    private void open(File dir) {
        home = false;
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
            final boolean browsing = !home && query.isEmpty();
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), browsing ? dp(10) : dp(8), dp(12), browsing ? dp(10) : dp(8));
            row.setOnClickListener(v -> {
                if (e.browse) { home = false; current = root; refresh(); }
                else if (e.up && e.dir == null) { home = true; refresh(); }
                else if (browsing || e.up) open(e.dir);
                else pick(e.dir); // accueil et recherche : toucher un dossier le choisit
            });

            int box = browsing ? 48 : 84;
            ImageView ic = new ImageView(act);
            ic.setBackground(Ui.rect(act, Ui.CARD, 12));
            ic.setClipToOutline(true);
            MediaRepo.Album al = e.dir != null ? albumMap.get(e.dir.getAbsolutePath()) : null;
            if (!browsing && al != null && !al.items.isEmpty()) {
                ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
                thumbs.load(al.items.get(0), ic);
            } else {
                ic.setImageResource(e.up ? R.drawable.ic_back : R.drawable.ic_folder);
                ic.setColorFilter(Ui.MUTED);
                ic.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                ic.setPadding(dp(browsing ? 8 : 22), dp(browsing ? 8 : 22), dp(browsing ? 8 : 22), dp(browsing ? 8 : 22));
            }
            row.addView(ic, new LinearLayout.LayoutParams(dp(box), dp(box)));

            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(16), 0, dp(8), 0);
            TextView name = new TextView(act);
            name.setText(e.browse ? "Parcourir tout le stockage" : e.up ? ".." : e.dir.getName());
            name.setTextColor(Ui.TEXT);
            name.setTextSize(browsing ? 16 : 19);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(name);
            if (e.sub != null && !e.sub.isEmpty()) {
                TextView sub = new TextView(act);
                sub.setText(e.sub);
                sub.setTextColor(Ui.MUTED);
                sub.setTextSize(browsing ? 12 : 14);
                sub.setSingleLine(true);
                col.addView(sub);
            }
            if (!browsing && e.dir != null && !e.up) {
                TextView path = new TextView(act);
                path.setText(e.dir.getAbsolutePath());
                path.setTextColor(Ui.MUTED);
                path.setTextSize(12);
                path.setSingleLine(true);
                path.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(path);
            }
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

            if (browsing && !e.up && !e.browse) {
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
        if (home && query.isEmpty()) {
            current = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            if (!current.isDirectory()) current.mkdirs();
        }
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
                        open(nd);
                    } else Toast.makeText(act, "Impossible de créer le dossier", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }
}
