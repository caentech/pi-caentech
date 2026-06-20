#!/usr/bin/env bash
#
# Construit une version WEB de l'application d'affichage LÖVE (signage Caen.tech),
# destinée à PARTAGER UNE PREVIEW à des collègues (pas la prod sur Pi).
#
# Principe — le navigateur n'offre ni sous-processus (curl, dwebp/rsvg/magick) ni
# thread fiable : on ne peut pas y télécharger le programme/visuels comme sur le Pi.
# On reproduit donc la stratégie offline-first de l'app :
#
#   1. PREFETCH : on remplit le cache (programme + visuels) EN LIGNE, en réutilisant
#      le mode headless de l'app (CAENTECH_PREFETCH=1) — exactement le même chemin de
#      téléchargement/conversion que sur le Pi (cf. pi-app/setup.sh étape 6).
#   2. BUNDLE : on EMBARQUE ce cache dans le .love. Au runtime web, fetch est désactivé
#      (cf. src/fetch.lua) et l'app lit ce cache embarqué (offline-first) sans réseau.
#   3. WEB : on convertit le .love en page web via love.js, en mode COMPATIBILITÉ
#      (mono-thread) — ainsi PAS besoin des en-têtes COOP/COEP : n'importe quel
#      hébergeur statique (ou `python3 -m http.server`) suffit.
#
# Pré-requis : `love` (binaire), `node`/`npx` (pour love.js), `zip`, `rsync`, `curl`.
#
# Variables d'environnement (optionnelles) :
#   CAENTECH_PROGRAM_URL   URL du programme (défaut : https://caen.tech/program/model.json)
#   CAENTECH_WEB_MEMORY    mémoire totale du module WASM en octets (défaut : 256 Mio)
#   LOVE_BIN               chemin du binaire love (sinon auto-détecté)
#
set -euo pipefail

# --- Chemins (le script vit dans pi-app/web/). --------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOVE_SRC="$ROOT/pi-app/love"
BUILD="$SCRIPT_DIR/.build"
STAGE="$BUILD/love"
LOVE_FILE="$BUILD/caentech-signage.love"
DIST="$SCRIPT_DIR/dist"

PROGRAM_URL="${CAENTECH_PROGRAM_URL:-https://caen.tech/program/model.json}"
MEMORY="${CAENTECH_WEB_MEMORY:-268435456}"   # 256 Mio
TITLE="Caen.tech — signage (preview)"

log() { echo "[web] $*"; }
die() { echo "[web] ERREUR : $*" >&2; exit 1; }

# --- Outils requis. -----------------------------------------------------------
LOVE_BIN="${LOVE_BIN:-$(command -v love 2>/dev/null || true)}"
if [ -z "$LOVE_BIN" ]; then
    for c in /Applications/love.app/Contents/MacOS/love /usr/bin/love /usr/local/bin/love /opt/homebrew/bin/love; do
        [ -x "$c" ] && LOVE_BIN="$c" && break
    done
fi
[ -n "$LOVE_BIN" ] || die "binaire 'love' introuvable (brew install love / love2d.org)."
command -v npx  >/dev/null 2>&1 || die "'npx' introuvable — installe Node.js (pour love.js)."
command -v zip  >/dev/null 2>&1 || die "'zip' introuvable."
command -v rsync >/dev/null 2>&1 || die "'rsync' introuvable."
log "love     : $LOVE_BIN"
log "programme: $PROGRAM_URL"

# Save dir LÖVE où le prefetch écrit le cache (identity = caentech-signage, cf. conf.lua).
case "$(uname -s)" in
    Darwin) SAVE_DIR="$HOME/Library/Application Support/LOVE/caentech-signage" ;;
    *)      SAVE_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/love/caentech-signage" ;;
esac

# --- 1. Prefetch (en ligne, headless) : remplit le cache. ---------------------
log "pré-remplissage du cache (headless, en ligne)…"
if CAENTECH_PREFETCH=1 CAENTECH_PROGRAM_URL="$PROGRAM_URL" "$LOVE_BIN" "$LOVE_SRC"; then
    log "cache pré-rempli."
else
    log "AVERTISSEMENT : prefetch incomplet (réseau ? outils image ?) — on embarque le cache disponible."
fi
[ -f "$SAVE_DIR/cache/program.json" ] \
    || die "aucun cache/program.json dans « $SAVE_DIR » — le prefetch a échoué (réseau ?)."

# --- 2. Bundle : app + cache embarqué → .love. --------------------------------
log "assemblage du .love (app + cache embarqué)…"
rm -rf "$BUILD"
mkdir -p "$STAGE/cache"
# Copie de l'app (sans les artefacts d'outillage locaux).
rsync -a --exclude '.mcp_data' "$LOVE_SRC"/ "$STAGE"/
# Cache pré-rempli embarqué : love.filesystem.read("cache/…") le trouvera dans le .love.
cp -R "$SAVE_DIR/cache"/. "$STAGE/cache"/
# love.js embarque LÖVE 11.4 ; déclarer 11.5 déclenche une alerte de compatibilité
# bloquante. On aligne la version DANS LE BUNDLE seulement (la source reste en 11.5
# pour le Pi). Inoffensif : 11.4 et 11.5 sont compatibles pour cette app.
sed -i.bak 's/t\.version = "11\.5"/t.version = "11.4"/' "$STAGE/conf.lua" && rm -f "$STAGE/conf.lua.bak"
( cd "$STAGE" && zip -qr -X "$LOVE_FILE" . )
log ".love : $LOVE_FILE ($(du -h "$LOVE_FILE" | cut -f1))"

# --- 3. love.js : .love → page web (mode compatibilité, sans COOP/COEP). ------
log "conversion web via love.js…"
rm -rf "$DIST"
npx --yes love.js@latest --compatibility --title "$TITLE" --memory "$MEMORY" "$LOVE_FILE" "$DIST" \
    || die "love.js a échoué (réessaie : npx --yes love.js@latest --help)."

# Page hôte personnalisée : canvas plein écran responsive (ratio conservé), écran de
# chargement propre, sans le bandeau « Built with love.js ». Remplace l'index par défaut.
log "installation de la page hôte (canvas responsive)…"
sed -e "s/@TITLE@/$TITLE/g" -e "s/@MEMORY@/$MEMORY/g" \
    "$SCRIPT_DIR/index.template.html" > "$DIST/index.html"

log "terminé — page web prête : $DIST"
echo
echo "  Pour prévisualiser localement :"
echo "    make web-serve            # puis ouvrir http://localhost:8080"
echo
echo "  Pour partager : déposer le contenu de $DIST sur n'importe quel hébergeur statique"
echo "  (GitHub Pages, Netlify, S3…). Aucun en-tête spécial requis (mode compatibilité)."
