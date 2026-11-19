package com.galerie.app;

import android.Manifest;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // onglets
    private static final int TAB_PHOTOS = 0, TAB_ALBUMS = 1;
    // types de lignes de la grille
    private static final int T_HEADER = 0, T_MEDIA = 1, T_ALBUM = 2;
    private static final int SPAN = 12;
    // filtres
    private static final int F_ALL = 0, F_PHOTOS = 1, F_VIDEOS = 2;

    private static class Row {
        final int type;
        String text;
        MediaItem media;
        MediaRepo.Album album;

        Row(int type) { this.type = type; }
    }

    private int tab = TAB_PHOTOS;
    private String openAlbum = null;
    private String query = "";
    private int filter = F_ALL;
    private int cols = 4;
    private final int[] savedScroll = new int[2];

    private MediaRepo repo;
    private ThumbLoader thumbs;
    private final List<Row> rows = new ArrayList<>();
    private final List<MediaItem> visibleMedia = new ArrayList<>();
    private final Set<MediaItem> selected = new LinkedHashSet<>();
    private ActionMode actionMode;

    private RecyclerView rv;
    private GridLayoutManager glm;
    private GridAdapter adapter;
    private LinearLayout chipsRow;
    private final TextView[] chips = new TextView[3];
    private TextView navPhotos, navAlbums;
    private TextView emptyView;
    private ProgressBar progress;
    private LinearLayout permissionView;
    private View contentView;
    private MenuItem searchItem;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private String pendingQuery = "";
    private final Runnable applySearch = () -> {
        query = pendingQuery;
        rebuildRows();
        glm.scrollToPosition(0);
    };

    private final SimpleDateFormat fmtDay = new SimpleDateFormat("EEEE d MMMM", Locale.FRANCE);
    private final SimpleDateFormat fmtDayYear = new SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE);

    // ================================================================== cycle de vie

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        repo = MediaRepo.get();
        thumbs = new ThumbLoader(this);
        cols = prefs().getInt("cols", 4);
        if (state != null) {
            tab = state.getInt("tab", TAB_PHOTOS);
            openAlbum = state.getString("album");
            filter = state.getInt("filter", F_ALL);
        }
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasPermission()) {
            permissionView.setVisibility(View.VISIBLE);
            contentView.setVisibility(View.GONE);
            return;
        }
        permissionView.setVisibility(View.GONE);
        contentView.setVisibility(View.VISIBLE);
        if (!repo.loaded) reload();
        else if (repo.dirty) {
            repo.dirty = false;
            rebuildRows();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        out.putString("album", openAlbum);
        out.putInt("filter", filter);
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
        if (searchItem != null && searchItem.isActionViewExpanded()) {
            searchItem.collapseActionView();
            return;
        }
        if (openAlbum != null) {
            closeAlbum();
            return;
        }
        super.onBackPressed();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("galerie", MODE_PRIVATE);
    }

    private void reload() {
        progress.setVisibility(View.VISIBLE);
        final Context app = getApplicationContext();
        bg.execute(() -> {
            List<MediaItem> list = MediaRepo.scan(app);
            ui.post(() -> {
                repo.set(app, list);
                progress.setVisibility(View.GONE);
                rebuildRows();
            });
        });
    }

    // ================================================================== interface

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF121212);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        contentView = main;

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        main.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));

        // filtres Tout / Photos / Vidéos
        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(dp(8), dp(6), dp(8), dp(6));
        String[] labels = {"Tout", "📷 Photos", "🎬 Vidéos"};
        for (int i = 0; i < 3; i++) {
            final int f = i;
            TextView c = new TextView(this);
            c.setText(labels[i]);
            c.setTextSize(14);
            c.setPadding(dp(16), dp(7), dp(16), dp(7));
            c.setOnClickListener(v -> {
                filter = f;
                rebuildRows();
                glm.scrollToPosition(0);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            chipsRow.addView(c, lp);
            chips[i] = c;
        }
        main.addView(chipsRow, new LinearLayout.LayoutParams(-1, -2));

        // grille
        FrameLayout gridBox = new FrameLayout(this);
        rv = new RecyclerView(this);
        glm = new GridLayoutManager(this, SPAN);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (position < 0 || position >= rows.size()) return SPAN;
                int t = rows.get(position).type;
                if (t == T_MEDIA) return SPAN / cols;
                if (t == T_ALBUM) return SPAN / 2;
                return SPAN;
            }
        });
        rv.setLayoutManager(glm);
        adapter = new GridAdapter();
        rv.setAdapter(adapter);
        rv.setPadding(dp(2), 0, dp(2), dp(8));
        rv.setClipToPadding(false);
        rv.setItemAnimator(null);
        gridBox.addView(rv, new FrameLayout.LayoutParams(-1, -1));

        emptyView = new TextView(this);
        emptyView.setTextColor(0xFF9E9E9E);
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(32), 0, dp(32), 0);
        emptyView.setVisibility(View.GONE);
        gridBox.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
        main.addView(gridBox, new LinearLayout.LayoutParams(-1, 0, 1));

        // barre du bas : Photos | Albums
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(0xFF1A1A1A);
        navPhotos = navButton("🖼️\nPhotos", v -> setTab(TAB_PHOTOS));
        navAlbums = navButton("📁\nAlbums", v -> setTab(TAB_ALBUMS));
        nav.addView(navPhotos, new LinearLayout.LayoutParams(0, dp(60), 1));
        nav.addView(navAlbums, new LinearLayout.LayoutParams(0, dp(60), 1));
        main.addView(nav, new LinearLayout.LayoutParams(-1, -2));

        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        // écran d'autorisation
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
        root.addView(permissionView, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    private TextView navButton(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(12);
        t.setOnClickListener(l);
        return t;
    }

    private void styleChrome() {
        boolean albumsList = tab == TAB_ALBUMS && openAlbum == null && query.isEmpty();
        chipsRow.setVisibility(albumsList ? View.GONE : View.VISIBLE);
        for (int i = 0; i < 3; i++) {
            GradientDrawable bgd = new GradientDrawable();
            bgd.setCornerRadius(dp(18));
            if (i == filter) {
                bgd.setColor(0xFF42A5F5);
                chips[i].setTextColor(Color.BLACK);
            } else {
                bgd.setColor(0x00000000);
                bgd.setStroke(dp(1), 0xFF555555);
                chips[i].setTextColor(0xFFE0E0E0);
            }
            chips[i].setBackground(bgd);
        }
        boolean photosActive = tab == TAB_PHOTOS;
        navPhotos.setTextColor(photosActive ? 0xFF42A5F5 : 0xFF9E9E9E);
        navAlbums.setTextColor(!photosActive ? 0xFF42A5F5 : 0xFF9E9E9E);
        navPhotos.setTypeface(null, photosActive ? Typeface.BOLD : Typeface.NORMAL);
        navAlbums.setTypeface(null, !photosActive ? Typeface.BOLD : Typeface.NORMAL);

        ActionBar ab = getActionBar();
        if (ab != null) {
            MediaRepo.Album a = openAlbum != null ? repo.album(openAlbum) : null;
            if (!query.isEmpty()) ab.setTitle("Recherche");
            else if (a != null) ab.setTitle(a.name);
            else ab.setTitle(tab == TAB_PHOTOS ? "Photos" : "Albums");
            ab.setSubtitle(a != null && query.isEmpty() ? a.countLabel() : null);
            ab.setDisplayHomeAsUpEnabled(openAlbum != null);
        }
    }

    // ================================================================== contenu

    private boolean passFilter(MediaItem m) {
        return filter == F_ALL || (filter == F_VIDEOS) == m.video;
    }

    private void addHeader(String text) {
        Row r = new Row(T_HEADER);
        r.text = text;
        rows.add(r);
    }

    private void rebuildRows() {
        rows.clear();
        visibleMedia.clear();
        if (openAlbum != null && repo.album(openAlbum) == null) openAlbum = null;
        String q = query.trim().toLowerCase(Locale.ROOT);
        String empty;

        if (!q.isEmpty()) {
            List<MediaRepo.Album> found = new ArrayList<>();
            for (MediaRepo.Album a : repo.albums) if (a.name.toLowerCase(Locale.ROOT).contains(q)) found.add(a);
            if (!found.isEmpty()) {
                addHeader("Albums (" + found.size() + ")");
                for (MediaRepo.Album a : found) {
                    Row r = new Row(T_ALBUM);
                    r.album = a;
                    rows.add(r);
                }
            }
            for (MediaItem m : repo.all) {
                if (passFilter(m) && m.name().toLowerCase(Locale.ROOT).contains(q)) visibleMedia.add(m);
            }
            if (!visibleMedia.isEmpty()) {
                addHeader("Photos et vidéos (" + visibleMedia.size() + ")");
                for (MediaItem m : visibleMedia) {
                    Row r = new Row(T_MEDIA);
                    r.media = m;
                    rows.add(r);
                }
            }
            empty = "Aucun résultat pour « " + query.trim() + " »";
        } else if (tab == TAB_ALBUMS && openAlbum == null) {
            for (MediaRepo.Album a : repo.albums) {
                Row r = new Row(T_ALBUM);
                r.album = a;
                rows.add(r);
            }
            empty = "Aucun album";
        } else {
            List<MediaItem> src = openAlbum != null ? repo.album(openAlbum).items : repo.all;
            for (MediaItem m : src) if (passFilter(m)) visibleMedia.add(m);
            addDateGrouped(visibleMedia);
            empty = filter == F_VIDEOS ? "Aucune vidéo" : filter == F_PHOTOS ? "Aucune photo" : "Aucune photo ni vidéo";
        }

        adapter.notifyDataSetChanged();
        boolean isEmpty = rows.isEmpty() && repo.loaded;
        emptyView.setText(empty);
        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        styleChrome();
    }

    private void addDateGrouped(List<MediaItem> list) {
        Calendar cal = Calendar.getInstance();
        int todayKey = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR);
        int thisYear = cal.get(Calendar.YEAR);
        cal.add(Calendar.DAY_OF_YEAR, -1);
        int yesterdayKey = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR);
        int lastKey = -1;
        for (MediaItem m : list) {
            cal.setTimeInMillis(m.date);
            int key = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR);
            if (key != lastKey) {
                lastKey = key;
                String label;
                if (key == todayKey) label = "Aujourd'hui";
                else if (key == yesterdayKey) label = "Hier";
                else {
                    label = (cal.get(Calendar.YEAR) == thisYear ? fmtDay : fmtDayYear).format(cal.getTime());
                    label = label.substring(0, 1).toUpperCase(Locale.FRANCE) + label.substring(1);
                }
                addHeader(label);
            }
            Row r = new Row(T_MEDIA);
            r.media = m;
            rows.add(r);
        }
    }

    // ================================================================== navigation

    private void setTab(int t) {
        if (actionMode != null) actionMode.finish();
        if (searchItem != null && searchItem.isActionViewExpanded()) searchItem.collapseActionView();
        if (t == tab && openAlbum == null) {
            glm.scrollToPosition(0);
            return;
        }
        if (openAlbum == null) savedScroll[tab] = glm.findFirstVisibleItemPosition();
        openAlbum = null;
        tab = t;
        rebuildRows();
        glm.scrollToPositionWithOffset(Math.max(0, savedScroll[t]), 0);
    }

    private void openAlbumView(MediaRepo.Album a) {
        if (searchItem != null && searchItem.isActionViewExpanded()) searchItem.collapseActionView();
        if (tab == TAB_ALBUMS) savedScroll[TAB_ALBUMS] = glm.findFirstVisibleItemPosition();
        tab = TAB_ALBUMS;
        openAlbum = a.path;
        rebuildRows();
        glm.scrollToPosition(0);
    }

    private void closeAlbum() {
        if (actionMode != null) actionMode.finish();
        openAlbum = null;
        rebuildRows();
        glm.scrollToPositionWithOffset(Math.max(0, savedScroll[TAB_ALBUMS]), 0);
    }

    private void openViewer(List<MediaItem> list, int index, boolean randomMode) {
        if (list.isEmpty()) {
            Toast.makeText(this, "Aucune photo ou vidéo", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<String> paths = new ArrayList<>(list.size());
        for (MediaItem m : list) paths.add(m.path);
        ViewerActivity.sPaths = paths;
        Intent i = new Intent(this, ViewerActivity.class);
        i.putExtra("index", index);
        i.putExtra("random", randomMode);
        startActivity(i);
    }

    private void playRandom() {
        List<MediaItem> list = new ArrayList<>(visibleMedia);
        if (list.isEmpty()) for (MediaItem m : repo.all) if (passFilter(m)) list.add(m);
        if (list.isEmpty()) {
            toast("Aucune photo ou vidéo");
            return;
        }
        openViewer(list, random.nextInt(list.size()), true);
    }

    // ================================================================== menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        searchItem = menu.add(0, 1, 0, "Chercher");
        searchItem.setIcon(android.R.drawable.ic_menu_search);
        searchItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
        final SearchView sv = new SearchView(this);
        sv.setQueryHint("Photo, vidéo ou album…");
        sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String q) {
                sv.clearFocus();
                ui.removeCallbacks(applySearch);
                pendingQuery = q;
                applySearch.run();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String q) {
                pendingQuery = q;
                ui.removeCallbacks(applySearch);
                ui.postDelayed(applySearch, 250);
                return true;
            }
        });
        searchItem.setActionView(sv);
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) { return true; }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                ui.removeCallbacks(applySearch);
                if (!query.isEmpty()) {
                    query = "";
                    rebuildRows();
                }
                return true;
            }
        });

        menu.add(0, 2, 0, "🔀").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, 3, 0, "Lecture aléatoire");
        menu.add(0, 4, 0, "Nouvel album");
        menu.add(0, 5, 0, "Taille de la grille");
        menu.add(0, 6, 0, "Actualiser");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            closeAlbum();
            return true;
        }
        if (!hasPermission() && id != 1) {
            requestPermission();
            return true;
        }
        switch (id) {
            case 2:
            case 3: playRandom(); return true;
            case 4: createAlbum(); return true;
            case 5: chooseColumns(); return true;
            case 6: reload(); return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void chooseColumns() {
        final int[] options = {3, 4, 6};
        new AlertDialog.Builder(this)
                .setTitle("Photos par ligne")
                .setItems(new String[]{"3 (grandes)", "4 (normal)", "6 (petites)"}, (d, w) -> {
                    cols = options[w];
                    prefs().edit().putInt("cols", cols).apply();
                    adapter.notifyDataSetChanged();
                })
                .show();
    }

    // ================================================================== permissions

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

    // ================================================================== sélection multiple

    private void toggle(MediaItem m, int pos) {
        if (!selected.remove(m)) selected.add(m);
        if (selected.isEmpty()) {
            if (actionMode != null) actionMode.finish();
        } else if (actionMode != null) {
            actionMode.setTitle(selected.size() + " sélectionné(s)");
            actionMode.invalidate();
        }
        adapter.notifyItemChanged(pos);
    }

    private void startSelection(MediaItem first, int pos) {
        actionMode = startActionMode(new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(0, 1, 0, "Supprimer").setIcon(android.R.drawable.ic_menu_delete)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                menu.add(0, 2, 0, "Déplacer").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
                menu.add(0, 3, 0, "Renommer").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
                menu.add(0, 4, 0, "Tout sélectionner");
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                MenuItem r = menu.findItem(3);
                if (r != null) r.setVisible(selected.size() == 1);
                return true;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                switch (item.getItemId()) {
                    case 1: deleteMedia(new ArrayList<>(selected)); return true;
                    case 2: chooseDestination(dir -> moveMedia(new ArrayList<>(selected), dir)); return true;
                    case 3: if (selected.size() == 1) renameMedia(selected.iterator().next()); return true;
                    case 4:
                        selected.addAll(visibleMedia);
                        mode.setTitle(selected.size() + " sélectionné(s)");
                        mode.invalidate();
                        adapter.notifyDataSetChanged();
                        return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                actionMode = null;
                selected.clear();
                adapter.notifyDataSetChanged();
            }
        });
        toggle(first, pos);
    }

    private void endSelection() {
        if (actionMode != null) actionMode.finish();
    }

    // ================================================================== opérations

    private interface NameCallback { void onName(String name); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void askName(String title, String initial, boolean isFile, NameCallback cb) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(initial);
        int dot = initial.lastIndexOf('.');
        if (isFile && dot > 0) et.setSelection(0, dot);
        else et.selectAll();
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!FileUtil.isValidName(n)) toast("Nom invalide");
                    else cb.onName(n);
                })
                .setNegativeButton("Annuler", null)
                .show();
        et.requestFocus();
    }

    @SuppressWarnings("deprecation")
    private File picturesDir() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
    }

    private void createAlbum() {
        askName("Nouvel album", "", false, n -> {
            File d = new File(picturesDir(), n);
            if (d.exists()) { toast("Cet album existe déjà"); return; }
            if (!d.mkdirs()) { toast("Impossible de créer l'album"); return; }
            MediaRepo.addEmptyAlbum(this, d.getAbsolutePath());
            repo.rebuildAlbums(this);
            toast("Album créé. Pour le remplir : appui long sur des photos → Déplacer");
            setTab(TAB_ALBUMS);
            rebuildRows();
        });
    }

    /** Choix d'un album de destination (ou nouvel album / autre dossier). */
    private void chooseDestination(FolderPicker.Callback cb) {
        final List<MediaRepo.Album> albums = new ArrayList<>(repo.albums);
        Collections.sort(albums, (a, b) -> FileUtil.naturalCompare(a.name, b.name));
        String[] labels = new String[albums.size() + 2];
        labels[0] = "➕  Nouvel album…";
        labels[1] = "📂  Parcourir les dossiers…";
        for (int i = 0; i < albums.size(); i++) labels[i + 2] = "🖼️  " + albums.get(i).name + "   (" + albums.get(i).items.size() + ")";
        new AlertDialog.Builder(this)
                .setTitle("Déplacer vers…")
                .setItems(labels, (d, w) -> {
                    if (w == 0) {
                        askName("Nouvel album", "", false, n -> {
                            File dir = new File(picturesDir(), n);
                            if (!dir.isDirectory() && !dir.mkdirs()) { toast("Impossible de créer l'album"); return; }
                            cb.onPicked(dir);
                        });
                    } else if (w == 1) {
                        FolderPicker.show(this, Environment.getExternalStorageDirectory(), cb);
                    } else {
                        cb.onPicked(new File(albums.get(w - 2).path));
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void deleteMedia(final List<MediaItem> list) {
        if (list.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Supprimer ?")
                .setMessage(list.size() == 1 ? "« " + list.get(0).name() + " » sera supprimé définitivement."
                        : list.size() + " éléments seront supprimés définitivement.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    progress.setVisibility(View.VISIBLE);
                    final Context app = getApplicationContext();
                    bg.execute(() -> {
                        List<MediaItem> done = new ArrayList<>();
                        for (MediaItem m : list) {
                            File f = new File(m.path);
                            if (!f.exists() || f.delete()) {
                                done.add(m);
                                MediaRepo.deleteFromMediaStore(app, m);
                            }
                        }
                        ui.post(() -> {
                            progress.setVisibility(View.GONE);
                            repo.removed(app, done);
                            for (MediaItem m : done) thumbs.forget(m.path);
                            endSelection();
                            rebuildRows();
                            toast(done.size() == list.size() ? "Supprimé" : (list.size() - done.size()) + " élément(s) non supprimé(s)");
                        });
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void moveMedia(final List<MediaItem> list, final File dir) {
        progress.setVisibility(View.VISIBLE);
        final Context app = getApplicationContext();
        bg.execute(() -> {
            final List<MediaItem> movedItems = new ArrayList<>();
            final List<String> newPaths = new ArrayList<>();
            final List<String> scanPaths = new ArrayList<>();
            int skipped = 0;
            for (MediaItem m : list) {
                File src = new File(m.path);
                if (dir.getAbsolutePath().equals(src.getParent())) { skipped++; continue; }
                File dest = FileUtil.uniqueFile(dir, src.getName());
                if (src.renameTo(dest) || FileUtil.copyThenDelete(src, dest)) {
                    movedItems.add(m);
                    newPaths.add(dest.getAbsolutePath());
                    scanPaths.add(m.path);
                    scanPaths.add(dest.getAbsolutePath());
                }
            }
            final int fails = list.size() - movedItems.size() - skipped;
            ui.post(() -> {
                progress.setVisibility(View.GONE);
                for (int i = 0; i < movedItems.size(); i++) {
                    thumbs.forget(movedItems.get(i).path);
                    repo.moved(movedItems.get(i), newPaths.get(i));
                }
                repo.rebuildAlbums(app);
                MediaRepo.scan(app, scanPaths);
                endSelection();
                rebuildRows();
                toast(movedItems.size() + " déplacé(s) vers « " + dir.getName() + " »" + (fails > 0 ? " — " + fails + " échec(s)" : ""));
            });
        });
    }

    private void renameMedia(final MediaItem m) {
        askName("Renommer", m.name(), true, n -> {
            File src = new File(m.path);
            File dest = new File(src.getParentFile(), n);
            if (dest.exists()) { toast("Un fichier porte déjà ce nom"); return; }
            if (!src.renameTo(dest)) { toast("Échec du renommage"); return; }
            String old = m.path;
            thumbs.forget(old);
            repo.moved(m, dest.getAbsolutePath());
            List<String> scan = new ArrayList<>();
            scan.add(old);
            scan.add(m.path);
            MediaRepo.scan(this, scan);
            endSelection();
            rebuildRows();
            toast("Renommé");
        });
    }

    // ---------------------------------------------------------------- albums

    private void albumMenu(final MediaRepo.Album a) {
        new AlertDialog.Builder(this)
                .setTitle(a.name)
                .setItems(new String[]{"✏️  Renommer l'album", "📦  Déplacer l'album…", "🗑️  Supprimer l'album"}, (d, w) -> {
                    if (w == 0) renameAlbum(a);
                    else if (w == 1) moveAlbum(a);
                    else deleteAlbum(a);
                })
                .show();
    }

    private void afterFolderChange(String oldDir, String newDir) {
        List<String> touched = repo.folderChanged(this, oldDir, newDir);
        MediaRepo.scan(this, touched);
        if (newDir != null && repo.album(newDir) == null && new File(newDir).isDirectory()) {
            MediaRepo.addEmptyAlbum(this, newDir);
            repo.rebuildAlbums(this);
        }
        if (openAlbum != null && (openAlbum.equals(oldDir) || openAlbum.startsWith(oldDir + "/"))) {
            openAlbum = newDir == null ? null : newDir + openAlbum.substring(oldDir.length());
        }
        rebuildRows();
    }

    private void renameAlbum(final MediaRepo.Album a) {
        askName("Renommer l'album", a.name, false, n -> {
            File old = new File(a.path);
            File dest = new File(old.getParentFile(), n);
            if (dest.exists()) { toast("Un dossier porte déjà ce nom"); return; }
            if (!old.renameTo(dest)) { toast("Échec du renommage"); return; }
            afterFolderChange(a.path, dest.getAbsolutePath());
            toast("Album renommé");
        });
    }

    private void moveAlbum(final MediaRepo.Album a) {
        final File old = new File(a.path);
        FolderPicker.show(this, old.getParentFile(), dir -> {
            String target = dir.getAbsolutePath();
            if (target.equals(a.path) || target.startsWith(a.path + "/")) { toast("Impossible de déplacer un album dans lui-même"); return; }
            final File dest = new File(dir, old.getName());
            if (dest.exists()) { toast("Un dossier du même nom existe déjà ici"); return; }
            progress.setVisibility(View.VISIBLE);
            bg.execute(() -> {
                boolean ok = old.renameTo(dest) || FileUtil.copyThenDelete(old, dest);
                ui.post(() -> {
                    progress.setVisibility(View.GONE);
                    if (ok) {
                        afterFolderChange(a.path, dest.getAbsolutePath());
                        toast("Album déplacé");
                    } else toast("Échec du déplacement");
                });
            });
        });
    }

    private void deleteAlbum(final MediaRepo.Album a) {
        new AlertDialog.Builder(this)
                .setTitle("Supprimer « " + a.name + " » ?")
                .setMessage("Contenu : " + a.countLabel() + ".\n\n"
                        + "• « Dossier entier » supprime le dossier et TOUT ce qu'il contient (y compris sous-dossiers et autres fichiers).\n"
                        + "• « Photos et vidéos » supprime seulement les médias de cet album.")
                .setPositiveButton("Dossier entier", (d, w) -> {
                    progress.setVisibility(View.VISIBLE);
                    final Context app = getApplicationContext();
                    bg.execute(() -> {
                        for (MediaItem m : new ArrayList<>(a.items)) MediaRepo.deleteFromMediaStore(app, m);
                        boolean ok = FileUtil.deleteRecursive(new File(a.path));
                        ui.post(() -> {
                            progress.setVisibility(View.GONE);
                            afterFolderChange(a.path, null);
                            toast(ok ? "Album supprimé" : "Suppression incomplète");
                        });
                    });
                })
                .setNeutralButton("Photos et vidéos", (d, w) -> {
                    List<MediaItem> list = new ArrayList<>(a.items);
                    if (list.isEmpty()) { toast("L'album est déjà vide"); return; }
                    deleteMedia(list);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ================================================================== grille

    private static class SquareFrame extends FrameLayout {
        SquareFrame(Context c) { super(c); }

        @Override
        protected void onMeasure(int w, int h) { super.onMeasure(w, w); }
    }

    private static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView text;
        HeaderVH(TextView v) { super(v); text = v; }
    }

    private static class MediaVH extends RecyclerView.ViewHolder {
        ImageView image;
        TextView badge, check;
        View dim;
        MediaVH(View v) { super(v); }
    }

    private static class AlbumVH extends RecyclerView.ViewHolder {
        ImageView image;
        TextView name, count;
        AlbumVH(View v) { super(v); }
    }

    private class GridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) { return rows.get(position).type; }

        @Override
        public int getItemCount() { return rows.size(); }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            if (type == T_HEADER) {
                TextView t = new TextView(MainActivity.this);
                t.setTextColor(Color.WHITE);
                t.setTextSize(15);
                t.setTypeface(null, Typeface.BOLD);
                t.setPadding(dp(10), dp(16), dp(10), dp(8));
                t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new HeaderVH(t);
            }
            if (type == T_ALBUM) {
                LinearLayout box = new LinearLayout(MainActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(6), dp(6), dp(6), dp(10));
                box.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                SquareFrame sq = new SquareFrame(MainActivity.this);
                GradientDrawable g = new GradientDrawable();
                g.setColor(0xFF242424);
                g.setCornerRadius(dp(14));
                sq.setBackground(g);
                sq.setClipToOutline(true);
                AlbumVH vh = new AlbumVH(box);
                vh.image = new ImageView(MainActivity.this);
                sq.addView(vh.image, new FrameLayout.LayoutParams(-1, -1));
                box.addView(sq, new LinearLayout.LayoutParams(-1, -2));
                vh.name = new TextView(MainActivity.this);
                vh.name.setTextColor(Color.WHITE);
                vh.name.setTextSize(14);
                vh.name.setTypeface(null, Typeface.BOLD);
                vh.name.setSingleLine(true);
                vh.name.setEllipsize(TextUtils.TruncateAt.END);
                vh.name.setPadding(dp(2), dp(6), dp(2), 0);
                box.addView(vh.name);
                vh.count = new TextView(MainActivity.this);
                vh.count.setTextColor(0xFF9E9E9E);
                vh.count.setTextSize(12);
                vh.count.setPadding(dp(2), 0, dp(2), 0);
                box.addView(vh.count);
                box.setOnClickListener(v -> {
                    int p = vh.getBindingAdapterPosition();
                    if (p >= 0 && p < rows.size()) openAlbumView(rows.get(p).album);
                });
                box.setOnLongClickListener(v -> {
                    int p = vh.getBindingAdapterPosition();
                    if (p >= 0 && p < rows.size()) albumMenu(rows.get(p).album);
                    return true;
                });
                return vh;
            }
            // T_MEDIA
            SquareFrame sq = new SquareFrame(MainActivity.this);
            sq.setPadding(dp(1), dp(1), dp(1), dp(1));
            sq.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            MediaVH vh = new MediaVH(sq);
            vh.image = new ImageView(MainActivity.this);
            vh.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            vh.image.setBackgroundColor(0xFF242424);
            sq.addView(vh.image, new FrameLayout.LayoutParams(-1, -1));
            vh.dim = new View(MainActivity.this);
            vh.dim.setBackgroundColor(0x6642A5F5);
            sq.addView(vh.dim, new FrameLayout.LayoutParams(-1, -1));
            vh.badge = new TextView(MainActivity.this);
            vh.badge.setTextColor(Color.WHITE);
            vh.badge.setTextSize(11);
            vh.badge.setShadowLayer(3, 0, 0, Color.BLACK);
            vh.badge.setPadding(dp(4), dp(2), dp(5), dp(3));
            sq.addView(vh.badge, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));
            vh.check = new TextView(MainActivity.this);
            vh.check.setGravity(Gravity.CENTER);
            vh.check.setTextColor(Color.WHITE);
            vh.check.setTextSize(13);
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.START);
            cl.setMargins(dp(5), dp(5), 0, 0);
            sq.addView(vh.check, cl);
            sq.setOnClickListener(v -> {
                int p = vh.getBindingAdapterPosition();
                if (p < 0 || p >= rows.size()) return;
                MediaItem m = rows.get(p).media;
                if (actionMode != null) toggle(m, p);
                else openViewer(visibleMedia, Math.max(0, visibleMedia.indexOf(m)), false);
            });
            sq.setOnLongClickListener(v -> {
                int p = vh.getBindingAdapterPosition();
                if (p < 0 || p >= rows.size()) return true;
                MediaItem m = rows.get(p).media;
                if (actionMode == null) startSelection(m, p);
                else toggle(m, p);
                return true;
            });
            return vh;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row r = rows.get(position);
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).text.setText(r.text);
            } else if (holder instanceof AlbumVH) {
                AlbumVH vh = (AlbumVH) holder;
                vh.name.setText(r.album.name);
                vh.count.setText(r.album.countLabel());
                MediaItem cover = r.album.cover();
                if (cover != null) {
                    vh.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    thumbs.load(cover, vh.image);
                } else {
                    vh.image.setTag(null);
                    vh.image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    vh.image.setImageResource(R.drawable.ic_folder);
                }
            } else if (holder instanceof MediaVH) {
                MediaVH vh = (MediaVH) holder;
                MediaItem m = r.media;
                thumbs.load(m, vh.image);
                if (m.video) {
                    vh.badge.setVisibility(View.VISIBLE);
                    vh.badge.setText(m.duration > 0 ? "▶ " + FileUtil.formatTime((int) m.duration) : "▶");
                } else vh.badge.setVisibility(View.GONE);
                boolean sel = selected.contains(m);
                vh.dim.setVisibility(sel ? View.VISIBLE : View.GONE);
                if (actionMode != null) {
                    vh.check.setVisibility(View.VISIBLE);
                    GradientDrawable g = new GradientDrawable();
                    g.setShape(GradientDrawable.OVAL);
                    g.setColor(sel ? 0xFF42A5F5 : 0x55000000);
                    g.setStroke(dp(2), Color.WHITE);
                    vh.check.setBackground(g);
                    vh.check.setText(sel ? "✓" : "");
                } else vh.check.setVisibility(View.GONE);
            }
        }
    }
}
