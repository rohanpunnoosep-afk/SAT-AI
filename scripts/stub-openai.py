#!/usr/bin/env python3
"""Stub OpenAI chat-completions endpoint for validator e2e tests.

Usage: stub-openai.py <port> <scenario> <logfile>

Serves POST /v1/chat/completions on localhost only. Inspects the request's
user-message content to decide whether it is a generator prompt (asks the
model to write a new question) or a solver prompt (built by
AnswerValidator.buildSolverPrompt, always instructs the model to end its
reply with "FINAL: <choice id>"), and replies with a scenario-specific
fixture. Every request is logged (GENERATOR or SOLVER) to <logfile>.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SOLVER_MARKER = "FINAL: <choice id>"

SCENARIOS = {
    "good": {
        "generator": {
            "stem": "What is 7 + 8?",
            "choices": [
                {"id": "A", "text": "14"},
                {"id": "B", "text": "15"},
                {"id": "C", "text": "16"},
                {"id": "D", "text": "17"},
            ],
            "work": "7 + 8 = 15",
            "correct_answer": "B",
            "explanation": "Adding 7 and 8 gives 15, which is choice B.",
        },
        "solver_final": "B",
    },
    "mismarked": {
        "generator": {
            "stem": "What is 9 + 5?",
            "choices": [
                {"id": "A", "text": "13"},
                {"id": "B", "text": "14"},
                {"id": "C", "text": "15"},
                {"id": "D", "text": "16"},
            ],
            "work": "9 + 5 = 13",
            "correct_answer": "A",
            "explanation": "9 plus 5 gives 13, so the answer is A.",
        },
        "solver_final": "C",
    },
    "dupchoices": {
        "generator": {
            "stem": "What is 4 x 3?",
            "choices": [
                {"id": "A", "text": "11"},
                {"id": "B", "text": "12"},
                {"id": "C", "text": "12"},
                {"id": "D", "text": "13"},
            ],
            "work": "4 x 3 = 12",
            "correct_answer": "B",
            "explanation": "4 times 3 equals 12, so the answer is B.",
        },
        "solver_final": "B",
    },
}


def make_handler(scenario_name, log_path):
    scenario = SCENARIOS[scenario_name]

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass

        def do_GET(self):
            # Readiness probe: the verify script waits on this before starting
            # the app server, so the stub is never raced.
            if self.path == "/health":
                body = b"ok"
                self.send_response(200)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            self.send_response(404)
            self.end_headers()

        def do_POST(self):
            if self.path != "/v1/chat/completions":
                self.send_response(404)
                self.end_headers()
                return

            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length)
            try:
                body = json.loads(raw)
                content = body["messages"][0]["content"]
            except Exception:
                content = ""

            if SOLVER_MARKER in content:
                kind = "SOLVER"
                final_id = scenario["solver_final"]
                payload = (
                    "Working through the question step by step, the reasoning "
                    "leads to a single option.\n"
                    f"FINAL: {final_id}\n"
                )
            else:
                kind = "GENERATOR"
                payload = json.dumps(scenario["generator"])

            with open(log_path, "a") as f:
                f.write(kind + "\n")

            response_body = json.dumps(
                {"choices": [{"message": {"role": "assistant", "content": payload}}]}
            ).encode("utf-8")

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response_body)))
            self.end_headers()
            self.wfile.write(response_body)

    return Handler


def main():
    port = int(sys.argv[1])
    scenario_name = sys.argv[2]
    log_path = sys.argv[3]

    if scenario_name not in SCENARIOS:
        print(f"unknown scenario: {scenario_name}", file=sys.stderr)
        sys.exit(1)

    handler = make_handler(scenario_name, log_path)
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
