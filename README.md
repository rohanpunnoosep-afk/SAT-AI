# SAT Tutoring

A locally-run SAT practice app. It loads the SAT question bank into a local
SQLite database and serves a small web UI where you can filter questions, answer
them, track what you miss, and ask OpenAI to generate fresh practice questions
"similar to this one". Each generated question is checked by an independent
blind solver before you see it.

## How it works

```
 browser (index.html + app.js)
        │  fetch /api/...
        ▼
 Javalin web server  ── satapp.web.WebServer
        │
        ├── satapp.db       SQLite: questions table + practice "boxes"
        ├── satapp.session  in-memory session: answers, grading, review list, stats
        └── satapp.ai       OpenAI client → generate question → AnswerValidator
```

| Package / file | Role |
|---|---|
| `Main.java` | Terminal menu entry point (`help`, `serve`, `exit`). `serve` starts the web app. |
| `satapp.web.WebServer` | Javalin HTTP server; serves `src/main/resources/public/` and the JSON API. |
| `satapp.db` | `Database` (schema, `DB_PATH`), `QuestionRepository`, `BoxRepository`. |
| `satapp.model` | `Question`, `Box`, `TopicStat`. |
| `satapp.session` | `SessionStore` / `SessionState` hold the current session; `AnswerGrader` grades multiple-choice and student-produced answers. |
| `satapp.ai.OpenAiClient` | Calls the OpenAI API to write a new question from a seed question (or from a whole box). |
| `satapp.ai.QuestionGenPrompt` | Builds the generation prompt; the model reasons before committing to an answer. |
| `satapp.ai.AnswerValidator` | Deterministic checks on generated questions plus a blind-solve pass: a second model call solves the question without seeing the key, and the question is rejected if the answers disagree. |
| `satapp.tools.ImportQuestions` | One-time importer: question-bank JSON → SQLite. |
| `satapp.tools.GenCheck`, `ValidateCheck` | Offline self-checks for the generation and validation logic. |

### Practice flow

1. **Browse.** Filter by section, domain, skill, and difficulty (`GET /api/questions`, `/api/meta/filters`).
2. **Answer.** Submit an answer (`POST /api/session/answer`). The correct answer stays hidden until you ask for it (`GET /api/questions/{id}/answer`).
3. **Review.** Missed questions build a live "topics to review" list and per-topic stats (`/api/session/review-list`, `/api/session/stats`). Session state is in memory and resets when the server restarts.
4. **Generate.** Ask for a similar question (`POST /api/questions/{id}/generate`). The server retries up to 3 times until a candidate passes validation, then saves it with `source = generated` and a link to its parent.
5. **Boxes.** Group questions into named practice boxes (`/api/boxes`). `POST /api/boxes/{id}/generate` writes a new question in the style of the whole box.

## Requirements

- JDK 17+ (the build targets Java 11)
- Maven
- An OpenAI API key, needed only for question generation

## Setup

### 1. API keys (kept secret)

Keys are read **only from environment variables**. They are never stored in the
code or committed. Pick one option:

- **`.env` file (recommended).** Copy the template and fill it in:
  ```bash
  cp .env.example .env
  # edit .env and set OPENAI_API_KEY=sk-...
  ```
  `.env` is gitignored. `Launch-SAT-App.command` loads it automatically.
- **Shell profile.** Add `export OPENAI_API_KEY=...` to `~/.zshrc`.

Without a key, the app still runs; the generate endpoints return `503` with a clear message.

| Variable | Default | Purpose |
|---|---|---|
| `OPENAI_API_KEY` | — | Required for generation |
| `OPENAI_MODEL` | `gpt-5.4-mini` | Question-writing model |
| `OPENAI_VALIDATOR_MODEL` | same as `OPENAI_MODEL` | Blind-solver model |
| `OPENAI_BASE_URL` | `https://api.openai.com` | API base (used by the test stub) |
| `SAT_VALIDATE_ANSWERS` | on | `off`/`false`/`0` disables blind-solve validation |
| `SAT_WRITER_REASONING_EFFORT` | `medium` | `none`, `low`, `medium`, `high`, `xhigh` |
| `SAT_VALIDATOR_REASONING_EFFORT` | `high` | as above |
| `SAT_DISTRACTOR_REASONING_EFFORT` | `low` | as above |
| `DB_PATH` | `data/questions.db` | SQLite database file |
| `PORT` | `8080` | Web server port |

### 2. Question data

The question bank is **not** in this repo, because it is large and not
redistributable (`data/` and `vendor/` are gitignored). Import your copy once:

```bash
mvn -q compile
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" satapp.tools.ImportQuestions path/to/questions.json [data/questions.db]
```

`src/test/resources/sample-questions.json` is a small sample in the same format.

## Run

**macOS, double-click:** open `Launch-SAT-App.command`. It builds the project,
finds a free port, starts the server, and opens your browser.

**Terminal menu:**

```bash
mvn compile
mvn -q exec:java -Dexec.mainClass=Main
# then type: serve
```

**Directly:**

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" satapp.web.WebServer
```

## Verification scripts

`scripts/verify-*.sh` start a throwaway server and test each feature end to end
(API, sessions, boxes, frontend, generation). `verify-validator-e2e.sh` uses
`scripts/stub-openai.py`, a fake OpenAI server, to prove that a mis-keyed
generated question is rejected. None of these scripts need a real API key.

