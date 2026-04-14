package it.lo.exp.saturn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences("saturn", Context.MODE_PRIVATE);
        int intervalM = prefs.getInt("nudge_interval_m", 30);
        NudgeScheduler.schedule(context, intervalM);
    }
}
