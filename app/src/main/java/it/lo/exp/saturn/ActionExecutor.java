package it.lo.exp.saturn;

import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ActionExecutor {

    private static final String TAG = "Saturn";

    /** Applies the agent's actions and returns one receipt line per action,
     *  describing what actually happened in the database (not what the model
     *  claims in its reply). */
    public static List<String> execute(List<AgentClient.Action> actions, Database db, SharedPreferences prefs) {
        List<String> receipts = new ArrayList<>();
        if (actions == null) return receipts;
        for (AgentClient.Action a : actions) {
            Log.d(TAG, "action: type=" + a.type + " id=" + a.id + " desc=" + a.description);
            switch (a.type != null ? a.type : "") {
                case "add_task": {
                    boolean rec = Boolean.TRUE.equals(a.recurring)
                        || (a.recurMinutes != null && a.recurMinutes > 0);
                    Task t = db.addTask(a.description, rec);
                    if (a.recurMinutes != null && a.recurMinutes > 0) {
                        db.setRecurMinutes(t.id, a.recurMinutes);
                    }
                    String addTime = validatedFutureTime(a.nextNudgeAt);
                    if (addTime != null) {
                        db.setNextNudgeAt(t.id, addTime);
                        receipts.add("✓ added: " + a.description + " → " + addTime
                            + intervalSuffix(a.recurMinutes));
                    } else if (a.nextNudgeAt != null && !a.nextNudgeAt.isEmpty()) {
                        Log.w(TAG, "add_task: rejected invalid/past next_nudge_at: " + a.nextNudgeAt);
                        receipts.add("⚠ added without reminder (time rejected: "
                            + a.nextNudgeAt + "): " + a.description);
                    } else {
                        receipts.add("⚠ added without reminder: " + a.description);
                    }
                    break;
                }
                case "update_task": {
                    if (db.getTask(a.id) == null) {
                        Log.w(TAG, "update_task: unknown task id " + a.id);
                        receipts.add("⚠ update failed: unknown task " + a.id);
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
                    if (a.recurMinutes != null && a.recurMinutes > 0) {
                        db.setRecurMinutes(a.id, a.recurMinutes);
                        db.setRecurring(a.id, true);
                    }
                    if (a.recurring != null) {
                        db.setRecurring(a.id, a.recurring);
                        if (!a.recurring) db.setRecurMinutes(a.id, null);
                    }
                    Task t = db.getTask(a.id);
                    if (updTime != null) {
                        receipts.add("✓ updated: " + t.description + " → " + updTime
                            + intervalSuffix(a.recurMinutes));
                    } else if (a.nextNudgeAt != null && !a.nextNudgeAt.isEmpty()) {
                        receipts.add("⚠ updated, but time rejected ("
                            + a.nextNudgeAt + "): " + t.description);
                    } else {
                        receipts.add("✓ updated: " + t.description);
                    }
                    break;
                }
                case "complete_task": {
                    Task ct = db.getTask(a.id);
                    if (ct == null) {
                        Log.w(TAG, "complete_task: unknown task id " + a.id);
                        receipts.add("⚠ complete failed: unknown task " + a.id);
                        break;
                    }
                    if (ct.recurring) {
                        Log.w(TAG, "skipping complete_task on recurring task " + a.id);
                        receipts.add("⚠ not completed (recurring): " + ct.description);
                    } else {
                        db.completeTask(a.id);
                        receipts.add("✓ completed: " + ct.description);
                    }
                    break;
                }
                case "delete_task": {
                    Task dt = db.getTask(a.id);
                    if (dt == null) {
                        Log.w(TAG, "delete_task: unknown task id " + a.id);
                        receipts.add("⚠ delete failed: unknown task " + a.id);
                        break;
                    }
                    db.deleteTask(a.id);
                    receipts.add("✓ deleted: " + dt.description);
                    break;
                }
                case "update_schedule":
                    if (a.schedule != null) {
                        prefs.edit().putString("schedule", a.schedule).apply();
                        receipts.add("✓ schedule updated: " + a.schedule);
                    }
                    break;
                case "snooze_task": {
                    Task st = db.getTask(a.id);
                    if (st == null) {
                        Log.w(TAG, "snooze_task: unknown task id " + a.id);
                        receipts.add("⚠ snooze failed: unknown task " + a.id);
                        break;
                    }
                    int mins = a.minutes > 0 ? a.minutes : 30;
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.MINUTE, mins);
                    String snoozeTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                        .format(cal.getTime());
                    db.setNextNudgeAt(a.id, snoozeTime);
                    Log.d(TAG, "snooze_task " + a.id + " by " + mins + " min → " + snoozeTime);
                    receipts.add("✓ snoozed " + mins + " min: " + st.description);
                    break;
                }
                default:
                    Log.w(TAG, "unknown action type: " + a.type);
            }
        }
        return receipts;
    }

    private static String intervalSuffix(Integer minutes) {
        if (minutes == null || minutes <= 0) return "";
        return ", repeats every " + formatInterval(minutes);
    }

    static String formatInterval(int minutes) {
        if (minutes % 1440 == 0) return (minutes / 1440) + "d";
        if (minutes % 60 == 0)   return (minutes / 60) + "h";
        return minutes + "m";
    }

    /** Returns the time normalized to plain device-local yyyy-MM-dd'T'HH:mm:ss if it
     *  parses as ISO 8601 and is in the future, else null. Models sometimes append
     *  an offset (+02:00, Z) despite the prompt; a suffixed string stored verbatim
     *  breaks the lexicographic due-task comparison and misparses in the scheduler,
     *  so the offset is honored here and then dropped. */
    static String validatedFutureTime(String s) {
        if (s == null || s.isEmpty()) return null;
        s = s.trim();
        Date d;
        try {
            String pattern = s.matches(".*([+-]\\d{2}:?\\d{2}|Z)$")
                ? "yyyy-MM-dd'T'HH:mm:ssXXX"
                : "yyyy-MM-dd'T'HH:mm:ss";
            d = new SimpleDateFormat(pattern, Locale.US).parse(s);
        } catch (Exception e) {
            return null;
        }
        if (d == null || d.getTime() <= System.currentTimeMillis()) return null;
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(d);
    }
}
