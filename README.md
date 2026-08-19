# SAT Tutoring

A Java project for SAT tutoring workflows.

## Requirements

- Java (JDK 17+ recommended)
- Maven

## Build

```bash
mvn compile
```

## Run

The terminal workflow menu is the main entry point:

```bash
mvn compile
mvn -q exec:java -Dexec.mainClass=Main
```

or, with an explicit classpath:

```bash
mvn dependency:build-classpath -q -Dmdep.outputFile=cp.txt
java -cp target/classes:$(cat cp.txt) Main
```

## Credentials setup

Secrets are never committed. Put local credentials in
`src/main/resources/credentials.json` (gitignored) and OAuth tokens under
`tokens/` (gitignored).

## AI-assisted workflow

- `CLAUDE.md` — the rules every agent (interactive or autonomous) must follow.
- `tasks/` — the task queue; its own private git repo so the board is readable
  from a phone. `tasks/BOARD.csv` is the status table, `tasks/JOURNAL.md` the log.
- `./run-runner.sh` — starts the autonomous runner against this repo.
- `/save-task` — turn a conversation's conclusions into runner-ready task files.
- `/debrief` — morning summary of what the runner built, and what to merge.
