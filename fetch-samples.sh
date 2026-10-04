#!/usr/bin/env bash
# Downloads the OMA LwM2M object registry (every registered object definition, one XML per object id)
# into samples/. Requires git.
#
#   ./fetch-samples.sh            # full registry (≈400 files, a few MB)
set -eu
cd "$(dirname "$0")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
REPO=https://github.com/OpenMobileAlliance/lwm2m-registry
REF=prod # the default branch of the registry, the one the clone takes
git clone --depth 1 --quiet "$REPO" "$TMP/registry"
mkdir -p samples
rm -f samples/*.xml samples/*.xsd
cp "$TMP"/registry/*.xml "$TMP"/registry/*.xsd samples/
# Not shipped: DDF.xml, the index of the registry (the converter reads object definitions only), and object 513,
# which is outside the scope of these samples.
rm -f samples/DDF.xml samples/513.xml
# Every sample is a file of the root of the registry.
{
    echo "# Where every sample comes from: <path in samples/> <page of the original>. Written by fetch-samples.sh;"
    echo "# the converter links these pages in the headers of the descriptions, the registry itself for the whole folder."
    for f in samples/*.xml samples/*.xsd; do
        printf '%-28s %s\n' "${f#samples/}" "$REPO/blob/$REF/${f#samples/}"
    done
} > samples/sources.txt
echo "samples/: $(ls samples/*.xml | wc -l) XML files from $REPO"
