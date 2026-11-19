# Galerie — photos et vidéos (Android)

Application Android simple pour parcourir, regarder et ranger ses photos et vidéos.
L'APK est compilé automatiquement par GitHub Actions et publié dans les **Releases** à chaque envoi de code.

## Fonctions

Barre du bas : **📁 Albums · 🎬 Vidéos · 🖼️ Photos · ⚙️ Réglages**

- **📁 Albums (accueil)** : tous les dossiers (liste : couverture, nom, nombre, ⋮ pour renommer/déplacer/supprimer)
- **🎬 Vidéos** : seulement les dossiers qui contiennent des vidéos (ou toutes les vidéos en grille, par date)
- **🖼️ Photos** : toutes les photos en grille par date, vidéos exclues par défaut ; menu ⋮ → « Inclure les vidéos » ; « Afficher par dossiers » pour la vue dossiers
- **⚙️ Réglages** : taille de la grille, vidéos dans Photos, vues, nouvel album, actualiser
- Menu ⋮ → Sélectionner (ou appui long) : supprimer, déplacer, renommer plusieurs éléments
- 🔍 recherche (albums + fichiers) · 🔀 lecture aléatoire

**Lecteur vidéo plein écran** (commandes par-dessus la vidéo, masquées automatiquement)
- Barre de progression avec temps · pastille du bas : 🔓 verrouiller · ⏮ précédent · -10 s · ⏯ · +10 s · ⏭ suivant · ⛶ image entière / plein écran rogné
- Outils : 🔀 aléatoire sans répétition · ↶ retour (élément vu juste avant) · ↻ relancer · 🔊 muet · vitesse (1x, 1.25x, 1.5x, 2x, 0.5x, 0.75x) · PiP (image dans l'image) · 📷 capture d'image (Pictures/Captures)
- Haut : ← retour · 🔄 rotation écran · ⋮ renommer / supprimer · double-tap gauche/droite = -10 s / +10 s · glisser = précédent/suivant

## Publier avec Termux

```bash
termux-setup-storage                 # une seule fois : accès au dossier Téléchargements
pkg install -y unzip
cp ~/storage/downloads/galerie.zip ~ && cd ~ && unzip -o galerie.zip
cd galerie
bash publier.sh
```

Le script installe `git` et `gh`, vous connecte à GitHub, crée le dépôt `galerie`,
envoie le code, attend la compilation puis télécharge l'APK dans Téléchargements.

Pour publier une nouvelle version après une modification : `bash publier.sh` à nouveau.
Chaque envoi crée une Release `v1.0.N` contenant `Galerie-v1.0.N.apk`.

## Première ouverture

Android demande l'autorisation **« Accès à tous les fichiers »** : activez-la pour la Galerie
(nécessaire pour renommer, déplacer et supprimer).

## Note sur la signature

L'APK est signé avec `app/galerie.keystore` (inclus dans le projet) pour que les mises à jour
s'installent par-dessus l'ancienne version. Pour un usage personnel c'est suffisant ;
si le dépôt est public, n'importe qui pourrait signer un APK « Galerie » avec cette clé.
