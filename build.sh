#!/usr/bin/env bash
# Compiles the converter and runs it over samples/ into AdHoc/.
#
#   ./build.sh                 # samples/ -> AdHoc/LwM2M.cs
#   ./build.sh samples/3.xml   # one object -> AdHoc/Device.cs
set -eu
cd "$(dirname "$0")"
rm -rf out
javac -encoding UTF-8 --release 17 -d out src/org/unirail/adhoc/*.java src/org/unirail/*.java
java -Dfile.encoding=UTF-8 -cp out org.unirail.LwM2M2AdHoc "${1:-samples}" AdHoc
