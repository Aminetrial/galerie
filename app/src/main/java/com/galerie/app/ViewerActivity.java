package com.galerie.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import java.util.List;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Visionneuse photo + lecteur vidéo.
 * - Vidéo : -10 s, lecture/pause, +10 s, relancer, barre de progression.
 * - Navigation : précédent / suivant (ou glisser), aléatoire sans répétition,
 *   « Retour » qui revient à l'élément vu juste avant (historique).
 */
public class ViewerActivity extends Activity {

    public static ArrayList<String> sPaths;

    private ArrayList<String> paths;
    private int index;
    private boolean randomMode;
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final ArrayList<Integer> shuffleBag = new ArrayList<>();

    private ImageView imageView;
    private VideoView videoView;
    private LinearLayout topBar, bottomBar, videoControls;
    private TextView title, counter, timeText;
    private SeekBar seek;
    private Button playBtn, prevBtn, nextBtn;
    private boolean controlsVisible = true;
    private boolean userSeeking = false;
    private boolean isVideo = false;
    private int resumePos = -1;
    private int loadToken = 0;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private GestureDetector gestures;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (isVideo && !userSeeking) {
                int cur = videoView.getCurrentPosition();
                int dur = videoView.getDuration();
                if (dur > 0) {
                    seek.setMax(dur);
                    seek.setProgress(cur);
                    timeText.setText(FileUtil.formatTime(cur) + " / " + FileUtil.formatTime(dur));
                }
            }
            h.postDelayed(this, 400);
        }
    };

    private final Runnable autoHide = () -> {
        if (isVideo && videoView.isPlaying()) setControlsVisible(false);
    };

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (state != null) {
            paths = state.getStringArrayList("paths");
            index = state.getInt("index", 0);
            resumePos = state.getInt("pos", -1);
            randomMode = state.getBoolean("random", false);
        } else {
            paths = sPaths;
            index = getIntent().getIntExtra("index", 0);
            randomMode = getIntent().getBooleanExtra("random", false);
        }
        if (paths == null || paths.isEmpty()) {
            finish();
            return;
        }
        if (index < 0 || index >= paths.size()) index = 0;

        buildUi();
        setupGestures();
        show(index);
        h.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isVideo) {
            resumePos = videoView.getCurrentPosition();
            videoView.pause();
            updatePlayButton();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putStringArrayList("paths", paths);
        out.putInt("index", index);
        out.putInt("pos", isVideo ? videoView.getCurrentPosition() : -1);
        out.putBoolean("random", randomMode);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        loader.shutdownNow();
        if (videoView != null) videoView.stopPlayback();
    }

    // ------------------------------------------------------------------ interface

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(2), 0, dp(2), 0);
        b.setOnClickListener(v -> {
            l.onClick(v);
            scheduleAutoHide();
        });
        return b;
    }

    private void addWeighted(LinearLayout row, View v) {
        row.addView(v, new LinearLayout.LayoutParams(0, dp(48), 1));
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(imageView, new FrameLayout.LayoutParams(-1, -1));

        videoView = new VideoView(this);
        root.addView(videoView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        // --- barre du haut
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xAA000000);
        topBar.setPadding(dp(4), dp(4), dp(12), dp(4));
        Button close = btn("✕", v -> finish());
        topBar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        topBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        counter = new TextView(this);
        counter.setTextColor(0xFFBDBDBD);
        counter.setTextSize(13);
        topBar.addView(counter);
        topBar.addView(btn("↶", v -> goBackInHistory()), new LinearLayout.LayoutParams(dp(44), dp(48)));
        topBar.addView(btn("🔀", v -> randomJump()), new LinearLayout.LayoutParams(dp(44), dp(48)));
        topBar.addView(btn("⋮", v -> showMenu()), new LinearLayout.LayoutParams(dp(36), dp(48)));
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // --- flèches précédent / suivant sur les côtés
        prevBtn = btn("‹", v -> prev());
        prevBtn.setTextSize(32);
        prevBtn.setBackgroundColor(0x44000000);
        root.addView(prevBtn, new FrameLayout.LayoutParams(dp(40), dp(96), Gravity.START | Gravity.CENTER_VERTICAL));
        nextBtn = btn("›", v -> next());
        nextBtn.setTextSize(32);
        nextBtn.setBackgroundColor(0x44000000);
        root.addView(nextBtn, new FrameLayout.LayoutParams(dp(40), dp(96), Gravity.END | Gravity.CENTER_VERTICAL));

        // --- barre du bas
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setBackgroundColor(0xAA000000);
        bottomBar.setPadding(dp(4), dp(4), dp(4), dp(8));

        videoControls = new LinearLayout(this);
        videoControls.setOrientation(LinearLayout.VERTICAL);

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        seek = new SeekBar(this);
        seekRow.addView(seek, new LinearLayout.LayoutParams(0, -2, 1));
        timeText = new TextView(this);
        timeText.setTextColor(Color.WHITE);
        timeText.setTextSize(12);
        timeText.setPadding(dp(4), 0, dp(8), 0);
        timeText.setText("0:00 / 0:00");
        seekRow.addView(timeText);
        videoControls.addView(seekRow);

        LinearLayout vRow = new LinearLayout(this);
        vRow.setOrientation(LinearLayout.HORIZONTAL);
        addWeighted(vRow, btn("⏪ -10 s", v -> skip(-10000)));
        playBtn = btn("⏸ Pause", v -> togglePlay());
        addWeighted(vRow, playBtn);
        addWeighted(vRow, btn("+10 s ⏩", v -> skip(10000)));
        addWeighted(vRow, btn("↻ Relancer", v -> restart()));
        videoControls.addView(vRow);
        bottomBar.addView(videoControls);

        root.addView(bottomBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) timeText.setText(FileUtil.formatTime(p) + " / " + FileUtil.formatTime(s.getMax()));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                userSeeking = true;
                h.removeCallbacks(autoHide);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                videoView.seekTo(s.getProgress());
                scheduleAutoHide();
            }
        });

        videoView.setOnPreparedListener(mp -> {
            seek.setMax(Math.max(0, videoView.getDuration()));
            if (resumePos > 0) {
                videoView.seekTo(resumePos);
                resumePos = -1;
            }
            videoView.start();
            updatePlayButton();
            scheduleAutoHide();
        });
        videoView.setOnCompletionListener(mp -> {
            updatePlayButton();
            setControlsVisible(true);
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Impossible de lire cette vidéo", Toast.LENGTH_SHORT).show();
            updatePlayButton();
            return true;
        });
    }

    private void setControlsVisible(boolean v) {
        controlsVisible = v;
        topBar.setVisibility(v ? View.VISIBLE : View.GONE);
        prevBtn.setVisibility(v ? View.VISIBLE : View.GONE);
        nextBtn.setVisibility(v ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(v && isVideo ? View.VISIBLE : View.GONE);
        if (v) scheduleAutoHide();
    }

    private void scheduleAutoHide() {
        h.removeCallbacks(autoHide);
        h.postDelayed(autoHide, 4000);
    }

    // ------------------------------------------------------------------ gestes

    private boolean inside(View v, MotionEvent e) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        Rect r = new Rect();
        return v.getGlobalVisibleRect(r) && r.contains((int) e.getRawX(), (int) e.getRawY());
    }

    private boolean onControls(MotionEvent e) {
        return inside(topBar, e) || inside(bottomBar, e) || inside(prevBtn, e) || inside(nextBtn, e);
    }

    private void showMenu() {
        if (isVideo) videoView.pause();
        new AlertDialog.Builder(this)
                .setItems(new String[]{"✏️  Renommer", "🗑️  Supprimer"}, (d, w) -> {
                    if (w == 0) renameCurrent(); else deleteCurrent();
                })
                .show();
    }

    private void setupGestures() {
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (onControls(e)) return false;
                setControlsVisible(!controlsVisible);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!isVideo || onControls(e)) return false;
                // double-tap côté gauche = -10 s, côté droit = +10 s
                int w = getResources().getDisplayMetrics().widthPixels;
                skip(e.getRawX() < w / 2f ? -10000 : 10000);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || onControls(e1)) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > dp(80)) {
                    if (dx < 0) next(); else prev();
                    return true;
                }
                if (dy > dp(150) && Math.abs(dy) > Math.abs(dx)) { // glisser vers le bas = fermer
                    finish();
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (gestures != null) gestures.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------------ affichage

    private void show(int i) {
        index = i;
        final String p = paths.get(i);
        final File f = new File(p);
        title.setText(f.getName());
        counter.setText((i + 1) + " / " + paths.size());
        final int token = ++loadToken;

        if (FileUtil.isVideo(f)) {
            isVideo = true;
            imageView.setImageDrawable(null);
            imageView.setVisibility(View.GONE);
            videoControls.setVisibility(View.VISIBLE);
            videoView.setVisibility(View.VISIBLE);
            seek.setProgress(0);
            timeText.setText("0:00 / 0:00");
            videoView.setVideoPath(p);
            updatePlayButton();
        } else {
            isVideo = false;
            videoView.stopPlayback();
            videoView.setVisibility(View.GONE);
            videoControls.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageDrawable(null);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            final int req = Math.max(dm.widthPixels, dm.heightPixels);
            loader.execute(() -> {
                Bitmap bm = null;
                try {
                    bm = FileUtil.decodeSampled(f, req, req);
                } catch (OutOfMemoryError oom) {
                    try { bm = FileUtil.decodeSampled(f, req / 2, req / 2); } catch (Throwable ignored) { }
                } catch (Throwable ignored) { }
                final Bitmap res = bm;
                h.post(() -> {
                    if (token != loadToken) return;
                    if (res != null) imageView.setImageBitmap(res);
                    else Toast.makeText(this, "Impossible d'afficher cette image", Toast.LENGTH_SHORT).show();
                });
            });
        }
        setControlsVisible(true);
    }

    // ------------------------------------------------------------------ navigation

    private void goTo(int i) {
        if (i < 0 || i >= paths.size() || i == index) return;
        history.push(paths.get(index));
        if (history.size() > 500) history.removeLast();
        show(i);
    }

    private void next() {
        if (index + 1 < paths.size()) goTo(index + 1);
        else Toast.makeText(this, "Dernier élément", Toast.LENGTH_SHORT).show();
    }

    private void prev() {
        if (index > 0) goTo(index - 1);
        else Toast.makeText(this, "Premier élément", Toast.LENGTH_SHORT).show();
    }

    /** Aléatoire « sans répétition » : chaque élément passe une fois avant de recommencer. */
    private void randomJump() {
        if (paths.size() < 2) return;
        if (shuffleBag.isEmpty()) {
            for (int i = 0; i < paths.size(); i++) if (i != index) shuffleBag.add(i);
            Collections.shuffle(shuffleBag);
        }
        int r = shuffleBag.remove(shuffleBag.size() - 1);
        if (r == index && !shuffleBag.isEmpty()) r = shuffleBag.remove(shuffleBag.size() - 1);
        goTo(r);
    }

    private void goBackInHistory() {
        if (history.isEmpty()) {
            Toast.makeText(this, "Aucun élément précédent dans l'historique", Toast.LENGTH_SHORT).show();
            return;
        }
        while (!history.isEmpty()) {
            int i = paths.indexOf(history.pop());
            if (i >= 0) { show(i); return; }
        }
        Toast.makeText(this, "Aucun élément précédent dans l'historique", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ renommer / supprimer

    private void renameCurrent() {
        final String old = paths.get(index);
        final File src = new File(old);
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(src.getName());
        int dot = src.getName().lastIndexOf('.');
        if (dot > 0) et.setSelection(0, dot); else et.selectAll();
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et);
        if (isVideo) videoView.pause();
        new AlertDialog.Builder(this)
                .setTitle("Renommer")
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!FileUtil.isValidName(n) || n.equals(src.getName())) return;
                    File dest = new File(src.getParentFile(), n);
                    if (dest.exists()) { Toast.makeText(this, "Un fichier porte déjà ce nom", Toast.LENGTH_SHORT).show(); return; }
                    if (!src.renameTo(dest)) { Toast.makeText(this, "Échec du renommage", Toast.LENGTH_SHORT).show(); return; }
                    String np = dest.getAbsolutePath();
                    paths.set(index, np);
                    MediaRepo repo = MediaRepo.get();
                    for (MediaItem m : repo.all) if (m.path.equals(old)) { repo.moved(m, np); break; }
                    repo.dirty = true;
                    List<String> scan = new ArrayList<>();
                    scan.add(old);
                    scan.add(np);
                    MediaRepo.scan(this, scan);
                    title.setText(n);
                    Toast.makeText(this, "Renommé", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void deleteCurrent() {
        final String p = paths.get(index);
        if (isVideo) videoView.pause();
        new AlertDialog.Builder(this)
                .setTitle("Supprimer ?")
                .setMessage("« " + new File(p).getName() + " » sera supprimé définitivement.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    File f = new File(p);
                    if (f.exists() && !f.delete()) {
                        Toast.makeText(this, "Suppression impossible", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    MediaRepo repo = MediaRepo.get();
                    for (MediaItem m : repo.all) if (m.path.equals(p)) { MediaRepo.deleteFromMediaStore(this, m); break; }
                    repo.removePath(this, p);
                    repo.dirty = true;
                    Toast.makeText(this, "Supprimé", Toast.LENGTH_SHORT).show();
                    paths.remove(index);
                    shuffleBag.clear();
                    if (paths.isEmpty()) { finish(); return; }
                    if (isVideo) videoView.stopPlayback();
                    show(Math.min(index, paths.size() - 1));
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ------------------------------------------------------------------ vidéo

    private void togglePlay() {
        if (!isVideo) return;
        if (videoView.isPlaying()) videoView.pause();
        else videoView.start();
        updatePlayButton();
    }

    private void skip(int ms) {
        if (!isVideo) return;
        int dur = videoView.getDuration();
        int pos = videoView.getCurrentPosition() + ms;
        if (pos < 0) pos = 0;
        if (dur > 0 && pos > dur - 500) pos = Math.max(0, dur - 500);
        videoView.seekTo(pos);
        Toast.makeText(this, ms > 0 ? "+10 s" : "-10 s", Toast.LENGTH_SHORT).show();
    }

    private void restart() {
        if (!isVideo) return;
        videoView.seekTo(0);
        videoView.start();
        updatePlayButton();
    }

    private void updatePlayButton() {
        h.postDelayed(() -> playBtn.setText(videoView.isPlaying() ? "⏸ Pause" : "▶ Lecture"), 100);
    }
}
