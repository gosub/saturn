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

public class NudgeService extends Service {

    private static final String TAG = "Saturn";
    private static final int FOREGROUND_NOTIF_ID = 1;
    private static final int NUDGE_NOTIF_ID = 2;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(FOREGROUND_NOTIF_ID, buildCheckingNotification());
        int capturedStartId = startId;
        new Thread(() -> {
            try {
                runNudgeCycle();
            } catch (Exception e) {
                Log.e(TAG, "nudge cycle error", e);
            } finally {
                stopForeground(true);
                stopSelf(capturedStartId);
            }
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
        String timezone = prefs.getString("timezone", "");
        String language = prefs.getString("language", "en");
        String schedule = prefs.getString("schedule", "");

        long nowMillis = System.currentTimeMillis();
        String nowISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .format(new Date(nowMillis));

        Database db = new Database(this);
        try {
            List<Task> due = db.getDueTasks(nowISO);
            if (!due.isEmpty()) {
                Log.d(TAG, "nudge phase: " + due.size() + " due tasks");
                runNudgePhase(db, prefs, apiKey, model, language, schedule, timezone,
                              due, nowMillis, nowISO);
                // Always clear still-due tasks after the phase, even if it failed,
                // so a past next_nudge_at never causes an immediate-refire loop.
                List<Task> stillDue = db.getDueTasks(nowISO);
                for (Task t : stillDue) {
                    Log.d(TAG, "clearing unresolved due task " + t.id);
                    db.setNextNudgeAt(t.id, null);
                }
                if (!stillDue.isEmpty()) {
                    StringBuilder warn = new StringBuilder("⚠ No reminder set for:");
                    for (Task t : stillDue) warn.append("\n  \u2022 ").append(t.description);
                    warn.append("\nTell me when to remind you again.");
                    appendPendingNudge(prefs, warn.toString());
                }
            }
            NudgeScheduler.scheduleNext(this, db);
        } finally {
            db.close();
        }
    }

    private void runNudgePhase(Database db, SharedPreferences prefs,
                                String apiKey, String model,
                                String language, String schedule, String timezone,
                                List<Task> due, long nowMillis, String nowISO) {
        try {
            String prompt = AgentClient.buildNudgePrompt(language, schedule, due, nowMillis, timezone);
            String trigger = "Nudge check at " + nowISO + ". " + due.size() + " task(s) due.";

            AgentClient.AgentResponse resp = new AgentClient()
                .chat(apiKey, model, prompt, null, trigger);
            ActionExecutor.execute(resp.actions, db, prefs);

            if (resp.reply != null && !resp.reply.isEmpty()) {
                postNudgeNotification(resp.reply);
                appendPendingNudge(prefs, resp.reply);
            }
        } catch (Exception e) {
            Log.e(TAG, "nudge phase error", e);
        }
    }

    private static void appendPendingNudge(SharedPreferences prefs, String message) {
        String existing = prefs.getString("pending_nudges", "[]");
        com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(existing).getAsJsonArray();
        arr.add(message);
        prefs.edit().putString("pending_nudges", arr.toString()).apply();
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

    private void postNudgeNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new Notification.Builder(this, SaturnApp.CHANNEL_NUDGE)
            .setContentTitle("Saturn")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NUDGE_NOTIF_ID, notif);
        Log.d(TAG, "nudge notification posted");
    }
}
