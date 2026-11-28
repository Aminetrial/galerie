package com.galerie.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tous les médias marqués ♥. Toucher ouvre le lecteur sur cette liste ; appui long : retirer des favoris. */
public class FavoritesActivity extends Activity {
    private final List<MediaItem> items = new ArrayList<>();
    private RecyclerView rv;
    private RecyclerView.Adapter<Holder> adapter;
    private ThumbLoader thumbs;
    private TextView subView, emptyView;
    private int cols = 3;
    private boolean original = true;

    private int dp(int v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CrashLog.start(this);
        Favorites.init(this);
        android.content.SharedPreferences p = getSharedPreferences("galerie", MODE_PRIVATE);
        cols = Math.max(2, Math.min(6, p.getInt("cols", 3)));
        original = p.getBoolean("thumbs_original", true);
        thumbs = new ThumbLoader(this);
        getWindow().setStatusBarColor(Ui.BAR);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Ui.BAR);
        top.addView(Ui.iconButton(this, R.drawable.ic_back, v -> finish()), new LinearLayout.LayoutParams(dp(48), dp(56)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Favoris");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(19);
        title.setTypeface(null, Typeface.BOLD);
        col.addView(title);
        subView = new TextView(this);
        subView.setTextColor(Ui.MUTED);
        subView.setTextSize(12);
        col.addView(subView);
        top.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(56)));

        FrameLayout content = new FrameLayout(this);
        rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, cols));
        rv.setClipToPadding(false);
        rv.setPadding(dp(2), dp(2), dp(2), dp(8));
        adapter = new RecyclerView.Adapter<Holder>() {
            @Override
            public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
                return new Holder(makeCell());
            }

            @Override
            public void onBindViewHolder(Holder hd, int i) {
                bind(hd, i);
            }

            @Override
            public int getItemCount() {
                return items.size();
            }
        };
        rv.setAdapter(adapter);
        content.addView(rv, new FrameLayout.LayoutParams(-1, -1));
        emptyView = new TextView(this);
        emptyView.setTextColor(Ui.MUTED);
        emptyView.setTextSize(16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(32), 0, dp(32), 0);
        emptyView.setLineSpacing(0, 1.15f);
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbs.shutdown();
    }

    private void refresh() {
        items.clear();
        MediaRepo repo = MediaRepo.get();
        if (repo.loaded) {
            for (MediaItem m : repo.all) if (Favorites.is(m.path)) items.add(m);
        }
        adapter.notifyDataSetChanged();
        if (!repo.loaded) {
            emptyView.setText("La bibliothèque se charge. Revenez dans un instant.");
            subView.setText("");
        } else if (items.isEmpty()) {
            emptyView.setText("Aucun favori pour l'instant.\nTouchez ♥ en haut du lecteur pour en ajouter.");
            subView.setText("");
        } else {
            subView.setText(items.size() + (items.size() > 1 ? " éléments" : " élément"));
        }
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static class Holder extends RecyclerView.ViewHolder {
        final ImageView img;
        final TextView badge;

        Holder(FrameLayout c) {
            super(c);
            img = (ImageView) c.getChildAt(0);
            badge = (TextView) c.getChildAt(1);
        }
    }

    private FrameLayout makeCell() {
        int size = getResources().getDisplayMetrics().widthPixels / cols - dp(2);
        FrameLayout c = new FrameLayout(this);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(size, size);
        lp.setMargins(dp(1), dp(1), dp(1), dp(1));
        c.setLayoutParams(lp);
        ImageView img = new ImageView(this);
        img.setBackground(Ui.rect(this, Ui.CARD, 8));
        img.setClipToOutline(true);
        c.addView(img, new FrameLayout.LayoutParams(-1, -1));
        TextView badge = new TextView(this);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(11);
        badge.setBackground(Ui.rect(this, 0x99000000, 10));
        badge.setPadding(dp(6), dp(2), dp(7), dp(2));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        bl.setMargins(0, 0, dp(6), dp(6));
        c.addView(badge, bl);
        return c;
    }

    private void bind(final Holder hd, int i) {
        final MediaItem m = items.get(i);
        hd.img.setScaleType(original ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        thumbs.load(m, hd.img);
        if (m.video) {
            hd.badge.setVisibility(View.VISIBLE);
            hd.badge.setText(m.duration > 0 ? "▶ " + FileUtil.formatTime((int) m.duration) : "▶");
        } else hd.badge.setVisibility(View.GONE);
        hd.itemView.setContentDescription(m.name());
        hd.itemView.setOnClickListener(v -> {
            int p = hd.getBindingAdapterPosition();
            if (p < 0 || p >= items.size()) return;
            ArrayList<String> paths = new ArrayList<>(items.size());
            for (MediaItem x : items) paths.add(x.path);
            ViewerActivity.sPaths = paths;
            Intent it = new Intent(this, ViewerActivity.class);
            it.putExtra("index", p);
            it.putExtra("random", false);
            startActivity(it);
        });
        hd.itemView.setOnLongClickListener(v -> {
            PopupMenu pm = new PopupMenu(this, v);
            pm.getMenu().add(0, 1, 0, "Retirer des favoris");
            pm.setOnMenuItemClickListener(mi -> {
                Favorites.removeAll(Collections.singletonList(m.path));
                Toast.makeText(this, "Retiré des favoris", Toast.LENGTH_SHORT).show();
                refresh();
                return true;
            });
            pm.show();
            return true;
        });
    }
}
