# Saturn model benchmark

Scores free OpenRouter models on Saturn's task-action protocol along two axes:

- **Task intelligence** — does the model emit correct JSON actions (right action
  type, right id, well-formed `next_nudge_at`, recurring semantics, scope
  discipline)? Graded by running the model's actions through a port of the app's
  real `ActionExecutor` against a seeded in-memory store and checking the
  resulting state + receipt kinds.
- **Availability** — real-world reliability: HTTP success rate, 429 rate, retries,
  latency p50/p95, and malformed-JSON rate.

It is a standalone Python tool; it does **not** touch the Android build.

## Running

Inside the Nix shell (creates `.venv`, installs deps):

```sh
cd bench
nix-shell                       # sets up .venv and activates it
python -m saturn_bench.runner --selftest          # offline grader check, no API calls
python -m saturn_bench.runner --discover-only      # list discovered free models
python -m saturn_bench.runner --only chat --max-models 1 --reps 1   # smoke run
python -m saturn_bench.runner                       # full run
```

Or via the project Makefile (from the repo root):

```sh
make bench                       # full run
make bench BENCH_ARGS="--only chat --max-models 1 --reps 1"
make bench-selftest              # offline grader check
```

Reports land in `bench/reports/report-<timestamp>.{md,csv}` (gitignored).

### Resuming an interrupted run

Every graded call is appended to `bench/reports/checkpoint.jsonl` the moment it
is scored, so `Ctrl-C` (or a crash, or a dropped connection) never loses more
than the one call in flight — a partial report is still written from everything
completed. To continue where it stopped:

```sh
python -m saturn_bench.runner --resume        # skips the (model, case) reps already done
```

A fresh run that finds an existing checkpoint moves it aside to a `.bak` first,
so nothing is clobbered. Live progress prints one line per call showing the
model, case, HTTP status, latency, composite score, and a summary of the
actions/reply the model returned — so you can watch what it is doing, not just
the failures.

### Speed

The run is `models × cases × reps` calls; on the full free roster that is
thousands of calls, and free models are individually slow and rate-limit
aggressively. The unit of work is a single call on a shared, rate-limit-aware
queue (`scheduler.py`) that `--concurrency N` (default 4) worker threads pull
from. A 429 never blocks a worker — the call is re-queued and only that *model*
is held back until its `Retry-After` elapses, so the worker immediately serves
another model whose turn is ready. Each model still stays sequential (one call
in flight) and polite (a `--sleep`-second cooldown between its own calls), so a
slow or throttled model can't starve a concurrency slot. Tune with
`--concurrency`, `--max-retries`, and `--sleep`; narrow the matrix with `--only`,
`--max-models`, and `--reps` for quick iterations.

### API key

Resolved in order: `--key` → `$OPENROUTER_KEY` → `OPENROUTER_KEY=` in
`../nudgent/.env`. The key is never printed or written to a report.

### Useful flags

`--reps N` (or `$REPS`, default 3) · `--only chat|nudge|edit` · `--max-models N` ·
`--concurrency N` (or `$CONCURRENCY`, default 4) · `--resume` · `--checkpoint PATH` ·
`--models-file PATH` · `--sleep S` · `--max-retries N` · `--out DIR`.

## The corpus

`saturn_bench/cases.py` holds ~33 cases across the chat / nudge / edit prompt
paths, including the tricky ones that separate models: completing a recurring
task (must refuse), past-time guards, ambiguous-id resolution, multi-action
messages, edit scope discipline, JSON escaping, Italian, and large context. Add
more by appending `Case(...)` entries; each carries an `expect` callable scoring
`action` / `fields` / `outcome` in [0,1].

## Keep in sync with the app (IMPORTANT)

`prompts.py` and `executor.py` are hand-ports of Java source. If the app changes,
update these or the benchmark silently measures the wrong thing:

| Python                  | Java source of truth                                    |
|-------------------------|---------------------------------------------------------|
| `prompts.py`            | `AgentClient.java` `buildChatPrompt/Nudge/EditTask`     |
| `executor.py` (execute) | `ActionExecutor.java` `execute`, `validatedFutureTime`  |
| `executor.py` (Store)   | `FakeTaskStore.java`                                     |
| `executor.py` (Kind)    | `Receipt.java` `Kind`                                    |
| `client.py` (chat)      | `AgentClient.java` `chat` (endpoint, headers, parsing)  |

The offline `--selftest` exercises the grader end-to-end with canned responses,
so a regression in the ported logic is caught without spending API calls.
