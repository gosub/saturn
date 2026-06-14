"""Grade a single model response against a case.

A run is scored on independent dimensions, each in [0,1]:
  json_valid  - the model returned a parseable JSON object
  envelope_ok - the right top-level keys are present (reply+actions / nudges+actions)
  action      - the expected action types were chosen, with correct scope (case)
  fields      - required fields are well-formed (case)
  outcome     - resulting store state + receipt kinds match expectation (case, gold)

composite (only when transport succeeded) weights the gold outcome highest.
When the server never returned a completion (transport failure), correctness is
N/A and the run only feeds the availability metrics.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Optional

from .cases import Case, GradeCtx
from .client import CallResult
from .executor import Store, execute, normalize_action, kinds as receipt_kinds

W_ENVELOPE = 0.10
W_ACTION = 0.25
W_FIELDS = 0.15
W_OUTCOME = 0.50


@dataclass
class RunScore:
    model: str
    case_id: str
    path: str
    # availability
    status: int
    latency_ms: float
    error_kind: str
    retries: int
    transport_ok: bool
    json_ok: bool
    # correctness (None when transport failed)
    envelope: Optional[float] = None
    action: Optional[float] = None
    fields: Optional[float] = None
    outcome: Optional[float] = None
    composite: Optional[float] = None


def grade(case: Case, call: CallResult, now: datetime) -> RunScore:
    rs = RunScore(
        model=call.model, case_id=case.id, path=case.path,
        status=call.status, latency_ms=call.latency_ms, error_kind=call.error_kind,
        retries=call.retries, transport_ok=call.transport_ok, json_ok=call.json_ok,
    )
    if not call.transport_ok:
        return rs  # availability-only

    if not call.json_ok:
        rs.envelope = rs.action = rs.fields = rs.outcome = 0.0
        rs.composite = 0.0
        return rs

    env = call.parsed or {}

    # envelope shape
    if case.path == "nudge":
        rs.envelope = 1.0 if ("nudges" in env and "actions" in env) else 0.0
    else:
        rs.envelope = 1.0 if ("reply" in env and "actions" in env) else 0.0

    # run the emitted actions through the real executor on a seeded store
    actions = [normalize_action(a) for a in (env.get("actions") or []) if isinstance(a, dict)]
    store = Store(seed=case.seeds)
    receipts = execute(actions, store, now=now)

    ctx = GradeCtx(
        envelope=env, actions=actions, receipts=receipts,
        kinds=receipt_kinds(receipts), state=store.tasks, now=now,
    )
    scores = case.expect(ctx)
    rs.action = float(scores.get("action", 1.0))
    rs.fields = float(scores.get("fields", 1.0))
    rs.outcome = float(scores.get("outcome", 1.0))

    rs.composite = (W_ENVELOPE * rs.envelope + W_ACTION * rs.action
                    + W_FIELDS * rs.fields + W_OUTCOME * rs.outcome)
    return rs
