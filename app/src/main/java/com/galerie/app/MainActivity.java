package com.galerie.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
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
import java.util.function.Supplier;

/**
 * Écran principal : 4 onglets (Albums · Vidéos · Photos · Réglages), grille de médias
 * ou liste de dossiers, recherche, sélection multiple (supprimer / déplacer / renommer).
 */
public class MainActivity extends Activity {

    private static final int TAB_ALBUMS = 0, TAB_VIDEOS = 1, TAB_PHOTOS = 2, TAB_SETTINGS = 3;
    private static final String[] TAB_NAMES = {"Albums", "Vidéos", "Photos", "Réglages"};
    private static final int T_HEADER = 0, T_MEDIA = 1, T_ALBUM = 2;
    private static final int SPAN = 60; // divisible par 2, 3, 4, 5, 6
    private static final int F_ALL = 0, F_PHOTOS = 1, F_VIDEOS = 2;
    private static final int SORT_DATE = 0, SORT_MODIFIED = 1, SORT_SIZE = 2, SORT_NAME = 3;
    private static final int BAR_NORMAL = 0, BAR_SEARCH = 1, BAR_SELECT = 2;

    private static class Row {
        final int type;
        String text;          // en-tête, ou 2e ligne d'un dossier
        MediaItem media;
        MediaRepo.Album album;
        MediaItem cover;
        long date, modified, size;

        Row(int type) { this.type = type; }
    }

    // ---- état et réglages
    private int tab = TAB_ALBUMS;
    private String openAlbum = null;
    private String query = "";
    private int filter = F_ALL;
    private int cols = 4;
    private boolean includeVideos = false;
    private boolean photosFolders = false;
    private boolean videosFolders = true;
    private boolean thumbsOriginal = true;
    private int leftTap = 1;       // 0 = -10 s, 1 = média suivant, 2 = rien
    private int photoDelay = 0;    // secondes (0 = désactivé)
    private boolean randomOn = false;
    private int randomKind = 2; // 0 = vidéos seulement, 1 = photos seulement, 2 = les deux
    private int sortMode = SORT_DATE;
    private boolean sortRev = false;
    private boolean selecting = false;
    private boolean loading = false;
    private List<MediaItem> sortedAll = new ArrayList<>(); // toute la bibliothèque, déjà triée (calculée en arrière-plan)
    private String sortedKey = "";
    private String sortingKey = "";

