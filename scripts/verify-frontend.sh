#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

DB_PATH_VERIFY="/tmp/sat_verify_frontend.db"
PORT_VERIFY="8096"
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

# a. GET / returns 200 and contains SAT Tutoring and app.js
index_body=$(curl -fsS "${BASE_URL}/")
case "$index_body" in
    *"SAT Tutoring"*) ;;
    *) echo "FAIL: / did not contain 'SAT Tutoring'"; exit 1 ;;
esac
case "$index_body" in
    *"app.js"*) ;;
    *) echo "FAIL: / did not reference app.js"; exit 1 ;;
esac

# b. GET /app.js and GET /styles.css each return 200 and are larger than 1000 bytes
appjs_body=$(curl -fsS "${BASE_URL}/app.js")
appjs_len=${#appjs_body}
if [ "$appjs_len" -le 1000 ]; then
    echo "FAIL: app.js is too small ($appjs_len bytes) - looks like a placeholder"
    exit 1
fi

styles_body=$(curl -fsS "${BASE_URL}/styles.css")
styles_len=${#styles_body}
if [ "$styles_len" -le 1000 ]; then
    echo "FAIL: styles.css is too small ($styles_len bytes) - looks like a placeholder"
    exit 1
fi

# c. the served /index.html body contains every required DOM id
required_ids="filter-section filter-domain filter-skill filter-difficulty filter-search apply-filters question-list result-count question-detail answer-area submit-answer show-answer generate-similar review-list session-stats error-banner"

missing_ids=""
for id in $required_ids; do
    case "$index_body" in
        *"id=\"$id\""*) ;;
        *"id='$id'"*) ;;
        *)
            missing_ids="$missing_ids $id"
            ;;
    esac
done

if [ -n "$missing_ids" ]; then
    echo "FAIL: missing DOM ids in served index.html:$missing_ids"
    exit 1
fi

# d. the served /app.js body contains each required endpoint string
required_endpoints="/api/meta/filters /api/questions /api/session/answer /api/session/review-list /api/session/stats /generate /answer"

missing_endpoints=""
for ep in $required_endpoints; do
    case "$appjs_body" in
        *"$ep"*) ;;
        *)
            missing_endpoints="$missing_endpoints $ep"
            ;;
    esac
done

if [ -n "$missing_endpoints" ]; then
    echo "FAIL: missing endpoint references in served app.js:$missing_endpoints"
    exit 1
fi

# e. the served /app.js does NOT reference any CDN dependency
case "$appjs_body" in
    *cdn.jsdelivr*) echo "FAIL: app.js references cdn.jsdelivr"; exit 1 ;;
esac
case "$appjs_body" in
    *katex*) echo "FAIL: app.js references katex"; exit 1 ;;
esac

# f. GET /api/questions still returns exactly 3 elements
body=$(curl -fsS "${BASE_URL}/api/questions")
count=$(python3 -c "import json,sys; print(len(json.loads(sys.argv[1])))" "$body")
if [ "$count" != "3" ]; then
    echo "FAIL: expected 3 questions, got $count"
    exit 1
fi

echo "VERIFY_FRONTEND_OK"
