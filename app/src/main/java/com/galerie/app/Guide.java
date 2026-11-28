package com.galerie.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.ScrollView;
import android.widget.TextView;

/** Fenêtre « Boutons et gestes » : explique toutes les commandes. */
public final class Guide {
    private Guide() { }

    private static final String[][] SECTIONS = {
            {"Dans la galerie",
                    "Toucher une miniature|l'ouvre en plein écran",
                    "Appui long|sélectionne (puis supprimer, déplacer, partager… en haut)",
                    "⋮ à droite d'un dossier|renommer, déplacer ou supprimer le dossier",
                    "Loupe|cherche un dossier ou un fichier par son nom",
                    "Bouton aléatoire (en haut)|ouvre un média au hasard et active le mode aléatoire",
                    "♥ (en haut)|affiche tous vos favoris ; en sélection, ⋮ → Ajouter aux favoris",
                    "Corbeille (⋮ ou Réglages)|tout ce qui est supprimé reste 30 jours : restaurer ou supprimer définitivement"},
            {"Dans le lecteur",
                    "Toucher l'écran|affiche ou masque les boutons",
                    "♥ (en haut)|ajoute ou retire le média des favoris",
                    "Glisser vers la gauche|média suivant",
                    "Glisser vers la droite|média précédent",
                    "Glisser vers le bas|ferme le lecteur",
                    "Pincer (photo)|zoome ; un double-tap revient à la taille normale",
                    "Double-tap à droite|avance de 10 s (vidéo)",
                    "Double-tap à gauche|selon Réglages : média suivant, reculer de 10 s ou rien"},
            {"Barre du lecteur",
                    "↻|relance la vidéo depuis le début",
                    "⏮ / ⏭|précédent / suivant (en mode aléatoire : ⏮ revient au média vu juste avant)",
                    "⏯|lecture / pause (y compris pour le passage automatique des images)",
                    "Aléatoire|bleu = activé : suivant et fin de vidéo ouvrent un média au hasard ; appui long = vidéos seulement, photos seulement ou les deux",
                    "🗑 (à droite)|met à la corbeille en 1 clic ; « ANNULER » reste affiché 6 secondes"},
            {"Menu ⋮ du lecteur",
                    "Partager|envoie le fichier vers une autre application",
                    "Déplacer vers…|choisit ou crée un dossier de destination",
                    "Propriétés|nom, dossier, taille et date du fichier"},
    };

    public static void show(Activity a) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (String[] sec : SECTIONS) {
            if (sb.length() > 0) sb.append("\n");
            int st = sb.length();
            sb.append(sec[0].toUpperCase(java.util.Locale.ROOT)).append("\n");
            sb.setSpan(new StyleSpan(Typeface.BOLD), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(Ui.ACCENT), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            for (int i = 1; i < sec.length; i++) {
                String[] kv = sec[i].split("\\|", 2);
                int s2 = sb.length();
                sb.append(kv[0]);
                sb.setSpan(new StyleSpan(Typeface.BOLD), s2, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(" : ").append(kv[1]).append("\n");
            }
        }
        TextView tv = new TextView(a);
        tv.setText(sb);
        tv.setTextSize(14);
        tv.setLineSpacing(0, 1.15f);
        int pad = Ui.dp(a, 20);
        tv.setPadding(pad, Ui.dp(a, 8), pad, Ui.dp(a, 8));
        ScrollView sv = new ScrollView(a);
        sv.addView(tv);
        new AlertDialog.Builder(a)
                .setTitle("Boutons et gestes")
                .setView(sv)
                .setPositiveButton("Compris", null)
                .show();
    }
}
