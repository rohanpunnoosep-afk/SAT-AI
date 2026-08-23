#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

DB_PATH_VERIFY="/tmp/sat_verify_boxes.db"
PORT_VERIFY="8096"
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

SEED_ID=$(python3 -c "
import json
with open('src/test/resources/sample-questions.json') as f:
    data = json.load(f)
for uid in data.keys():
    print(uid)
    break
")

# a. no boxes initially
body=$(curl -fsS "${BASE_URL}/api/boxes")
if [ "$body" != "[]" ]; then
    echo "FAIL: expected no boxes initially, got $body"
    exit 1
fi

# b. create a box
body=$(curl -fsS -X POST -H 'Content-Type: application/json' -d '{"label":"Box1"}' "${BASE_URL}/api/boxes")
BOX_ID=$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['id'])" "$body")
case "$body" in
    *'"label":"Box1"'*) ;;
    *) echo "FAIL: created box missing label Box1, got $body"; exit 1 ;;
esac

# c. add a question to the box
status=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
    -d "{\"questionId\":\"${SEED_ID}\"}" "${BASE_URL}/api/boxes/${BOX_ID}/questions")
if [ "$status" != "204" ]; then
    echo "FAIL: expected 204 adding question to box, got $status"
    exit 1
fi

# d. box question_count reflects the add
body=$(curl -fsS "${BASE_URL}/api/boxes")
count=$(python3 -c "import json,sys; print(json.loads(sys.argv[1])[0]['question_count'])" "$body")
if [ "$count" != "1" ]; then
    echo "FAIL: expected question_count 1, got $count"
    exit 1
fi

# e. list questions in the box
body=$(curl -fsS "${BASE_URL}/api/boxes/${BOX_ID}/questions")
count=$(python3 -c "import json,sys; print(len(json.loads(sys.argv[1])))" "$body")
if [ "$count" != "1" ]; then
    echo "FAIL: expected 1 question in box, got $count"
    exit 1
fi

# f. generate against the box without an API key returns 503
body=$(curl -sS -w '\n%{http_code}' -X POST -H 'Content-Type: application/json' \
    -d '{"count":2,"level":2}' "${BASE_URL}/api/boxes/${BOX_ID}/generate")
status="${body##*$'\n'}"
payload="${body%$'\n'*}"
if [ "$status" != "503" ]; then
    echo "FAIL: expected 503 for box generate without API key, got $status"
    exit 1
fi
case "$payload" in
    *OPENAI_API_KEY*) ;;
    *) echo "FAIL: box generate 503 body did not mention OPENAI_API_KEY, got: $payload"; exit 1 ;;
esac

# g. generating against a box with no questions returns 400
body=$(curl -fsS -X POST -H 'Content-Type: application/json' -d '{"label":"EmptyBox"}' "${BASE_URL}/api/boxes")
EMPTY_BOX_ID=$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['id'])" "$body")
status=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
    -d '{"count":1}' "${BASE_URL}/api/boxes/${EMPTY_BOX_ID}/generate")
if [ "$status" != "400" ]; then
    echo "FAIL: expected 400 for generate against empty box, got $status"
    exit 1
fi

# h. removing a question from a box drops the count
status=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "${BASE_URL}/api/boxes/${BOX_ID}/questions/${SEED_ID}")
if [ "$status" != "204" ]; then
    echo "FAIL: expected 204 removing question from box, got $status"
    exit 1
fi
body=$(curl -fsS "${BASE_URL}/api/boxes/${BOX_ID}/questions")
count=$(python3 -c "import json,sys; print(len(json.loads(sys.argv[1])))" "$body")
if [ "$count" != "0" ]; then
    echo "FAIL: expected 0 questions in box after removal, got $count"
    exit 1
fi

echo "VERIFY_BOXES_OK"
