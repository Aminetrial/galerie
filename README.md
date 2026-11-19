# Galerie — photos et vidéos (Android)

Application Android simple pour parcourir, regarder et ranger ses photos et vidéos.
L'APK est compilé automatiquement par GitHub Actions et publié dans les **Releases** à chaque envoi de code.

## Fonctions

**🖼️ Photos** : toutes les photos et vidéos du téléphone, classées par date (Aujourd'hui, Hier, …)
- Filtres Tout / 📷 Photos / 🎬 Vidéos, durée affichée sur les vidéos
- Appui long = sélection multiple → Supprimer · Déplacer · Renommer · Tout sélectionner

**📁 Albums** : tous les dossiers qui contiennent des photos/vidéos (couverture + nombre)
- Toucher = ouvrir l'album · Appui long = Renommer / Déplacer / Supprimer l'album
- Menu ⋮ → Nouvel album

**Partout** : 🔍 recherche (albums + fichiers) · 🔀 lecture aléatoire · taille de la grille (3/4/6)

**Lecteur** : barre du bas (vidéo seulement) : ⏪ -10 s · ⏸ Pause · +10 s ⏩ · ↻ Relancer · barre de progression · double-tap gauche/droite
- Flèches ‹ › sur les côtés (ou glisser) : élément précédent / suivant dans l'ordre
- En haut : ↶ Retour (élément vu juste avant) · 🔀 Aléatoire (sans répétition) · ⋮ Renommer / Supprimer

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
