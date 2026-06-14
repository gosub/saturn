"""Render the benchmark results to a markdown report and a per-run CSV."""

from __future__ import annotations

import csv
import os
from datetime import datetime
from typing import Dict, List, Optional

from .cases import Case
from .grader import RunScore
from .metrics import ModelMetrics

try:
    from tabulate import tabulate
    _HAVE_TABULATE = True
except ImportError:  # pragma: no cover - tabulate is in requirements.txt
    _HAVE_TABULATE = False


def _table(headers: List[str], rows: List[List[str]]) -> str:
    if _HAVE_TABULATE:
        return tabulate(rows, headers=headers, tablefmt="github")
    # minimal github-markdown fallback
    out = ["| " + " | ".join(headers) + " |",
           "| " + " | ".join("---" for _ in headers) + " |"]
    for r in rows:
        out.append("| " + " | ".join(str(c) for c in r) + " |")
    return "\n".join(out)


def _pct(v: Optional[float]) -> str:
    return f"{v * 100:.0f}%" if v is not None else "—"


def _ms(v: Optional[float]) -> str:
    return f"{v:.0f}" if v is not None else "—"


def _glyph(v: Optional[float]) -> str:
    if v is None:
        return "·"
    if v >= 0.8:
        return "●"
    if v >= 0.4:
        return "◐"
    return "○"


def render_markdown(metrics: Dict[str, ModelMetrics], cases: List[Case],
                    reps: int, started: datetime) -> str:
    ranked = sorted(
        metrics.values(),
        key=lambda m: (m.correctness if m.correctness is not None else -1.0),
        reverse=True,
    )

    lines: List[str] = []
    lines.append("# Saturn model benchmark")
    lines.append("")
    lines.append(f"- Run: {started.strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- Models: {len(metrics)}  ·  Cases: {len(cases)}  ·  Reps: {reps}")
    lines.append(f"- Total calls: {sum(m.calls for m in metrics.values())}")
    lines.append("")
    lines.append("Correctness is the mean composite score over transport-successful "
                 "runs (envelope 10% · action 25% · fields 15% · outcome 50%). "
                 "Availability is measured independently.")
    lines.append("")

    # ---- leaderboard ----
    lines.append("## Leaderboard (by task correctness)")
    lines.append("")
    headers = ["#", "model", "correct", "outcome", "action", "fields",
               "json", "success", "p50ms", "p95ms", "429", "n"]
    rows = []
    for i, m in enumerate(ranked, 1):
        rows.append([
            i, m.model, _pct(m.correctness), _pct(m.mean_outcome),
            _pct(m.mean_action), _pct(m.mean_fields), _pct(m.json_compliance),
            _pct(m.success_rate), _ms(m.p50), _ms(m.p95),
            _pct(m.rate_limited_rate), m.n_graded,
        ])
    lines.append(_table(headers, rows))
    lines.append("")

    # ---- availability ----
    lines.append("## Availability")
    lines.append("")
    headers = ["model", "calls", "success", "p50ms", "p95ms", "max ms",
               "429", "json", "retries", "top errors"]
    rows = []
    for m in ranked:
        errs = m.error_hist
        top = ", ".join(f"{k}:{v}" for k, v in errs.most_common(3) if k != "none") or "—"
        rows.append([
            m.model, m.calls, _pct(m.success_rate), _ms(m.p50), _ms(m.p95),
            _ms(m.max_latency), m.rate_limited, _pct(m.json_compliance),
            f"{m.mean_retries:.1f}", top,
        ])
    lines.append(_table(headers, rows))
    lines.append("")

    # ---- per-case matrix ----
    lines.append("## Per-case matrix")
    lines.append("")
    lines.append("Legend: ● ≥0.8  ◐ 0.4–0.8  ○ <0.4  · no successful call")
    lines.append("")
    headers = ["model"] + [c.id for c in cases]
    rows = []
    for m in ranked:
        rows.append([m.model] + [_glyph(m.case_composite(c.id)) for c in cases])
    lines.append(_table(headers, rows))
    lines.append("")

    # ---- case legend ----
    lines.append("## Cases")
    lines.append("")
    for c in cases:
        lines.append(f"- `{c.id}` ({c.path}) — {c.title}")
    lines.append("")

    return "\n".join(lines)


def write_reports(metrics: Dict[str, ModelMetrics], cases: List[Case],
                  all_runs: List[RunScore], reps: int, started: datetime,
                  out_dir: str) -> Dict[str, str]:
    os.makedirs(out_dir, exist_ok=True)
    ts = started.strftime("%Y%m%d-%H%M%S")
    md_path = os.path.join(out_dir, f"report-{ts}.md")
    csv_path = os.path.join(out_dir, f"report-{ts}.csv")

    with open(md_path, "w", encoding="utf-8") as f:
        f.write(render_markdown(metrics, cases, reps, started))

    with open(csv_path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["model", "case_id", "path", "status", "error_kind",
                    "latency_ms", "retries", "transport_ok", "json_ok",
                    "envelope", "action", "fields", "outcome", "composite"])
        for r in all_runs:
            w.writerow([
                r.model, r.case_id, r.path, r.status, r.error_kind,
                f"{r.latency_ms:.0f}", r.retries, int(r.transport_ok), int(r.json_ok),
                r.envelope, r.action, r.fields, r.outcome, r.composite,
            ])

    return {"markdown": md_path, "csv": csv_path}
