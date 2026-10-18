#!/usr/bin/env bash
# Compiles the server (Java 17 or newer, no other dependencies) and starts it.
#
#   ./run.sh                       http://localhost:8080
#   PORT=9000 ./run.sh             another port
#   ADMIN_TOKEN=<16+ chars> ./run.sh   also switches on the CSV download
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v javac >/dev/null 2>&1; then
  echo "Java 17+ is required but javac was not found in PATH." >&2
  exit 1
fi

rm -rf out
mkdir -p out
mapfile -d '' java_files < <(find src -name '*.java' -print0)
if [ ${#java_files[@]} -eq 0 ]; then
  echo "No Java source files were found under src/." >&2
  exit 1
fi

javac -d out "${java_files[@]}"
exec java -cp out com.fatshe.movers.Main "$@"
