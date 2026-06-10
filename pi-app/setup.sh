#!/usr/bin/env bash
#
# Script de SETUP de l'application déployée sur le Pi.
# Lancé par pi-manager à la fin de la phase de setup (après décompression du zip
# dans ~/.pi-manager). C'est le point d'entrée de la mise en place.
# Autorisé en sudo NOPASSWD (cf. /etc/sudoers.d/pi-manager) : ~/.pi-manager/setup.sh.
# Pour l'instant, il ne fait que confirmer son exécution.
#
set -euo pipefail

echo "ok"
