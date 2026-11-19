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
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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
import android.widget.ScrollView;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // onglets (barre du bas)
    private static final int TAB_ALBUMS = 0, TAB_VIDEOS = 1, TAB_PHOTOS = 2, TAB_SETTINGS = 3;
    private static final String[] TAB_NAMES = {"Albums", "Vidéos", "Photos", "Réglages"};
    // types de lignes
    private static final int T_HEADER = 0, T_MEDIA = 1, T_ALBUM = 2;
    private static final int SPAN = 12;
    // filtres (dans un album de l'onglet Albums)
    private static final int F_ALL = 0, F_PHOTOS = 1, F_VIDEOS = 2;
    // tris
    private static final int SORT_DATE = 0, SORT_MODIFIED = 1, SORT_SIZE = 2, SORT_NAME = 3;

    private static class Row {
        final int type;
        String text;
        MediaItem media;
        MediaRepo.Album album;
        MediaItem cover;
        int count;
        long date, modified, size;

        Row(int type) { this.type = type; }
    }

    private int tab = TAB_ALBUMS;
    private String openAlbum = null;
    private String query = "";
    private int filter = F_ALL;
    private int cols = 4;
    private boolean includeVideos = false;  // onglet Photos : inclure les vidéos
    private boolean photosFolders = false;  // onglet Photos : vue par dossiers (sinon grille)
    private boolean videosFolders = true;   // onglet Vidéos : vue par dossiers (sinon grille)
    private boolean thumbsOriginal = true;  // miniatures entières (format original) au lieu de carrées rognées
    private int leftTap = 1;             // 0 = -10 s, 1 = média suivant, 2 = rien
    private int photoDelay = 0;           // secondes avant de passer à l'image suivante (0 = désactivé)
    private boolean randomOn = false;       // mode aléatoire (partagé avec la visionneuse)
    private int sortMode = SORT_DATE;
    private boolean sortRev = false;        // false = sens par défaut du tri choisi
    private final int[] savedScroll = new int[4];

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
    private final LinearLayout[] navItems = new LinearLayout[4];
    private ScrollView settingsView;
    private final TextView[] setRows = new TextView[11];
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
        SharedPreferences p = prefs();
        cols = p.getInt("cols", 4);
        includeVideos = p.getBoolean("inc_video", false);
        photosFolders = p.getBoolean("photos_folders", false);
        videosFolders = p.getBoolean("videos_folders", true);
        randomOn = p.getBoolean("random_on", false);
        photoDelay = p.getInt("photo_delay", 0);
        leftTap = p.getInt("left_tap", 1);
        thumbsOriginal = p.getBoolean("thumbs_original", true);
        sortMode = p.getInt("sort_mode", SORT_DATE);
        sortRev = p.getBoolean("sort_rev", false);
        if (state != null) {
            tab = state.getInt("tab", TAB_ALBUMS);
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
        randomOn = prefs().getBoolean("random_on", false);
        refreshSettings();
        if (!repo.loaded) reload();
        else if (repo.dirty) {
            repo.dirty = false;
            rebuildRows();
        } else rebuildRows();
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
        root.setBackgroundColor(0xFF0F1115);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        contentView = main;

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        main.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));

        // filtres Tout / Photos / Vidéos (dans un album de l'onglet Albums)
        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(dp(8), dp(6), dp(8), dp(6));
        String[] labels = {"Tout", "Photos", "Vidéos"};
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

        // contenu : grille/liste + réglages
        FrameLayout gridBox = new FrameLayout(this);
        rv = new RecyclerView(this);
        glm = new GridLayoutManager(this, SPAN);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (position < 0 || position >= rows.size()) return SPAN;
                return rows.get(position).type == T_MEDIA ? SPAN / cols : SPAN;
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
        android.graphics.drawable.Drawable ed = getResources().getDrawable(R.drawable.ic_empty).mutate();
        ed.setTint(0xFF3A3F49);
        ed.setBounds(0, 0, dp(72), dp(72));
        emptyView.setCompoundDrawables(null, ed, null, null);
        emptyView.setCompoundDrawablePadding(dp(16));
        emptyView.setVisibility(View.GONE);
        gridBox.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));

        buildSettings(gridBox);
        main.addView(gridBox, new LinearLayout.LayoutParams(-1, 0, 1));

        // barre du bas : Albums | Vidéos | Photos | Réglages
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(0xFF171A21);
        nav.setElevation(dp(8));
        String[] navLabels = {"Albums", "Vidéos", "Photos", "Réglages"};
        int[] navIcons = {R.drawable.ic_nav_albums, R.drawable.ic_nav_videos, R.drawable.ic_nav_photos, R.drawable.ic_nav_settings};
        for (int i = 0; i < 4; i++) {
            final int t = i;
            LinearLayout n = new LinearLayout(this);
            n.setOrientation(LinearLayout.VERTICAL);
            n.setGravity(Gravity.CENTER);
            ImageView ic = new ImageView(this);
            ic.setImageResource(navIcons[i]);
            ic.setPadding(dp(18), dp(4), dp(18), dp(4));
            n.addView(ic, new LinearLayout.LayoutParams(dp(64), dp(32)));
            TextView lb = new TextView(this);
            lb.setText(navLabels[i]);
            lb.setTextSize(11);
            lb.setGravity(Gravity.CENTER);
            lb.setPadding(0, dp(2), 0, 0);
            n.addView(lb, new LinearLayout.LayoutParams(-2, -2));
            n.setOnClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); setTab(t); });
            nav.addView(n, new LinearLayout.LayoutParams(0, dp(64), 1));
            navItems[i] = n;
        }
        main.addView(nav, new LinearLayout.LayoutParams(-1, -2));

        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        // écran d'autorisation
        permissionView = new LinearLayout(this);
        permissionView.setOrientation(LinearLayout.VERTICAL);
        permissionView.setGravity(Gravity.CENTER);
        permissionView.setPadding(dp(32), dp(32), dp(32), dp(32));
        permissionView.setBackgroundColor(0xFF0F1115);
        ImageView pic = new ImageView(this);
        pic.setImageResource(R.drawable.ic_nav_photos);
        pic.setColorFilter(0xFF5B9CFF);
        permissionView.addView(pic, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView ptitle = new TextView(this);
        ptitle.setText("Bienvenue dans Galerie");
        ptitle.setTextColor(Color.WHITE);
        ptitle.setTextSize(22);
        ptitle.setTypeface(null, Typeface.BOLD);
        ptitle.setGravity(Gravity.CENTER);
        ptitle.setPadding(0, dp(20), 0, dp(10));
        permissionView.addView(ptitle);
        TextView msg = new TextView(this);
        msg.setText("La galerie a besoin d'accéder à vos fichiers pour afficher, renommer, déplacer et supprimer vos photos et vidéos.");
        msg.setTextColor(0xFFB6BBC4);
        msg.setGravity(Gravity.CENTER);
        msg.setTextSize(16);
        permissionView.addView(msg);
        Button grant = new Button(this);
        grant.setText("Autoriser l'accès");
        grant.setAllCaps(false);
        grant.setTextColor(Color.BLACK);
        grant.setTextSize(16);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(0xFF5B9CFF);
        gb.setCornerRadius(dp(26));
        grant.setBackground(gb);
        grant.setPadding(dp(32), dp(12), dp(32), dp(12));
        grant.setOnClickListener(v -> requestPermission());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(24);
        permissionView.addView(grant, lp);
        root.addView(permissionView, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    private void buildSettings(FrameLayout parent) {
        settingsView = new ScrollView(this);
        settingsView.setBackgroundColor(0xFF0F1115);
        settingsView.setVisibility(View.GONE);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(16));
        View.OnClickListener[] actions = {
                v -> chooseColumns(),
                v -> chooseSort(),
                v -> toggleThumbs(),
                v -> toggleRandom(),
                v -> chooseDelay(),
                v -> chooseLeftTap(),
                v -> { includeVideos = !includeVideos; prefs().edit().putBoolean("inc_video", includeVideos).apply(); refreshSettings(); },
                v -> { photosFolders = !photosFolders; prefs().edit().putBoolean("photos_folders", photosFolders).apply(); refreshSettings(); },
                v -> { videosFolders = !videosFolders; prefs().edit().putBoolean("videos_folders", videosFolders).apply(); refreshSettings(); },
                v -> createAlbum(),
                v -> reload()
        };
        for (int i = 0; i < setRows.length; i++) {
            TextView t = new TextView(this);
            t.setTextColor(Color.WHITE);
            t.setTextSize(16);
            t.setPadding(dp(20), dp(16), dp(20), dp(16));
            GradientDrawable card = new GradientDrawable();
            card.setColor(0xFF171A21);
            card.setCornerRadius(dp(16));
            t.setBackground(card);
            t.setOnClickListener(actions[i]);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.setMargins(dp(12), dp(4), dp(12), dp(4));
            list.addView(t, rl);
            setRows[i] = t;
        }
        settingsView.addView(list);
        parent.addView(settingsView, new FrameLayout.LayoutParams(-1, -1));
        refreshSettings();
    }

    private void setRow(TextView t, String title, String value) {
        SpannableString sp = new SpannableString(title + "\n" + value);
        int st = title.length() + 1;
        sp.setSpan(new ForegroundColorSpan(0xFF9E9E9E), st, sp.length(), 0);
        sp.setSpan(new RelativeSizeSpan(0.85f), st, sp.length(), 0);
        t.setText(sp);
    }

    private void refreshSettings() {
        setRow(setRows[0], "Taille de la grille", cols + " éléments par ligne");
        setRow(setRows[1], "Trier par", sortLabel(sortMode) + " — " + sortDirection(sortMode));
        setRow(setRows[2], "Miniatures", thumbsOriginal ? "Format original (image entière, sans rogner)" : "Carrées (rognées)");
        setRow(setRows[3], "Mode aléatoire", randomOn
                ? "Activé : en faisant défiler et à la fin d'une vidéo, l'élément suivant est tiré au hasard"
                : "Désactivé : suivant = ordre de la liste");
        setRow(setRows[4], "Passage automatique des images", photoDelay == 0
                ? "Désactivé : on change d'image avec ⏭ ou en glissant"
                : "Après " + photoDelay + " secondes, passe au suivant (aléatoire si activé)");
        setRow(setRows[5], "Double-tap à gauche", tapLabel(leftTap));
        setRow(setRows[6], "Vidéos dans l'onglet Photos", includeVideos ? "Incluses" : "Exclues");
        setRow(setRows[7], "Onglet Photos", photosFolders ? "Affiché par dossiers" : "Affiché en grille");
        setRow(setRows[8], "Onglet Vidéos", videosFolders ? "Affiché par dossiers" : "Affiché en grille");
        setRow(setRows[9], "Nouvel album", "Créer un dossier dans Pictures");
        setRow(setRows[10], "Actualiser la bibliothèque", "Relire toutes les photos et vidéos");
    }

    private void toggleThumbs() {
        thumbsOriginal = !thumbsOriginal;
        prefs().edit().putBoolean("thumbs_original", thumbsOriginal).apply();
        refreshSettings();
        adapter.notifyDataSetChanged();
        invalidateOptionsMenu();
    }

    private ImageView.ScaleType thumbScale() {
        return thumbsOriginal ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP;
    }

    private void setRandomOn(boolean on) {
        randomOn = on;
        prefs().edit().putBoolean("random_on", on).apply();
        refreshSettings();
    }

    private void chooseDelay() {
        final int[] values = {0, 5, 10};
        int checked = photoDelay == 5 ? 1 : photoDelay == 10 ? 2 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Passage automatique des images")
                .setSingleChoiceItems(new String[]{"Désactivé", "5 secondes", "10 secondes"}, checked, (d, w) -> {
                    photoDelay = values[w];
                    prefs().edit().putInt("photo_delay", photoDelay).apply();
                    refreshSettings();
                    d.dismiss();
                })
                .setNegativeButton("Fermer", null)
                .show();
    }

    private static String tapLabel(int m) {
        return m == 1 ? "Média suivant (photo et vidéo)" : m == 2 ? "Aucune action" : "Reculer de 10 secondes (vidéo)";
    }

    private void chooseLeftTap() {
        new AlertDialog.Builder(this)
                .setTitle("Double-tap à gauche")
                .setSingleChoiceItems(new String[]{tapLabel(0), tapLabel(1), tapLabel(2)}, leftTap, (d, w) -> {
                    leftTap = w;
                    prefs().edit().putInt("left_tap", w).apply();
                    refreshSettings();
                    d.dismiss();
                })
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void toggleRandom() {
        setRandomOn(!randomOn);
    }

    private void styleChrome() {
        boolean settings = tab == TAB_SETTINGS;
        settingsView.setVisibility(settings ? View.VISIBLE : View.GONE);
        rv.setVisibility(settings ? View.GONE : View.VISIBLE);
        chipsRow.setVisibility(tab == TAB_ALBUMS && openAlbum != null && query.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 3; i++) {
            GradientDrawable bgd = new GradientDrawable();
            bgd.setCornerRadius(dp(18));
            if (i == filter) {
                bgd.setColor(0xFF5B9CFF);
                chips[i].setTextColor(Color.BLACK);
            } else {
                bgd.setColor(0x00000000);
                bgd.setStroke(dp(1), 0xFF3A3F49);
                chips[i].setTextColor(0xFFE0E0E0);
            }
            chips[i].setBackground(bgd);
        }
        for (int i = 0; i < 4; i++) {
            boolean on = i == tab;
            ImageView ic = (ImageView) navItems[i].getChildAt(0);
            TextView lb = (TextView) navItems[i].getChildAt(1);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(16));
            pill.setColor(on ? 0x335B9CFF : 0x00000000);
            ic.setBackground(pill);
            ic.setColorFilter(on ? 0xFF5B9CFF : 0xFF8A8F98);
            lb.setTextColor(on ? 0xFF5B9CFF : 0xFF8A8F98);
            lb.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        ActionBar ab = getActionBar();
        if (ab != null) {
            MediaRepo.Album a = openAlbum != null ? repo.album(openAlbum) : null;
            if (!query.isEmpty()) ab.setTitle("Recherche");
            else if (a != null) ab.setTitle(a.name);
            else ab.setTitle(TAB_NAMES[tab]);
            ab.setSubtitle(a != null && query.isEmpty() ? a.countLabel() : null);
            ab.setDisplayHomeAsUpEnabled(openAlbum != null);
        }
    }

    // ================================================================== contenu

    private boolean passFilter(MediaItem m) {
        if (tab == TAB_VIDEOS) return m.video;
        if (tab == TAB_PHOTOS) return !m.video || includeVideos;
        return filter == F_ALL || (filter == F_VIDEOS) == m.video;
    }

    /** Vue « liste de dossiers » pour l'onglet courant ? */
    private boolean isFolderView() {
        if (openAlbum != null) return false;
        if (tab == TAB_ALBUMS) return true;
        if (tab == TAB_VIDEOS) return videosFolders;
        if (tab == TAB_PHOTOS) return photosFolders;
        return false;
    }

    private void addHeader(String text) {
        Row r = new Row(T_HEADER);
        r.text = text;
        rows.add(r);
    }

    /** Ajoute les dossiers adaptés à l'onglet ; renvoie leur nombre. */
    private int addAlbumRows(String q) {
        List<Row> list = new ArrayList<>();
        for (MediaRepo.Album a : repo.albums) {
            if (!q.isEmpty() && !a.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            int count = 0;
            MediaItem cover = null;
            long date = 0, modified = 0, size = 0;
            for (MediaItem m : a.items) {
                if (passFilter(m)) {
                    count++;
                    if (cover == null) cover = m;
                    date = Math.max(date, m.date);
                    modified = Math.max(modified, m.modified);
                    size += m.size();
                }
            }
            if (count == 0 && tab != TAB_ALBUMS) continue; // les albums vides n'apparaissent que dans « Albums »
            Row r = new Row(T_ALBUM);
            r.album = a;
            r.cover = cover;
            r.count = count;
            r.date = date;
            r.modified = modified;
            r.size = size;
            list.add(r);
        }
        Comparator<Row> c;
        switch (sortMode) {
            case SORT_MODIFIED: c = (x, y) -> Long.compare(y.modified, x.modified); break;
            case SORT_SIZE: c = (x, y) -> Long.compare(y.size, x.size); break;
            case SORT_NAME: c = (x, y) -> FileUtil.naturalCompare(x.album.name, y.album.name); break;
            default: c = (x, y) -> Long.compare(y.date, x.date);
        }
        Collections.sort(list, sortRev ? Collections.reverseOrder(c) : c);
        rows.addAll(list);
        return list.size();
    }

    // ---- tri des photos / vidéos

    private Comparator<MediaItem> mediaCmp() {
        Comparator<MediaItem> c;
        switch (sortMode) {
            case SORT_MODIFIED: c = (a, b) -> Long.compare(b.modified, a.modified); break;
            case SORT_SIZE: c = (a, b) -> Long.compare(b.size(), a.size()); break;
            case SORT_NAME: c = (a, b) -> FileUtil.naturalCompare(a.name(), b.name()); break;
            default: c = (a, b) -> Long.compare(b.date, a.date);
        }
        return sortRev ? Collections.reverseOrder(c) : c;
    }

    private long dateOf(MediaItem m) {
        return sortMode == SORT_MODIFIED ? m.modified : m.date;
    }

    private String sortLabel(int mode) {
        switch (mode) {
            case SORT_MODIFIED: return "Date de modification";
            case SORT_SIZE: return "Taille (stockage)";
            case SORT_NAME: return "Nom (alphabétique)";
            default: return "Date";
        }
    }

    private String sortDirection(int mode) {
        if (mode == SORT_NAME) return sortRev ? "Z → A" : "A → Z";
        if (mode == SORT_SIZE) return sortRev ? "plus petits d'abord" : "plus gros d'abord";
        return sortRev ? "plus anciens d'abord" : "plus récents d'abord";
    }

    private void chooseSort() {
        final int[] modes = {SORT_DATE, SORT_MODIFIED, SORT_SIZE, SORT_NAME};
        String[] items = new String[modes.length];
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            items[i] = sortLabel(modes[i]);
            if (modes[i] == sortMode) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Trier par  (" + sortDirection(sortMode) + ")")
                .setSingleChoiceItems(items, checked, (d, w) -> {
                    if (modes[w] != sortMode) {
                        sortMode = modes[w];
                        sortRev = false; // sens par défaut du nouveau tri
                    }
                    applySort();
                    d.dismiss();
                })
                .setNeutralButton("⇅ Inverser l'ordre", (d, w) -> {
                    sortRev = !sortRev;
                    applySort();
                })
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void applySort() {
        prefs().edit().putInt("sort_mode", sortMode).putBoolean("sort_rev", sortRev).apply();
        refreshSettings();
        rebuildRows();
        glm.scrollToPosition(0);
        toast("Tri : " + sortLabel(sortMode) + " — " + sortDirection(sortMode));
    }

    private void rebuildRows() {
        rows.clear();
        visibleMedia.clear();
        if (openAlbum != null && repo.album(openAlbum) == null) openAlbum = null;
        String empty = "";

        if (tab != TAB_SETTINGS) {
            String q = query.trim().toLowerCase(Locale.ROOT);
            if (!q.isEmpty()) {
                int hi = rows.size();
                addHeader("");
                int n = addAlbumRows(q);
                if (n == 0) rows.remove(hi); else rows.get(hi).text = "Albums (" + n + ")";
                for (MediaItem m : repo.all) {
                    if (passFilter(m) && m.name().toLowerCase(Locale.ROOT).contains(q)) visibleMedia.add(m);
                }
                Collections.sort(visibleMedia, mediaCmp());
                if (!visibleMedia.isEmpty()) {
                    addHeader("Photos et vidéos (" + visibleMedia.size() + ")");
                    for (MediaItem m : visibleMedia) {
                        Row r = new Row(T_MEDIA);
                        r.media = m;
                        rows.add(r);
                    }
                }
                empty = "Aucun résultat pour « " + query.trim() + " »";
            } else if (isFolderView()) {
                addAlbumRows("");
                empty = tab == TAB_VIDEOS ? "Aucun dossier contenant des vidéos"
                        : tab == TAB_PHOTOS ? "Aucun dossier contenant des photos" : "Aucun album";
            } else {
                List<MediaItem> src = openAlbum != null ? repo.album(openAlbum).items : repo.all;
                for (MediaItem m : src) if (passFilter(m)) visibleMedia.add(m);
                Collections.sort(visibleMedia, mediaCmp());
                if (sortMode == SORT_DATE || sortMode == SORT_MODIFIED) addDateGrouped(visibleMedia);
                else {
                    for (MediaItem m : visibleMedia) {
                        Row r = new Row(T_MEDIA);
                        r.media = m;
                        rows.add(r);
                    }
                }
                empty = tab == TAB_VIDEOS ? "Aucune vidéo"
                        : tab == TAB_PHOTOS ? "Aucune photo" : "Aucune photo ni vidéo";
            }
        }

        adapter.notifyDataSetChanged();
        emptyView.setText(empty);
        emptyView.setVisibility(rows.isEmpty() && repo.loaded && tab != TAB_SETTINGS ? View.VISIBLE : View.GONE);
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
            cal.setTimeInMillis(dateOf(m));
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
        filter = F_ALL;
        tab = t;
        rebuildRows();
        invalidateOptionsMenu();
        glm.scrollToPositionWithOffset(Math.max(0, savedScroll[t]), 0);
    }

    private void openAlbumView(MediaRepo.Album a) {
        if (searchItem != null && searchItem.isActionViewExpanded()) searchItem.collapseActionView();
        if (openAlbum == null && query.isEmpty()) savedScroll[tab] = glm.findFirstVisibleItemPosition();
        openAlbum = a.path;
        filter = F_ALL;
        rebuildRows();
        invalidateOptionsMenu();
        glm.scrollToPosition(0);
    }

    private void closeAlbum() {
        if (actionMode != null) actionMode.finish();
        openAlbum = null;
        filter = F_ALL;
        rebuildRows();
        invalidateOptionsMenu();
        glm.scrollToPositionWithOffset(Math.max(0, savedScroll[tab]), 0);
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
        i.putExtra("random", randomOn || randomMode);
        startActivity(i);
    }

    private void playRandom() {
        List<MediaItem> list = new ArrayList<>(visibleMedia);
        if (list.isEmpty()) for (MediaItem m : repo.all) if (passFilter(m)) list.add(m);
        if (list.isEmpty()) {
            toast("Aucune photo ou vidéo");
            return;
        }
        setRandomOn(true);
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

        MenuItem rnd = menu.add(0, 2, 0, "Lecture aléatoire");
        rnd.setIcon(R.drawable.ic_shuffle);
        rnd.getIcon().setTint(Color.WHITE);
        rnd.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, 3, 0, "Sélectionner");
        menu.add(0, 9, 0, "Trier…");
        menu.add(0, 10, 0, "Miniatures");
        menu.add(0, 4, 0, "Vue");
        menu.add(0, 5, 0, "Vidéos");
        menu.add(0, 6, 0, "Nombre de colonnes");
        menu.add(0, 7, 0, "Nouvel album");
        menu.add(0, 8, 0, "Actualiser");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean settings = tab == TAB_SETTINGS;
        boolean folders = query.isEmpty() && isFolderView();
        menu.findItem(1).setVisible(!settings);
        menu.findItem(2).setVisible(!settings);
        menu.findItem(3).setVisible(!settings && !folders);
        menu.findItem(9).setVisible(!settings);
        MenuItem th = menu.findItem(10);
        th.setVisible(!settings);
        th.setTitle(thumbsOriginal ? "Miniatures : carrées (rognées)" : "Miniatures : format original");
        MenuItem view = menu.findItem(4);
        view.setVisible((tab == TAB_PHOTOS || tab == TAB_VIDEOS) && openAlbum == null && query.isEmpty());
        view.setTitle(folders ? "Tout afficher en grille" : "Afficher par dossiers");
        MenuItem vid = menu.findItem(5);
        vid.setVisible(tab == TAB_PHOTOS);
        vid.setTitle(includeVideos ? "Exclure les vidéos" : "Inclure les vidéos");
        menu.findItem(6).setVisible(!settings && !folders);
        menu.findItem(7).setVisible(tab == TAB_ALBUMS);
        menu.findItem(8).setVisible(!settings);
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
            case 2: playRandom(); return true;
            case 3: beginSelection(); return true;
            case 4:
                if (tab == TAB_PHOTOS) {
                    photosFolders = !photosFolders;
                    prefs().edit().putBoolean("photos_folders", photosFolders).apply();
                } else {
                    videosFolders = !videosFolders;
                    prefs().edit().putBoolean("videos_folders", videosFolders).apply();
                }
                refreshSettings();
                rebuildRows();
                invalidateOptionsMenu();
                glm.scrollToPosition(0);
                return true;
            case 5:
                includeVideos = !includeVideos;
                prefs().edit().putBoolean("inc_video", includeVideos).apply();
                refreshSettings();
                rebuildRows();
                invalidateOptionsMenu();
                return true;
            case 6: chooseColumns(); return true;
            case 9: chooseSort(); return true;
            case 10: toggleThumbs(); return true;
            case 7: createAlbum(); return true;
            case 8: reload(); return true;
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
                    refreshSettings();
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
        if (actionMode != null) {
            actionMode.setTitle(selected.isEmpty() ? "Sélectionner" : selected.size() + " sélectionné(s)");
            actionMode.invalidate();
        }
        adapter.notifyItemChanged(pos);
    }

    private void startSelection(MediaItem first, int pos) {
        rv.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        beginSelection();
        toggle(first, pos);
    }

    private void beginSelection() {
        if (actionMode != null) return;
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
                menu.findItem(1).setVisible(!selected.isEmpty());
                menu.findItem(2).setVisible(!selected.isEmpty());
                menu.findItem(3).setVisible(selected.size() == 1);
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
        if (actionMode != null) actionMode.setTitle("Sélectionner");
        adapter.notifyDataSetChanged();
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
        for (int i = 0; i < albums.size(); i++) labels[i + 2] = "" + albums.get(i).name + "   (" + albums.get(i).items.size() + ")";
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
        ImageView more;
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
                t.setTextSize(14);
                t.setTypeface(null, Typeface.BOLD);
                t.setLetterSpacing(0.02f);
                t.setPadding(dp(12), dp(20), dp(12), dp(8));
                t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new HeaderVH(t);
            }
            if (type == T_ALBUM) {
                // ligne de dossier : couverture | nom | nombre | ⋮
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(6), dp(4), dp(6));
                row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                AlbumVH vh = new AlbumVH(row);
                vh.image = new ImageView(MainActivity.this);
                GradientDrawable g = new GradientDrawable();
                g.setColor(0xFF1B1F27);
                g.setCornerRadius(dp(14));
                vh.image.setBackground(g);
                vh.image.setClipToOutline(true);
                row.addView(vh.image, new LinearLayout.LayoutParams(dp(76), dp(76)));
                vh.name = new TextView(MainActivity.this);
                vh.name.setTextColor(Color.WHITE);
                vh.name.setTextSize(17);
                vh.name.setSingleLine(true);
                vh.name.setEllipsize(TextUtils.TruncateAt.END);
                vh.name.setPadding(dp(16), 0, dp(8), 0);
                row.addView(vh.name, new LinearLayout.LayoutParams(0, -2, 1));
                vh.count = new TextView(MainActivity.this);
                vh.count.setTextColor(0xFF8A8F98);
                vh.count.setTextSize(15);
                row.addView(vh.count);
                vh.more = new ImageView(MainActivity.this);
                vh.more.setImageResource(R.drawable.ic_more);
                vh.more.setColorFilter(0xFFB6BBC4);
                vh.more.setPadding(dp(10), dp(12), dp(10), dp(12));
                row.addView(vh.more, new LinearLayout.LayoutParams(dp(44), dp(48)));
                row.setOnClickListener(v -> {
                    int p = vh.getBindingAdapterPosition();
                    if (p >= 0 && p < rows.size()) openAlbumView(rows.get(p).album);
                });
                View.OnClickListener menu = v -> {
                    int p = vh.getBindingAdapterPosition();
                    if (p >= 0 && p < rows.size()) albumMenu(rows.get(p).album);
                };
                row.setOnLongClickListener(v -> { menu.onClick(v); return true; });
                vh.more.setOnClickListener(menu);
                return vh;
            }
            // T_MEDIA
            SquareFrame sq = new SquareFrame(MainActivity.this);
            sq.setPadding(dp(2), dp(2), dp(2), dp(2));
            sq.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            MediaVH vh = new MediaVH(sq);
            vh.image = new ImageView(MainActivity.this);
            vh.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable tb = new GradientDrawable();
            tb.setColor(0xFF1B1F27);
            tb.setCornerRadius(dp(8));
            vh.image.setBackground(tb);
            vh.image.setClipToOutline(true);
            sq.addView(vh.image, new FrameLayout.LayoutParams(-1, -1));
            vh.dim = new View(MainActivity.this);
            GradientDrawable dg = new GradientDrawable();
            dg.setColor(0x665B9CFF);
            dg.setCornerRadius(dp(8));
            dg.setStroke(dp(2), 0xFF5B9CFF);
            vh.dim.setBackground(dg);
            sq.addView(vh.dim, new FrameLayout.LayoutParams(-1, -1));
            vh.badge = new TextView(MainActivity.this);
            vh.badge.setTextColor(Color.WHITE);
            vh.badge.setTextSize(11);
            GradientDrawable bg2 = new GradientDrawable();
            bg2.setColor(0x99000000);
            bg2.setCornerRadius(dp(10));
            vh.badge.setBackground(bg2);
            vh.badge.setPadding(dp(6), dp(2), dp(7), dp(2));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            bl.setMargins(0, 0, dp(6), dp(6));
            sq.addView(vh.badge, bl);
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
                vh.count.setText(String.valueOf(r.count));
                if (r.cover != null) {
                    vh.image.setScaleType(thumbScale());
                    thumbs.load(r.cover, vh.image);
                } else {
                    vh.image.setTag(null);
                    vh.image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    vh.image.setImageResource(R.drawable.ic_folder);
                }
            } else if (holder instanceof MediaVH) {
                MediaVH vh = (MediaVH) holder;
                MediaItem m = r.media;
                vh.image.setScaleType(thumbScale());
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
                    g.setColor(sel ? 0xFF5B9CFF : 0x55000000);
                    g.setStroke(dp(2), Color.WHITE);
                    vh.check.setBackground(g);
                    vh.check.setText(sel ? "✓" : "");
                } else vh.check.setVisibility(View.GONE);
            }
        }
    }
}
