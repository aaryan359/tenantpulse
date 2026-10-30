#!/usr/bin/env bash
# Walks through the case study's example against a running server (docker compose up --build).
# The server must have the demo token endpoint on (the compose file does this).
set -euo pipefail
B="${1:-http://localhost:8080}"
T="demo-$RANDOM"          # a fresh tenant each run, so reruns do not interfere

tok() { curl -s -X POST "$B/dev/token" -H 'Content-Type: application/json' \
          -d "{\"subject\":\"$1\",\"role\":\"$2\",\"tenant\":\"$3\"}" | sed 's/.*"token":"\([^"]*\)".*/\1/'; }
get()  { curl -s -H "Authorization: Bearer $1" "$B$2"; echo; }
post() { curl -s -X POST -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3" "$B$2"; echo; }

OP=$(tok op-ravi OPERATOR "$T"); SVC=$(tok booking-svc SERVICE "$T"); OTHER=$(tok other-svc SERVICE "other-$T")

echo "1) Bengaluru cut-off 60 -> 30 for one office"
post "$OP" /v1/change-sets "{\"reason\":\"festival rush\",\"idempotencyKey\":\"$T-1\",\"changes\":[{\"key\":\"booking.cutoff.minutes\",\"scope\":{\"level\":\"OFFICE\",\"clientId\":\"$T\",\"officeId\":\"blr\"},\"value\":\"30\"}]}"
echo "2) Bengaluru is 30 (OFFICE level); Pune is still 60 (default):"
get "$SVC" "/v1/config/booking.cutoff.minutes?office=blr"
get "$SVC" "/v1/config/booking.cutoff.minutes?office=pune"
echo "3) Another client never sees it:"
get "$OTHER" "/v1/config/booking.cutoff.minutes?office=blr"
echo "4) A bad value is rejected before it is published (422):"
post "$OP" /v1/change-sets "{\"reason\":\"typo\",\"changes\":[{\"key\":\"booking.cutoff.minutes\",\"scope\":{\"level\":\"CLIENT\",\"clientId\":\"$T\"},\"value\":\"-30\"}]}"
echo "5) Another tenant's operator cannot change it (403):"
post "$(tok op-x OPERATOR "other-$T")" /v1/change-sets "{\"reason\":\"x\",\"changes\":[{\"key\":\"escort.rule\",\"scope\":{\"level\":\"CLIENT\",\"clientId\":\"$T\"},\"value\":\"NONE\"}]}"
echo "6) Staged rollout to two offices, then promote:"
R=$(curl -s -X POST -H "Authorization: Bearer $OP" -H 'Content-Type: application/json' "$B/v1/rollouts" \
    -d "{\"clientId\":\"$T\",\"offices\":[\"mumbai\",\"delhi\"],\"changes\":[{\"key\":\"escort.rule\",\"value\":\"REQUIRED\"}],\"reason\":\"pilot\"}")
echo "$R"; ID=$(echo "$R" | sed 's/.*"rolloutId":\([0-9]*\).*/\1/')
get "$SVC" "/v1/config/escort.rule?office=mumbai"; get "$SVC" "/v1/config/escort.rule?office=hyd"
post "$OP" "/v1/rollouts/$ID/promote" '{"reason":"pilot went well"}'
get "$SVC" "/v1/config/escort.rule?office=hyd"
echo "7) Roll back to the state at version $ID (dry run first, then for real):"
post "$OP" /v1/rollbacks "{\"targetVersion\":$ID,\"reason\":\"preview\",\"dryRun\":true}"
post "$OP" /v1/rollbacks "{\"targetVersion\":$ID,\"reason\":\"promotion was too early\"}"
get "$SVC" "/v1/config/escort.rule?office=hyd"
