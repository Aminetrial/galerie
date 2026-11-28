package com.galerie.app;

import android.content.SharedPreferences;

/**
 * Actions possibles pour un double-tap sur la moitié gauche ou droite de l'écran du lecteur :
 * aucune, aller de l'avant (média suivant, ou au hasard si l'aléatoire est activé), ou favori.
 * (Les secondes -10 / +10 d'une vidéo ont leurs boutons dans la barre du bas.)
 */
public final class Taps {
    public static final int NONE = 0, NEXT = 1, FAV = 2;
    public static final int COUNT = 3;

    private Taps() { }

    public static String label(int a) {
        switch (a) {
            case NEXT: return "Aller de l'avant : média suivant, ou au hasard si l'aléatoire est activé";
            case FAV: return "Ajouter ou retirer des favoris";
            default: return "Aucune action";
        }
    }

    public static String shortLabel(int a) {
        switch (a) {
            case NEXT: return "Suivant / aléatoire";
            case FAV: return "Favori";
            default: return "Aucune action";
        }
    }

    /** Anciens réglages (v36 : 0 rien, 1 suivant, 2 -10 s, 3 +10 s, 4 favori, 5 photo favori/vidéo +10 s) ramenés aux 3 actions actuelles. */
    private static int fromV36(int a) {
        switch (a) {
            case 1: case 2: return NEXT;
            case 3: case 4: case 5: return FAV;
            default: return NONE;
        }
    }

    /** Moitié gauche. Défaut : aller de l'avant. */
    public static int left(SharedPreferences p) {
        if (p.getInt("tap_version", 0) >= 37 && p.contains("tap_left")) return p.getInt("tap_left", NEXT);
        if (p.contains("tap_left")) return fromV36(p.getInt("tap_left", 1));
        return p.getInt("left_tap", 1) == 2 ? NONE : NEXT;
    }

    /** Moitié droite. Défaut : favori. */
    public static int right(SharedPreferences p) {
        if (p.getInt("tap_version", 0) >= 37 && p.contains("tap_right")) return p.getInt("tap_right", FAV);
        if (p.contains("tap_right")) return fromV36(p.getInt("tap_right", 5));
        return FAV;
    }

    /** À appeler quand l'utilisateur enregistre un choix : marque les valeurs comme étant au nouveau format. */
    public static void save(SharedPreferences p, int left, int right) {
        p.edit().putInt("tap_left", left).putInt("tap_right", right).putInt("tap_version", 37).apply();
    }
}
