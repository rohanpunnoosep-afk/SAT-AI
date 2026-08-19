#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

DB_PATH_VERIFY="/tmp/sat_verify_api.db"
PORT_VERIFY="8099"
BASE_URL="http://localhost:${PORT_VERIFY}"
SERVER_LOG="$(mktemp)"

rm -f "$DB_PATH_VERIFY"

echo "Building classpath..."
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
CP="target/classes:$(cat cp.txt)"

echo "Importing fixture data..."
DB_PATH="$DB_PATH_VERIFY" java -cp "$CP" satapp.tools.ImportQuestions src/test/resources/sample-questions.json "$DB_PATH_VERIFY"

echo "Starting server..."
DB_PATH="$DB_PATH_VERIFY" PORT="$PORT_VERIFY" java -cp "$CP" satapp.web.WebServer >"$SERVER_LOG" 2>&1 &
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

# Derive fixture ids from the fixture JSON itself.
SPR_ID=$(python3 -c "
import json
with open('src/test/resources/sample-questions.json') as f:
    data = json.load(f)
for uid, entry in data.items():
    if entry.get('content', {}).get('type') == 'spr':
        print(uid)
        break
")

if [ -z "$SPR_ID" ]; then
    echo "FAIL: could not find spr fixture id"
    exit 1
fi

# a. GET /api/questions returns exactly 3 elements, no correct_answer/explanation
body=$(curl -fsS "${BASE_URL}/api/questions")
count=$(python3 -c "import json,sys; print(len(json.loads(sys.argv[1])))" "$body")
if [ "$count" != "3" ]; then
    echo "FAIL: expected 3 questions, got $count"
    exit 1
fi
case "$body" in
    *correct_answer*) echo "FAIL: /api/questions leaked correct_answer"; exit 1 ;;
esac
case "$body" in
    *explanation*) echo "FAIL: /api/questions leaked explanation"; exit 1 ;;
esac

# b. filters
body=$(curl -fsS "${BASE_URL}/api/questions?section=Math")
count=$(python3 -c "import json,sys; print(len(json.loads(sys.argv[1])))" "$body")
if [ "$count" != "2" ]; then
    echo "FAIL: expected 2 Math questions, got $count"
    exit 1
fi

body=$(curl -fsS "${BASE_URL}/api/questions?difficulty=Hard")
count=$(python3 -c "import json,sys; print(len(json.loads(sys.argv[1])))" "$body")
if [ "$count" != "1" ]; then
    echo "FAIL: expected 1 Hard question, got $count"
    exit 1
fi

# c. single question omits answer fields
body=$(curl -fsS -w '\n%{http_code}' "${BASE_URL}/api/questions/${SPR_ID}")
status="${body##*$'\n'}"
payload="${body%$'\n'*}"
if [ "$status" != "200" ]; then
    echo "FAIL: expected 200 for /api/questions/${SPR_ID}, got $status"
    exit 1
fi
case "$payload" in
    *'"stem"'*) ;;
    *) echo "FAIL: question payload missing stem"; exit 1 ;;
esac
case "$payload" in
    *correct_answer*) echo "FAIL: /api/questions/{id} leaked correct_answer"; exit 1 ;;
esac
case "$payload" in
    *explanation*) echo "FAIL: /api/questions/{id} leaked explanation"; exit 1 ;;
esac

# d. answer endpoint returns correct_answer and expected value
body=$(curl -fsS -w '\n%{http_code}' "${BASE_URL}/api/questions/${SPR_ID}/answer")
status="${body##*$'\n'}"
payload="${body%$'\n'*}"
if [ "$status" != "200" ]; then
    echo "FAIL: expected 200 for /api/questions/${SPR_ID}/answer, got $status"
    exit 1
fi
case "$payload" in
    *correct_answer*) ;;
    *) echo "FAIL: answer payload missing correct_answer"; exit 1 ;;
esac
case "$payload" in
    *'.1764,3/17'*) ;;
    *) echo "FAIL: answer payload missing expected value .1764,3/17"; exit 1 ;;
esac

# e. unknown id returns 404
status=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/api/questions/no-such-id")
if [ "$status" != "404" ]; then
    echo "FAIL: expected 404 for unknown id, got $status"
    exit 1
fi

# f. static hosting works
body=$(curl -fsS "${BASE_URL}/")
case "$body" in
    *"SAT Tutoring"*) ;;
    *) echo "FAIL: static index did not contain 'SAT Tutoring'"; exit 1 ;;
esac

echo "VERIFY_API_OK"
