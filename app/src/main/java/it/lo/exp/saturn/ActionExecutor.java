package it.lo.exp.saturn;

import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ActionExecutor {

    private static final String TAG = "Saturn";

    public static void execute(List<AgentClient.Action> actions, Database db, SharedPreferences prefs) {
        if (actions == null) return;
        for (AgentClient.Action a : actions) {
            Log.d(TAG, "action: type=" + a.type + " id=" + a.id + " desc=" + a.description);
            switch (a.type != null ? a.type : "") {
                case "add_task": {
                    Task t = db.addTask(a.description, a.recurring);
                    String addTime = validatedFutureTime(a.nextNudgeAt);
                    if (addTime != null) {
                        db.setNextNudgeAt(t.id, addTime);
                    } else if (a.nextNudgeAt != null && !a.nextNudgeAt.isEmpty()) {
                        Log.w(TAG, "add_task: rejected invalid/past next_nudge_at: " + a.nextNudgeAt);
                    }
                    break;
                }
                case "update_task": {
                    if (db.getTask(a.id) == null) {
                        Log.w(TAG, "update_task: unknown task id " + a.id);
                        break;
                    }
                    if (a.description != null && !a.description.isEmpty()) {
                        db.updateTask(a.id, a.description);
                    }
                    String updTime = validatedFutureTime(a.nextNudgeAt);
                    if (updTime != null) {
                        db.setNextNudgeAt(a.id, updTime);
                    } else if (a.nextNudgeAt != null && !a.nextNudgeAt.isEmpty()) {
                        Log.w(TAG, "update_task " + a.id + ": rejected invalid/past next_nudge_at: " + a.nextNudgeAt);
                    }
                    if (a.recurring) {
                        db.setRecurring(a.id, true);
                    }
                    break;
                }
                case "complete_task": {
                    Task ct = db.getTask(a.id);
                    if (ct == null) {
                        Log.w(TAG, "complete_task: unknown task id " + a.id);
                        break;
                    }
                    if (ct.recurring) {
                        Log.w(TAG, "skipping complete_task on recurring task " + a.id);
                    } else {
                        db.completeTask(a.id);
                    }
                    break;
                }
                case "delete_task":
                    if (db.getTask(a.id) == null) {
                        Log.w(TAG, "delete_task: unknown task id " + a.id);
                        break;
                    }
                    db.deleteTask(a.id);
                    break;
                case "update_schedule":
                    if (a.schedule != null) {
                        prefs.edit().putString("schedule", a.schedule).apply();
                    }
                    break;
                case "snooze_task": {
                    if (db.getTask(a.id) == null) {
                        Log.w(TAG, "snooze_task: unknown task id " + a.id);
                        break;
                    }
                    int mins = a.minutes > 0 ? a.minutes : 30;
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.MINUTE, mins);
                    String snoozeTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                        .format(cal.getTime());
                    db.setNextNudgeAt(a.id, snoozeTime);
                    Log.d(TAG, "snooze_task " + a.id + " by " + mins + " min → " + snoozeTime);
                    break;
                }
                default:
                    Log.w(TAG, "unknown action type: " + a.type);
            }
        }
    }

    /** Returns the time string if it parses as ISO 8601 and is in the future, else null. */
    private static String validatedFutureTime(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(s);
            if (d != null && d.getTime() > System.currentTimeMillis()) return s;
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