    private MediaRepo repo;
    private ThumbLoader thumbs;
    private final List<Row> rows = new ArrayList<>();
    private final List<MediaItem> visibleMedia = new ArrayList<>();
    private final Set<MediaItem> selected = new LinkedHashSet<>();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final SimpleDateFormat fmtDay = new SimpleDateFormat("EEEE d MMMM", Locale.FRANCE);
    private final SimpleDateFormat fmtDayYear = new SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE);

    // ---- vues
    private LinearLayout root;
    private FrameLayout topFrame;
    private LinearLayout normalBar, searchBar, selectBar;
    private ImageButton backBtn, searchBtn, shuffleBtn, moreBtn, favTopBtn;
    private TextView titleView, subtitleView, selCount;
    private EditText searchEdit;
    private LinearLayout chipsRow;
    private final TextView[] chips = new TextView[3];
    private RecyclerView rv;
    private GridLayoutManager glm;
    private GridAdapter adapter;
    private TextView emptyView;
    private ScrollView settingsView;
    private ProgressBar progress;
    private LinearLayout permissionView;
    private final LinearLayout[] navItems = new LinearLayout[4];

    // réglages : titres de section, choix, interrupteurs, actions
    private static class Setting {
        String header;              // non nul = titre de section
        String title, desc;
        Supplier<String> value;     // valeur affichée à droite (choix)
        Supplier<Boolean> toggle;   // non nul = interrupteur
        Runnable action;
        TextView valueView;
        Switch sw;
    }

    private final List<Setting> settings = new ArrayList<>();
    private String pendingQuery = "";
    private final Runnable applySearch = () -> {
        query = pendingQuery;
        rebuildRows();
        glm.scrollToPosition(0);
    };

    // ================================================================== cycle de vie

    @Override
    protected void onCreate(Bundle state) {
        CrashLog.start(this);
        super.onCreate(state);
        repo = MediaRepo.get();
        thumbs = new ThumbLoader(this);
        SharedPreferences p = prefs();
        cols = p.getInt("cols", 4);
        if (cols < 2 || cols > 6 || SPAN % cols != 0) cols = 4;
        includeVideos = p.getBoolean("inc_video", false);
        photosFolders = p.getBoolean("photos_folders", false);
        videosFolders = p.getBoolean("videos_folders", true);
        thumbsOriginal = p.getBoolean("thumbs_original", true);
        leftTap = p.getInt("left_tap", 1);
        photoDelay = p.getInt("photo_delay", 0);
        randomOn = p.getBoolean("random_on", false);
        randomKind = p.getInt("random_kind", 2);
        Favorites.init(this);
        sortMode = p.getInt("sort_mode", SORT_DATE);
        sortRev = p.getBoolean("sort_rev", false);
        if (state != null) {
            tab = state.getInt("tab", TAB_ALBUMS);
            openAlbum = state.getString("album");
            filter = state.getInt("filter", F_ALL);
        }
        CrashLog.note("onCreate : début interface");
        buildUi();
        buildSettings();
        CrashLog.note("onCreate : interface construite");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasPermission()) {
            permissionView.setVisibility(View.VISIBLE);
            return;
        }
        permissionView.setVisibility(View.GONE);
        bg.execute(Trash::purgeExpired);
        randomOn = prefs().getBoolean("random_on", false);
        randomKind = prefs().getInt("random_kind", 2);
        refreshSettings();
        if (!repo.loaded) {
            if (!loading) reload();
        } else if (rows.isEmpty() || repo.dirty) {
            repo.dirty = false;
            rebuildRows();
        }
        updateChrome();
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
        ui.removeCallbacksAndMessages(null);
        thumbs.shutdown();
        bg.shutdownNow();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (selecting) { endSelection(); return; }
        if (searchBar.getVisibility() == View.VISIBLE) { closeSearch(); return; }
        if (openAlbum != null) { openAlbum = null; filter = F_ALL; rebuildRows(); return; }
        super.onBackPressed();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("galerie", MODE_PRIVATE);
    }

    private int dp(int v) { return Ui.dp(this, v); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void post(Runnable r) {
        if (!isFinishing() && !isDestroyed()) runOnUiThread(r);
    }

    // ================================================================== permission

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
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
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (hasPermission()) {
            permissionView.setVisibility(View.GONE);
            reload();
        }
    }

    private void reload() {
        if (loading) return;
        loading = true;
        progress.setVisibility(View.VISIBLE);
        bg.execute(() -> {
            List<MediaItem> list;
            try {
                list = MediaRepo.scan(this);
            } catch (Throwable t) {
                list = new ArrayList<>();
            }
            final List<MediaItem> res = list;
            post(() -> {
                CrashLog.note("scan terminé : " + res.size() + " éléments");
                repo.set(this, res);
                loading = false;
                progress.setVisibility(View.GONE);
                rebuildRows();
                CrashLog.note("rebuildRows terminé : " + rows.size() + " lignes");
            });
        });
    }

    // ================================================================== construction de l'interface

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        // --- barre du haut (3 variantes superposées)
        topFrame = new FrameLayout(this);
        topFrame.setBackgroundColor(Ui.BAR);
        root.addView(topFrame, new LinearLayout.LayoutParams(-1, dp(56)));

        normalBar = new LinearLayout(this);
        normalBar.setOrientation(LinearLayout.HORIZONTAL);
        normalBar.setGravity(Gravity.CENTER_VERTICAL);
        backBtn = Ui.iconButton(this, R.drawable.ic_back, v -> onBackPressed());
        backBtn.setContentDescription("Retour");
        normalBar.addView(backBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(dp(8), 0, dp(8), 0);
        titleView = new TextView(this);
        titleView.setTextColor(Ui.TEXT);
        titleView.setTextSize(20);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(titleView);
        subtitleView = new TextView(this);
        subtitleView.setTextColor(Ui.MUTED);
        subtitleView.setTextSize(12);
        subtitleView.setSingleLine(true);
        titleCol.addView(subtitleView);
        normalBar.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));
        searchBtn = Ui.iconButton(this, R.drawable.ic_search, v -> openSearch());
        searchBtn.setContentDescription("Rechercher");
        normalBar.addView(searchBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        favTopBtn = Ui.iconButton(this, R.drawable.ic_heart, v -> startActivity(new Intent(this, FavoritesActivity.class)));
        favTopBtn.setContentDescription("Favoris");
        normalBar.addView(favTopBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        shuffleBtn = Ui.iconButton(this, R.drawable.ic_shuffle, v -> playRandom());
        shuffleBtn.setContentDescription("Lecture aléatoire");
        normalBar.addView(shuffleBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        moreBtn = Ui.iconButton(this, R.drawable.ic_more, v -> showMainMenu(v));
        moreBtn.setContentDescription("Plus d'options");
        normalBar.addView(moreBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        topFrame.addView(normalBar, new FrameLayout.LayoutParams(-1, -1));

        searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackgroundColor(Ui.BAR);
        searchBar.addView(Ui.iconButton(this, R.drawable.ic_back, v -> closeSearch()),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        searchEdit = new EditText(this);
        searchEdit.setHint("Rechercher un dossier ou un fichier");
        searchEdit.setHintTextColor(Ui.MUTED);
        searchEdit.setTextColor(Ui.TEXT);
        searchEdit.setTextSize(16);
        searchEdit.setSingleLine(true);
        searchEdit.setBackground(null);
        searchEdit.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable e) {
                pendingQuery = e.toString();
                ui.removeCallbacks(applySearch);
                ui.postDelayed(applySearch, 250);
            }
        });
        searchBar.addView(searchEdit, new LinearLayout.LayoutParams(0, -2, 1));
        searchBar.addView(Ui.iconButton(this, R.drawable.ic_close, v -> searchEdit.setText("")),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        topFrame.addView(searchBar, new FrameLayout.LayoutParams(-1, -1));

        selectBar = new LinearLayout(this);
        selectBar.setOrientation(LinearLayout.HORIZONTAL);
        selectBar.setGravity(Gravity.CENTER_VERTICAL);
        selectBar.setBackgroundColor(0xFF1F3354);
        selectBar.addView(Ui.iconButton(this, R.drawable.ic_close, v -> endSelection()),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        selCount = new TextView(this);
        selCount.setTextColor(Ui.TEXT);
        selCount.setTextSize(18);
        selCount.setTypeface(null, Typeface.BOLD);
        selCount.setPadding(dp(8), 0, 0, 0);
        selectBar.addView(selCount, new LinearLayout.LayoutParams(0, -2, 1));
        selectBar.addView(Ui.iconButton(this, R.drawable.ic_delete, v -> deleteSelected()),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        selectBar.addView(Ui.iconButton(this, R.drawable.ic_move, v -> moveSelected()),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        selectBar.addView(Ui.iconButton(this, R.drawable.ic_more, v -> showSelectionMenu(v)),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        topFrame.addView(selectBar, new FrameLayout.LayoutParams(-1, -1));
        showBar(BAR_NORMAL);

        // --- filtres Tout / Photos / Vidéos (dans un album de l'onglet Albums)
        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(dp(12), dp(8), dp(12), dp(8));
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
        chipsRow.setVisibility(View.GONE);
        root.addView(chipsRow, new LinearLayout.LayoutParams(-1, -2));

        // --- contenu
        FrameLayout content = new FrameLayout(this);
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
        rv.setPadding(dp(4), 0, dp(4), dp(8));
        rv.setClipToPadding(false);
        rv.setItemAnimator(null);
        content.addView(rv, new FrameLayout.LayoutParams(-1, -1));

        emptyView = new TextView(this);
        emptyView.setTextColor(Ui.MUTED);
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(32), 0, dp(32), 0);
        emptyView.setCompoundDrawablePadding(dp(16));
        emptyView.setVisibility(View.GONE);
        emptyView.setClickable(false);
        content.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));

        settingsView = new ScrollView(this);
        settingsView.setVisibility(View.GONE);
        content.addView(settingsView, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        content.addView(progress, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));

        permissionView = new LinearLayout(this);
        permissionView.setOrientation(LinearLayout.VERTICAL);
        permissionView.setGravity(Gravity.CENTER);
        permissionView.setPadding(dp(32), dp(32), dp(32), dp(32));
        permissionView.setBackgroundColor(Ui.BG);
        permissionView.setClickable(true);
        permissionView.setVisibility(View.GONE);
        ImageView pic = new ImageView(this);
        pic.setImageResource(R.drawable.ic_nav_photos);
        pic.setColorFilter(Ui.ACCENT);
        permissionView.addView(pic, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView pt = new TextView(this);
        pt.setText("Bienvenue dans Galerie");
        pt.setTextColor(Ui.TEXT);
        pt.setTextSize(22);
        pt.setTypeface(null, Typeface.BOLD);
        pt.setGravity(Gravity.CENTER);
        pt.setPadding(0, dp(20), 0, dp(10));
        permissionView.addView(pt);
        TextView pm = new TextView(this);
        pm.setText("Pour afficher, renommer, déplacer et supprimer vos photos et vidéos, Galerie a besoin d'accéder à vos fichiers.");
        pm.setTextColor(0xFFB6BBC4);
        pm.setTextSize(15);
        pm.setGravity(Gravity.CENTER);
        permissionView.addView(pm);
        Button grant = new Button(this);
        grant.setText("Autoriser l'accès");
        grant.setAllCaps(false);
        grant.setTextColor(Color.BLACK);
        grant.setBackground(Ui.rect(this, Ui.ACCENT, 26));
        grant.setPadding(dp(32), dp(12), dp(32), dp(12));
        grant.setOnClickListener(v -> requestPermission());
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
        gl.topMargin = dp(24);
        permissionView.addView(grant, gl);
        content.addView(permissionView, new FrameLayout.LayoutParams(-1, -1));

        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        // --- barre du bas
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(Ui.BAR);
        int[] icons = {R.drawable.ic_nav_albums, R.drawable.ic_nav_videos, R.drawable.ic_nav_photos, R.drawable.ic_nav_settings};
        for (int i = 0; i < 4; i++) {
            final int t = i;
            LinearLayout n = new LinearLayout(this);
            n.setOrientation(LinearLayout.VERTICAL);
            n.setGravity(Gravity.CENTER);
            ImageView ic = new ImageView(this);
            ic.setImageResource(icons[i]);
            ic.setPadding(dp(20), dp(4), dp(20), dp(4));
            n.addView(ic, new LinearLayout.LayoutParams(dp(64), dp(32)));
            TextView lb = new TextView(this);
            lb.setText(TAB_NAMES[i]);
            lb.setTextSize(11);
            lb.setGravity(Gravity.CENTER);
            n.addView(lb, new LinearLayout.LayoutParams(-2, -2));
            n.setOnClickListener(v -> setTab(t));
            nav.addView(n, new LinearLayout.LayoutParams(0, dp(62), 1));
            navItems[i] = n;
        }
        root.addView(nav, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        updateChrome();
    }

    private void showBar(int which) {
        normalBar.setVisibility(which == BAR_NORMAL ? View.VISIBLE : View.GONE);
        searchBar.setVisibility(which == BAR_SEARCH ? View.VISIBLE : View.GONE);
        selectBar.setVisibility(which == BAR_SELECT ? View.VISIBLE : View.GONE);
    }

    /** Met à jour titres, onglet actif, filtres, visibilité des zones. */
    private void updateChrome() {
        boolean inSettings = tab == TAB_SETTINGS;
        settingsView.setVisibility(inSettings ? View.VISIBLE : View.GONE);
        rv.setVisibility(inSettings ? View.GONE : View.VISIBLE);
        if (inSettings) emptyView.setVisibility(View.GONE);

        MediaRepo.Album a = openAlbum != null ? repo.album(openAlbum) : null;
        titleView.setText(a != null ? a.name : TAB_NAMES[tab]);
        subtitleView.setText(a != null ? a.countLabel() : "");
        subtitleView.setVisibility(a != null ? View.VISIBLE : View.GONE);
        backBtn.setVisibility(a != null ? View.VISIBLE : View.GONE);
        searchBtn.setVisibility(inSettings ? View.GONE : View.VISIBLE);
        shuffleBtn.setVisibility(inSettings ? View.GONE : View.VISIBLE);
        moreBtn.setVisibility(View.VISIBLE);
        ((View) titleView.getParent()).setPadding(a != null ? dp(4) : dp(16), 0, dp(8), 0);

        chipsRow.setVisibility(tab == TAB_ALBUMS && a != null ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 3; i++) {
            boolean on = i == filter;
            GradientDrawableHolder.apply(this, chips[i], on);
        }
        for (int i = 0; i < 4; i++) {
            boolean on = i == tab;
            ImageView ic = (ImageView) navItems[i].getChildAt(0);
            TextView lb = (TextView) navItems[i].getChildAt(1);
            ic.setBackground(Ui.rect(this, on ? 0x335B9CFF : 0x00000000, 16));
            ic.setColorFilter(on ? Ui.ACCENT : Ui.MUTED);
            lb.setTextColor(on ? Ui.ACCENT : Ui.MUTED);
            lb.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    /** Style d'une pastille de filtre. */
    private static final class GradientDrawableHolder {
        static void apply(MainActivity c, TextView t, boolean on) {
            android.graphics.drawable.GradientDrawable g = Ui.rect(c, on ? Ui.ACCENT : 0x00000000, 18);
            if (!on) g.setStroke(c.dp(1), 0xFF3A3F49);
            t.setBackground(g);
            t.setTextColor(on ? Color.BLACK : 0xFFE0E0E0);
        }
    }

    private void setTab(int t) {
        if (selecting) endSelection();
        if (searchBar.getVisibility() == View.VISIBLE) closeSearchQuiet();
        if (t == tab && openAlbum == null) {
            glm.scrollToPosition(0);
            return;
        }
        openAlbum = null;
        filter = F_ALL;
        tab = t;
        rebuildRows();
    }

    // ================================================================== recherche

    private void openSearch() {
        showBar(BAR_SEARCH);
        searchEdit.setText("");
        searchEdit.requestFocus();
        ui.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchEdit, 0);
        }, 100);
    }

    private void closeSearchQuiet() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchEdit.getWindowToken(), 0);
        ui.removeCallbacks(applySearch);
        searchEdit.setText("");
        query = "";
        pendingQuery = "";
        showBar(selecting ? BAR_SELECT : BAR_NORMAL);
    }

    private void closeSearch() {
        closeSearchQuiet();
        rebuildRows();
    }

    // ================================================================== contenu

    private boolean passFilter(MediaItem m) {
        if (tab == TAB_VIDEOS) return m.video;
        if (tab == TAB_PHOTOS) return !m.video || includeVideos;
        return filter == F_ALL || (filter == F_VIDEOS) == m.video;
    }

    /** Dans la vue des dossiers : un média compte-t-il pour l'onglet courant ? */
    private boolean albumPass(MediaItem m) {
        if (tab == TAB_VIDEOS) return m.video;
        if (tab == TAB_PHOTOS) return !m.video || includeVideos;
        return true;
    }

    private boolean isFolderView() {
        if (openAlbum != null) return false;
        if (tab == TAB_ALBUMS) return true;
        if (tab == TAB_VIDEOS) return videosFolders;
        if (tab == TAB_PHOTOS) return photosFolders;
        return false;
    }

    private long dateOf(MediaItem m) {
        return sortMode == SORT_MODIFIED ? m.modified : m.date;
    }

    private Comparator<MediaItem> mediaCmp() {
        Comparator<MediaItem> c;
        switch (sortMode) {
            case SORT_MODIFIED:
                c = (a, b) -> Long.compare(b.modified, a.modified);
                break;
            case SORT_SIZE:
                c = (a, b) -> Long.compare(b.size(), a.size());
                break;
            case SORT_NAME:
                c = (a, b) -> FileUtil.naturalCompare(a.name(), b.name());
                break;
            default:
                c = (a, b) -> Long.compare(b.date, a.date);
        }
        return sortRev ? Collections.reverseOrder(c) : c;
    }

    private String sortKey() {
        return sortMode + "/" + sortRev + "/" + repo.version;
    }

    /** Trie toute la bibliothèque hors du fil principal (une seule fois par réglage de tri). */
    private void sortAsync() {
        final String key = sortKey();
        if (key.equals(sortingKey)) return;
        sortingKey = key;
        progress.setVisibility(View.VISIBLE);
        final List<MediaItem> copy = new ArrayList<>(repo.all);
        final Comparator<MediaItem> cmp = mediaCmp();
        bg.execute(() -> {
            try {
                Collections.sort(copy, cmp);
            } catch (Throwable ignored) { }
            post(() -> {
                sortingKey = "";
                if (!key.equals(sortKey())) { sortAsync(); return; }
                sortedAll = copy;
                sortedKey = key;
                progress.setVisibility(loading ? View.VISIBLE : View.GONE);
                rebuildRows();
            });
        });
    }

    /** Appelé quand le sélecteur de dossiers se ferme : affiche un dossier qui vient d'être créé. */
    void albumsChanged() {
        if (repo.dirty && repo.loaded) {
            repo.dirty = false;
            rebuildRows();
        }
    }

    private void openTrash() {
        startActivity(new android.content.Intent(this, TrashActivity.class));
    }

    private String trashSummary() {
        int n = Trash.count();
        return (n == 0 ? "Vide" : n + (n > 1 ? " éléments" : " élément"))
                + " · les éléments supprimés y restent 30 jours, puis sont effacés définitivement.";
    }

    private void rebuildRows() {
        boolean needsSorted = repo.loaded && tab != TAB_SETTINGS && !(isFolderView() && query.trim().isEmpty());
        if (needsSorted && !sortKey().equals(sortedKey)) {
            sortAsync();
            return;
        }
        rows.clear();
        visibleMedia.clear();
        if (openAlbum != null && repo.album(openAlbum) == null) openAlbum = null;
        String empty = "";

        if (tab != TAB_SETTINGS && repo.loaded) {
            String q = query.trim().toLowerCase(Locale.ROOT);
            if (!q.isEmpty()) {
                List<Row> found = albumRows(q);
                if (!found.isEmpty()) {
                    addHeader("Dossiers (" + found.size() + ")");
                    rows.addAll(found);
                }
                for (MediaItem m : sortedAll) {
                    if (passFilter(m) && m.name().toLowerCase(Locale.ROOT).contains(q)) visibleMedia.add(m);
                }
                if (!visibleMedia.isEmpty()) {
                    addHeader("Photos et vidéos (" + visibleMedia.size() + ")");
                    addMediaRows(visibleMedia);
                }
                empty = "Aucun résultat pour « " + query.trim() + " »";
            } else if (isFolderView()) {
                rows.addAll(albumRows(""));
                empty = tab == TAB_VIDEOS ? "Aucun dossier contenant des vidéos"
                        : tab == TAB_PHOTOS ? "Aucun dossier contenant des photos" : "Aucun album";
            } else {
                for (MediaItem m : sortedAll) {
                    if (openAlbum != null && !m.folder().equals(openAlbum)) continue;
                    if (passFilter(m)) visibleMedia.add(m);
                }
                if (sortMode == SORT_DATE || sortMode == SORT_MODIFIED) addDateGrouped(visibleMedia);
                else addMediaRows(visibleMedia);
                empty = tab == TAB_VIDEOS ? "Aucune vidéo" : tab == TAB_PHOTOS ? "Aucune photo" : "Aucune photo ni vidéo";
            }
        }

        adapter.notifyDataSetChanged();
        emptyView.setText(empty);
        android.graphics.drawable.Drawable ed = getResources().getDrawable(R.drawable.ic_empty).mutate();
        ed.setTint(0xFF3A3F49);
        ed.setBounds(0, 0, dp(72), dp(72));
        emptyView.setCompoundDrawables(null, ed, null, null);
        emptyView.setVisibility(rows.isEmpty() && repo.loaded && tab != TAB_SETTINGS ? View.VISIBLE : View.GONE);
        updateChrome();
    }

    private void addHeader(String text) {
        Row r = new Row(T_HEADER);
        r.text = text;
        rows.add(r);
    }

    private void addMediaRows(List<MediaItem> list) {
        for (MediaItem m : list) {
            Row r = new Row(T_MEDIA);
            r.media = m;
            rows.add(r);
        }
    }

    private void addDateGrouped(List<MediaItem> list) {
        Calendar cal = Calendar.getInstance();
        int thisYear = cal.get(Calendar.YEAR);
        int todayKey = thisYear * 1000 + cal.get(Calendar.DAY_OF_YEAR);
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
                    if (!label.isEmpty()) label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
                }
                addHeader(label);
            }
            Row r = new Row(T_MEDIA);
            r.media = m;
            rows.add(r);
        }
    }

    /** Lignes « dossier » de l'onglet courant (q = filtre de recherche, "" = tous). */
    private List<Row> albumRows(String q) {
        List<Row> out = new ArrayList<>();
        for (MediaRepo.Album a : repo.albums) {
            if (!q.isEmpty() && !a.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            int count = 0, photos = 0, videos = 0;
            MediaItem cover = null;
            long newest = 0, mod = 0, size = 0;
            for (MediaItem m : a.items) {
                if (!albumPass(m)) continue;
                count++;
                if (m.video) videos++; else photos++;
                if (cover == null) cover = m;
                if (m.date > newest) newest = m.date;
                if (m.modified > mod) mod = m.modified;
                if (sortMode == SORT_SIZE) size += m.size();
            }
            if (count == 0 && !(tab == TAB_ALBUMS && a.items.isEmpty())) continue;
            Row r = new Row(T_ALBUM);
            r.album = a;
            r.cover = cover;
            r.date = newest;
            r.modified = mod;
            r.size = size;
            if (count == 0) r.text = "Vide";
            else if (tab == TAB_VIDEOS) r.text = videos + (videos > 1 ? " vidéos" : " vidéo");
            else if (photos > 0 && videos > 0) r.text = photos + (photos > 1 ? " photos" : " photo") + " · " + videos + (videos > 1 ? " vidéos" : " vidéo");
            else if (videos > 0) r.text = videos + (videos > 1 ? " vidéos" : " vidéo");
            else r.text = photos + (photos > 1 ? " photos" : " photo");
            out.add(r);
        }
        Comparator<Row> c;
        switch (sortMode) {
            case SORT_MODIFIED:
                c = (x, y) -> Long.compare(y.modified, x.modified);
                break;
            case SORT_SIZE:
                c = (x, y) -> Long.compare(y.size, x.size);
                break;
            case SORT_NAME:
                c = (x, y) -> FileUtil.naturalCompare(x.album.name, y.album.name);
                break;
            default:
                c = (x, y) -> Long.compare(y.date, x.date);
        }
        Collections.sort(out, sortRev ? Collections.reverseOrder(c) : c);
        return out;
    }

    // ================================================================== ouverture / aléatoire

    private void openViewer(List<MediaItem> list, int index) {
        if (list.isEmpty()) return;
        ArrayList<String> paths = new ArrayList<>(list.size());
        for (MediaItem m : list) paths.add(m.path);
        ViewerActivity.sPaths = paths;
        Intent i = new Intent(this, ViewerActivity.class);
        i.putExtra("index", Math.max(0, Math.min(index, paths.size() - 1)));
        i.putExtra("random", randomOn);
        startActivity(i);
    }

    /** Bouton 🔀 de la barre : active l'aléatoire et ouvre un média au hasard. */
    private void playRandom() {
        List<MediaItem> pool = new ArrayList<>();
        List<MediaItem> src = openAlbum != null && repo.album(openAlbum) != null ? repo.album(openAlbum).items : repo.all;
        for (MediaItem m : src) {
            if (!passFilter(m)) continue;
            if (randomKind == 0 && !m.video) continue;
            if (randomKind == 1 && m.video) continue;
            pool.add(m);
        }
        if (pool.isEmpty()) {
            toast(randomKind == 0 ? "Aucune vidéo ici" : randomKind == 1 ? "Aucune photo ici" : "Aucun média");
            return;
        }
        setRandom(true);
        openViewer(pool, random.nextInt(pool.size()));
    }

    private void setRandom(boolean on) {
        randomOn = on;
        prefs().edit().putBoolean("random_on", on).apply();
        refreshSettings();
    }

    // ================================================================== sélection

    private void startSelection(MediaItem first, int pos) {
        selecting = true;
        selected.clear();
        selected.add(first);
        if (searchBar.getVisibility() == View.VISIBLE) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(searchEdit.getWindowToken(), 0);
        }
        showBar(BAR_SELECT);
        updateSelCount();
        adapter.notifyDataSetChanged();
        rv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private void toggle(MediaItem m, int pos) {
        if (!selected.remove(m)) selected.add(m);
        if (selected.isEmpty()) { endSelection(); return; }
        updateSelCount();
        adapter.notifyItemChanged(pos);
    }

    private void endSelection() {
        selecting = false;
        selected.clear();
        showBar(searchEdit.getText().length() > 0 || query.length() > 0 ? BAR_SEARCH : BAR_NORMAL);
        adapter.notifyDataSetChanged();
    }

    private void updateSelCount() {
        selCount.setText(selected.size() + (selected.size() > 1 ? " sélectionnés" : " sélectionné"));
    }

    private void showSelectionMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 3, 0, "Partager");
        boolean allFav = true;
        for (MediaItem m : selected) if (!Favorites.is(m.path)) { allFav = false; break; }
        final boolean removeFav = allFav;
        pm.getMenu().add(0, 4, 0, allFav ? "Retirer des favoris" : "Ajouter aux favoris");
        if (selected.size() == 1) pm.getMenu().add(0, 1, 0, "Renommer");
        pm.getMenu().add(0, 2, 0, "Tout sélectionner");
        pm.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 3) {
                List<String> ps = new ArrayList<>();
                for (MediaItem m : selected) ps.add(m.path);
                Share.send(this, ps);
            } else if (it.getItemId() == 4) {
                List<String> ps = new ArrayList<>();
                for (MediaItem m : selected) ps.add(m.path);
                if (removeFav) Favorites.removeAll(ps); else Favorites.addAll(ps);
                toast(removeFav ? "Retiré des favoris" : "Ajouté aux favoris");
                endSelection();
                adapter.notifyDataSetChanged();
            } else if (it.getItemId() == 1) renameSelected();
            else {
                selected.addAll(visibleMedia);
                updateSelCount();
                adapter.notifyDataSetChanged();
            }
            return true;
        });
        pm.show();
    }

    // ================================================================== opérations

    private void runBusy(Runnable work) {
        progress.setVisibility(View.VISIBLE);
        bg.execute(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                post(() -> toast("Erreur : " + t));
            }
            post(() -> progress.setVisibility(View.GONE));
        });
    }

    private void askName(String title, String initial, final java.util.function.Consumer<String> cb) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(initial);
        et.setSelection(initial.length());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("OK", (dd, w) -> {
                    String n = et.getText().toString().trim();
                    if (!FileUtil.isValidName(n)) toast("Nom invalide");
                    else cb.accept(n);
                })
                .setNegativeButton("Annuler", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private File startDir() {
        if (openAlbum != null) return new File(openAlbum);
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
    }

    private void deleteSelected() {
        final List<MediaItem> items = new ArrayList<>(selected);
        if (items.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Mettre " + items.size() + (items.size() > 1 ? " éléments" : " élément") + " à la corbeille ?")
                .setMessage("Ils restent 30 jours dans la corbeille (restauration possible), puis sont supprimés définitivement.")
                .setPositiveButton("Mettre à la corbeille", (d, w) -> {
                    endSelection();
                    runBusy(() -> {
                        final List<MediaItem> done = new ArrayList<>();
                        final List<String> paths = new ArrayList<>();
                        for (MediaItem m : items) {
                            File f = new File(m.path);
                            if (Trash.moveToTrash(f) || !f.exists()) {
                                MediaRepo.deleteFromMediaStore(this, m);
                                done.add(m);
                                paths.add(m.path);
                            }
                        }
                        post(() -> {
                            repo.removed(this, done);
                            MediaRepo.scan(this, paths);
                            rebuildRows();
                            toast(done.size() + (done.size() > 1 ? " éléments mis à la corbeille" : " élément mis à la corbeille"));
                        });
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void moveSelected() {
        final List<MediaItem> items = new ArrayList<>(selected);
        if (items.isEmpty()) return;
        FolderPicker.show(this, startDir(), dest -> {
            endSelection();
            runBusy(() -> {
                final List<MediaItem> movedItems = new ArrayList<>();
                final List<String> newPaths = new ArrayList<>();
                final List<String> oldPaths = new ArrayList<>();
                for (MediaItem m : items) {
                    File src = new File(m.path);
                    File parent = src.getParentFile();
                    if (parent != null && parent.equals(dest)) continue;
                    File dst = FileUtil.uniqueFile(dest, src.getName());
                    if (src.renameTo(dst) || FileUtil.copyThenDelete(src, dst)) {
                        movedItems.add(m);
                        oldPaths.add(m.path);
                        newPaths.add(dst.getAbsolutePath());
                    }
                }
                post(() -> {
                    for (int i = 0; i < movedItems.size(); i++) {
                        thumbs.forget(oldPaths.get(i));
                        repo.moved(movedItems.get(i), newPaths.get(i));
                    }
                    repo.rebuildAlbums(this);
                    List<String> all = new ArrayList<>(oldPaths);
                    all.addAll(newPaths);
                    MediaRepo.scan(this, all);
                    rebuildRows();
                    toast(movedItems.size() + (movedItems.size() > 1 ? " éléments déplacés" : " élément déplacé"));
                });
            });
        });
    }

    private void renameSelected() {
        if (selected.size() != 1) return;
        final MediaItem m = selected.iterator().next();
        askName("Renommer", m.name(), name -> {
            File src = new File(m.path);
            File dst = new File(src.getParentFile(), name);
            if (dst.exists()) { toast("Ce nom existe déjà"); return; }
            if (!src.renameTo(dst)) { toast("Impossible de renommer"); return; }
            final String old = m.path;
            thumbs.forget(old);
            repo.moved(m, dst.getAbsolutePath());
            List<String> l = new ArrayList<>();
            l.add(old);
            l.add(dst.getAbsolutePath());
            MediaRepo.scan(this, l);
            endSelection();
            rebuildRows();
        });
    }

    private void createAlbum() {
        askName("Nouvel album", "", name -> {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), name);
            if (dir.isDirectory() || dir.mkdirs()) {
                MediaRepo.addEmptyAlbum(this, dir.getAbsolutePath());
                repo.rebuildAlbums(this);
                rebuildRows();
                toast("Album créé");
            } else toast("Impossible de créer l'album");
        });
    }

    // ---- dossiers

    private void albumMenu(View anchor, final MediaRepo.Album a) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Renommer");
        pm.getMenu().add(0, 2, 0, "Déplacer");
        pm.getMenu().add(0, 3, 0, "Supprimer");
        pm.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 1) renameAlbum(a);
            else if (it.getItemId() == 2) moveAlbum(a);
            else deleteAlbum(a);
            return true;
        });
        pm.show();
    }

    private void renameAlbum(final MediaRepo.Album a) {
        askName("Renommer le dossier", a.name, name -> {
            File src = new File(a.path);
            File dst = new File(src.getParentFile(), name);
            if (dst.exists()) { toast("Ce nom existe déjà"); return; }
            applyFolderMove(src, dst, src.renameTo(dst));
        });
    }

    private void moveAlbum(final MediaRepo.Album a) {
        FolderPicker.show(this, new File(a.path).getParentFile(), dest -> {
            final File src = new File(a.path);
            if (dest.getAbsolutePath().startsWith(src.getAbsolutePath())) { toast("Destination invalide"); return; }
            final File dst = FileUtil.uniqueFile(dest, src.getName());
            runBusy(() -> {
                boolean ok = src.renameTo(dst) || FileUtil.copyThenDelete(src, dst);
                post(() -> applyFolderMove(src, dst, ok));
            });
        });
    }

    private void applyFolderMove(File src, File dst, boolean ok) {
        if (!ok) { toast("Opération impossible"); return; }
        List<String> touched = repo.folderChanged(this, src.getAbsolutePath(), dst.getAbsolutePath());
        MediaRepo.scan(this, touched);
        if (openAlbum != null && openAlbum.equals(src.getAbsolutePath())) openAlbum = dst.getAbsolutePath();
        rebuildRows();
        toast("Dossier modifié");
    }

    private void deleteAlbum(final MediaRepo.Album a) {
        new AlertDialog.Builder(this)
                .setTitle("Supprimer le dossier ?")
                .setMessage("Les photos et vidéos de « " + a.name + " » (sous-dossiers compris) vont à la corbeille pendant 30 jours ; le dossier est supprimé.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    final String path = a.path;
                    final List<MediaItem> items = new ArrayList<>();
                    for (MediaItem m : repo.all) if (m.path.startsWith(path + "/")) items.add(m);
                    runBusy(() -> {
                        for (MediaItem m : items) {
                            File f = new File(m.path);
                            if (Trash.moveToTrash(f) || !f.exists()) MediaRepo.deleteFromMediaStore(this, m);
                        }
                        FileUtil.deleteRecursive(new File(path)); // ce qui reste (fichiers non médias, dossiers vides)
                        post(() -> {
                            List<String> touched = repo.folderChanged(this, path, null);
                            MediaRepo.scan(this, touched);
                            if (path.equals(openAlbum)) openAlbum = null;
                            rebuildRows();
                            toast("Dossier supprimé · contenu à la corbeille");
                        });
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ================================================================== menu principal

    private void showMainMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        if (tab != TAB_SETTINGS) {
            if (!isFolderView()) pm.getMenu().add(0, 1, 0, "Sélectionner");
            pm.getMenu().add(0, 2, 0, "Trier par…");
            pm.getMenu().add(0, 3, 0, "Nouvel album");
        }
        pm.getMenu().add(0, 5, 0, "Corbeille");
        pm.getMenu().add(0, 4, 0, "Actualiser");
        pm.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1:
                    if (!visibleMedia.isEmpty()) {
                        int p = 0;
                        for (int i = 0; i < rows.size(); i++) if (rows.get(i).type == T_MEDIA) { p = i; break; }
                        startSelection(rows.get(p).media, p);
                    }
                    break;
                case 2: chooseSort(); break;
                case 3: createAlbum(); break;
                case 5: openTrash(); break;
                default: reload();
            }
            return true;
        });
        pm.show();
    }

    // ================================================================== réglages

    private static String sortName(int m, boolean rev) {
        switch (m) {
            case SORT_MODIFIED: return rev ? "Modification · plus ancien d'abord" : "Modification · plus récent d'abord";
            case SORT_SIZE: return rev ? "Taille · plus petit d'abord" : "Taille · plus grand d'abord";
            case SORT_NAME: return rev ? "Nom · Z → A" : "Nom · A → Z";
            default: return rev ? "Date · plus ancien d'abord" : "Date · plus récent d'abord";
        }
    }

    private static String tapLabel(int m) {
        return m == 1 ? "Média suivant (photo et vidéo)" : m == 2 ? "Aucune action" : "Reculer de 10 secondes (vidéo)";
    }

    private static String tapShort(int m) {
        return m == 1 ? "Média suivant" : m == 2 ? "Aucune action" : "Reculer de 10 s";
    }

    private void section(String name) {
        Setting s = new Setting();
        s.header = name;
        settings.add(s);
    }

    private void choice(String title, String desc, Supplier<String> value, Runnable action) {
        Setting s = new Setting();
        s.title = title;
        s.desc = desc;
        s.value = value;
        s.action = action;
        settings.add(s);
    }

    private void switchRow(String title, String desc, Supplier<Boolean> state, Runnable action) {
        Setting s = new Setting();
        s.title = title;
        s.desc = desc;
        s.toggle = state;
        s.action = action;
        settings.add(s);
    }

    private void actionRow(String title, String desc, Runnable action) {
        Setting s = new Setting();
        s.title = title;
        s.desc = desc;
        s.action = action;
        settings.add(s);
    }

    private void saveBool(String key, boolean v) {
        prefs().edit().putBoolean(key, v).apply();
    }

    private void buildSettings() {
        section("Affichage");
        choice("Taille de la grille", "Nombre de miniatures par ligne.",
                () -> cols + " par ligne", this::chooseColumns);
        switchRow("Miniatures entières", "Montre chaque photo en entier, sans la recadrer. Désactivé : miniatures carrées.",
                () -> thumbsOriginal, () -> {
                    thumbsOriginal = !thumbsOriginal;
                    saveBool("thumbs_original", thumbsOriginal);
                    refreshSettings();
                    adapter.notifyDataSetChanged();
                });
        choice("Trier par", "Ordre des photos, vidéos et dossiers.",
                () -> sortName(sortMode, sortRev), this::chooseSort);

        section("Lecture des photos et vidéos");
        switchRow("Mode aléatoire", "« Suivant », la fin d'une vidéo et le passage automatique ouvrent un média au hasard. Activable aussi avec le bouton aléatoire du lecteur.",
                () -> randomOn, () -> setRandom(!randomOn));
        choice("Contenu de l'aléatoire", "Ce que le mode aléatoire ouvre : seulement des vidéos, seulement des photos, ou les deux. Dans le lecteur : appui long sur le bouton aléatoire.",
                () -> randomKindName(randomKind), this::chooseRandomKind);
        choice("Passage automatique des images", "Délai avant de passer à la photo suivante. Une vidéo passe à la suite quand elle se termine.",
                () -> photoDelay == 0 ? "Désactivé" : photoDelay + " secondes", this::chooseDelay);
        choice("Double-tap à gauche", "Action du double-tap sur la moitié gauche de l'écran. Sur la moitié droite : avance de 10 s dans une vidéo.",
                () -> tapShort(leftTap), this::chooseLeftTap);

        section("Onglets");
        switchRow("Vidéos dans l'onglet Photos", "Affiche aussi les vidéos avec les photos.",
                () -> includeVideos, () -> {
                    includeVideos = !includeVideos;
                    saveBool("inc_video", includeVideos);
                    refreshSettings();
                });
        switchRow("Photos classées par dossiers", "L'onglet Photos montre vos dossiers au lieu d'une grille de toutes les photos.",
                () -> photosFolders, () -> {
                    photosFolders = !photosFolders;
                    saveBool("photos_folders", photosFolders);
                    refreshSettings();
                });
        switchRow("Vidéos classées par dossiers", "L'onglet Vidéos montre vos dossiers au lieu d'une grille de toutes les vidéos.",
                () -> videosFolders, () -> {
                    videosFolders = !videosFolders;
                    saveBool("videos_folders", videosFolders);
                    refreshSettings();
                });

        section("Organisation");
        actionRow("Nouvel album", "Crée un dossier dans « Images ».", this::createAlbum);
        actionRow("Corbeille", trashSummary(), this::openTrash);
        actionRow("Actualiser la bibliothèque", "Relit toutes les photos et vidéos du téléphone.", this::reload);

        section("Aide");
        actionRow("Boutons et gestes", "Comment utiliser la galerie et le lecteur.", () -> Guide.show(this));
        actionRow("Journal de diagnostic", "En cas de problème : copiez-le et envoyez-le au développeur.", this::showJournal);
        actionRow("À propos", "Version et confidentialité.", this::showAbout);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(24));
        for (final Setting s : settings) {
            if (s.header != null) {
                TextView h = new TextView(this);
                h.setText(s.header.toUpperCase(Locale.ROOT));
                h.setTextColor(Ui.ACCENT);
                h.setTextSize(12);
                h.setTypeface(null, Typeface.BOLD);
                h.setLetterSpacing(0.08f);
                h.setPadding(dp(24), dp(22), dp(24), dp(8));
                list.addView(h);
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(14), dp(16), dp(14));
            row.setBackground(Ui.rect(this, Ui.CARD, 16));
            row.setOnClickListener(v -> s.action.run());
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(this);
            t.setText(s.title);
            t.setTextColor(Ui.TEXT);
            t.setTextSize(16);
            col.addView(t);
            if (s.desc != null) {
                TextView d = new TextView(this);
                d.setText(s.desc);
                d.setTextColor(Ui.MUTED);
                d.setTextSize(13);
                d.setPadding(0, dp(3), 0, 0);
                col.addView(d);
            }
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            if (s.toggle != null) {
                s.sw = new Switch(this);
                s.sw.setClickable(false);
                s.sw.setFocusable(false);
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
                sl.leftMargin = dp(12);
                row.addView(s.sw, sl);
            } else if (s.value != null) {
                s.valueView = new TextView(this);
                s.valueView.setTextColor(Ui.ACCENT);
                s.valueView.setTextSize(14);
                s.valueView.setGravity(Gravity.END);
                s.valueView.setMaxWidth(dp(130));
                s.valueView.setPadding(dp(12), 0, 0, 0);
                row.addView(s.valueView, new LinearLayout.LayoutParams(-2, -2));
            } else {
                TextView chev = new TextView(this);
                chev.setText("›");
                chev.setTextColor(Ui.MUTED);
                chev.setTextSize(24);
                chev.setPadding(dp(12), 0, 0, 0);
                row.addView(chev);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(dp(12), dp(4), dp(12), dp(4));
            list.addView(row, lp);
        }
        settingsView.addView(list);
        refreshSettings();
    }

    private void refreshSettings() {
        for (Setting s : settings) {
            if (s.sw != null) s.sw.setChecked(s.toggle.get());
            if (s.valueView != null) s.valueView.setText(s.value.get());
        }
    }

    private void showAbout() {
        String v = "";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        new AlertDialog.Builder(this)
                .setTitle("Galerie")
                .setMessage("Version " + v + "\n\nVos photos et vidéos restent sur votre téléphone : "
                        + "l'application n'envoie aucune donnée et n'utilise pas Internet.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void chooseColumns() {
        final int[] values = {2, 3, 4, 5, 6};
        String[] labels = new String[values.length];
        int checked = 2;
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] + " par ligne";
            if (values[i] == cols) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Taille de la grille")
                .setSingleChoiceItems(labels, checked, (d, w) -> {
                    cols = values[w];
                    prefs().edit().putInt("cols", cols).apply();
                    refreshSettings();
                    rebuildRows();
                    d.dismiss();
                })
                .setNegativeButton("Fermer", null)
                .show();
    }

    static String randomKindName(int k) {
        return k == 0 ? "Vidéos seulement" : k == 1 ? "Photos seulement" : "Photos et vidéos";
    }

    private void chooseRandomKind() {
        final String[] labels = {"Vidéos seulement", "Photos seulement", "Photos et vidéos (les deux)"};
        new AlertDialog.Builder(this)
                .setTitle("L'aléatoire ouvre…")
                .setSingleChoiceItems(labels, randomKind, (d, w) -> {
                    randomKind = w;
                    prefs().edit().putInt("random_kind", w).apply();
                    refreshSettings();
                    d.dismiss();
                })
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void chooseSort() {
        final String[] labels = new String[8];
        for (int i = 0; i < 8; i++) labels[i] = sortName(i / 2, i % 2 == 1);
        new AlertDialog.Builder(this)
                .setTitle("Trier par")
                .setSingleChoiceItems(labels, sortMode * 2 + (sortRev ? 1 : 0), (d, w) -> {
                    sortMode = w / 2;
                    sortRev = w % 2 == 1;
                    prefs().edit().putInt("sort_mode", sortMode).putBoolean("sort_rev", sortRev).apply();
                    refreshSettings();
                    rebuildRows();
                    d.dismiss();
                })
                .setNegativeButton("Fermer", null)
                .show();
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

    private void showJournal() {
        final String txt = CrashLog.read();
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Journal de diagnostic")
                .setView(sv)
                .setPositiveButton("Copier", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("journal", txt));
                    toast("Copié");
                })
                .setNeutralButton("Effacer", (d, w) -> CrashLog.clear())
                .setNegativeButton("Fermer", null)
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
        ImageView fav;
        View dim;
        MediaVH(View v) { super(v); }
    }

    private static class AlbumVH extends RecyclerView.ViewHolder {
        ImageView image, more;
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
                t.setTextColor(Ui.TEXT);
                t.setTextSize(14);
                t.setTypeface(null, Typeface.BOLD);
                t.setPadding(dp(8), dp(20), dp(8), dp(8));
                t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new HeaderVH(t);
            }
            if (type == T_ALBUM) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(6), dp(0), dp(6));
                row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                final AlbumVH vh = new AlbumVH(row);
                vh.image = new ImageView(MainActivity.this);
                vh.image.setBackground(Ui.rect(MainActivity.this, Ui.CARD, 14));
                vh.image.setClipToOutline(true);
                row.addView(vh.image, new LinearLayout.LayoutParams(dp(72), dp(72)));
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(16), 0, dp(8), 0);
                vh.name = new TextView(MainActivity.this);
                vh.name.setTextColor(Ui.TEXT);
                vh.name.setTextSize(16);
                vh.name.setSingleLine(true);
                vh.name.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(vh.name);
                vh.count = new TextView(MainActivity.this);
                vh.count.setTextColor(Ui.MUTED);
                vh.count.setTextSize(13);
                vh.count.setSingleLine(true);
                col.addView(vh.count);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                vh.more = new ImageView(MainActivity.this);
                vh.more.setImageResource(R.drawable.ic_more);
                vh.more.setColorFilter(0xFFB6BBC4);
                vh.more.setPadding(dp(12), dp(14), dp(12), dp(14));
                row.addView(vh.more, new LinearLayout.LayoutParams(dp(48), dp(52)));
                row.setOnClickListener(v -> {
                    int p = vh.getBindingAdapterPosition();
                    if (p < 0 || p >= rows.size()) return;
                    openAlbum = rows.get(p).album.path;
                    filter = F_ALL;
                    if (searchBar.getVisibility() == View.VISIBLE) closeSearchQuiet();
                    rebuildRows();
                    glm.scrollToPosition(0);
                });
                final View.OnClickListener menu = v -> {
                    int p = vh.getBindingAdapterPosition();
                    if (p >= 0 && p < rows.size()) albumMenu(vh.more, rows.get(p).album);
                };
                row.setOnLongClickListener(v -> { menu.onClick(v); return true; });
                vh.more.setOnClickListener(menu);
                return vh;
            }
            // T_MEDIA
            SquareFrame sq = new SquareFrame(MainActivity.this);
            sq.setPadding(dp(2), dp(2), dp(2), dp(2));
            sq.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            final MediaVH vh = new MediaVH(sq);
            vh.image = new ImageView(MainActivity.this);
            vh.image.setBackground(Ui.rect(MainActivity.this, Ui.CARD, 8));
            vh.image.setClipToOutline(true);
            sq.addView(vh.image, new FrameLayout.LayoutParams(-1, -1));
            vh.dim = new View(MainActivity.this);
            android.graphics.drawable.GradientDrawable dg = Ui.rect(MainActivity.this, 0x665B9CFF, 8);
            dg.setStroke(dp(2), Ui.ACCENT);
            vh.dim.setBackground(dg);
            sq.addView(vh.dim, new FrameLayout.LayoutParams(-1, -1));
            vh.badge = new TextView(MainActivity.this);
            vh.badge.setTextColor(Color.WHITE);
            vh.badge.setTextSize(11);
            vh.badge.setBackground(Ui.rect(MainActivity.this, 0x99000000, 10));
            vh.badge.setPadding(dp(6), dp(2), dp(7), dp(2));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            bl.setMargins(0, 0, dp(6), dp(6));
            sq.addView(vh.badge, bl);
            vh.check = new TextView(MainActivity.this);
            vh.check.setGravity(Gravity.CENTER);
            vh.check.setTextColor(Color.WHITE);
            vh.check.setTextSize(13);
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.START);
            cl.setMargins(dp(6), dp(6), 0, 0);
            sq.addView(vh.check, cl);
            vh.fav = new ImageView(MainActivity.this);
            vh.fav.setImageResource(R.drawable.ic_heart_filled);
            vh.fav.setColorFilter(0xFFFF5A6E);
            vh.fav.setBackground(Ui.rect(MainActivity.this, 0x66000000, 10));
            vh.fav.setPadding(dp(3), dp(3), dp(3), dp(3));
            FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.END);
            fl.setMargins(0, dp(6), dp(6), 0);
            sq.addView(vh.fav, fl);
            sq.setOnClickListener(v -> {
                int p = vh.getBindingAdapterPosition();
                if (p < 0 || p >= rows.size()) return;
                MediaItem m = rows.get(p).media;
                if (selecting) toggle(m, p);
                else openViewer(visibleMedia, Math.max(0, visibleMedia.indexOf(m)));
            });
            sq.setOnLongClickListener(v -> {
                int p = vh.getBindingAdapterPosition();
                if (p < 0 || p >= rows.size()) return true;
                MediaItem m = rows.get(p).media;
                if (!selecting) startSelection(m, p); else toggle(m, p);
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
                vh.count.setText(r.text);
                if (r.cover != null) {
                    vh.image.setScaleType(thumbsOriginal ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
                    vh.image.clearColorFilter();
                    thumbs.load(r.cover, vh.image);
                } else {
                    vh.image.setTag(null);
                    vh.image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    vh.image.setImageResource(R.drawable.ic_folder);
                    vh.image.setColorFilter(Ui.MUTED);
                }
            } else if (holder instanceof MediaVH) {
                MediaVH vh = (MediaVH) holder;
                MediaItem m = r.media;
                vh.image.setScaleType(thumbsOriginal ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
                thumbs.load(m, vh.image);
                if (m.video) {
                    vh.badge.setVisibility(View.VISIBLE);
                    vh.badge.setText(m.duration > 0 ? "▶ " + FileUtil.formatTime((int) m.duration) : "▶");
                } else vh.badge.setVisibility(View.GONE);
                vh.fav.setVisibility(Favorites.is(m.path) ? View.VISIBLE : View.GONE);
                boolean sel = selected.contains(m);
                vh.dim.setVisibility(sel ? View.VISIBLE : View.GONE);
                if (selecting) {
                    vh.check.setVisibility(View.VISIBLE);
                    android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                    g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    g.setColor(sel ? Ui.ACCENT : 0x55000000);
                    g.setStroke(dp(2), Color.WHITE);
                    vh.check.setBackground(g);
                    vh.check.setText(sel ? "✓" : "");
                } else vh.check.setVisibility(View.GONE);
            }
        }
    }
}
