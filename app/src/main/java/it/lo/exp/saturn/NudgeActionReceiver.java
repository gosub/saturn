package it.lo.exp.saturn;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class NudgeActionReceiver extends BroadcastReceiver {

    private static final String TAG = "Saturn";

    public static final String ACTION_DONE   = "it.lo.exp.saturn.NUDGE_DONE";
    public static final String ACTION_SNOOZE = "it.lo.exp.saturn.NUDGE_SNOOZE";
    public static final String EXTRA_TASK_ID = "task_id";

    private static final int SNOOZE_MINUTES = 30;

    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(EXTRA_TASK_ID, -1);

        NotificationManager nm = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NudgeService.nudgeNotifId(id));

        Database db = Database.get(context);
        Task t = db.getTask(id);
        if (t == null) {
            Log.w(TAG, "notification action for unknown task " + id);
            return;
        }

        Context l10n = LocaleHelper.wrap(context);
        long now = System.currentTimeMillis();
        if (ACTION_DONE.equals(intent.getAction())) {
            if (t.recurring) {
                // The nudge cycle already scheduled the next occurrence.
                db.saveMessage(ChatMessage.ROLE_SYSTEM,
                    l10n.getString(R.string.nudge_done_recurring, t.description), now);
            } else {
                db.completeTask(id);
                db.saveMessage(ChatMessage.ROLE_SYSTEM,
                    l10n.getString(R.string.nudge_done, t.description), now);
            }
            Log.d(TAG, "notification action: done task " + id);
        } else if (ACTION_SNOOZE.equals(intent.getAction())) {
            db.setNextNudgeAt(id, NudgeService.isoPlusMinutes(now, SNOOZE_MINUTES));
            db.saveMessage(ChatMessage.ROLE_SYSTEM,
                l10n.getString(R.string.nudge_snoozed, SNOOZE_MINUTES, t.description), now);
            Log.d(TAG, "notification action: snoozed task " + id);
        }
        NudgeScheduler.scheduleNext(context, db);
        context.sendBroadcast(new Intent(MainActivity.ACTION_MESSAGES_CHANGED)
            .setPackage(context.getPackageName()));
    }
}
