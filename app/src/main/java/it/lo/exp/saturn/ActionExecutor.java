package it.lo.exp.saturn;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.List;

public class ActionExecutor {

    private static final String TAG = "Saturn";

    public static void execute(List<AgentClient.Action> actions, Database db, SharedPreferences prefs) {
        if (actions == null) return;
        for (AgentClient.Action a : actions) {
            Log.d(TAG, "action: type=" + a.type + " id=" + a.id + " desc=" + a.description);
            switch (a.type != null ? a.type : "") {
                case "add_task": {
                    Task t = db.addTask(a.description, a.recurring);
                    if (a.nextNudgeAt != null && !a.nextNudgeAt.isEmpty()) {
                        db.setNextNudgeAt(t.id, a.nextNudgeAt);
                    }
                    break;
                }
                case "update_task": {
                    if (a.description != null && !a.description.isEmpty()) {
                        db.updateTask(a.id, a.description);
                    }
                    if (a.nextNudgeAt != null && !a.nextNudgeAt.isEmpty()) {
                        db.setNextNudgeAt(a.id, a.nextNudgeAt);
                    }
                    if (a.recurring) {
                        db.setRecurring(a.id, true);
                    }
                    break;
                }
                case "complete_task": {
                    List<Task> tasks = db.getTasks();
                    boolean recurring = false;
                    for (Task t : tasks) {
                        if (t.id == a.id) { recurring = t.recurring; break; }
                    }
                    if (recurring) {
                        Log.w(TAG, "skipping complete_task on recurring task " + a.id);
                    } else {
                        db.completeTask(a.id);
                    }
                    break;
                }
                case "delete_task":
                    db.deleteTask(a.id);
                    break;
                case "update_schedule":
                    if (a.schedule != null) {
                        prefs.edit().putString("schedule", a.schedule).apply();
                    }
                    break;
                default:
                    Log.w(TAG, "unknown action type: " + a.type);
            }
        }
    }
}
