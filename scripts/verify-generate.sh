#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

DB_PATH_VERIFY="/tmp/sat_verify_generate.db"
PORT_VERIFY="8097"
BASE_URL="http://localhost:${PORT_VERIFY}"
SERVER_LOG="$(mktemp)"

rm -f "$DB_PATH_VERIFY"

echo "Building classpath..."
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
CP="target/classes:$(cat cp.txt)"

echo "Importing fixture data..."
DB_PATH="$DB_PATH_VERIFY" java -cp "$CP" satapp.tools.ImportQuestions src/test/resources/sample-questions.json "$DB_PATH_VERIFY"

echo "Starting server with OPENAI_API_KEY unset..."
env -u OPENAI_API_KEY DB_PATH="$DB_PATH_VERIFY" PORT="$PORT_VERIFY" java -cp "$CP" satapp.web.WebServer >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!

trap 'kill $SERVER_PID 2>/dev/null || true' EXIT

READY=""
for i in $(seq 1 30); do
    if curl -fsS "${BASE_URL}/api/meta/filters" >/dev/null 2>&1; then
        READY="1"
        break
    fi
    sleep 1
done

if [ -z "$READY" ]; then
    echo "FAIL: server never became ready"
    cat "$SERVER_LOG"
    exit 1
fi

# Derive a real fixture id from the fixture JSON itself.
SEED_ID=$(python3 -c "
import json
with open('src/test/resources/sample-questions.json') as f:
    data = json.load(f)
for uid in data.keys():
    print(uid)
    break
")

if [ -z "$SEED_ID" ]; then
    echo "FAIL: could not derive a fixture id"
    exit 1
fi

# a. generating against a real seed id returns 503 with a message mentioning OPENAI_API_KEY
body=$(curl -sS -w '\n%{http_code}' -X POST "${BASE_URL}/api/questions/${SEED_ID}/generate")
status="${body##*$'\n'}"
payload="${body%$'\n'*}"
if [ "$status" != "503" ]; then
    echo "FAIL: expected 503 for generate without API key, got $status"
    exit 1
fi
case "$payload" in
    *OPENAI_API_KEY*) ;;
    *) echo "FAIL: 503 body did not mention OPENAI_API_KEY, got: $payload"; exit 1 ;;
esac

# b. generating against an unknown id returns 404
status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/questions/no-such-id/generate")
if [ "$status" != "404" ]; then
    echo "FAIL: expected 404 for unknown seed id, got $status"
    exit 1
fi

# c. the 503 body does not leak the word Bearer
case "$payload" in
    *Bearer*) echo "FAIL: 503 body leaked the word Bearer"; exit 1 ;;
esac

# d. no phantom row was inserted
body=$(curl -fsS "${BASE_URL}/api/questions")
count=$(python3 -c "import json,sys; print(len(json.loads(sys.argv[1])))" "$body")
if [ "$count" != "3" ]; then
    echo "FAIL: expected 3 questions after failed generate attempts, got $count"
    exit 1
fi

echo "VERIFY_GENERATE_OK"
