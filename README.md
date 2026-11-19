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
- **Fin de vidéo** : enchaîne automatiquement sur l'élément suivant, ou sur un élément au hasard si le mode 🔀 est activé.
- **Format original** : miniatures entières (sans rogner), réglable (menu ⋮ → Miniatures, ou Réglages). Le lecteur affiche toujours l'image / la vidéo en entier.
- **Bouton 🔀 en bas à droite** (accueil et lecteur) : bleu = mode aléatoire activé. Une fois activé, ouvrez une photo/vidéo puis faites défiler : « suivant » donne un élément au hasard (sans répétition), « précédent » / ↶ revient en arrière.
- **Tri** (menu ⋮ → Trier…, ou Réglages) : date, date de modification, taille (stockage), nom (alphabétique) + « ⇅ Inverser l'ordre ». S'applique aux photos, vidéos et albums.

**Lecteur vidéo plein écran** (commandes par-dessus la vidéo, masquées automatiquement)
- Pastille du bas : ↻ relancer · ⏮ précédent · -10 s · ⏯ · +10 s · ⏭ suivant · ⚙ réglages (Propriétés, Partager, Renommer, Supprimer, Tourner l'écran)
- Au-dessus : barre de progression avec temps · ↶ retour (élément vu juste avant) · 🔓 verrouiller · 🔊 muet · vitesse (1x, 1.25x, 1.5x, 2x, 0.5x, 0.75x) · PiP · 📷 capture d'image (Pictures/Captures)
- Double-tap gauche/droite = -10 s / +10 s · glisser = précédent/suivant · bouton 🔀 en bas à droite = mode aléatoire

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
