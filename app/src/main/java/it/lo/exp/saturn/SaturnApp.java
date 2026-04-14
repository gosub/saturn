package it.lo.exp.saturn;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

public class SaturnApp extends Application {

    public static final String CHANNEL_NUDGE   = "saturn_nudge";
    public static final String CHANNEL_SERVICE = "saturn_service";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);

        NotificationChannel nudge = new NotificationChannel(
            CHANNEL_NUDGE, "Nudges", NotificationManager.IMPORTANCE_HIGH);
        nudge.setDescription("Task nudge reminders");
        nm.createNotificationChannel(nudge);

        NotificationChannel service = new NotificationChannel(
            CHANNEL_SERVICE, "Background checks", NotificationManager.IMPORTANCE_LOW);
        service.setDescription("Shown briefly while checking for nudges");
        nm.createNotificationChannel(service);
    }
}
