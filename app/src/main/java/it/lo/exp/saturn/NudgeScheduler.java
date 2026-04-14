package it.lo.exp.saturn;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

public class NudgeScheduler {

    private static final String TAG = "Saturn";

    public static void schedule(Context context, int intervalMinutes) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = buildPendingIntent(context);
        am.cancel(pi);

        if (intervalMinutes <= 0) {
            Log.d(TAG, "nudge scheduling disabled");
            return;
        }

        long intervalMs = intervalMinutes * 60L * 1000L;
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + intervalMs,
            intervalMs,
            pi
        );
        Log.d(TAG, "nudge alarm scheduled every " + intervalMinutes + "m");
    }

    private static PendingIntent buildPendingIntent(Context context) {
        Intent intent = new Intent(context, NudgeReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
