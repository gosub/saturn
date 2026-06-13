package it.lo.exp.saturn;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class NudgeService extends Service {

    private static final String TAG = "Saturn";
    private static final int FOREGROUND_NOTIF_ID = 1;
    // Summary notification (e.g. "gave up retrying") with no per-task buttons.
    static final int GIVEUP_NOTIF_ID = 2;
    // Per-task nudge notifications use a stable id derived from the task id, so
    // each is posted, updated and cancelled independently of the others.
    private static final int NUDGE_NOTIF_BASE = 1000;

    static int nudgeNotifId(long taskId) {
        return NUDGE_NOTIF_BASE + (int) taskId;
    }

    private static final long CYCLE_TIMEOUT_MS = 90_000L;
    private static final int RETRY_MINUTES = 30;
    private static final int MAX_FAILURES = 5;

    // Serializes nudge cycles: concurrent starts (e.g. two scheduleNext calls
    // both hitting the past-trigger path) queue up instead of running in
    // parallel, and the second cycle finds no due tasks left.
    private static final ExecutorService CYCLES = Executors.newSingleThreadExecutor();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(FOREGROUND_NOTIF_ID, buildCheckingNotification());
        int capturedStartId = startId;
        Future<?> cycle = CYCLES.submit(() -> {
            try {
                runNudgeCycle();
            } catch (Exception e) {
                Log.e(TAG, "nudge cycle error", e);
            } finally {
                stopForeground(true);
                // Only stops the service when this is the most recent start.
                stopSelf(capturedStartId);
            }
        });
        new Thread(() -> {
            try {
                cycle.get(CYCLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                Log.e(TAG, "nudge cycle timed out after " + CYCLE_TIMEOUT_MS + "ms, interrupting");
                cycle.cancel(true);
            } catch (Exception ignored) {}
        }).start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void runNudgeCycle() {
        SharedPreferences prefs = getSharedPreferences("saturn", MODE_PRIVATE);
        String apiKey = KeystoreHelper.readApiKey(prefs);
        if (apiKey.isEmpty()) {
            Log.d(TAG, "no api key set, skipping nudge cycle");
            return;
        }

        String model    = prefs.getString("model", "openai/gpt-oss-120b:free");
        String language = prefs.getString("language", "en");
        String schedule = prefs.getString("schedule", "");

        long nowMillis = System.currentTimeMillis();
        String nowISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .format(new Date(nowMillis));

        Database db = Database.get(this);
        List<Task> due = db.getDueTasks(nowISO);
        if (!due.isEmpty()) {
            Log.d(TAG, "nudge phase: " + due.size() + " due tasks");
            boolean ok = runNudgePhase(db, prefs, apiKey, model, language, schedule,
                                       due, nowMillis, nowISO);
            if (ok) {
                prefs.edit().putInt("nudge_fail_count", 0).apply();
                rescheduleLeftDue(db, nowISO, nowMillis);
            } else {
                handleNudgeFailure(db, prefs, due, nowMillis);
            }
        }
        NudgeScheduler.scheduleNext(this, db);
    }

    /** Deterministic fallback for tasks the model nudged but left due: a past
     *  next_nudge_at must never survive the cycle or the alarm refires at once. */
    private void rescheduleLeftDue(Database db, String nowISO, long nowMillis) {
        for (Task t : db.getDueTasks(nowISO)) {
            String next;
            if (t.recurring && t.recurMinutes != null && t.recurMinutes > 0) {
                next = nextOccurrence(t.nextNudgeAt, t.recurMinutes, nowMillis);
            } else if (t.recurring) {
                next = isoPlusMinutes(nowMillis, 24 * 60);
            } else {
                next = isoPlusMinutes(nowMillis, 60);
            }
            Log.d(TAG, "task " + t.id + " left due by model, rescheduling to " + next);
            db.setNextNudgeAt(t.id, next);
        }
    }

    /** First anchor + k*interval strictly after now. Anchoring on the scheduled
     *  time instead of now keeps fixed-interval tasks from drifting when a cycle
     *  runs late or fails. Pure epoch arithmetic: a daily task shifts wall-clock
     *  time across DST changes. */
    static String nextOccurrence(String anchorISO, int intervalMinutes, long nowMillis) {
        long step = intervalMinutes * 60_000L;
        long base;
        try {
            base = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .parse(anchorISO).getTime();
        } catch (Exception e) {
            base = nowMillis;
        }
        long next = base + step;
        if (next <= nowMillis) {
            next = base + ((nowMillis - base) / step + 1) * step;
        }
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date(next));
    }

    /** The reminder itself must not depend on the API being reachable: deliver
     *  the raw task text on the first failure, then retry the cycle with backoff.
     *  After MAX_FAILURES consecutive failures give up loudly. */
    private void handleNudgeFailure(Database db, SharedPreferences prefs,
                                     List<Task> due, long nowMillis) {
        int fails = prefs.getInt("nudge_fail_count", 0) + 1;
        prefs.edit().putInt("nudge_fail_count", fails).apply();
        Log.w(TAG, "nudge phase failed (attempt " + fails + ")");

        if (fails == 1) {
            for (Task t : due) {
                postTaskNotification(t, t.description);
            }
            saveNudgeMessage(db, rawReminderText(due)
                + "\n(I couldn\u2019t reach the model, this is a raw reminder.)");
        }

        if (fails >= MAX_FAILURES) {
            prefs.edit().putInt("nudge_fail_count", 0).apply();
            StringBuilder warn = new StringBuilder("⚠ Gave up retrying. No reminder set for:");
            for (Task t : due) {
                db.setNextNudgeAt(t.id, null);
                warn.append("\n  \u2022 ").append(t.description);
            }
            warn.append("\nTell me when to remind you again.");
            postSummaryNotification(warn.toString());
            db.saveMessage(ChatMessage.ROLE_BOT, warn.toString(), System.currentTimeMillis());
            notifyMessagesChanged();
        } else {
            String retryAt = isoPlusMinutes(nowMillis, RETRY_MINUTES);
            for (Task t : due) {
                db.setNextNudgeAt(t.id, retryAt);
            }
            Log.d(TAG, "retrying nudge cycle at " + retryAt);
        }
    }

    private static String rawReminderText(List<Task> due) {
        if (due.size() == 1) return due.get(0).description;
        StringBuilder sb = new StringBuilder(due.size() + " tasks due:");
        for (Task t : due) sb.append("\n  \u2022 ").append(t.description);
        return sb.toString();
    }

    static String isoPlusMinutes(long baseMillis, int minutes) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .format(new Date(baseMillis + minutes * 60_000L));
    }

    private boolean runNudgePhase(Database db, SharedPreferences prefs,
                                   String apiKey, String model,
                                   String language, String schedule,
                                   List<Task> due, long nowMillis, String nowISO) {
        try {
            String prompt = AgentClient.buildNudgePrompt(language, schedule, due, nowMillis);
            String trigger = "Nudge check at " + nowISO + ". " + due.size() + " task(s) due.";

            AgentClient.AgentResponse resp = new AgentClient()
                .chat(apiKey, model, prompt, null, trigger);
            ActionExecutor.execute(resp.actions, db, prefs);

            if (resp.nudges != null) {
                for (AgentClient.Nudge n : resp.nudges) {
                    if (n.text == null || n.text.isEmpty()) continue;
                    Task t = db.getTask(n.id);
                    if (t == null) {
                        Log.w(TAG, "nudge for unknown task id " + n.id);
                        continue;
                    }
                    postTaskNotification(t, n.text);
                    saveNudgeMessage(db, n.text);
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "nudge phase error", e);
            return false;
        }
    }

    /** Nudges land in the shared messages table; the chat shows them live (or
     *  on resume) and the model sees them as assistant turns. */
    private void saveNudgeMessage(Database db, String text) {
        db.saveMessage(ChatMessage.ROLE_BOT, "⏰ " + text, System.currentTimeMillis());
        notifyMessagesChanged();
    }

    private void notifyMessagesChanged() {
        sendBroadcast(new Intent(MainActivity.ACTION_MESSAGES_CHANGED)
            .setPackage(getPackageName()));
    }

    private Notification buildCheckingNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, SaturnApp.CHANNEL_SERVICE)
            .setContentTitle("Saturn")
            .setContentText("Checking nudges\u2026")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(pi)
            .build();
    }

    /** One actionable notification per task, keyed by a stable per-task id with
     *  its own Done / Snooze buttons. */
    private void postTaskNotification(Task task, String text) {
        Notification.Builder builder = new Notification.Builder(this, SaturnApp.CHANNEL_NUDGE)
            .setContentTitle("Saturn")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .addAction(buildAction(NudgeActionReceiver.ACTION_DONE, "Done",
                task.id, (int) task.id * 2))
            .addAction(buildAction(NudgeActionReceiver.ACTION_SNOOZE, "Snooze 30m",
                task.id, (int) task.id * 2 + 1));

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(nudgeNotifId(task.id), builder.build());
        Log.d(TAG, "nudge notification posted for task " + task.id);
    }

    /** A single non-actionable notification (e.g. the give-up summary), since
     *  there is no longer a per-task reminder to act on. */
    private void postSummaryNotification(String text) {
        Notification n = new Notification.Builder(this, SaturnApp.CHANNEL_NUDGE)
            .setContentTitle("Saturn")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(GIVEUP_NOTIF_ID, n);
    }

    private PendingIntent openAppIntent() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification.Action buildAction(String action, String label, long taskId, int requestCode) {
        Intent intent = new Intent(this, NudgeActionReceiver.class);
        intent.setAction(action);
        intent.putExtra(NudgeActionReceiver.EXTRA_TASK_ID, taskId);
        PendingIntent pi = PendingIntent.getBroadcast(this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(null, label, pi).build();
    }
}
