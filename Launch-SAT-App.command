#!/usr/bin/env bash
# Double-click this file in Finder to build (if needed), start the SAT Tutoring
# web server, and open the app in your default browser.
# Close the Terminal window or press Ctrl-C to stop the server.

set -uo pipefail

cd "$(dirname "$0")"

# Maven and java are often not on the PATH that Finder hands to a .command file.
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"

# Load local secrets (OPENAI_API_KEY, etc.) from the gitignored .env, if present.
# Copy .env.example to .env and fill it in; .env is never committed.
if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
fi

if ! command -v mvn >/dev/null 2>&1; then
    echo "ERROR: 'mvn' not found. Install Maven (brew install maven) and try again."
    echo "Press Enter to close."
    read -r _
    exit 1
fi

DB_PATH="${DB_PATH:-data/questions.db}"
export DB_PATH

# Pick the first free port starting at $PORT (default 8080).
START_PORT="${PORT:-8080}"
PORT=""
for candidate in $(seq "$START_PORT" $((START_PORT + 20))); do
    if ! lsof -nP -iTCP:"$candidate" -sTCP:LISTEN >/dev/null 2>&1; then
        PORT="$candidate"
        break
    fi
done
if [ -z "$PORT" ]; then
    echo "ERROR: no free port found starting at $START_PORT."
    echo "Press Enter to close."
    read -r _
    exit 1
fi
export PORT

echo "Building SAT Tutoring..."
if ! mvn -q compile; then
    echo
    echo "ERROR: build failed (see the Maven output above)."
    echo "Press Enter to close."
    read -r _
    exit 1
fi

mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
CP="target/classes:$(cat cp.txt)"

echo "Using database: $DB_PATH"
echo "Starting web server on http://localhost:$PORT ..."
java -cp "$CP" satapp.web.WebServer &
SERVER_PID=$!

cleanup() {
    echo
    echo "Stopping server..."
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# Wait for the server to answer before opening the browser.
READY=""
for _ in $(seq 1 40); do
    if curl -fsS "http://localhost:$PORT/api/meta/filters" >/dev/null 2>&1; then
        READY="1"
        break
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        break
    fi
    sleep 0.5
done

if [ -z "$READY" ]; then
    echo "ERROR: the server did not start (see the output above)."
    echo "Press Enter to close."
    read -r _
    exit 1
fi

open "http://localhost:$PORT"

echo
echo "SAT Tutoring is running at http://localhost:$PORT"
echo "Press Ctrl-C (or close this window) to stop the server."
wait "$SERVER_PID"
