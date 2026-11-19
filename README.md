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
- **Format original** : miniatures entières (sans rogner), réglable (menu ⋮ → Miniatures, ou Réglages). Le lecteur affiche toujours l'image / la vidéo en entier.
- **Mode aléatoire** : bouton 🔀 dans le lecteur, ou ⚙️ Réglages (même réglage).
- **Tri** (menu ⋮ → Trier…, ou Réglages) : date, date de modification, taille (stockage), nom (alphabétique) + « ⇅ Inverser l'ordre ». S'applique aux photos, vidéos et albums.

**Lecteur photo / vidéo** : une seule barre de commandes : ↻ relancer · ⏮ précédent · ⏯ · ⏭ suivant · 🔀 aléatoire (activé ou non)
- Aléatoire activé : ⏭ / glisser gauche / fin de vidéo / fin du délai d'une image = élément au hasard (sans répétition) ; ⏮ / glisser droite = revenir à l'élément vu avant
- Aléatoire désactivé : suivant / précédent dans l'ordre de la liste affichée (tout, un dossier, une recherche)
- Fin de vidéo : passe au suivant (ou au hasard). Images : Réglages → « Passage automatique des images » (désactivé, 5 s, 10 s)
- Barre de progression (vidéo) · double-tap droite = +10 s, double-tap gauche = média suivant (réglable : -10 s / suivant / rien) · glisser = précédent/suivant · glisser vers le bas = fermer

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

## Apparence (v14)
Thème sombre bleu, icônes vectorielles (barre du bas, lecteur), icône adaptative (Android 13 : icône monochrome), écran de démarrage, miniatures arrondies avec badge durée, fondus, retour haptique, plein écran immersif dans le lecteur.

## v17 — interface réécrite
Nouvelle MainActivity (barre du haut maison, recherche intégrée, sélection avec suppression / déplacement / renommage, menus ⋮), thème sans ActionBar, réglages en cartes, journal de diagnostic dans Réglages.

## v20 — correctif du blocage
Cause trouvée grâce au journal : le tri par nom utilisait une expression régulière à chaque comparaison (plusieurs centaines de milliers de fois) sur le fil principal → « L'application ne répond pas ». Le tri est maintenant sans regex, fait en arrière-plan et mis en cache.
