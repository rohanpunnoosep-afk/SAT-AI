#!/usr/bin/env bash
# Verifies that answering a question incorrectly auto-files it into the standing
# "Missed Questions" box, and that a correct answer does not.
set -euo pipefail

cd "$(dirname "$0")/.."

DB_PATH_VERIFY="/tmp/sat_verify_missed.db"
PORT_VERIFY="8097"
BASE_URL="http://localhost:${PORT_VERIFY}"
SERVER_LOG="$(mktemp)"
COOKIES="$(mktemp)"

rm -f "$DB_PATH_VERIFY"

echo "Building classpath..."
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
CP="target/classes:$(cat cp.txt)"

echo "Importing fixture data..."
DB_PATH="$DB_PATH_VERIFY" java -cp "$CP" satapp.tools.ImportQuestions src/test/resources/sample-questions.json "$DB_PATH_VERIFY"

echo "Starting server..."
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

# Pick an MCQ question and read its real correct answer straight from the fixture.
read -r QID CORRECT <<EOF
$(python3 -c "
import json
with open('src/test/resources/sample-questions.json') as f:
    data = json.load(f)
for uid, q in data.items():
    content = q['content']
    if content.get('type') == 'mcq':
        print(uid, content['correct_answer'][0])
        break
")
EOF

if [ -z "$QID" ]; then
    echo "FAIL: could not pick a fixture MCQ"
    exit 1
fi
echo "Using question $QID (correct answer: $CORRECT)"

# a. a wrong answer is graded wrong and reports the missed box
body=$(curl -fsS -c "$COOKIES" -b "$COOKIES" -X POST -H 'Content-Type: application/json' \
    -d "{\"questionId\":\"${QID}\",\"submitted\":\"definitely-not-the-answer\"}" \
    "${BASE_URL}/api/session/answer")
python3 - "$body" <<'PY'
import json, sys
r = json.loads(sys.argv[1])
assert r["correct"] is False, r
assert r.get("missed_box_label") == "Missed Questions", r
PY

# b. the box now exists and holds that question
body=$(curl -fsS "${BASE_URL}/api/boxes")
MISSED_ID=$(python3 -c "
import json,sys
boxes = json.loads(sys.argv[1])
missed = [b for b in boxes if b['label'] == 'Missed Questions']
assert len(missed) == 1, boxes
assert missed[0]['question_count'] == 1, boxes
print(missed[0]['id'])
" "$body")

body=$(curl -fsS "${BASE_URL}/api/boxes/${MISSED_ID}/questions")
python3 - "$body" "$QID" <<'PY'
import json, sys
qs = json.loads(sys.argv[1])
assert [q["id"] for q in qs] == [sys.argv[2]], qs
PY

# c. answering the same question wrong again does not duplicate the row
curl -fsS -c "$COOKIES" -b "$COOKIES" -X POST -H 'Content-Type: application/json' \
    -d "{\"questionId\":\"${QID}\",\"submitted\":\"still-wrong\"}" \
    "${BASE_URL}/api/session/answer" >/dev/null
body=$(curl -fsS "${BASE_URL}/api/boxes/${MISSED_ID}/questions")
count=$(python3 -c "import json,sys; print(len(json.loads(sys.argv[1])))" "$body")
if [ "$count" != "1" ]; then
    echo "FAIL: expected no duplicate in missed box, got $count rows"
    exit 1
fi

# d. a correct answer is graded correct and adds nothing
body=$(curl -fsS -c "$COOKIES" -b "$COOKIES" -X POST -H 'Content-Type: application/json' \
    -d "{\"questionId\":\"${QID}\",\"submitted\":\"${CORRECT}\"}" \
    "${BASE_URL}/api/session/answer")
python3 - "$body" <<'PY'
import json, sys
r = json.loads(sys.argv[1])
assert r["correct"] is True, r
assert "missed_box_id" not in r, r
PY

echo "VERIFY_MISSED_BOX_OK"
