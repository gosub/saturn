"""The benchmark corpus.

Each Case carries a system-prompt context (path/language/schedule/seed tasks)
and a user message, plus an `expect` callable that scores the graded outcome on
three dimensions in [0,1]: action (right action types, scope), fields (well-formed
required fields), outcome (resulting DB state + receipt kinds, the gold signal).

Cases are Python (not YAML) because outcome assertions are relative predicates
over wall-clock time and final state, which need real callables.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Callable, Dict, List, Optional

from .executor import Task, Action, Receipt, validated_future_time


# ---- grading context handed to each case's expect() ----

@dataclass
class GradeCtx:
    envelope: Dict
    actions: List[Action]
    receipts: List[Receipt]
    kinds: List[str]
    state: Dict[int, Task]
    now: datetime

    @property
    def types(self) -> List[str]:
        return [a.type for a in self.actions]

    @property
    def reply(self) -> str:
        return self.envelope.get("reply") or ""

    @property
    def nudges(self) -> List[Dict]:
        return self.envelope.get("nudges") or []

    def has(self, kind: str) -> bool:
        return kind in self.kinds

    def type_count(self, t: str) -> int:
        return self.types.count(t)

    def only_types(self, *allowed: str) -> bool:
        return all(t in allowed for t in self.types)

    def find(self, substr: str) -> Optional[Task]:
        s = substr.lower()
        for t in self.state.values():
            if t.description and s in t.description.lower():
                return t
        return None

    def future(self, when: Optional[str]) -> bool:
        return validated_future_time(when, self.now) is not None


Expect = Callable[[GradeCtx], Dict[str, float]]


@dataclass
class Case:
    id: str
    path: str                      # "chat" | "nudge" | "edit"
    title: str
    user_message: Optional[str]
    expect: Expect
    language: str = "en"
    schedule: str = ""
    seeds: List[Task] = field(default_factory=list)
    edit_target_id: Optional[int] = None


def s(cond: bool) -> float:
    return 1.0 if cond else 0.0


# ---- common seed sets ----

def _seed_basic() -> List[Task]:
    return [
        Task(1, "Submit the quarterly report", "2026-06-14T17:00:00", False, None),
        Task(2, "Dentist appointment", "2026-06-20T10:00:00", False, None),
    ]


def _seed_recurring() -> List[Task]:
    return [
        Task(1, "Morning run", "2026-06-14T07:00:00", True, 1440),
        Task(2, "Water the plants", "2026-06-16T09:00:00", True, 4320),
        Task(3, "Journal", "2026-06-14T22:00:00", True, None),
    ]


def _seed_ambiguous() -> List[Task]:
    return [
        Task(1, "Call Alex about the budget at 3pm", "2026-06-13T15:00:00", False, None),
        Task(2, "Call Alex about the offsite at 5pm", "2026-06-13T17:00:00", False, None),
    ]


def _seed_many() -> List[Task]:
    base = []
    for i in range(1, 13):
        base.append(Task(i, f"Task number {i}", "2026-06-15T09:00:00", False, None))
    return base


# ============================ CHAT CASES ============================

CHAT_CASES: List[Case] = [
    Case(
        "chat_add_explicit", "chat", "Add one-time task with explicit time",
        "Remind me to call mom tomorrow at 6pm",
        lambda c: {
            "action": s(c.type_count("add_task") == 1 and c.only_types("add_task")),
            "fields": s(any(c.future(a.next_nudge_at) for a in c.actions if a.type == "add_task")),
            "outcome": s(c.has("ADDED") and any(not t.recurring and c.future(t.next_nudge_at)
                                                for t in c.state.values())),
        },
    ),
    Case(
        "chat_add_relative", "chat", "Add task with relative time (in 2 hours)",
        "Remind me to take the cake out of the oven in 2 hours",
        lambda c: {
            "action": s(c.type_count("add_task") == 1),
            "fields": s(any(c.future(a.next_nudge_at) for a in c.actions if a.type == "add_task")),
            "outcome": s(c.has("ADDED")),
        },
    ),
    Case(
        "chat_add_recurring_daily", "chat", "Add recurring daily task",
        "Remind me to take my vitamins every morning at 8",
        lambda c: {
            "action": s(c.type_count("add_task") == 1),
            "fields": s(any((a.recurring is True) or (a.recur_minutes and a.recur_minutes > 0)
                            for a in c.actions if a.type == "add_task")),
            "outcome": s(any(t.recurring for t in c.state.values()
                             if t.description and "vitamin" in t.description.lower())),
        },
    ),
    Case(
        "chat_add_recurring_interval", "chat", "Add recurring N-day interval task",
        "Water the plants every 3 days",
        lambda c: {
            "action": s(c.type_count("add_task") == 1),
            "fields": s(any(a.recur_minutes and a.recur_minutes > 0
                            for a in c.actions if a.type == "add_task")),
            "outcome": s(any(t.recurring and t.recur_minutes and t.recur_minutes > 0
                             for t in c.state.values()
                             if t.description and "plant" in t.description.lower())),
        },
    ),
    Case(
        "chat_add_no_time", "chat", "Add task with no time given",
        "Add buy milk to my list",
        lambda c: {
            "action": s(c.type_count("add_task") == 1),
            "fields": 1.0,
            # ADDED_NO_TIME is ideal; inventing a time is defensible (partial).
            "outcome": (1.0 if c.has("ADDED_NO_TIME") else (0.5 if c.has("ADDED") else 0.0)),
        },
    ),
    Case(
        "chat_complete_onetime", "chat", "Complete a one-time task",
        "I submitted the quarterly report, mark it done",
        lambda c: {
            "action": s(c.type_count("complete_task") == 1 and c.only_types("complete_task")),
            "fields": 1.0,
            "outcome": s(c.has("COMPLETED") and c.find("quarterly report") is None),
        },
        seeds=_seed_basic(),
    ),
    Case(
        "chat_complete_recurring_guard", "chat", "Recurring task must not be completed",
        "I went for my morning run, done",
        lambda c: {
            # Right behaviour: update_task (reschedule), never complete_task.
            "action": s("complete_task" not in c.types),
            "fields": 1.0,
            "outcome": s(not c.has("COMPLETE_RECURRING") and c.find("morning run") is not None),
        },
        seeds=_seed_recurring(),
    ),
    Case(
        "chat_delete", "chat", "Delete a task",
        "Forget about the dentist appointment, cancel it",
        lambda c: {
            "action": s(c.type_count("delete_task") == 1),
            "fields": 1.0,
            "outcome": s(c.has("DELETED") and c.find("dentist") is None),
        },
        seeds=_seed_basic(),
    ),
    Case(
        "chat_snooze_explicit", "chat", "Snooze a task by an explicit interval",
        "Snooze the quarterly report for an hour",
        lambda c: {
            "action": s(c.type_count("snooze_task") == 1 or c.type_count("update_task") == 1),
            "fields": s(any(a.minutes >= 45 for a in c.actions if a.type == "snooze_task") or
                        any(c.future(a.next_nudge_at) for a in c.actions if a.type == "update_task")),
            "outcome": s(c.has("SNOOZED") or c.has("UPDATED")),
        },
        seeds=_seed_basic(),
    ),
    Case(
        "chat_snooze_vague", "chat", "Snooze with a vague 'later'",
        "Remind me about the quarterly report later",
        lambda c: {
            "action": s(c.type_count("snooze_task") == 1 or c.type_count("update_task") == 1),
            "fields": 1.0,
            "outcome": s(c.has("SNOOZED") or c.has("UPDATED")),
        },
        seeds=_seed_basic(),
    ),
    Case(
        "chat_update_time", "chat", "Update an existing task's time",
        "Move the dentist appointment to 3pm that day",
        lambda c: {
            "action": s(c.type_count("update_task") == 1 and
                        all(a.id == 2 for a in c.actions if a.type == "update_task")),
            "fields": s(any(c.future(a.next_nudge_at) for a in c.actions if a.type == "update_task")),
            "outcome": s(c.has("UPDATED") and c.find("dentist") is not None),
        },
        seeds=_seed_basic(),
    ),
    Case(
        "chat_update_desc", "chat", "Update an existing task's description",
        "Change 'Submit the quarterly report' to 'Submit the Q2 report'",
        lambda c: {
            "action": s(c.type_count("update_task") == 1 and
                        all(a.id == 1 for a in c.actions if a.type == "update_task")),
            "fields": 1.0,
            "outcome": s(c.find("q2 report") is not None),
        },
        seeds=_seed_basic(),
    ),
    Case(
        "chat_update_schedule", "chat", "Update the user's schedule",
        "By the way, I work from 9 to 5, Monday to Friday",
        lambda c: {
            "action": s(c.type_count("update_schedule") == 1),
            "fields": s(any(a.schedule for a in c.actions if a.type == "update_schedule")),
            "outcome": s(c.has("SCHEDULE_UPDATED")),
        },
    ),
    Case(
        "chat_multi_action", "chat", "Two actions in one message",
        "Add 'buy milk' for tomorrow at 9am, and delete the dentist appointment",
        lambda c: {
            "action": s(c.type_count("add_task") == 1 and c.type_count("delete_task") == 1),
            "fields": s(any(c.future(a.next_nudge_at) for a in c.actions if a.type == "add_task")),
            "outcome": s(c.has("ADDED") and c.has("DELETED") and c.find("dentist") is None),
        },
        seeds=_seed_basic(),
    ),
    Case(
        "chat_ambiguous_id", "chat", "Disambiguate between similar tasks",
        "I finished the 3pm budget call, mark that one done",
        lambda c: {
            "action": s(c.type_count("complete_task") == 1 and
                        all(a.id == 1 for a in c.actions if a.type == "complete_task")),
            "fields": 1.0,
            "outcome": s(c.find("budget") is None and c.find("offsite") is not None),
        },
        seeds=_seed_ambiguous(),
    ),
    Case(
        "chat_pure_question", "chat", "Pure question, no mutation",
        "What's on my list right now?",
        lambda c: {
            "action": s(len(c.actions) == 0),
            "fields": s(bool(c.reply)),
            "outcome": s(len(c.kinds) == 0 and c.find("quarterly report") is not None),
        },
        seeds=_seed_basic(),
    ),
    Case(
        "chat_greeting", "chat", "Greeting, no actions",
        "Hey Saturn, good morning!",
        lambda c: {
            "action": s(len(c.actions) == 0),
            "fields": s(bool(c.reply)),
            "outcome": s(len(c.kinds) == 0),
        },
        seeds=_seed_basic(),
    ),
    Case(
        "chat_change_interval", "chat", "Change a recurring interval",
        "Actually water the plants every 2 days instead of 3",
        lambda c: {
            "action": s(c.type_count("update_task") == 1 and
                        all(a.id == 2 for a in c.actions if a.type == "update_task")),
            "fields": s(any(a.recur_minutes and a.recur_minutes > 0
                            for a in c.actions if a.type == "update_task")),
            "outcome": s(any(t.recur_minutes and t.recur_minutes > 0
                             for t in c.state.values()
                             if t.description and "plant" in t.description.lower())),
        },
        seeds=_seed_recurring(),
    ),
    Case(
        "chat_stop_recurring", "chat", "Stop a recurring task",
        "Stop reminding me to journal every day",
        lambda c: {
            "action": s(c.type_count("update_task") == 1 or c.type_count("delete_task") == 1),
            "fields": 1.0,
            # success = journal is no longer a recurring reminder
            "outcome": s((c.find("journal") is None) or
                         (c.find("journal") is not None and not c.find("journal").recurring)),
        },
        seeds=_seed_recurring(),
    ),
    Case(
        "chat_past_time_guard", "chat", "Refuse / fix a past time",
        "Remind me to call the bank yesterday at 9am",
        lambda c: {
            "action": 1.0,
            "fields": 1.0,
            # Must not silently produce a rejected past time; either no time or a future one.
            "outcome": s(not c.has("ADDED_TIME_REJECTED")),
        },
    ),
    Case(
        "chat_italian", "chat", "Italian add request",
        "Ricordami di chiamare il dottore domani alle 10",
        lambda c: {
            "action": s(c.type_count("add_task") == 1),
            "fields": s(any(c.future(a.next_nudge_at) for a in c.actions if a.type == "add_task")),
            "outcome": s(c.has("ADDED")),
        },
        language="it",
    ),
    Case(
        "chat_escaping", "chat", "Quotes/ampersand need JSON escaping",
        'Add a task: buy "organic" eggs & milk tomorrow at 9am',
        lambda c: {
            "action": s(c.type_count("add_task") == 1),
            "fields": s(any(c.future(a.next_nudge_at) for a in c.actions if a.type == "add_task")),
            "outcome": s(c.has("ADDED")),
        },
    ),
    Case(
        "chat_large_context", "chat", "Add into a large existing list",
        "Add 'renew passport' for next Monday at noon",
        lambda c: {
            "action": s(c.type_count("add_task") == 1),
            "fields": s(any(c.future(a.next_nudge_at) for a in c.actions if a.type == "add_task")),
            # the new task was added and the 12 originals are untouched
            "outcome": s(c.has("ADDED") and len(c.state) == 13),
        },
        seeds=_seed_many(),
    ),
    Case(
        "chat_readonly_question", "chat", "Question about a task, no mutation",
        "When is my dentist appointment again?",
        lambda c: {
            "action": s(len(c.actions) == 0),
            "fields": s(bool(c.reply)),
            "outcome": s(len(c.kinds) == 0 and c.find("dentist") is not None),
        },
        seeds=_seed_basic(),
    ),
]


# ============================ NUDGE CASES ============================

NUDGE_CASES: List[Case] = [
    Case(
        "nudge_onetime_due", "nudge", "One-time task due: nudge + reschedule",
        None,
        lambda c: {
            "action": s(c.type_count("update_task") + c.type_count("snooze_task") >= 1),
            "fields": s(len(c.nudges) == 1),
            "outcome": s(c.has("UPDATED") or c.has("SNOOZED")),
        },
        seeds=[Task(1, "Pay the electricity bill", "2026-06-13T08:00:00", False, None)],
    ),
    Case(
        "nudge_recurring_interval_due", "nudge", "Recurring+interval due: nudge only, no action",
        None,
        lambda c: {
            "action": s(len(c.actions) == 0),
            "fields": s(len(c.nudges) == 1),
            "outcome": s(len(c.kinds) == 0),
        },
        seeds=[Task(1, "Morning run", "2026-06-13T07:00:00", True, 1440)],
    ),
    Case(
        "nudge_recurring_no_interval_due", "nudge", "Recurring w/o interval: nudge + reschedule",
        None,
        lambda c: {
            "action": s(c.type_count("update_task") >= 1),
            "fields": s(len(c.nudges) == 1),
            "outcome": s(c.has("UPDATED") and "COMPLETE_RECURRING" not in c.kinds),
        },
        seeds=[Task(1, "Journal", "2026-06-13T22:00:00", True, None)],
    ),
    Case(
        "nudge_multiple_mixed", "nudge", "Multiple due tasks: one nudge each",
        None,
        lambda c: {
            "action": s("complete_task" not in c.types and "delete_task" not in c.types),
            "fields": s(len(c.nudges) == 2),
            "outcome": s("COMPLETED" not in c.kinds and "DELETED" not in c.kinds),
        },
        seeds=[
            Task(1, "Pay the electricity bill", "2026-06-13T08:00:00", False, None),
            Task(2, "Journal", "2026-06-13T22:00:00", True, None),
        ],
    ),
    Case(
        "nudge_no_complete_delete", "nudge", "Nudge path must never complete/delete",
        None,
        lambda c: {
            "action": s("complete_task" not in c.types and "delete_task" not in c.types),
            "fields": s(len(c.nudges) >= 1),
            "outcome": s("COMPLETED" not in c.kinds and "DELETED" not in c.kinds
                         and "COMPLETE_RECURRING" not in c.kinds),
        },
        seeds=[Task(1, "Finish the slide deck", "2026-06-13T09:00:00", False, None)],
    ),
]


# ============================ EDIT CASES ============================

EDIT_CASES: List[Case] = [
    Case(
        "edit_mark_done", "edit", "Edit screen: mark the task done",
        "I finished this one",
        lambda c: {
            "action": s(c.type_count("complete_task") == 1 and all(a.id == 1 for a in c.actions)),
            "fields": 1.0,
            "outcome": s(c.has("COMPLETED")),
        },
        seeds=[Task(1, "Send the invoice to the client", "2026-06-14T10:00:00", False, None)],
        edit_target_id=1,
    ),
    Case(
        "edit_change_time", "edit", "Edit screen: push the time",
        "Push this to 5pm today",
        lambda c: {
            "action": s(c.type_count("update_task") == 1 and all(a.id == 1 for a in c.actions)),
            "fields": s(any(c.future(a.next_nudge_at) for a in c.actions if a.type == "update_task")),
            "outcome": s(c.has("UPDATED")),
        },
        seeds=[Task(1, "Send the invoice to the client", "2026-06-14T10:00:00", False, None)],
        edit_target_id=1,
    ),
    Case(
        "edit_scope_guard", "edit", "Edit screen: must touch only the target id",
        "Mark this done and also delete the dentist task",
        lambda c: {
            # only the target id (1) may be acted on
            "action": s(all(a.id == 1 for a in c.actions) and len(c.actions) >= 1),
            "fields": 1.0,
            # the other task (id 2) must survive
            "outcome": s(c.find("dentist") is not None),
        },
        seeds=[
            Task(1, "Send the invoice to the client", "2026-06-14T10:00:00", False, None),
            Task(2, "Dentist appointment", "2026-06-20T10:00:00", False, None),
        ],
        edit_target_id=1,
    ),
    Case(
        "edit_make_recurring", "edit", "Edit screen: make the task weekly",
        "Make this repeat every week",
        lambda c: {
            "action": s(c.type_count("update_task") == 1 and all(a.id == 1 for a in c.actions)),
            "fields": s(any((a.recurring is True) or (a.recur_minutes and a.recur_minutes > 0)
                            for a in c.actions if a.type == "update_task")),
            "outcome": s(c.find("invoice") is not None and c.find("invoice").recurring),
        },
        seeds=[Task(1, "Send the invoice to the client", "2026-06-14T10:00:00", False, None)],
        edit_target_id=1,
    ),
]


ALL_CASES: List[Case] = CHAT_CASES + NUDGE_CASES + EDIT_CASES


def cases_for(only: Optional[str]) -> List[Case]:
    if not only:
        return ALL_CASES
    return [c for c in ALL_CASES if c.path == only]
