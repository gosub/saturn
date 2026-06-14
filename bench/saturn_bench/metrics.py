"""Aggregate per-run scores into per-model intelligence + availability metrics."""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from statistics import mean
from typing import Dict, List, Optional

from .grader import RunScore


def percentile(values: List[float], q: float) -> Optional[float]:
    if not values:
        return None
    xs = sorted(values)
    if len(xs) == 1:
        return xs[0]
    pos = q * (len(xs) - 1)
    lo = int(pos)
    hi = min(lo + 1, len(xs) - 1)
    frac = pos - lo
    return xs[lo] + (xs[hi] - xs[lo]) * frac


def _mean_or_none(values: List[float]) -> Optional[float]:
    return mean(values) if values else None


@dataclass
class ModelMetrics:
    model: str
    runs: List[RunScore] = field(default_factory=list)

    # ---- availability ----
    @property
    def calls(self) -> int:
        return len(self.runs)

    @property
    def transport_successes(self) -> int:
        return sum(1 for r in self.runs if r.transport_ok)

    @property
    def success_rate(self) -> float:
        return self.transport_successes / self.calls if self.calls else 0.0

    @property
    def latencies(self) -> List[float]:
        return [r.latency_ms for r in self.runs if r.transport_ok]

    @property
    def p50(self) -> Optional[float]:
        return percentile(self.latencies, 0.50)

    @property
    def p95(self) -> Optional[float]:
        return percentile(self.latencies, 0.95)

    @property
    def max_latency(self) -> Optional[float]:
        return max(self.latencies) if self.latencies else None

    @property
    def error_hist(self) -> Counter:
        return Counter(r.error_kind for r in self.runs)

    @property
    def rate_limited(self) -> int:
        return sum(1 for r in self.runs if r.error_kind == "rate_limited")

    @property
    def rate_limited_rate(self) -> float:
        return self.rate_limited / self.calls if self.calls else 0.0

    @property
    def mean_retries(self) -> float:
        return mean([r.retries for r in self.runs]) if self.runs else 0.0

    @property
    def json_compliance(self) -> float:
        return sum(1 for r in self.runs if r.json_ok) / self.calls if self.calls else 0.0

    # ---- intelligence (over transport-successful runs only) ----
    @property
    def graded(self) -> List[RunScore]:
        return [r for r in self.runs if r.transport_ok]

    @property
    def correctness(self) -> Optional[float]:
        return _mean_or_none([r.composite for r in self.graded if r.composite is not None])

    @property
    def mean_outcome(self) -> Optional[float]:
        return _mean_or_none([r.outcome for r in self.graded if r.outcome is not None])

    @property
    def mean_action(self) -> Optional[float]:
        return _mean_or_none([r.action for r in self.graded if r.action is not None])

    @property
    def mean_fields(self) -> Optional[float]:
        return _mean_or_none([r.fields for r in self.graded if r.fields is not None])

    @property
    def n_graded(self) -> int:
        return len(self.graded)

    def case_composite(self, case_id: str) -> Optional[float]:
        vals = [r.composite for r in self.graded
                if r.case_id == case_id and r.composite is not None]
        return _mean_or_none(vals)


def aggregate(all_runs: List[RunScore]) -> Dict[str, ModelMetrics]:
    by_model: Dict[str, ModelMetrics] = {}
    for r in all_runs:
        by_model.setdefault(r.model, ModelMetrics(r.model)).runs.append(r)
    return by_model
