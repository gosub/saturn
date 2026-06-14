"""Port of the app's three system-prompt builders.

PORTED FROM (keep in sync):
  app/src/main/java/it/lo/exp/saturn/AgentClient.java:182-329
    buildChatPrompt / buildNudgePrompt / buildEditTaskPrompt / formatNow / langName

These must reproduce the prompts the real app sends so the benchmark measures
the model on the actual task. If AgentClient's prompts change, change these.
"""

from __future__ import annotations

from datetime import datetime
from typing import List, Optional

from .executor import Task


def _lang_name(code: str) -> str:
    return "Italian" if code == "it" else "English"


def format_now(now: datetime) -> str:
    """Mirror AgentClient.formatNow: 2026-04-16T09:00:00+02:00 (Wednesday)."""
    aware = now.astimezone() if now.tzinfo is None else now
    base = aware.strftime("%Y-%m-%dT%H:%M:%S")
    off = aware.strftime("%z")  # e.g. +0200
    if len(off) == 5:
        off = off[:3] + ":" + off[3:]
    return f"{base}{off} ({aware.strftime('%A')})"


def build_chat_prompt(language: str, schedule: Optional[str],
                      tasks: List[Task], now: datetime) -> str:
    sb: List[str] = []
    sb.append("You are Saturn, an intelligent task and nudge assistant.\n")
    sb.append("You help the user track tasks, remember commitments, and get things done.\n")
    sb.append("Be concise and direct.\n")
    sb.append(f"Always respond in {_lang_name(language)}.\n\n")
    sb.append(f"Current time: {format_now(now)}\n\n")
    if schedule:
        sb.append(f"User's schedule: {schedule}\n\n")
    else:
        sb.append("User's schedule: not set\n\n")
    if not tasks:
        sb.append("Active tasks: none\n\n")
    else:
        sb.append(f"Active tasks ({len(tasks)}):\n")
        for t in tasks:
            nudge = t.next_nudge_at if t.next_nudge_at else "not set"
            prefix = "↻ " if t.recurring else ""
            line = f"  {t.id}. {prefix}{t.description} — next nudge: {nudge}"
            if t.recur_minutes and t.recur_minutes > 0:
                line += f" (every {t.recur_minutes} min)"
            sb.append(line + "\n")
        sb.append("\n")
    sb.append('Respond ONLY with a JSON object: {"reply": "...", "actions": [...]}\n')
    sb.append('No text outside the JSON. If no actions are needed, use "actions": [].\n\n')
    sb.append("Available actions:\n")
    sb.append('  {"type": "add_task",        "description": "...", "next_nudge_at": "ISO8601 (required)", "recurring": true, "recur_minutes": 1440}\n')
    sb.append('  {"type": "update_task",     "id": N, "description": "...", "next_nudge_at": "ISO8601", "recurring": true, "recur_minutes": 1440}\n')
    sb.append('  {"type": "complete_task",   "id": N}\n')
    sb.append('  {"type": "delete_task",     "id": N}\n')
    sb.append('  {"type": "update_schedule", "schedule": "..."}\n')
    sb.append('  {"type": "snooze_task",      "id": N, "minutes": 30}\n')
    sb.append("Always use numeric id from the task list. next_nudge_at is required for add_task.\n")
    sb.append("next_nudge_at must be ISO 8601 (e.g. 2026-03-21T09:00:00). Respect the user's schedule.\n")
    sb.append("Set recurring: true for habitual/repeating tasks; recurring: false in update_task stops the repetition.\n")
    sb.append("For tasks repeating at a fixed interval also set recur_minutes (e.g. 1440 = daily);\n")
    sb.append("they are then rescheduled automatically after each nudge.\n")
    sb.append("Recurring tasks (↻) must never be completed — use update_task with the next next_nudge_at.\n")
    return "".join(sb)


