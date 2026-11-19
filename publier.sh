#!/data/data/com.termux/files/usr/bin/bash
# Publie le projet sur GitHub depuis Termux, attend la compilation
# et télécharge l'APK de la Release dans le dossier Téléchargements.
#
# Utilisation :  bash publier.sh            (dépôt nommé "galerie")
#                bash publier.sh mon-depot  (autre nom)
set -e
REPO="${1:-galerie}"
cd "$(dirname "$0")"

echo "== 1/5 Installation de git et gh =="
command -v git >/dev/null || pkg install -y git
command -v gh  >/dev/null || pkg install -y gh

echo "== 2/5 Connexion à GitHub =="
if ! gh auth status >/dev/null 2>&1; then
  echo "Choisissez : GitHub.com > HTTPS > Login with a web browser"
  gh auth login
fi
gh auth setup-git
git config --global user.name  >/dev/null || git config --global user.name  "$(gh api user -q .login)"
git config --global user.email >/dev/null || git config --global user.email "$(gh api user -q .login)@users.noreply.github.com"

echo "== 3/5 Envoi du code =="
[ -d .git ] || git init -q -b main
git add -A
git commit -qm "Mise à jour Galerie $(date '+%Y-%m-%d %H:%M')" || echo "(rien de nouveau à envoyer)"
if git remote get-url origin >/dev/null 2>&1; then
  git push -u origin main
else
  gh repo create "$REPO" --public --source=. --remote=origin --push
fi

echo "== 4/5 Compilation sur GitHub (3 à 6 minutes) =="
sleep 10
RUN_ID=$(gh run list --limit 1 --json databaseId -q '.[0].databaseId')
if [ -n "$RUN_ID" ]; then
  gh run watch "$RUN_ID" --exit-status || { echo "La compilation a échoué. Détails : gh run view $RUN_ID --log-failed"; exit 1; }
fi

echo "== 5/5 Téléchargement de l'APK =="
DEST="$HOME/storage/downloads"
[ -d "$DEST" ] || { termux-setup-storage; sleep 3; }
[ -d "$DEST" ] || DEST="$HOME"
TAG=$(gh release list --limit 1 --json tagName -q '.[0].tagName')
gh release download "$TAG" --pattern '*.apk' --dir "$DEST" --clobber
APK=$(ls -t "$DEST"/Galerie-*.apk | head -1)
echo "APK prêt : $APK"
echo "Release : $(gh repo view --json url -q .url)/releases/tag/$TAG"
command -v termux-open >/dev/null && termux-open "$APK" || true
