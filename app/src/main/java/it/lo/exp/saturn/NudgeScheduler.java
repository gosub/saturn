package it.lo.exp.saturn;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Locale;

public class NudgeScheduler {

    private static final String TAG = "Saturn";

    public static void scheduleNext(Context context, Database db) {
        String nextTime = db.getNextScheduledTime();
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = buildPendingIntent(context);

        if (nextTime == null) {
            am.cancel(pi);
            Log.d(TAG, "no upcoming tasks, alarm cancelled");
            return;
        }

        long triggerMs;
        try {
            triggerMs = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .parse(nextTime).getTime();
        } catch (Exception e) {
            Log.e(TAG, "failed to parse next nudge time: " + nextTime, e);
            return;
        }

        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            // No permission granted — use a 15-minute window around the target time
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerMs, 15 * 60 * 1000L, pi);
            Log.d(TAG, "inexact alarm set for " + nextTime + " (no exact alarm permission)");
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi);
            Log.d(TAG, "exact alarm set for " + nextTime);
        }
    }

    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(buildPendingIntent(context));
        Log.d(TAG, "alarm cancelled");
    }

    private static PendingIntent buildPendingIntent(Context context) {
        Intent intent = new Intent(context, NudgeReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
