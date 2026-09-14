#!/usr/bin/env bash
# Compare NCP with vanilla for every block state on a real server: collision, ground and line of sight.
# Run it after adding blocks or updating Minecraft. Known, fine differences are in audit/allowlist.txt.
# Needs a test server that started once (./testserver.sh). Usage: audit/run.sh [--no-build]   env: MC_VERSION, DIR
set -euo pipefail
cd "$(dirname "$0")/.."

MC_VERSION="${MC_VERSION:-26.2}"
DIR="${DIR:-testserver}"
SERVER_JAR="$DIR/folia-$MC_VERSION.jar"
if [ ! -f "$SERVER_JAR" ] || [ ! -d "$DIR/versions/$MC_VERSION" ]; then
  echo "Start ./testserver.sh once first, the audit needs $SERVER_JAR and $DIR/versions/$MC_VERSION."
  exit 2
fi
JDK="$DIR/jdk25"
[ -x "$JDK/bin/javac" ] || JDK="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
JDK="$(readlink -f "$JDK")"
MVN="$(command -v mvn || ls "$DIR"/apache-maven-*/bin/mvn | head -n1)"

if [ "${1:-}" != "--no-build" ]; then
  JAVA_HOME="$JDK" "$MVN" -q package -DskipTests
fi

# Compile against the (Mojang mapped) server and the libraries of this version.
OUT="$DIR/audit-build"
rm -rf "$OUT" && mkdir -p "$OUT"
CP="$DIR/versions/$MC_VERSION/folia-$MC_VERSION.jar:target/NoCheatPlus.jar"
while read -r _ _ path; do CP="$CP:$DIR/libraries/$path"; done < <(unzip -p "$SERVER_JAR" META-INF/libraries.list)
"$JDK/bin/javac" -nowarn -d "$OUT" -cp "$CP" audit/src/audit/Audit.java
cp audit/plugin.yml "$OUT/"

# Separate server next to the test server, sharing its downloads.
S="$DIR/audit"
mkdir -p "$S/plugins"
for f in "folia-$MC_VERSION.jar" libraries versions cache; do ln -sfn "../$f" "$S/$f"; done
"$JDK/bin/jar" cf "$S/plugins/NcpAudit.jar" -C "$OUT" .
cp target/NoCheatPlus.jar "$S/plugins/"
echo eula=true > "$S/eula.txt"
printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=25599\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nspawn-protection=0\n' > "$S/server.properties"
REPORT="$S/plugins/NcpAudit/report.txt"
ALLOWLIST="$PWD/audit/allowlist.txt"
rm -f "$REPORT"

echo "Running the audit, this takes a few minutes..."
(cd "$S" && timeout 1200 "$JDK/bin/java" -Daudit.allowlist="$ALLOWLIST" -Xmx2G -jar "folia-$MC_VERSION.jar" --nogui < /dev/null > run.log 2>&1) || true
if [ ! -f "$REPORT" ]; then
  echo "No report, see $S/run.log"
  exit 2
fi
grep -v '^    ' "$REPORT"
echo "Details: $REPORT"
tail -n1 "$REPORT" | grep -q '^RESULT: PASS'
