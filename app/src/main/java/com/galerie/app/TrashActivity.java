package com.galerie.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Corbeille : tout ce qui a été supprimé, 30 jours.
 * Toucher un élément le sélectionne ; en bas : Restaurer (retour dans son dossier d'origine)
 * ou Supprimer définitivement (avec confirmation). Menu ⋮ : Tout sélectionner, Vider la corbeille.
 */
public class TrashActivity extends Activity {
    private static final int COLS = 3;

    private final List<Trash.Entry> entries = new ArrayList<>();
    private final Set<String> sel = new HashSet<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private RecyclerView rv;
    private RecyclerView.Adapter<Holder> adapter;
    private ThumbLoader thumbs;
    private TextView titleView, subView, emptyView, infoView;
    private LinearLayout actionBar;
    private Button restoreBtn, deleteBtn;
    private ImageView backBtn;
    private boolean busy = false;

    private int dp(int v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CrashLog.start(this);
        thumbs = new ThumbLoader(this);
        getWindow().setStatusBarColor(Ui.BAR);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        // --- barre du haut
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Ui.BAR);
        top.addView(Ui.iconButton(this, R.drawable.ic_back, v -> onBackPressed()), new LinearLayout.LayoutParams(dp(48), dp(56)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(this);
        titleView.setTextColor(Ui.TEXT);
        titleView.setTextSize(19);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setText("Corbeille");
        col.addView(titleView);
        subView = new TextView(this);
        subView.setTextColor(Ui.MUTED);
        subView.setTextSize(12);
        col.addView(subView);
        top.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(Ui.iconButton(this, R.drawable.ic_more, v -> showMenu(v)), new LinearLayout.LayoutParams(dp(48), dp(56)));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(56)));

        // --- information
        infoView = new TextView(this);
        infoView.setText("Les éléments restent 30 jours dans la corbeille, puis sont supprimés définitivement. Touchez pour sélectionner.");
        infoView.setTextColor(Ui.MUTED);
        infoView.setTextSize(13);
        infoView.setLineSpacing(0, 1.1f);
        infoView.setPadding(dp(14), dp(10), dp(14), dp(10));
        infoView.setBackground(Ui.rect(this, Ui.CARD, 12));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.setMargins(dp(10), dp(10), dp(10), dp(6));
        root.addView(infoView, ip);

        // --- grille
        FrameLayout content = new FrameLayout(this);
        rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, COLS));
        rv.setClipToPadding(false);
        rv.setPadding(dp(2), dp(2), dp(2), dp(8));
        adapter = new RecyclerView.Adapter<Holder>() {
            @Override
            public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
                return new Holder(makeCell());
            }

            @Override
            public void onBindViewHolder(Holder hd, int i) {
                bind(hd, entries.get(i));
            }

            @Override
            public int getItemCount() {
                return entries.size();
            }
        };
        rv.setAdapter(adapter);
        content.addView(rv, new FrameLayout.LayoutParams(-1, -1));
        emptyView = new TextView(this);
        emptyView.setText("La corbeille est vide");
        emptyView.setTextColor(Ui.MUTED);
        emptyView.setTextSize(16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        // --- actions (visibles quand quelque chose est sélectionné)
        actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setBackgroundColor(Ui.BAR);
        actionBar.setPadding(dp(10), dp(10), dp(10), dp(10));
        restoreBtn = actionButton("Restaurer", Ui.ACCENT, v -> restoreSelected());
        deleteBtn = actionButton("Supprimer", 0xFFE5484D, v -> confirmDeleteSelected());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(48), 1);
        bp.setMargins(dp(4), 0, dp(4), 0);
        actionBar.addView(restoreBtn, bp);
        actionBar.addView(deleteBtn, new LinearLayout.LayoutParams(bp));
        actionBar.setVisibility(View.GONE);
        root.addView(actionBar, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        load();
    }

    private Button actionButton(String text, int color, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(null, Typeface.BOLD);
        b.setBackground(Ui.rect(this, color, 14));
        b.setOnClickListener(l);
        return b;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        thumbs.shutdown();
        bg.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (!sel.isEmpty()) {
            sel.clear();
            refreshUi();
            adapter.notifyDataSetChanged();
        } else super.onBackPressed();
    }

    // ------------------------------------------------------------------ cellules

    private static class Holder extends RecyclerView.ViewHolder {
        final FrameLayout cell;
        final ImageView img, check;
        final View shade;
        final TextView badge;

        Holder(FrameLayout c) {
            super(c);
            cell = c;
            img = (ImageView) c.getChildAt(0);
            shade = c.getChildAt(1);
            check = (ImageView) c.getChildAt(2);
            badge = (TextView) c.getChildAt(3);
        }
    }

    private FrameLayout makeCell() {
        int size = getResources().getDisplayMetrics().widthPixels / COLS - dp(2);
        FrameLayout c = new FrameLayout(this);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(size, size);
        lp.setMargins(dp(1), dp(1), dp(1), dp(1));
        c.setLayoutParams(lp);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(Ui.rect(this, Ui.CARD, 8));
        img.setClipToOutline(true);
        c.addView(img, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(Ui.rect(this, 0x665B9CFF, 8));
        shade.setVisibility(View.GONE);
        c.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        ImageView check = new ImageView(this);
        check.setImageResource(R.drawable.ic_check);
        check.setColorFilter(Color.WHITE);
        check.setBackground(Ui.rect(this, Ui.ACCENT, 12));
        check.setPadding(dp(3), dp(3), dp(3), dp(3));
        check.setVisibility(View.GONE);
        FrameLayout.LayoutParams ck = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.END);
        ck.setMargins(0, dp(6), dp(6), 0);
        c.addView(check, ck);
        TextView badge = new TextView(this);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(11);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setPadding(dp(7), dp(2), dp(7), dp(2));
        badge.setBackground(Ui.rect(this, 0xA6000000, 9));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        bl.setMargins(dp(6), 0, 0, dp(6));
        c.addView(badge, bl);
        return c;
    }

    private void bind(Holder hd, final Trash.Entry e) {
        MediaItem m = new MediaItem(-1, e.file.getAbsolutePath(), e.deleted, e.video(), 0);
        thumbs.load(m, hd.img);
        boolean on = sel.contains(e.name);
        hd.shade.setVisibility(on ? View.VISIBLE : View.GONE);
        hd.check.setVisibility(on ? View.VISIBLE : View.GONE);
        int d = e.daysLeft();
        hd.badge.setText(d <= 1 ? "1 j" : d + " j");
        hd.cell.setContentDescription(e.displayName() + ", " + d + " jours restants");
        hd.cell.setOnClickListener(v -> {
            if (busy) return;
            if (!sel.remove(e.name)) sel.add(e.name);
            refreshUi();
            int i = entries.indexOf(e);
            if (i >= 0) adapter.notifyItemChanged(i);
        });
    }

    // ------------------------------------------------------------------ données

    private void load() {
        bg.execute(() -> {
            Trash.purgeExpired();
            final List<Trash.Entry> list = Trash.list();
            h.post(() -> {
                entries.clear();
                entries.addAll(list);
                Set<String> names = new HashSet<>();
                for (Trash.Entry e : entries) names.add(e.name);
                sel.retainAll(names);
                adapter.notifyDataSetChanged();
                refreshUi();
            });
        });
    }

    private static String sizeText(long b) {
        if (b >= 1073741824L) return String.format(Locale.FRANCE, "%.1f Go", b / 1073741824.0);
        if (b >= 1048576L) return String.format(Locale.FRANCE, "%.0f Mo", b / 1048576.0);
        return (b / 1024) + " Ko";
    }

    private void refreshUi() {
        boolean selecting = !sel.isEmpty();
        if (selecting) {
            titleView.setText(sel.size() + (sel.size() > 1 ? " sélectionnés" : " sélectionné"));
            subView.setText("Restaurer ou supprimer définitivement");
        } else {
            titleView.setText("Corbeille");
            subView.setText(entries.isEmpty() ? "" : entries.size() + (entries.size() > 1 ? " éléments · " : " élément · ")
                    + sizeText(Trash.totalBytes(entries)));
        }
        actionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        infoView.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private List<Trash.Entry> selected() {
        List<Trash.Entry> out = new ArrayList<>();
        for (Trash.Entry e : entries) if (sel.contains(e.name)) out.add(e);
        return out;
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Tout sélectionner");
        pm.getMenu().add(0, 2, 0, "Vider la corbeille");
        pm.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 1) {
                for (Trash.Entry e : entries) sel.add(e.name);
                refreshUi();
                adapter.notifyDataSetChanged();
            } else if (!entries.isEmpty()) {
                confirmDelete(new ArrayList<>(entries), true);
            }
            return true;
        });
        pm.show();
    }

    // ------------------------------------------------------------------ actions

    private void restoreSelected() {
        final List<Trash.Entry> chosen = selected();
        if (chosen.isEmpty() || busy) return;
        busy = true;
        Toast.makeText(this, "Restauration…", Toast.LENGTH_SHORT).show();
        bg.execute(() -> {
            final List<String> paths = new ArrayList<>();
            int failed = 0;
            for (Trash.Entry e : chosen) {
                File f = Trash.restore(e);
                if (f != null) paths.add(f.getAbsolutePath()); else failed++;
            }
            if (!paths.isEmpty()) {
                final CountDownLatch latch = new CountDownLatch(paths.size());
                MediaScannerConnection.scanFile(getApplicationContext(), paths.toArray(new String[0]), null,
                        (p, u) -> latch.countDown());
                try { latch.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
            final int ok = paths.size(), bad = failed;
            h.post(() -> {
                busy = false;
                sel.clear();
                if (ok > 0) {
                    MediaRepo.get().loaded = false; // la galerie se relit à son retour
                    MediaRepo.get().dirty = true;
                }
                String msg = ok + (ok > 1 ? " éléments restaurés" : " élément restauré") + " dans leur dossier d'origine";
                if (bad > 0) msg += " (" + bad + " impossible)";
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                load();
            });
        });
    }

    private void confirmDeleteSelected() {
        List<Trash.Entry> chosen = selected();
        if (!chosen.isEmpty() && !busy) confirmDelete(chosen, false);
    }

    private void confirmDelete(final List<Trash.Entry> chosen, boolean all) {
        int n = chosen.size();
        new AlertDialog.Builder(this)
                .setTitle(all ? "Vider la corbeille ?" : "Supprimer définitivement ?")
                .setMessage(n + (n > 1 ? " éléments seront supprimés" : " élément sera supprimé")
                        + " définitivement. Cette action est irréversible.")
                .setPositiveButton(all ? "Vider" : "Supprimer", (d, w) -> {
                    busy = true;
                    bg.execute(() -> {
                        int ok = 0;
                        for (Trash.Entry e : chosen) if (Trash.deleteForever(e)) ok++;
                        final int done = ok;
                        h.post(() -> {
                            busy = false;
                            sel.clear();
                            Toast.makeText(this, done + (done > 1 ? " éléments supprimés" : " élément supprimé"), Toast.LENGTH_SHORT).show();
                            load();
                        });
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }
}
