#!/usr/bin/env python3
"""Stub OpenAI chat-completions endpoint for validator e2e tests.

Usage: stub-openai.py <port> <scenario> <logfile>

Serves POST /v1/chat/completions on localhost only. It inspects the request's
user-message content to work out which step of the generation pipeline is
calling, and replies with a scenario-specific fixture. Every request is logged
by kind to <logfile>.

The pipeline has two shapes:

  Math (answer-first)  OPEN_GENERATOR -> OPEN_SOLVER x2 -> DISTRACTORS
  Reading and Writing  GENERATOR      -> SOLVER x2
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Markers taken verbatim from the prompt builders.
OPEN_SOLVER_MARKER = "FINAL: <your answer>"
SOLVER_MARKER = "FINAL: <choice id>"
DISTRACTOR_MARKER = '{"distractors"'
OPEN_GENERATOR_MARKER = '"answer": "..."'

SCENARIOS = {
    # Writer and both blind solvers agree; distractors are clean.
    "good": {
        "open_generator": {
            "stem": "What is 7 + 8?",
            "work": "7 + 8 = 15",
            "answer": "15",
            "explanation": "Adding 7 and 8 gives 15.",
        },
        "open_solver_finals": ["15", "15"],
        "distractors": ["14", "16", "17"],
    },
    # The writer marks 13 but an independent solve of the bare stem gives 15.
    "mismarked": {
        "open_generator": {
            "stem": "What is 9 + 5?",
            "work": "9 + 5 = 13",
            "answer": "13",
            "explanation": "9 plus 5 gives 13.",
        },
        "open_solver_finals": ["14", "14"],
        "distractors": ["12", "15", "16"],
    },
    # The two blind solvers do not even agree with each other: the question is
    # ambiguous and must be thrown away rather than shipped.
    "ambiguous": {
        "open_generator": {
            "stem": "A number is doubled. What is it?",
            "work": "unclear",
            "answer": "8",
            "explanation": "Doubling gives 8.",
        },
        "open_solver_finals": ["8", "12"],
        "distractors": ["6", "10", "12"],
    },
    # A distractor repeats the verified answer, so the choice set is unusable.
    "dupchoices": {
        "open_generator": {
            "stem": "What is 4 x 3?",
            "work": "4 x 3 = 12",
            "answer": "12",
            "explanation": "4 times 3 equals 12.",
        },
        "open_solver_finals": ["12", "12"],
        "distractors": ["11", "12", "13"],
    },
    # Reading and Writing keeps the choices-first shape; here the solvers agree
    # with the marked choice.
    "verbal": {
        "generator": {
            "stem": "Which word best completes the text?",
            "choices": [
                {"id": "A", "text": "reluctant"},
                {"id": "B", "text": "eager"},
                {"id": "C", "text": "hostile"},
                {"id": "D", "text": "puzzled"},
            ],
            "work": "The text describes enthusiasm, so B fits.",
            "correct_answer": "B",
            "explanation": "The text describes enthusiasm, so eager (B) fits.",
        },
        "solver_finals": ["B", "B"],
    },
    # Reading and Writing where the blind solvers reject the marked choice.
    "verbal_mismarked": {
        "generator": {
            "stem": "Which word best completes the text?",
            "choices": [
                {"id": "A", "text": "reluctant"},
                {"id": "B", "text": "eager"},
                {"id": "C", "text": "hostile"},
                {"id": "D", "text": "puzzled"},
            ],
            "work": "The text describes enthusiasm.",
            "correct_answer": "A",
            "explanation": "The text describes enthusiasm, so reluctant (A) fits.",
        },
        "solver_finals": ["B", "B"],
    },
}


def make_handler(scenario_name, log_path):
    scenario = SCENARIOS[scenario_name]
    # The two blind solves run concurrently, so hand out the configured replies
    # in turn rather than assuming a fixed order per attempt.
    counters = {"open_solver": 0, "solver": 0}

    def next_final(key, values):
        value = values[counters[key] % len(values)]
        counters[key] += 1
        return value

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

            if OPEN_SOLVER_MARKER in content:
                kind = "OPEN_SOLVER"
                final = next_final("open_solver", scenario["open_solver_finals"])
                payload = (
                    "Working through the question step by step from scratch.\n"
                    f"FINAL: {final}\n"
                )
            elif SOLVER_MARKER in content:
                kind = "SOLVER"
                final = next_final("solver", scenario["solver_finals"])
                payload = (
                    "Working through the question step by step, the reasoning "
                    "leads to a single option.\n"
                    f"FINAL: {final}\n"
                )
            elif DISTRACTOR_MARKER in content:
                kind = "DISTRACTORS"
                payload = json.dumps({"distractors": scenario["distractors"]})
            elif OPEN_GENERATOR_MARKER in content:
                kind = "OPEN_GENERATOR"
                payload = json.dumps(scenario["open_generator"])
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
