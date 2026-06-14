"""Benchmark orchestrator + entry point.

Usage (from the bench/ directory, inside the venv):
  python -m saturn_bench.runner [options]

Options:
  --key KEY           OpenRouter API key (else $OPENROUTER_KEY, else ../nudgent/.env)
  --models-file PATH  curated model id list (default bench/models.txt if present)
  --only PATH         restrict to one path: chat | nudge | edit
  --reps N            repetitions per (model x case)   [env REPS, default 3]
  --max-models N      cap the number of models tested
  --concurrency N     models tested in parallel        [default 4]
  --sleep S           seconds to pause between calls (politeness)   [default 0.5]
  --max-retries N     429 retries per call            [default 3]
  --out DIR           report output dir                [default bench/reports]
  --checkpoint PATH   per-run JSONL checkpoint         [default <out>/checkpoint.jsonl]
  --resume            continue from the checkpoint, skipping completed runs
  --discover-only     just print the discovered free-model list and exit
  --selftest          run the offline grader self-test (no network) and exit

Every graded run is appended to the checkpoint immediately, so Ctrl-C (or a
crash) never loses more than the call in flight: a partial report is still
written, and --resume picks up where it left off.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
from collections import Counter
from dataclasses import asdict
from datetime import datetime, timedelta
from pathlib import Path
from typing import List, Optional

import requests

from . import cases as cases_mod
from . import client as client_mod
from . import prompts
from .cases import Case
from .client import CallResult
from .executor import Task
from .grader import grade, RunScore
from .metrics import aggregate
from .report import write_reports
from .scheduler import run_queue

_REPO_ROOT = Path(__file__).resolve().parents[2]      # .../♄
_DEFAULT_OUT = str(_REPO_ROOT / "bench" / "reports")
_DEFAULT_MODELS_FILE = str(_REPO_ROOT / "bench" / "models.txt")


# ---------- key sourcing ----------

def _read_env_file_key(path: Path) -> Optional[str]:
    if not path.is_file():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("OPENROUTER_KEY="):
            val = line.split("=", 1)[1].strip().strip('"').strip("'")
            return val or None
    return None


def resolve_key(cli_key: Optional[str]) -> str:
    if cli_key:
        return cli_key
    env = os.environ.get("OPENROUTER_KEY")
    if env:
        return env
    for candidate in (_REPO_ROOT.parent / "nudgent" / ".env",):
        key = _read_env_file_key(candidate)
        if key:
            return key
    sys.exit("No API key: pass --key, set $OPENROUTER_KEY, or add OPENROUTER_KEY "
             "to ../nudgent/.env")


# ---------- model list ----------

def load_curated(path: str) -> List[str]:
    p = Path(path)
    if not p.is_file():
        return []
    out = []
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            out.append(line)
    return out


def resolve_models(key: str, models_file: str, max_models: Optional[int],
                   session: requests.Session) -> List[str]:
    curated = load_curated(models_file)
    try:
        discovered = client_mod.discover_free_models(key, session)
    except Exception as e:  # discovery is best-effort
        print(f"warning: model discovery failed ({e}); using curated list only",
              file=sys.stderr)
        discovered = []
    merged = list(dict.fromkeys(curated + discovered))  # curated first, de-duped
    if max_models:
        merged = merged[:max_models]
    return merged


# ---------- prompt assembly per case ----------

def build_call_args(case: Case, now: datetime):
    """Return (system_prompt, user_message) for a case."""
    if case.path == "chat":
        sysp = prompts.build_chat_prompt(case.language, case.schedule, case.seeds, now)
        return sysp, case.user_message
    if case.path == "nudge":
        sysp = prompts.build_nudge_prompt(case.language, case.schedule, case.seeds, now)
        return sysp, None
    if case.path == "edit":
        target = next((t for t in case.seeds if t.id == case.edit_target_id), case.seeds[0])
        sysp = prompts.build_edit_task_prompt(case.language, case.schedule, target, now)
        return sysp, case.user_message
    raise ValueError(f"unknown path {case.path}")


# ---------- checkpoint ----------

def load_checkpoint(path: str) -> List[RunScore]:
    """Reconstruct the RunScores already written to a checkpoint JSONL."""
    p = Path(path)
    if not p.is_file():
        return []
    runs: List[RunScore] = []
    bad = 0
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            runs.append(RunScore(**json.loads(line)))
        except (json.JSONDecodeError, TypeError):
            bad += 1
    if bad:
        print(f"warning: skipped {bad} malformed checkpoint line(s) in {path}",
              file=sys.stderr)
    return runs


# ---------- per-call visibility ----------

def summarize_call(call: CallResult) -> str:
    """One line describing what the model actually returned, for live output."""
    if not call.transport_ok:
        if call.error_detail:
            return f"{call.error_kind}: {call.error_detail.splitlines()[0][:60]}"
        return call.error_kind
    if call.parsed is None:
        return f"{call.error_kind} (unparseable)"
    env = call.parsed
    parts = []
    for a in (env.get("actions") or [])[:3]:
        if not isinstance(a, dict):
            continue
        t = a.get("type", "?")
        label = a.get("description") or a.get("id") or a.get("next_nudge_at") or ""
        label = str(label)[:24]
        parts.append(f"{t}({label})" if label else t)
    more = "…" if len(env.get("actions") or []) > 3 else ""
    actsum = ", ".join(parts) + more if parts else "no actions"
    reply = env.get("reply") or env.get("nudges") or ""
    reply = str(reply).replace("\n", " ").strip()[:40]
    return actsum + (f' · "{reply}"' if reply else "")


# ---------- main run ----------

def run(args) -> int:
    key = resolve_key(args.key)
    session = requests.Session()

    if args.discover_only:
        models = resolve_models(key, args.models_file, args.max_models, session)
        print(f"{len(models)} free models:")
        for m in models:
            print(f"  {m}")
        return 0

    cases = cases_mod.cases_for(args.only)
    models = resolve_models(key, args.models_file, args.max_models, session)
    if not models:
        sys.exit("No models to test (discovery empty and no models.txt).")

    os.makedirs(args.out, exist_ok=True)
    ckpt_path = args.checkpoint or os.path.join(args.out, "checkpoint.jsonl")

    # resume from, or step around, an existing checkpoint
    prior: List[RunScore] = []
    if args.resume:
        prior = load_checkpoint(ckpt_path)
        msg = (f"{len(prior)} runs loaded; skipping completed work"
               if prior else "nothing to resume; starting fresh")
        print(f"resume: {msg} ({ckpt_path})", file=sys.stderr)
    elif os.path.exists(ckpt_path) and os.path.getsize(ckpt_path) > 0:
        bak = f"{ckpt_path}.{datetime.now():%Y%m%d-%H%M%S}.bak"
        os.replace(ckpt_path, bak)
        print(f"note: prior checkpoint kept at {bak} (--resume to continue it)",
              file=sys.stderr)
    done_counts = Counter((r.model, r.case_id) for r in prior)

    started = datetime.now()
    total = len(models) * len(cases) * args.reps
    print(f"benchmark: {len(models)} models x {len(cases)} cases x {args.reps} reps "
          f"= {total} calls, concurrency {args.concurrency}", file=sys.stderr)

    # one queue entry per call still owed, after subtracting resumed work
    items = [(model, case)
             for model in models
             for case in cases
             for _ in range(max(0, args.reps - done_counts.get((model, case.id), 0)))]

    all_runs: List[RunScore] = list(prior)
    lock = threading.Lock()
    counter = {"done": len(prior)}
    ckpt = open(ckpt_path, "a", encoding="utf-8")

    sessions = threading.local()

    def session_for_thread() -> requests.Session:
        s = getattr(sessions, "s", None)
        if s is None:
            s = sessions.s = requests.Session()
        return s

    def call_fn(model: str, case: Case) -> CallResult:
        sysp, user = build_call_args(case, datetime.now())   # fresh "now" each attempt
        return client_mod.chat(key, model, sysp, user_message=user,
                               session=session_for_thread())

    def on_result(model: str, case: Case, call: CallResult) -> None:
        rs = grade(case, call, datetime.now())
        with lock:
            all_runs.append(rs)
            ckpt.write(json.dumps(asdict(rs)) + "\n")
            ckpt.flush()
            counter["done"] += 1
            n = counter["done"]
        comp = f"{rs.composite:.2f}" if rs.composite is not None else " -- "
        print(f"  [{n}/{total}] {model} :: {case.id}  "
              f"{rs.status or '---'} {rs.latency_ms:.0f}ms comp={comp}  "
              f"↳ {summarize_call(call)}", file=sys.stderr)

    try:
        run_queue(items, call_fn, on_result,
                  concurrency=args.concurrency, sleep=args.sleep,
                  max_retries=args.max_retries)
    except KeyboardInterrupt:
        print(f"\ninterrupted -- {counter['done']} runs saved to {ckpt_path}; "
              f"rerun with --resume to finish", file=sys.stderr)
    finally:
        ckpt.close()

    if not all_runs:
        print("no runs to report", file=sys.stderr)
        return 1

    metrics = aggregate(all_runs)
    paths = write_reports(metrics, cases, all_runs, args.reps, started, args.out)
    print(f"\nwrote {paths['markdown']}\n      {paths['csv']}", file=sys.stderr)

    # short console summary
    ranked = sorted(metrics.values(),
                    key=lambda m: (m.correctness if m.correctness is not None else -1),
                    reverse=True)
    print("\ntop models by correctness:")
    for m in ranked[:10]:
        c = f"{m.correctness * 100:.0f}%" if m.correctness is not None else "—"
        sr = f"{m.success_rate * 100:.0f}%"
        print(f"  {c:>5}  success {sr:>4}  {m.model}")
    return 0


# ---------- offline self-test ----------

def selftest() -> int:
    """Validate the grader deterministically with canned responses (no network)."""
    from .cases import ALL_CASES

    by_id = {c.id: c for c in ALL_CASES}
    now = datetime.now()
    future = (now + timedelta(days=1)).replace(microsecond=0)
    fut = future.strftime("%Y-%m-%dT%H:%M:%S")

    def canned(parsed) -> CallResult:
        r = CallResult(model="selftest", status=200, latency_ms=10.0)
        if parsed is not None and parsed.get("actions") is None:
            parsed["actions"] = []
        r.parsed = parsed
        return r

    def transport_fail() -> CallResult:
        return CallResult(model="selftest", status=0, error_kind="conn_error")

    checks = []

    def expect_range(name, score, lo, hi):
        ok = score is not None and lo <= score <= hi
        checks.append((name, ok, score))

    # 1. good add -> high composite
    rs = grade(by_id["chat_add_explicit"],
               canned({"reply": "Done.", "actions": [
                   {"type": "add_task", "description": "call mom", "next_nudge_at": fut}]}),
               now)
    expect_range("good add", rs.composite, 0.8, 1.0)

    # 2. completing a recurring task -> outcome must fail
    rs = grade(by_id["chat_complete_recurring_guard"],
               canned({"reply": "ok", "actions": [{"type": "complete_task", "id": 1}]}),
               now)
    expect_range("bad complete-recurring", rs.composite, 0.0, 0.4)

    # 3. invalid JSON (transport ok, parse failed) -> composite 0
    bad = CallResult(model="selftest", status=200, error_kind="invalid_json")
    rs = grade(by_id["chat_add_explicit"], bad, now)
    expect_range("invalid json", rs.composite, 0.0, 0.0)

    # 4. transport failure -> correctness N/A
    rs = grade(by_id["chat_add_explicit"], transport_fail(), now)
    checks.append(("transport fail -> None", rs.composite is None, rs.composite))

    # 5. edit scope guard: deleting another task -> outcome fail (other task gone)
    rs = grade(by_id["edit_scope_guard"],
               canned({"reply": "ok", "actions": [
                   {"type": "complete_task", "id": 1},
                   {"type": "delete_task", "id": 2}]}),
               now)
    expect_range("edit scope violation", rs.outcome, 0.0, 0.0)

    # 6. past time on add -> ADDED_TIME_REJECTED -> guard case outcome fail
    past = (now - timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%S")
    rs = grade(by_id["chat_past_time_guard"],
               canned({"reply": "ok", "actions": [
                   {"type": "add_task", "description": "call the bank", "next_nudge_at": past}]}),
               now)
    expect_range("past-time rejected", rs.outcome, 0.0, 0.0)

    # 7. pure question -> no actions -> high composite
    rs = grade(by_id["chat_pure_question"],
               canned({"reply": "You have 2 tasks.", "actions": []}), now)
    expect_range("pure question", rs.composite, 0.8, 1.0)

    ok_all = all(ok for _, ok, _ in checks)
    print("grader self-test:")
    for name, ok, score in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name} (score={score})")
    print("RESULT:", "PASS" if ok_all else "FAIL")
    return 0 if ok_all else 1


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="saturn_bench")
    p.add_argument("--key")
    p.add_argument("--models-file", default=_DEFAULT_MODELS_FILE)
    p.add_argument("--only", choices=["chat", "nudge", "edit"])
    p.add_argument("--reps", type=int, default=int(os.environ.get("REPS", "3")))
    p.add_argument("--max-models", type=int)
    p.add_argument("--concurrency", type=int,
                   default=int(os.environ.get("CONCURRENCY", "4")))
    p.add_argument("--sleep", type=float, default=0.5)
    p.add_argument("--max-retries", type=int, default=3)
    p.add_argument("--out", default=_DEFAULT_OUT)
    p.add_argument("--checkpoint")
    p.add_argument("--resume", action="store_true")
    p.add_argument("--discover-only", action="store_true")
    p.add_argument("--selftest", action="store_true")
    args = p.parse_args(argv)

    if args.selftest:
        return selftest()
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
