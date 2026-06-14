"""In-memory port of the app's task store + action executor.

PORTED FROM (keep in sync):
  - app/src/main/java/it/lo/exp/saturn/ActionExecutor.java   (execute, validatedFutureTime)
  - app/src/main/java/it/lo/exp/saturn/FakeTaskStore.java    (in-memory store)
  - app/src/main/java/it/lo/exp/saturn/Receipt.java          (Kind enum)

The benchmark grades a model by feeding its emitted actions through THIS
executor against a seeded store and inspecting the resulting state + receipt
kinds, exactly as the real app would. If the Java logic changes, change this.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta
from typing import Any, Callable, Dict, List, Optional


# ---- data model (mirrors Task.java) ----

@dataclass
class Task:
    id: int
    description: str
    next_nudge_at: Optional[str] = None
    recurring: bool = False
    recur_minutes: Optional[int] = None


# ---- receipt kinds (mirrors Receipt.Kind) ----

class Kind:
    ADDED = "ADDED"
    ADDED_NO_TIME = "ADDED_NO_TIME"
    ADDED_TIME_REJECTED = "ADDED_TIME_REJECTED"
    UPDATED = "UPDATED"
    UPDATED_TIME_REJECTED = "UPDATED_TIME_REJECTED"
    UPDATE_UNKNOWN = "UPDATE_UNKNOWN"
    COMPLETED = "COMPLETED"
    COMPLETE_RECURRING = "COMPLETE_RECURRING"
    COMPLETE_UNKNOWN = "COMPLETE_UNKNOWN"
    DELETED = "DELETED"
    DELETE_UNKNOWN = "DELETE_UNKNOWN"
    SCHEDULE_UPDATED = "SCHEDULE_UPDATED"
    SNOOZED = "SNOOZED"
    SNOOZE_UNKNOWN = "SNOOZE_UNKNOWN"


@dataclass
class Receipt:
    kind: str
    text: Optional[str] = None
    time: Optional[str] = None
    rejected_time: Optional[str] = None
    recur_minutes: Optional[int] = None
    id: int = 0
    minutes: int = 0
    schedule: Optional[str] = None


# ---- normalized action (mirrors AgentClient.Action + Gson's lenient binding) ----

@dataclass
class Action:
    type: Optional[str] = None
    description: Optional[str] = None
    id: int = 0
    next_nudge_at: Optional[str] = None
    schedule: Optional[str] = None
    recurring: Optional[bool] = None  # None = not specified
    recur_minutes: Optional[int] = None
    minutes: int = 0


def _as_int(v: Any, default: int = 0) -> int:
    if v is None:
        return default
    try:
        return int(v)
    except (TypeError, ValueError):
        try:
            return int(float(v))
        except (TypeError, ValueError):
            return default


def _as_opt_int(v: Any) -> Optional[int]:
    if v is None:
        return None
    try:
        return int(v)
    except (TypeError, ValueError):
        try:
            return int(float(v))
        except (TypeError, ValueError):
            return None


def _as_opt_bool(v: Any) -> Optional[bool]:
    if v is None:
        return None
    if isinstance(v, bool):
        return v
    if isinstance(v, str):
        if v.strip().lower() in ("true", "yes", "1"):
            return True
        if v.strip().lower() in ("false", "no", "0"):
            return False
    return None


def normalize_action(d: Dict[str, Any]) -> Action:
    """Map a raw action dict from model JSON to an Action.

    Mirrors @SerializedName(value="type", alternate={"action"}) and Gson's
    tolerant scalar coercion.
    """
    if not isinstance(d, dict):
        return Action()
    return Action(
        type=d.get("type", d.get("action")),
        description=d.get("description"),
        id=_as_int(d.get("id")),
        next_nudge_at=d.get("next_nudge_at"),
        schedule=d.get("schedule"),
        recurring=_as_opt_bool(d.get("recurring")),
        recur_minutes=_as_opt_int(d.get("recur_minutes")),
        minutes=_as_int(d.get("minutes")),
    )


# ---- time validation (mirrors ActionExecutor.validatedFutureTime) ----

_PLAIN = "%Y-%m-%dT%H:%M:%S"


def validated_future_time(s: Optional[str], now: Optional[datetime] = None) -> Optional[str]:
    """Return time normalized to plain local yyyy-MM-dd'T'HH:mm:ss if it parses
    as ISO 8601 and is strictly in the future, else None.

    An incoming offset (+02:00 / Z) is honored then dropped, matching the Java
    behavior that keeps stored times offset-free for lexicographic comparison.
    """
    if not s:
        return None
    s = s.strip()
    if now is None:
        now = datetime.now()
    try:
        dt = datetime.fromisoformat(s)
    except ValueError:
        return None
    if dt.tzinfo is not None:
        # convert to local wall-clock, then make naive
        dt = dt.astimezone().replace(tzinfo=None)
    if dt <= now:
        return None
    return dt.strftime(_PLAIN)


# ---- in-memory store (mirrors FakeTaskStore) ----

class Store:
    def __init__(self, seed: Optional[List[Task]] = None):
        self.tasks: Dict[int, Task] = {}
        self.transactions = 0
        max_id = 0
        for t in (seed or []):
            self.tasks[t.id] = replace(t)
            max_id = max(max_id, t.id)
        self._next_id = max_id + 1

    def add_task(self, description: Optional[str], recurring: bool) -> Task:
        tid = self._next_id
        self._next_id += 1
        t = Task(id=tid, description=description or "", recurring=recurring)
        self.tasks[tid] = t
        return t

    def get_task(self, tid: int) -> Optional[Task]:
        t = self.tasks.get(tid)
        return replace(t) if t else None

    def update_task(self, tid: int, description: str) -> None:
        if tid in self.tasks:
            self.tasks[tid].description = description

    def set_next_nudge_at(self, tid: int, when: Optional[str]) -> None:
        if tid in self.tasks:
            self.tasks[tid].next_nudge_at = when

    def set_recurring(self, tid: int, recurring: bool) -> None:
        if tid in self.tasks:
            self.tasks[tid].recurring = recurring

    def set_recur_minutes(self, tid: int, minutes: Optional[int]) -> None:
        if tid in self.tasks:
            self.tasks[tid].recur_minutes = minutes

    def complete_task(self, tid: int) -> None:
        self.tasks.pop(tid, None)

    def delete_task(self, tid: int) -> None:
        self.tasks.pop(tid, None)

    def run_in_transaction(self, work: Callable[[], None]) -> None:
        self.transactions += 1
        work()


# ---- executor (mirrors ActionExecutor.execute) ----

def execute(actions: List[Action], store: Store,
            schedule_writer: Optional[Callable[[str], None]] = None,
            now: Optional[datetime] = None) -> List[Receipt]:
    receipts: List[Receipt] = []
    if actions is None:
        return receipts
    if now is None:
        now = datetime.now()
    captured_schedule: List[str] = []

    def _writer(s: str) -> None:
        captured_schedule.append(s)
        if schedule_writer:
            schedule_writer(s)

    for a in actions:
        t = (a.type or "")
        if t == "add_task":
            def _add(a=a):
                rec = (a.recurring is True) or (a.recur_minutes is not None and a.recur_minutes > 0)
                task = store.add_task(a.description, rec)
                if a.recur_minutes is not None and a.recur_minutes > 0:
                    store.set_recur_minutes(task.id, a.recur_minutes)
                add_time = validated_future_time(a.next_nudge_at, now)
                if add_time is not None:
                    store.set_next_nudge_at(task.id, add_time)
                    receipts.append(Receipt(Kind.ADDED, text=a.description, time=add_time,
                                            recur_minutes=a.recur_minutes))
                elif a.next_nudge_at:
                    receipts.append(Receipt(Kind.ADDED_TIME_REJECTED, text=a.description,
                                            rejected_time=a.next_nudge_at))
                else:
                    receipts.append(Receipt(Kind.ADDED_NO_TIME, text=a.description))
            store.run_in_transaction(_add)

        elif t == "update_task":
            def _upd(a=a):
                if store.get_task(a.id) is None:
                    receipts.append(Receipt(Kind.UPDATE_UNKNOWN, id=a.id))
                    return
                if a.description:
                    store.update_task(a.id, a.description)
                upd_time = validated_future_time(a.next_nudge_at, now)
                if upd_time is not None:
                    store.set_next_nudge_at(a.id, upd_time)
                if a.recur_minutes is not None and a.recur_minutes > 0:
                    store.set_recur_minutes(a.id, a.recur_minutes)
                    store.set_recurring(a.id, True)
                if a.recurring is not None:
                    store.set_recurring(a.id, a.recurring)
                    if not a.recurring:
                        store.set_recur_minutes(a.id, None)
                task = store.get_task(a.id)
                if upd_time is not None:
                    receipts.append(Receipt(Kind.UPDATED, text=task.description, time=upd_time,
                                            recur_minutes=a.recur_minutes))
                elif a.next_nudge_at:
                    receipts.append(Receipt(Kind.UPDATED_TIME_REJECTED, text=task.description,
                                            rejected_time=a.next_nudge_at))
                else:
                    receipts.append(Receipt(Kind.UPDATED, text=task.description))
            store.run_in_transaction(_upd)

        elif t == "complete_task":
            def _comp(a=a):
                ct = store.get_task(a.id)
                if ct is None:
                    receipts.append(Receipt(Kind.COMPLETE_UNKNOWN, id=a.id))
                    return
                if ct.recurring:
                    receipts.append(Receipt(Kind.COMPLETE_RECURRING, text=ct.description))
                else:
                    store.complete_task(a.id)
                    receipts.append(Receipt(Kind.COMPLETED, text=ct.description))
            store.run_in_transaction(_comp)

        elif t == "delete_task":
            def _del(a=a):
                dt = store.get_task(a.id)
                if dt is None:
                    receipts.append(Receipt(Kind.DELETE_UNKNOWN, id=a.id))
                    return
                store.delete_task(a.id)
                receipts.append(Receipt(Kind.DELETED, text=dt.description))
            store.run_in_transaction(_del)

        elif t == "update_schedule":
            if a.schedule is not None:
                _writer(a.schedule)
                receipts.append(Receipt(Kind.SCHEDULE_UPDATED, schedule=a.schedule))

        elif t == "snooze_task":
            def _snz(a=a):
                st = store.get_task(a.id)
                if st is None:
                    receipts.append(Receipt(Kind.SNOOZE_UNKNOWN, id=a.id))
                    return
                mins = a.minutes if a.minutes > 0 else 30
                snooze_time = (now + timedelta(minutes=mins)).strftime(_PLAIN)
                store.set_next_nudge_at(a.id, snooze_time)
                receipts.append(Receipt(Kind.SNOOZED, minutes=mins, text=st.description))
            store.run_in_transaction(_snz)
        # unknown types are ignored, matching the Java default branch

    return receipts


def kinds(receipts: List[Receipt]) -> List[str]:
    return [r.kind for r in receipts]
