package com.galerie.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private File root;
    private File currentDir;
    private final List<File> items = new ArrayList<>();
    private boolean searchMode = false;
    private String searchQuery = "";
    private File moveSource = null;
    private int searchToken = 0;
    private final Map<String, Integer> scrollMemory = new HashMap<>();

    private TextView pathBar;
    private LinearLayout moveBar;
    private TextView moveText;
    private ProgressBar progress;
    private GridView grid;
    private LinearLayout permissionView;
    private TextView emptyView;
    private GridAdapter adapter;
    private ThumbLoader thumbs;
    private MenuItem searchItem;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Random random = new Random();

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = Environment.getExternalStorageDirectory();
        currentDir = root;
        if (state != null) {
            String r = state.getString("root");
            String d = state.getString("dir");
            if (r != null) root = new File(r);
            if (d != null) currentDir = new File(d);
        }
        thumbs = new ThumbLoader();
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasPermission()) {
            permissionView.setVisibility(View.GONE);
            grid.setVisibility(View.VISIBLE);
            if (!searchMode) loadDir(currentDir.isDirectory() ? currentDir : root, false);
        } else {
            permissionView.setVisibility(View.VISIBLE);
            grid.setVisibility(View.GONE);
            emptyView.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("root", root.getAbsolutePath());
        out.putString("dir", currentDir.getAbsolutePath());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbs.shutdown();
        bg.shutdownNow();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (searchMode) {
            exitSearch();
            return;
        }
        File parent = currentDir.getParentFile();
        if (!currentDir.equals(root) && parent != null) {
            loadDir(parent, true);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ interface

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setBackgroundColor(0xFF121212);

        pathBar = new TextView(this);
        pathBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        pathBar.setTextColor(0xFFBDBDBD);
        pathBar.setTextSize(13);
        pathBar.setSingleLine(true);
        pathBar.setEllipsize(TextUtils.TruncateAt.START);
        pathBar.setBackgroundColor(0xFF1A1A1A);
        main.addView(pathBar, new LinearLayout.LayoutParams(-1, -2));

        // Barre de déplacement (affichée pendant un "Déplacer")
        moveBar = new LinearLayout(this);
        moveBar.setOrientation(LinearLayout.HORIZONTAL);
        moveBar.setGravity(Gravity.CENTER_VERTICAL);
        moveBar.setBackgroundColor(0xFF0D47A1);
        moveBar.setPadding(dp(12), dp(4), dp(4), dp(4));
        moveText = new TextView(this);
        moveText.setTextColor(Color.WHITE);
        moveText.setTextSize(13);
        moveBar.addView(moveText, new LinearLayout.LayoutParams(0, -2, 1));
        Button paste = new Button(this, null, android.R.attr.borderlessButtonStyle);
        paste.setText("Coller ici");
        paste.setTextColor(Color.WHITE);
        paste.setOnClickListener(v -> doPaste());
        moveBar.addView(paste);
        Button cancel = new Button(this, null, android.R.attr.borderlessButtonStyle);
        cancel.setText("Annuler");
        cancel.setTextColor(0xFFBBDEFB);
        cancel.setOnClickListener(v -> cancelMove());
        moveBar.addView(cancel);
        moveBar.setVisibility(View.GONE);
        main.addView(moveBar, new LinearLayout.LayoutParams(-1, -2));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        main.addView(progress, new LinearLayout.LayoutParams(-1, dp(6)));

        FrameLayout content = new FrameLayout(this);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(110));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(4));
        grid.setVerticalSpacing(dp(4));
        grid.setPadding(dp(4), dp(4), dp(4), dp(4));
        grid.setClipToPadding(false);
        grid.setFastScrollEnabled(true);
        adapter = new GridAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> onItemClick(items.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            onItemLongClick(items.get(pos));
            return true;
        });
        content.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        emptyView = new TextView(this);
        emptyView.setText("Aucune photo, vidéo ou dossier ici");
        emptyView.setTextColor(0xFF9E9E9E);
        emptyView.setGravity(Gravity.CENTER);
        content.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
        grid.setEmptyView(emptyView);

        permissionView = new LinearLayout(this);
        permissionView.setOrientation(LinearLayout.VERTICAL);
        permissionView.setGravity(Gravity.CENTER);
        permissionView.setPadding(dp(32), dp(32), dp(32), dp(32));
        TextView msg = new TextView(this);
        msg.setText("La galerie a besoin d'accéder à vos fichiers pour afficher, renommer, déplacer et supprimer vos photos et vidéos.");
        msg.setTextColor(Color.WHITE);
        msg.setGravity(Gravity.CENTER);
        msg.setTextSize(16);
        permissionView.addView(msg);
        Button grant = new Button(this);
        grant.setText("Autoriser l'accès");
        grant.setOnClickListener(v -> requestPermission());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(24);
        permissionView.addView(grant, lp);
        content.addView(permissionView, new FrameLayout.LayoutParams(-1, -1));

        main.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(main);
    }

    // ------------------------------------------------------------------ permissions

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        // onResume() est rappelé automatiquement après la fenêtre de permission
    }

    // ------------------------------------------------------------------ navigation

    private String displayPath(File f) {
        String r = root.getAbsolutePath();
        String p = f.getAbsolutePath();
        String name = r.equals(Environment.getExternalStorageDirectory().getAbsolutePath()) ? "Stockage" : root.getName();
        if (p.startsWith(r)) return "📁 " + name + p.substring(r.length());
        return "📁 " + p;
    }

    private void loadDir(File dir, boolean restoreScroll) {
        searchToken++; // annule une recherche en cours
        if (!searchMode && currentDir != null && grid.getCount() > 0) {
            scrollMemory.put(currentDir.getAbsolutePath(), grid.getFirstVisiblePosition());
        }
        searchMode = false;
        progress.setVisibility(View.GONE);
        currentDir = dir;
        items.clear();
        File[] list = dir.listFiles();
        if (list != null) {
            for (File f : list) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory() || FileUtil.isMedia(f)) items.add(f);
            }
        }
        FileUtil.sort(items);
        pathBar.setText(displayPath(dir));
        adapter.notifyDataSetChanged();
        Integer pos = restoreScroll ? scrollMemory.get(dir.getAbsolutePath()) : null;
        grid.setSelection(pos != null ? pos : 0);
    }

    private void refresh() {
        if (searchMode) runSearch(searchQuery);
        else {
            int pos = grid.getFirstVisiblePosition();
            loadDir(currentDir, false);
            grid.setSelection(pos);
        }
    }

    private void onItemClick(File f) {
        if (f.isDirectory()) {
            if (searchMode && searchItem != null) {
                searchMode = false;
                searchItem.collapseActionView();
            }
            loadDir(f, false);
        } else {
            ArrayList<String> media = new ArrayList<>();
            int index = 0;
            for (File x : items) {
                if (!x.isDirectory()) {
                    if (x.equals(f)) index = media.size();
                    media.add(x.getAbsolutePath());
                }
            }
            openViewer(media, index, false);
        }
    }

    private void openViewer(ArrayList<String> paths, int index, boolean randomMode) {
        if (paths.isEmpty()) {
            Toast.makeText(this, "Aucune photo ou vidéo trouvée", Toast.LENGTH_SHORT).show();
            return;
        }
        ViewerActivity.sPaths = paths;
        Intent i = new Intent(this, ViewerActivity.class);
        i.putExtra("index", index);
        i.putExtra("random", randomMode);
        startActivity(i);
    }

    // ------------------------------------------------------------------ aléatoire

    private void randomHere() {
        ArrayList<String> media = new ArrayList<>();
        for (File x : items) if (!x.isDirectory()) media.add(x.getAbsolutePath());
        if (media.isEmpty()) {
            Toast.makeText(this, "Pas de média ici — essayez « Aléatoire + sous-dossiers »", Toast.LENGTH_LONG).show();
            return;
        }
        openViewer(media, random.nextInt(media.size()), true);
    }

    private void randomRecursive() {
        final File base = currentDir;
        progress.setVisibility(View.VISIBLE);
        bg.execute(() -> {
            ArrayList<String> media = new ArrayList<>();
            ArrayDeque<File> stack = new ArrayDeque<>();
            stack.push(base);
            while (!stack.isEmpty() && media.size() < 20000) {
                File[] l = stack.pop().listFiles();
                if (l == null) continue;
                for (File f : l) {
                    if (f.getName().startsWith(".")) continue;
                    if (f.isDirectory()) stack.push(f);
                    else if (FileUtil.isMedia(f)) media.add(f.getAbsolutePath());
                }
            }
            ui.post(() -> {
                progress.setVisibility(View.GONE);
                if (!media.isEmpty()) openViewer(media, random.nextInt(media.size()), true);
                else Toast.makeText(this, "Aucune photo ou vidéo trouvée", Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ------------------------------------------------------------------ recherche

    private void runSearch(String q) {
        final String query = q.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return;
        if (!searchMode && grid.getCount() > 0) {
            scrollMemory.put(currentDir.getAbsolutePath(), grid.getFirstVisiblePosition());
        }
        searchMode = true;
        searchQuery = q;
        final int token = ++searchToken;
        final File base = currentDir;
        progress.setVisibility(View.VISIBLE);
        pathBar.setText("🔍 Recherche « " + q + " » dans " + displayPath(base).replace("📁 ", "") + "…");
        bg.execute(() -> {
            List<File> res = new ArrayList<>();
            ArrayDeque<File> stack = new ArrayDeque<>();
            stack.push(base);
            while (!stack.isEmpty() && res.size() < 3000) {
                if (token != searchToken) return;
                File[] l = stack.pop().listFiles();
                if (l == null) continue;
                for (File f : l) {
                    String n = f.getName();
                    if (n.startsWith(".")) continue;
                    boolean dir = f.isDirectory();
                    if (dir) stack.push(f);
                    if ((dir || FileUtil.isMedia(f)) && n.toLowerCase(Locale.ROOT).contains(query)) res.add(f);
                }
            }
            FileUtil.sort(res);
            ui.post(() -> {
                if (token != searchToken) return;
                progress.setVisibility(View.GONE);
                items.clear();
                items.addAll(res);
                adapter.notifyDataSetChanged();
                grid.setSelection(0);
                pathBar.setText("🔍 " + res.size() + " résultat(s) pour « " + q + " »");
            });
        });
    }

    private void exitSearch() {
        searchMode = false;
        if (searchItem != null && searchItem.isActionViewExpanded()) searchItem.collapseActionView();
        loadDir(currentDir, true);
    }

    // ------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        searchItem = menu.add(0, 1, 0, "Chercher");
        searchItem.setIcon(android.R.drawable.ic_menu_search);
        searchItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
        final SearchView sv = new SearchView(this);
        sv.setQueryHint("Fichier ou dossier…");
        sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                sv.clearFocus();
                runSearch(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                return false;
            }
        });
        searchItem.setActionView(sv);
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                if (searchMode) {
                    searchMode = false;
                    loadDir(currentDir, true);
                }
                return true;
            }
        });

        MenuItem rnd = menu.add(0, 2, 0, "🔀 Aléatoire");
        rnd.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        menu.add(0, 3, 0, "🔀 Aléatoire (+ sous-dossiers)");
        menu.add(0, 4, 0, "➕ Nouveau dossier");
        menu.add(0, 5, 0, "🔄 Actualiser");
        menu.add(0, 6, 0, "💾 Stockages (interne / SD)");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (!hasPermission() && item.getItemId() != 1) {
            requestPermission();
            return true;
        }
        switch (item.getItemId()) {
            case 2: randomHere(); return true;
            case 3: randomRecursive(); return true;
            case 4: createFolder(); return true;
            case 5: refresh(); return true;
            case 6: chooseStorage(); return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void chooseStorage() {
        final List<File> roots = new ArrayList<>();
        roots.add(Environment.getExternalStorageDirectory());
        File[] dirs = getExternalFilesDirs(null);
        if (dirs != null) {
            for (File d : dirs) {
                if (d == null) continue;
                String p = d.getAbsolutePath();
                int i = p.indexOf("/Android/data");
                if (i > 0) {
                    File r = new File(p.substring(0, i));
                    if (!roots.contains(r)) roots.add(r);
                }
            }
        }
        String[] names = new String[roots.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = i == 0 ? "Mémoire interne" : "Carte SD / USB (" + roots.get(i).getName() + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Choisir un stockage")
                .setItems(names, (d, w) -> {
                    root = roots.get(w);
                    if (searchItem != null) searchItem.collapseActionView();
                    loadDir(root, false);
                })
                .show();
    }

    // ------------------------------------------------------------------ actions fichiers

    private EditText makeInput(String text, boolean isFile) {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(text);
        int dot = text.lastIndexOf('.');
        if (isFile && dot > 0) et.setSelection(0, dot);
        else et.selectAll();
        return et;
    }

    private View pad(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(v);
        return f;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void createFolder() {
        if (searchMode) {
            toast("Quittez la recherche d'abord");
            return;
        }
        final EditText et = makeInput("Nouveau dossier", false);
        new AlertDialog.Builder(this)
                .setTitle("Créer un dossier")
                .setView(pad(et))
                .setPositiveButton("Créer", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!FileUtil.isValidName(n)) { toast("Nom invalide"); return; }
                    File nd = new File(currentDir, n);
                    if (nd.exists()) { toast("Existe déjà"); return; }
                    if (nd.mkdir()) { toast("Dossier créé"); refresh(); }
                    else toast("Impossible de créer le dossier");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void onItemLongClick(final File f) {
        List<String> opts = new ArrayList<>();
        opts.add("✏️ Renommer");
        opts.add("📦 Déplacer");
        opts.add("🗑️ Supprimer");
        if (searchMode) opts.add("📂 Ouvrir l'emplacement");
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    switch (w) {
                        case 0: rename(f); break;
                        case 1: startMove(f); break;
                        case 2: delete(f); break;
                        case 3:
                            File parent = f.getParentFile();
                            if (parent != null) {
                                searchMode = false;
                                if (searchItem != null) searchItem.collapseActionView();
                                loadDir(parent, false);
                            }
                            break;
                    }
                })
                .show();
    }

    private void rename(final File f) {
        final boolean isFile = !f.isDirectory();
        final EditText et = makeInput(f.getName(), isFile);
        new AlertDialog.Builder(this)
                .setTitle(isFile ? "Renommer le fichier" : "Renommer le dossier")
                .setView(pad(et))
                .setPositiveButton("OK", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!FileUtil.isValidName(n)) { toast("Nom invalide"); return; }
                    if (n.equals(f.getName())) return;
                    File dest = new File(f.getParentFile(), n);
                    if (dest.exists()) { toast("Un élément porte déjà ce nom"); return; }
                    if (f.renameTo(dest)) {
                        thumbs.forget(f);
                        toast("Renommé");
                        if (searchMode) {
                            int i = items.indexOf(f);
                            if (i >= 0) items.set(i, dest);
                            adapter.notifyDataSetChanged();
                        } else refresh();
                    } else toast("Échec du renommage");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void delete(final File f) {
        boolean dir = f.isDirectory();
        new AlertDialog.Builder(this)
                .setTitle("Supprimer ?")
                .setMessage((dir ? "Le dossier « " + f.getName() + " » et TOUT son contenu seront supprimés définitivement."
                        : "« " + f.getName() + " » sera supprimé définitivement."))
                .setPositiveButton("Supprimer", (d, w) -> {
                    progress.setVisibility(View.VISIBLE);
                    bg.execute(() -> {
                        boolean ok = FileUtil.deleteRecursive(f);
                        ui.post(() -> {
                            progress.setVisibility(View.GONE);
                            thumbs.forget(f);
                            toast(ok ? "Supprimé" : "Suppression incomplète");
                            if (searchMode) {
                                items.remove(f);
                                adapter.notifyDataSetChanged();
                            } else refresh();
                        });
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void startMove(File f) {
        moveSource = f;
        moveText.setText("Déplacer « " + f.getName() + " » : ouvrez le dossier de destination puis « Coller ici »");
        moveBar.setVisibility(View.VISIBLE);
        if (searchMode) exitSearch();
    }

    private void cancelMove() {
        moveSource = null;
        moveBar.setVisibility(View.GONE);
    }

    private void doPaste() {
        if (moveSource == null) { cancelMove(); return; }
        if (searchMode) { toast("Ouvrez un dossier (quittez la recherche)"); return; }
        final File src = moveSource;
        final File dest = new File(currentDir, src.getName());
        if (!src.exists()) { toast("L'élément n'existe plus"); cancelMove(); return; }
        if (dest.exists()) { toast("Un élément du même nom existe déjà ici"); return; }
        if (src.isDirectory() && (currentDir.getAbsolutePath() + "/").startsWith(src.getAbsolutePath() + "/")) {
            toast("Impossible de déplacer un dossier dans lui-même");
            return;
        }
        progress.setVisibility(View.VISIBLE);
        bg.execute(() -> {
            boolean ok = src.renameTo(dest) || FileUtil.copyThenDelete(src, dest);
            ui.post(() -> {
                progress.setVisibility(View.GONE);
                thumbs.forget(src);
                cancelMove();
                toast(ok ? "Déplacé" : "Échec du déplacement");
                refresh();
            });
        });
    }

    // ------------------------------------------------------------------ grille

    private static class SquareFrame extends FrameLayout {
        SquareFrame(Context c) { super(c); }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
        }
    }

    private static class Cell {
        LinearLayout root;
        ImageView image;
        TextView play;
        TextView name;
    }

    private class GridAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            Cell c;
            if (convertView == null) {
                c = new Cell();
                c.root = new LinearLayout(MainActivity.this);
                c.root.setOrientation(LinearLayout.VERTICAL);
                SquareFrame sq = new SquareFrame(MainActivity.this);
                sq.setBackgroundColor(0xFF242424);
                c.image = new ImageView(MainActivity.this);
                sq.addView(c.image, new FrameLayout.LayoutParams(-1, -1));
                c.play = new TextView(MainActivity.this);
                c.play.setText("▶");
                c.play.setTextColor(Color.WHITE);
                c.play.setTextSize(18);
                c.play.setGravity(Gravity.CENTER);
                c.play.setBackgroundColor(0x88000000);
                FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER);
                sq.addView(c.play, pl);
                c.root.addView(sq, new LinearLayout.LayoutParams(-1, -2));
                c.name = new TextView(MainActivity.this);
                c.name.setTextColor(0xFFE0E0E0);
                c.name.setTextSize(11);
                c.name.setMaxLines(2);
                c.name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                c.name.setPadding(dp(2), dp(2), dp(2), dp(4));
                c.root.addView(c.name, new LinearLayout.LayoutParams(-1, -2));
                c.root.setTag(c);
                convertView = c.root;
            } else {
                c = (Cell) convertView.getTag();
            }
            File f = items.get(pos);
            c.name.setText(f.getName());
            if (f.isDirectory()) {
                c.image.setTag(null);
                c.image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                c.image.setImageResource(R.drawable.ic_folder);
                c.play.setVisibility(View.GONE);
            } else {
                c.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                c.play.setVisibility(FileUtil.isVideo(f) ? View.VISIBLE : View.GONE);
                thumbs.load(f, c.image);
            }
            convertView.setAlpha(moveSource != null && f.equals(moveSource) ? 0.4f : 1f);
            return convertView;
        }
    }
}