def build_nudge_prompt(language: str, schedule: Optional[str],
                       tasks: List[Task], now: datetime) -> str:
    now_ms = now.timestamp() * 1000.0
    sb: List[str] = []
    sb.append("You are Saturn, a nudge agent.\n")
    sb.append(f"Current time: {format_now(now)}\n")
    if schedule:
        sb.append(f"User's schedule: {schedule}\n\n")
    sb.append(f"Always respond in {_lang_name(language)}.\n\n")
    sb.append("The following tasks are due for a nudge:\n")
    for t in tasks:
        prefix = "↻ " if t.recurring else ""
        interval = f" (every {t.recur_minutes} min)" if (t.recur_minutes and t.recur_minutes > 0) else ""
        late = ""
        if t.next_nudge_at:
            try:
                scheduled = datetime.strptime(t.next_nudge_at, "%Y-%m-%dT%H:%M:%S")
                late_min = int((now_ms - scheduled.timestamp() * 1000.0) / 60000)
                if late_min > 15:
                    late = f" [LATE by {late_min} min]"
            except ValueError:
                pass
        sb.append(f"  {t.id}. {prefix}{t.description}{interval}{late}\n")
    sb.append("\nWrite one short nudge per task above: one sentence each, no fluff.\n")
    sb.append("After nudging:\n")
    sb.append("  - Recurring (↻) with a fixed interval: rescheduled automatically, no action needed.\n")
    sb.append("  - Recurring (↻) without an interval: use update_task to set the next occurrence's next_nudge_at.\n")
    sb.append("  - One-time: use update_task or snooze_task to pick the next reminder time; if you do nothing it is re-nudged in about an hour.\n")
    sb.append("Never complete or delete a task here: only the user can declare a task done.\n")
    sb.append('If a task needs no nudge right now, omit it from "nudges".\n\n')
    sb.append('Respond: {"nudges": [{"id": N, "text": "..."}], "actions": [...]}\n')
    sb.append("Use the numeric id from the list above for each nudge.\n")
    sb.append("Actions: update_task (id, description optional, next_nudge_at optional), snooze_task (id, minutes).\n")
    return "".join(sb)


def build_edit_task_prompt(language: str, schedule: Optional[str],
                           task: Task, now: datetime) -> str:
    sb: List[str] = []
    sb.append("You are Saturn, editing a single task per the user's instruction.\n")
    sb.append(f"Always respond in {_lang_name(language)}.\n\n")
    sb.append(f"Current time: {format_now(now)}\n")
    if schedule:
        sb.append(f"User's schedule: {schedule}\n")
    sb.append("\nThe task being edited:\n")
    prefix = "↻ " if task.recurring else ""
    nudge = task.next_nudge_at if task.next_nudge_at else "not set"
    line = f"  {task.id}. {prefix}{task.description} — next nudge: {nudge}"
    if task.recur_minutes and task.recur_minutes > 0:
        line += f" (every {task.recur_minutes} min)"
    sb.append(line + "\n\n")
    sb.append(f"Apply the instruction to THIS task only (id {task.id}).\n")
    sb.append('Respond ONLY with JSON: {"reply": "...", "actions": [...]}\n')
    sb.append(f"Available actions (use only id {task.id}):\n")
    sb.append(f'  {{"type": "update_task",   "id": {task.id}, "description": "...", "next_nudge_at": "ISO8601", "recurring": true, "recur_minutes": 1440}}\n')
    sb.append(f'  {{"type": "complete_task", "id": {task.id}}}\n')
    sb.append(f'  {{"type": "delete_task",   "id": {task.id}}}\n')
    sb.append(f'  {{"type": "snooze_task",   "id": {task.id}, "minutes": 30}}\n')
    sb.append("next_nudge_at must be ISO 8601 (e.g. 2026-03-21T09:00:00), in the future, respecting the schedule.\n")
    sb.append("Do not add or touch any other task.\n")
    return "".join(sb)
