# Device verification battery

Manual/adb smoke tests for the reliability and UX changes. Run on a real
device with USB debugging, from `nix-shell`. Debug build only (`run-as`
needs a debuggable APK).

Conventions:
- `LOG` = `adb logcat -s Saturn` running in parallel
- DB inspection = `adb exec-out run-as it.lo.exp.saturn cat databases/saturn.db > /tmp/saturn.db`
  then query with `sqlite3`
- The LLM failure path is exercised with a deliberately invalid API key;
  no real key is needed for T1–T7.

## T1 — Clean install and first-run onboarding
1. `adb uninstall it.lo.exp.saturn` (if installed), `make install`, launch.
2. **Expect:** Settings opens immediately (no API key), toast "Set your
   OpenRouter API key to start". No timezone field in the form.

## T2 — Settings save
1. Enter invalid key `sk-or-v1-INVALID-test`, model left default, save.
2. **Expect:** toast "Settings saved", activity closes back to chat.
3. Reopen Settings. **Expect:** key field shows the saved value (decrypts
   via Keystore round-trip), no `timezone` key in prefs
   (`run-as it.lo.exp.saturn cat shared_prefs/saturn.xml`).

## T3 — Chat error path and stale-pref cleanup
1. Send "remind me to buy milk in 5 minutes".
2. **Expect:** typing indicator, then friendly error bubble ("API key
   rejected…" — 401 from OpenRouter), input re-enabled, message persisted
   (still there after force-stop + relaunch).
3. **Expect:** `shared_prefs/saturn.xml` has no `conversation_history` /
   `pending_nudges` keys.

## T4 — Nudge cycle failure: raw notification fallback
1. Inject a one-time task due in ~1 min directly into the DB, relaunch the
   app so `NudgeScheduler` picks it up (Debug menu should show next alarm).
2. Wait for the alarm. **Expect in LOG:** "nudge alarm fired", "nudge phase:
   1 due tasks", "nudge phase error" (401), "nudge phase failed (attempt 1)".
3. **Expect:** notification with the *raw task text* and Done / Snooze 30m
   buttons; chat (on resume) shows the raw-reminder bot message.
4. **Expect in DB:** `next_nudge_at` moved ~30 min ahead (retry), not NULL.
   `nudge_fail_count = 1` in prefs.

## T5 — Notification actions (no LLM involved)
1. With the T4 notification showing, tap **Snooze 30m**.
   **Expect:** notification dismissed, DB `next_nudge_at` ≈ now+30 min,
   system message "⏰ Snoozed 30 min: …" in chat, LOG "notification action:
   snoozed task N".
2. Re-trigger (or inject another due task), tap **Done**.
   **Expect:** task row deleted from DB, "✓ Done: …" system message,
   alarm rescheduled or cancelled (LOG).

## T6 — Give-up after repeated failures
1. Inject a due task, set `nudge_fail_count` to 4 in prefs, fire the cycle.
2. **Expect:** "⚠ Gave up retrying…" notification + chat message,
   `next_nudge_at` NULL, `nudge_fail_count` reset to 0.

## T7 — Reboot persistence
1. With a future-scheduled task, `adb reboot`, wait for boot.
2. **Expect in LOG:** BootReceiver runs, "exact alarm set for …" without
   opening the app.

## T8 — Real-key end-to-end (needs a valid OpenRouter key, run manually)
1. Enter a real key. Send "remind me to stretch in 2 minutes".
2. **Expect:** reply bubble + receipt system message "✓ added: … → time".
3. Wait for the nudge. **Expect:** LLM-phrased notification, nudge appears
   in chat with ⏰ prefix on resume, `nudge_fail_count` stays 0, and the
   task (one-time, left due by the model) is rescheduled ~+1 h, or as the
   model decided.
4. Reply "done" in chat. **Expect:** "✓ completed: …" receipt, alarm
   cancelled when no tasks remain (Debug menu: next alarm none).
