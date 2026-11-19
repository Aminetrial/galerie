package com.galerie.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Environment;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Petite fenêtre pour choisir (ou créer) un dossier de destination. */
public class FolderPicker {

    public interface Callback { void onPicked(File dir); }

    private final Activity act;
    private final Callback cb;
    private final File storageRoot = Environment.getExternalStorageDirectory();
    private File current;
    private final List<File> dirs = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private AlertDialog dialog;

    public static void show(Activity a, File start, Callback cb) {
        new FolderPicker(a, start, cb).open();
    }

    private FolderPicker(Activity a, File start, Callback cb) {
        this.act = a;
        this.cb = cb;
        this.current = start != null && start.isDirectory() ? start : storageRoot;
    }

    private void open() {
        ListView lv = new ListView(act);
        adapter = new ArrayAdapter<>(act, android.R.layout.simple_list_item_1, new ArrayList<>());
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> {
            File d = dirs.get(pos);
            if (d != null) {
                current = d;
                refresh();
            }
        });
        dialog = new AlertDialog.Builder(act)
                .setTitle("Choisir un dossier")
                .setView(lv)
                .setPositiveButton("Choisir ici", (d, w) -> cb.onPicked(current))
                .setNeutralButton("Nouveau dossier", null)
                .setNegativeButton("Annuler", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> newFolder()));
        dialog.show();
        refresh();
    }

    private void refresh() {
        dirs.clear();
        List<String> names = new ArrayList<>();
        File parent = current.getParentFile();
        if (!current.equals(storageRoot) && parent != null) {
            dirs.add(parent);
            names.add("⬆️  ..");
        }
        File[] l = current.listFiles();
        List<File> sub = new ArrayList<>();
        if (l != null) for (File f : l) if (f.isDirectory() && !f.getName().startsWith(".")) sub.add(f);
        FileUtil.sort(sub);
        for (File f : sub) {
            dirs.add(f);
            names.add("📁  " + f.getName());
        }
        adapter.clear();
        adapter.addAll(names);
        String p = current.getAbsolutePath().replace(storageRoot.getAbsolutePath(), "Stockage");
        dialog.setTitle(p);
    }

    private void newFolder() {
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setHint("Nom du dossier");
        FrameLayout box = new FrameLayout(act);
        int pad = (int) (20 * act.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        new AlertDialog.Builder(act)
                .setTitle("Nouveau dossier")
                .setView(box)
                .setPositiveButton("Créer", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!FileUtil.isValidName(n)) { Toast.makeText(act, "Nom invalide", Toast.LENGTH_SHORT).show(); return; }
                    File nd = new File(current, n);
                    if (nd.isDirectory() || nd.mkdir()) {
                        MediaRepo.addEmptyAlbum(act, nd.getAbsolutePath());
                        current = nd;
                        refresh();
                    } else Toast.makeText(act, "Impossible de créer le dossier", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }
}
