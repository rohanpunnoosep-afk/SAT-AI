#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

DB_PATH_VERIFY="/tmp/sat_verify_session.db"
PORT_VERIFY="8098"
BASE_URL="http://localhost:${PORT_VERIFY}"
SERVER_LOG="$(mktemp)"
COOKIE_JAR="/tmp/sat_verify_cookies.txt"

rm -f "$DB_PATH_VERIFY" "$COOKIE_JAR"

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

MCQ_INFO=$(python3 -c "
import json
with open('src/test/resources/sample-questions.json') as f:
    data = json.load(f)
for uid, entry in data.items():
    content = entry.get('content', {})
    if content.get('type') == 'mcq' and entry.get('module') == 'math':
        print(uid)
        print(content['correct_answer'][0])
        break
")
MCQ_ID=$(echo "$MCQ_INFO" | sed -n '1p')
MCQ_ANSWER=$(echo "$MCQ_INFO" | sed -n '2p')

if [ -z "$SPR_ID" ] || [ -z "$MCQ_ID" ] || [ -z "$MCQ_ANSWER" ]; then
    echo "FAIL: could not derive fixture ids"
    exit 1
fi

# a. First response sets a sat_session cookie.
curl -fsS -c "$COOKIE_JAR" "${BASE_URL}/api/session/stats" >/dev/null
if [ ! -f "$COOKIE_JAR" ]; then
    echo "FAIL: cookie jar was not created"
    exit 1
fi
case "$(cat "$COOKIE_JAR")" in
    *sat_session*) ;;
    *) echo "FAIL: sat_session cookie was not set"; exit 1 ;;
esac

# b. spr numeric equivalence: 3/17 correct, .1764 correct, 0.9 incorrect
body=$(curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST \
    -H "Content-Type: application/json" \
    -d "{\"questionId\":\"${SPR_ID}\",\"submitted\":\"3/17\"}" \
    "${BASE_URL}/api/session/answer")
case "$body" in
    *'"correct":true'*) ;;
    *) echo "FAIL: 3/17 should have graded correct, got: $body"; exit 1 ;;
esac

body=$(curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST \
    -H "Content-Type: application/json" \
    -d "{\"questionId\":\"${SPR_ID}\",\"submitted\":\".1764\"}" \
    "${BASE_URL}/api/session/answer")
case "$body" in
    *'"correct":true'*) ;;
    *) echo "FAIL: .1764 should have graded correct, got: $body"; exit 1 ;;
esac

body=$(curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST \
    -H "Content-Type: application/json" \
    -d "{\"questionId\":\"${SPR_ID}\",\"submitted\":\"0.9\"}" \
    "${BASE_URL}/api/session/answer")
case "$body" in
    *'"correct":false'*) ;;
    *) echo "FAIL: 0.9 should have graded incorrect, got: $body"; exit 1 ;;
esac

# d. no answer response leaks explanation or the raw stored answer string
case "$body" in
    *explanation*) echo "FAIL: answer response leaked explanation"; exit 1 ;;
esac
case "$body" in
    *'.1764,3/17'*) echo "FAIL: answer response leaked stored correct_answer"; exit 1 ;;
esac

# c. mcq correct / incorrect
body=$(curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST \
    -H "Content-Type: application/json" \
    -d "{\"questionId\":\"${MCQ_ID}\",\"submitted\":\"${MCQ_ANSWER}\"}" \
    "${BASE_URL}/api/session/answer")
case "$body" in
    *'"correct":true'*) ;;
    *) echo "FAIL: correct mcq letter should have graded correct, got: $body"; exit 1 ;;
esac
case "$body" in
    *explanation*) echo "FAIL: mcq answer response leaked explanation"; exit 1 ;;
esac

WRONG_LETTER="A"
if [ "$MCQ_ANSWER" = "A" ]; then
    WRONG_LETTER="B"
fi
body=$(curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST \
    -H "Content-Type: application/json" \
    -d "{\"questionId\":\"${MCQ_ID}\",\"submitted\":\"${WRONG_LETTER}\"}" \
    "${BASE_URL}/api/session/answer")
case "$body" in
    *'"correct":false'*) ;;
    *) echo "FAIL: wrong mcq letter should have graded incorrect, got: $body"; exit 1 ;;
esac

# e. review list: same wrong topic answered incorrectly twice should appear;
#    a request with no cookie jar returns empty topics.
curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST \
    -H "Content-Type: application/json" \
    -d "{\"questionId\":\"${MCQ_ID}\",\"submitted\":\"${WRONG_LETTER}\"}" \
    "${BASE_URL}/api/session/answer" >/dev/null

body=$(curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" "${BASE_URL}/api/session/review-list")
case "$body" in
    *"Algebra"*) ;;
    *) echo "FAIL: expected weak topic Algebra in review-list, got: $body"; exit 1 ;;
esac

body=$(curl -fsS "${BASE_URL}/api/session/review-list")
case "$body" in
    '{"topics":[]}') ;;
    *) echo "FAIL: no-cookie request should return empty topics, got: $body"; exit 1 ;;
esac

# f. unknown questionId returns 404
status=$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST \
    -H "Content-Type: application/json" \
    -d '{"questionId":"no-such-id","submitted":"A"}' \
    "${BASE_URL}/api/session/answer")
if [ "$status" != "404" ]; then
    echo "FAIL: expected 404 for unknown questionId, got $status"
    exit 1
fi

echo "VERIFY_SESSION_OK"
