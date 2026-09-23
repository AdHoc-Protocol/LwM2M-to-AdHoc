#!/usr/bin/env bash
# Downloads the OMA LwM2M object registry (every registered object definition, one XML per object id)
# into samples/. Requires git.
#
#   ./fetch-samples.sh            # full registry (≈400 files, a few MB)
set -eu
cd "$(dirname "$0")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
git clone --depth 1 --quiet https://github.com/OpenMobileAlliance/lwm2m-registry "$TMP/registry"
mkdir -p samples
rm -f samples/*.xml samples/*.xsd
cp "$TMP"/registry/*.xml "$TMP"/registry/*.xsd samples/
echo "samples/: $(ls samples/*.xml | wc -l) XML files from https://github.com/OpenMobileAlliance/lwm2m-registry"
