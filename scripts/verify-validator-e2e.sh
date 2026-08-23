#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

APP_PORT="8095"
STUB_PORT="8094"
BASE_URL="http://localhost:${APP_PORT}"
STUB_URL="http://localhost:${STUB_PORT}"

echo "Building classpath..."
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
CP="target/classes:$(cat cp.txt)"

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

STUB_PID=""
SERVER_PID=""

cleanup() {
    [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
    [ -n "$STUB_PID" ] && kill "$STUB_PID" 2>/dev/null || true
}
trap cleanup EXIT

wait_ready() {
    local url="$1"
    local i
    for i in $(seq 1 30); do
        if curl -fsS "$url" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# run_scenario <scenario> [extra env for app server...]
# Results are published in the globals DB_PATH_OUT / LOG_PATH_OUT / STUB_LOG_OUT.
# It must NOT be called inside $( ): the backgrounded stub and server inherit the
# command-substitution pipe, so the substitution would block until they exit (hang).
run_scenario() {
    local scenario="$1"
    shift
    local extra_env=("$@")

    DB_PATH_OUT="/tmp/sat_verify_validator_e2e_${scenario}_$$.db"
    LOG_PATH_OUT="/tmp/sat_verify_validator_e2e_${scenario}_$$.log"
    STUB_LOG_OUT="/tmp/sat_verify_validator_e2e_${scenario}_$$_stub.log"
    rm -f "$DB_PATH_OUT" "$STUB_LOG_OUT"

    python3 scripts/stub-openai.py "$STUB_PORT" "$scenario" "$STUB_LOG_OUT" >/dev/null 2>&1 &
    STUB_PID=$!
    if ! wait_ready "${STUB_URL}/health"; then
        echo "FAIL [$scenario]: stub OpenAI server never became ready"
        cat "$STUB_LOG_OUT" 2>/dev/null || true
        exit 1
    fi

    DB_PATH="$DB_PATH_OUT" java -cp "$CP" satapp.tools.ImportQuestions src/test/resources/sample-questions.json "$DB_PATH_OUT" >/dev/null

    # ${arr[@]+"${arr[@]}"} — bash 3.2 (stock macOS) treats a bare "${arr[@]}"
    # on an empty array as unbound under `set -u`.
    env \
        OPENAI_API_KEY="test-key-not-real" \
        OPENAI_BASE_URL="$STUB_URL" \
        DB_PATH="$DB_PATH_OUT" \
        PORT="$APP_PORT" \
        ${extra_env[@]+"${extra_env[@]}"} \
        java -cp "$CP" satapp.web.WebServer >"$LOG_PATH_OUT" 2>&1 &
    SERVER_PID=$!

    if ! wait_ready "${BASE_URL}/api/meta/filters"; then
        echo "FAIL [$scenario]: server never became ready"
        cat "$LOG_PATH_OUT"
        exit 1
    fi
}

stop_scenario() {
    [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
    SERVER_PID=""
    [ -n "$STUB_PID" ] && kill "$STUB_PID" 2>/dev/null || true
    wait "$STUB_PID" 2>/dev/null || true
    STUB_PID=""
}

count_questions() {
    curl -fsS "${BASE_URL}/api/questions" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))"
}

# ---- scenario: good ----
run_scenario "good"
db_path="$DB_PATH_OUT"
log_path="$LOG_PATH_OUT"
stub_log="$STUB_LOG_OUT"

resp_file=$(mktemp)
status=$(curl -sS -o "$resp_file" -w '%{http_code}' -X POST "${BASE_URL}/api/questions/${SEED_ID}/generate")
if [ "$status" != "201" ]; then
    echo "FAIL [good]: expected 201, got $status"
    cat "$resp_file"
    exit 1
fi
NEW_ID=$(python3 -c "import json; print(json.load(open('$resp_file'))['id'])")
answer_status=$(curl -sS -o "${resp_file}.ans" -w '%{http_code}' "${BASE_URL}/api/questions/${NEW_ID}/answer")
if [ "$answer_status" != "200" ]; then
    echo "FAIL [good]: could not fetch answer for generated question, status $answer_status"
    exit 1
fi
GOT_ANSWER=$(python3 -c "import json; print(json.load(open('${resp_file}.ans'))['correct_answer'])")
if [ "$GOT_ANSWER" != "B" ]; then
    echo "FAIL [good]: expected correct_answer B, got $GOT_ANSWER"
    exit 1
fi
stop_scenario

# ---- scenario: mismarked (validation on) ----
run_scenario "mismarked"
db_path="$DB_PATH_OUT"
log_path="$LOG_PATH_OUT"
stub_log="$STUB_LOG_OUT"

before_count=$(count_questions)
status=$(curl -sS -o "$resp_file" -w '%{http_code}' -X POST "${BASE_URL}/api/questions/${SEED_ID}/generate")
if [ "$status" != "502" ]; then
    echo "FAIL [mismarked]: expected 502, got $status"
    cat "$resp_file"
    exit 1
fi
case "$(cat "$resp_file")" in
    *validat*) ;;
    *) echo "FAIL [mismarked]: error body did not mention validation: $(cat "$resp_file")"; exit 1 ;;
esac
after_count=$(count_questions)
if [ "$before_count" != "$after_count" ]; then
    echo "FAIL [mismarked]: question count changed ($before_count -> $after_count), a mismarked row was persisted"
    exit 1
fi
stop_scenario

# ---- scenario: mismarked with validation disabled (kill switch) ----
run_scenario "mismarked" "SAT_VALIDATE_ANSWERS=off"
db_path="$DB_PATH_OUT"
log_path="$LOG_PATH_OUT"
stub_log="$STUB_LOG_OUT"

status=$(curl -sS -o "$resp_file" -w '%{http_code}' -X POST "${BASE_URL}/api/questions/${SEED_ID}/generate")
if [ "$status" != "201" ]; then
    echo "FAIL [mismarked/off]: expected 201 with SAT_VALIDATE_ANSWERS=off, got $status"
    cat "$resp_file"
    exit 1
fi
stop_scenario

# ---- scenario: dupchoices ----
run_scenario "dupchoices"
db_path="$DB_PATH_OUT"
log_path="$LOG_PATH_OUT"
stub_log="$STUB_LOG_OUT"

status=$(curl -sS -o "$resp_file" -w '%{http_code}' -X POST "${BASE_URL}/api/questions/${SEED_ID}/generate")
if [ "$status" != "502" ]; then
    echo "FAIL [dupchoices]: expected 502, got $status"
    cat "$resp_file"
    exit 1
fi
if grep -q "^SOLVER$" "$stub_log" 2>/dev/null; then
    echo "FAIL [dupchoices]: solver was called, but duplicate choices should be rejected before solving"
    cat "$stub_log"
    exit 1
fi
stop_scenario

echo "VERIFY_VALIDATOR_E2E_OK"
