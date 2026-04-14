package it.lo.exp.saturn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class NudgeReceiver extends BroadcastReceiver {

    private static final String TAG = "Saturn";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "nudge alarm fired");
        Intent serviceIntent = new Intent(context, NudgeService.class);
        context.startForegroundService(serviceIntent);
    }
}
