#!/usr/bin/env bash
# Kill-the-server demo with real processes: Postgres in Docker, the server jar, and two embedded clients.
#   1. a client reads, an operator changes a value, the client sees it
#   2. the server is killed with kill -9 right after another change
#   3. the running client keeps answering; a second client starts from its disk copy while the server is still down
#   4. the server restarts; both clients converge to the change made just before the crash
# Needs: docker, Java 21, Maven. Run from the repository root:  scripts/kill-the-server-demo.sh
set -euo pipefail
cd "$(dirname "$0")/.."
WORK="$(mktemp -d)"; PORT=8089; B="localhost:$PORT"
JAR=tenantpulse-server/target/tenantpulse-server-0.1.0-SNAPSHOT.jar
trap 'kill ${PA:-} ${PB:-} $(cat "$WORK/pid" 2>/dev/null) 2>/dev/null || true' EXIT

mvn -q -B -DskipTests install
mvn -q -B -pl tenantpulse-benchmarks dependency:build-classpath -Dmdep.outputFile="$WORK/cp"
CP="tenantpulse-benchmarks/target/classes:$(cat "$WORK/cp")"
docker compose down -v >/dev/null 2>&1 || true
docker compose up -d postgres >/dev/null
until docker ps --format '{{.Names}} {{.Status}}' | grep -q 'tenantpulse-postgres.*healthy'; do sleep 1; done

start_server() {
  java -jar "$JAR" --server.port=$PORT --tenantpulse.security.dev-token-endpoint-enabled=true > "$WORK/server.log" 2>&1 &
  echo $! > "$WORK/pid"
  until curl -s "$B/actuator/health" 2>/dev/null | grep -q UP; do sleep 1; done
}
tok() { curl -s -X POST $B/dev/token -H 'Content-Type: application/json' \
          -d "{\"subject\":\"$1\",\"role\":\"$2\",\"tenant\":\"acme\"}" | sed 's/.*"token":"\([^"]*\)".*/\1/'; }
publish() { curl -s -o /dev/null -w "publish cut-off=$1 -> HTTP %{http_code}\n" --max-time 3 -X POST \
  -H "Authorization: Bearer $OP" -H 'Content-Type: application/json' \
  -d "{\"reason\":\"demo\",\"changes\":[{\"key\":\"booking.cutoff.minutes\",\"scope\":{\"level\":\"OFFICE\",\"clientId\":\"acme\",\"officeId\":\"blr\"},\"value\":\"$1\"}]}" $B/v1/change-sets; }

mkdir -p "$WORK/a" "$WORK/b"
start_server; echo "== server up"
SVC=$(tok svc SERVICE); OP=$(tok op OPERATOR)
publish 60
java -cp "$CP" com.tenantpulse.benchmarks.ClientProbe "http://$B" acme "$SVC" "$WORK/a/tp.cache" blr > "$WORK/probeA.log" 2>&1 & PA=$!
sleep 4
echo "== operator changes 60 -> 30"; publish 30; sleep 3; tail -n 2 "$WORK/probeA.log"
cp "$WORK/a/tp.cache" "$WORK/b/tp.cache"
echo "== operator changes 30 -> 20 and the server is killed immediately"
publish 20; kill -9 "$(cat "$WORK/pid")"; echo "server KILLED"; sleep 4
echo "== client A while the server is down (keeps answering from its snapshot):"; tail -n 3 "$WORK/probeA.log"
echo "== client B starts while the server is STILL DOWN, from its disk copy:"
java -cp "$CP" com.tenantpulse.benchmarks.ClientProbe "http://$B" acme "$SVC" "$WORK/b/tp.cache" blr > "$WORK/probeB.log" 2>&1 & PB=$!
sleep 5; head -n 3 "$WORK/probeB.log"
echo "== restarting the server"; start_server; sleep 6
echo "== client A after the server returns:"; tail -n 2 "$WORK/probeA.log"
echo "== client B after the server returns (the change made just before the crash is applied):"; tail -n 2 "$WORK/probeB.log"
