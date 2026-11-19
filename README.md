# Galerie — photos et vidéos (Android)

Application Android simple pour parcourir, regarder et ranger ses photos et vidéos.
L'APK est compilé automatiquement par GitHub Actions et publié dans les **Releases** à chaque envoi de code.

## Fonctions

**Galerie**
- Grille avec miniatures (photos, vidéos ▶, dossiers 📁), tri naturel
- 🔍 Chercher un fichier ou un dossier (dans le dossier courant et ses sous-dossiers)
- 🔀 Aléatoire (dossier courant) / Aléatoire + sous-dossiers
- ➕ Créer un dossier
- Appui long sur un élément : ✏️ Renommer · 📦 Déplacer · 🗑️ Supprimer (fichier ou dossier)
- 💾 Choix du stockage : mémoire interne ou carte SD

**Lecteur vidéo**
- ⏪ -10 s · ⏸ Lecture/Pause · +10 s ⏩ · ↻ Relancer
- Barre de progression ; double-tap à gauche/droite = -10 s / +10 s

**Navigation dans la visionneuse**
- ◀ Préc. / Suiv. ▶ (ou glisser à gauche/droite)
- 🔀 Aléatoire sans répétition
- ↶ Retour : revient à l'élément vu juste avant (historique)
- Tap : afficher/masquer les commandes · glisser vers le bas : fermer

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
