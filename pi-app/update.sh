#!/usr/bin/env bash
#
# Script de MISE À JOUR de l'application sur le Pi.
# Déployé avec setup.sh dans ~/.pi-manager. Destiné à être lancé (à terme) pour
# rafraîchir l'application sans repasser par tout le setup.
# Autorisé en sudo NOPASSWD (cf. /etc/sudoers.d/pi-manager) : ~/.pi-manager/update.sh.
# Pour l'instant, il ne fait que confirmer son exécution.
#
set -euo pipefail

echo "ok"
