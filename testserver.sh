#!/usr/bin/env bash
# Build NCP + run a local Folia test server. Everything lives in testserver/ (git-excluded).
# Usage: ./testserver.sh [--no-build]      env: MC_VERSION, MEM, DIR, MVN_VERSION
set -euo pipefail
cd "$(dirname "$0")"

MC_VERSION="${MC_VERSION:-26.2}"
DIR="${DIR:-testserver}"
MEM="${MEM:-2G}"
MVN_VERSION="${MVN_VERSION:-3.9.16}"
mkdir -p "$DIR/plugins"

# Major version of a java binary, 0 if it is missing.
jver() { "$1" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p' | grep . || echo 0; }

# NCP builds with Java 25 (ScopedValue), which also runs every server version.
NEED=25
JAVA=java
if [ "$(jver "$JAVA")" -lt "$NEED" ]; then
  JAVA=""
  for c in /usr/lib/jvm/*/bin/java "$PWD/$DIR/jdk$NEED/bin/java"; do
    [ -x "$c" ] && [ "$(jver "$c")" -ge "$NEED" ] && JAVA="$c" && break
  done
  if [ -z "$JAVA" ]; then
    echo "No Java >= $NEED found, fetching Temurin $NEED into $DIR/jdk$NEED"
    mkdir -p "$DIR/jdk$NEED"
    curl -fSL --progress-bar "https://api.adoptium.net/v3/binary/latest/$NEED/ga/linux/x64/jdk/hotspot/normal/eclipse" \
      | tar xz -C "$DIR/jdk$NEED" --strip-components=1
    JAVA="$PWD/$DIR/jdk$NEED/bin/java"
  fi
fi
echo "Using java $(jver "$JAVA") ($JAVA)"

MVN=mvn
if ! command -v mvn >/dev/null; then
  MVN="$PWD/$DIR/apache-maven-$MVN_VERSION/bin/mvn"
  if [ ! -x "$MVN" ]; then
    echo "Maven not installed, fetching $MVN_VERSION into $DIR/"
    curl -fSL --progress-bar "https://dlcdn.apache.org/maven/maven-3/$MVN_VERSION/binaries/apache-maven-$MVN_VERSION-bin.tar.gz" \
      | tar xz -C "$DIR"
  fi
fi

if [ "${1:-}" != "--no-build" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v "$JAVA")")")")" "$MVN" -q package -DskipTests
fi
cp target/NoCheatPlus.jar "$DIR/plugins/"

# Latest Modrinth build of a plugin, skipped if a jar with that prefix exists. Delete the jar to update.
modrinth() { # <slug> <jar prefix> <version query>
  ls "$DIR/plugins/$2"-*.jar >/dev/null 2>&1 && return
  URL=$(curl -fsSL "https://api.modrinth.com/v2/project/$1/version?$3" \
        | grep -o 'https://cdn.modrinth.com/[^"]*\.jar' | sed -n 1p)
  echo "Downloading $URL"
  curl -fSL --progress-bar -o "$DIR/plugins/$(basename "$URL")" "$URL"
}
PAPER="loaders=%5B%22paper%22%5D&game_versions=%5B%22$MC_VERSION%22%5D"
modrinth viaversion ViaVersion "$PAPER"
modrinth viabackwards ViaBackwards "$PAPER"
# AnarchyExploitFixes needs PacketEvents + NBT-API. AEF has no build tagged for 26.x, so take its latest Folia one.
modrinth packetevents packetevents "$PAPER"
modrinth nbtapi item-nbt-api-plugin "$PAPER"
modrinth anarchyexploitfixes AnarchyExploitFixes "loaders=%5B%22folia%22%5D"

JAR="$DIR/folia-$MC_VERSION.jar"
if [ ! -f "$JAR" ]; then
  URL=$(curl -fsSL "https://fill.papermc.io/v3/projects/folia/versions/$MC_VERSION/builds/latest" \
        | grep -o 'https://[^"]*\.jar')
  echo "Downloading $URL"
  curl -fSL --progress-bar -o "$JAR" "$URL"
fi

echo eula=true > "$DIR/eula.txt"
# Offline mode, so only reachable from this machine. Clear server-ip in server.properties to let others join.
[ -f "$DIR/server.properties" ] || printf 'online-mode=false\nserver-ip=127.0.0.1\nmotd=NCP test server\nspawn-protection=0\n' > "$DIR/server.properties"

cd "$DIR"
# Folia patch (test only): no "Invalid move player packet received" kicks. Delete foliapatch.jar to revert.
AGENT=""
[ -f foliapatch.jar ] && AGENT="-javaagent:foliapatch.jar"
exec "$JAVA" $AGENT -Xms"$MEM" -Xmx"$MEM" -jar "$(basename "$JAR")"
