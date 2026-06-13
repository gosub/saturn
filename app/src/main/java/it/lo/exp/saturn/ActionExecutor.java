package it.lo.exp.saturn;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ActionExecutor {

    private static final String TAG = "Saturn";

    /** Persists the agent's schedule update. Kept separate from {@link TaskStore}
     *  so the executor never touches Android's SharedPreferences directly and can
     *  be tested with a capturing fake. */
    public interface ScheduleWriter {
        void setSchedule(String schedule);
    }

    /** Applies the agent's actions and returns one receipt per action,
     *  describing what actually happened in the database (not what the model
     *  claims in its reply). Receipts are structured; the UI formats them. */
    public static List<Receipt> execute(List<AgentClient.Action> actions,
                                        TaskStore db, ScheduleWriter scheduleWriter) {
        List<Receipt> receipts = new ArrayList<>();
        if (actions == null) return receipts;
        for (AgentClient.Action a : actions) {
            Log.d(TAG, "action: type=" + a.type + " id=" + a.id + " desc=" + a.description);
            switch (a.type != null ? a.type : "") {
                case "add_task":
                    db.runInTransaction(() -> {
                        boolean rec = Boolean.TRUE.equals(a.recurring)
                            || (a.recurMinutes != null && a.recurMinutes > 0);
                        Task t = db.addTask(a.description, rec);
                        if (a.recurMinutes != null && a.recurMinutes > 0) {
                            db.setRecurMinutes(t.id, a.recurMinutes);
                        }
                        String addTime = validatedFutureTime(a.nextNudgeAt);
                        if (addTime != null) {
                            db.setNextNudgeAt(t.id, addTime);
                            receipts.add(Receipt.added(a.description, addTime, a.recurMinutes));
                        } else if (a.nextNudgeAt != null && !a.nextNudgeAt.isEmpty()) {
                            Log.w(TAG, "add_task: rejected invalid/past next_nudge_at: " + a.nextNudgeAt);
                            receipts.add(Receipt.addedTimeRejected(a.description, a.nextNudgeAt));
                        } else {
                            receipts.add(Receipt.addedNoTime(a.description));
                        }
                    });
                    break;
                case "update_task":
                    db.runInTransaction(() -> {
                        if (db.getTask(a.id) == null) {
                            Log.w(TAG, "update_task: unknown task id " + a.id);
                            receipts.add(Receipt.unknownTask(Receipt.Kind.UPDATE_UNKNOWN, a.id));
                            return;
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
                            receipts.add(Receipt.updated(t.description, updTime, a.recurMinutes));
                        } else if (a.nextNudgeAt != null && !a.nextNudgeAt.isEmpty()) {
                            receipts.add(Receipt.updatedTimeRejected(t.description, a.nextNudgeAt));
                        } else {
                            receipts.add(Receipt.updated(t.description, null, null));
                        }
                    });
                    break;
                case "complete_task":
                    db.runInTransaction(() -> {
                        Task ct = db.getTask(a.id);
                        if (ct == null) {
                            Log.w(TAG, "complete_task: unknown task id " + a.id);
                            receipts.add(Receipt.unknownTask(Receipt.Kind.COMPLETE_UNKNOWN, a.id));
                            return;
                        }
                        if (ct.recurring) {
                            Log.w(TAG, "skipping complete_task on recurring task " + a.id);
                            receipts.add(Receipt.completeRecurring(ct.description));
                        } else {
                            db.completeTask(a.id);
                            receipts.add(Receipt.completed(ct.description));
                        }
                    });
                    break;
                case "delete_task":
                    db.runInTransaction(() -> {
                        Task dt = db.getTask(a.id);
                        if (dt == null) {
                            Log.w(TAG, "delete_task: unknown task id " + a.id);
                            receipts.add(Receipt.unknownTask(Receipt.Kind.DELETE_UNKNOWN, a.id));
                            return;
                        }
                        db.deleteTask(a.id);
                        receipts.add(Receipt.deleted(dt.description));
                    });
                    break;
                case "update_schedule":
                    if (a.schedule != null) {
                        scheduleWriter.setSchedule(a.schedule);
                        receipts.add(Receipt.scheduleUpdated(a.schedule));
                    }
                    break;
                case "snooze_task":
                    db.runInTransaction(() -> {
                        Task st = db.getTask(a.id);
                        if (st == null) {
                            Log.w(TAG, "snooze_task: unknown task id " + a.id);
                            receipts.add(Receipt.unknownTask(Receipt.Kind.SNOOZE_UNKNOWN, a.id));
                            return;
                        }
                        int mins = a.minutes > 0 ? a.minutes : 30;
                        Calendar cal = Calendar.getInstance();
                        cal.add(Calendar.MINUTE, mins);
                        String snoozeTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                            .format(cal.getTime());
                        db.setNextNudgeAt(a.id, snoozeTime);
                        Log.d(TAG, "snooze_task " + a.id + " by " + mins + " min → " + snoozeTime);
                        receipts.add(Receipt.snoozed(mins, st.description));
                    });
                    break;
                default:
                    Log.w(TAG, "unknown action type: " + a.type);
            }
        }
        return receipts;
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
