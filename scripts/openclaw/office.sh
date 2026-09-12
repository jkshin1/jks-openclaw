#!/bin/sh
# Native Office conversion with an explicit, private Mac font configuration.
set -eu
FONTCONFIG_FILE="$HOME/.local/share/openclaw-skill-tools/office-fonts.conf"
export FONTCONFIG_FILE
test -r "$FONTCONFIG_FILE"
exec "$HOME/Applications/LibreOffice.app/Contents/MacOS/soffice" "$@"
