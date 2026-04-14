package it.lo.exp.saturn;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "Saturn";
    private static final int REQUEST_NOTIFICATIONS = 1001;
    private static final String[] DOTS = {"\u25cf  \u25cb  \u25cb", "\u25cb  \u25cf  \u25cb", "\u25cb  \u25cb  \u25cf"};

    private ListView chatList;
    private EditText inputField;
    private Button sendBtn;
    private ChatAdapter adapter;
    private final List<ChatMessage> messages = new ArrayList<>();

    private Database db;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ChatMessage typingMessage;
    private int dotStep = 0;
    private final Handler dotsHandler = new Handler(Looper.getMainLooper());
    private final Runnable dotsRunnable = new Runnable() {
        @Override
        public void run() {
            if (typingMessage != null && typingMessage.maxProgress == 0) {
                typingMessage.content = DOTS[dotStep % 3];
                dotStep++;
                adapter.notifyDataSetChanged();
                dotsHandler.postDelayed(this, 400);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = new Database(this);
        prefs = getSharedPreferences("saturn", MODE_PRIVATE);

        chatList   = findViewById(R.id.chat_list);
        inputField = findViewById(R.id.input_field);
        sendBtn    = findViewById(R.id.send_btn);

        adapter = new ChatAdapter(this, messages);
        chatList.setAdapter(adapter);
        chatList.setStackFromBottom(true);

        sendBtn.setOnClickListener(v -> onSend());

        findViewById(R.id.settings_btn).setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));

        findViewById(R.id.overflow_btn).setOnClickListener(this::showOverflowMenu);

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            }
        }

        NudgeScheduler.scheduleNext(this, db);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dotsHandler.removeCallbacks(dotsRunnable);
        executor.shutdown();
        db.close();
    }

    private void onSend() {
        String text = inputField.getText().toString().trim();
        if (text.isEmpty()) return;

        String apiKey = prefs.getString("api_key", "");
        if (apiKey.isEmpty()) {
            addBotMessage("Please set your OpenRouter API key in Settings.");
            return;
        }

        inputField.setText("");
        setInputEnabled(false);
        addUserMessage(text);
        showTypingIndicator();

        executor.execute(() -> {
            Log.d(TAG, "chat: user=\"" + text + "\"");
            String model    = prefs.getString("model", "google/gemma-4-31b-it:free");
            String timezone = prefs.getString("timezone", "");
            String language = prefs.getString("language", "en");
            String schedule = prefs.getString("schedule", "");
            String histJson = prefs.getString("conversation_history", "");

            List<AgentClient.Message> history = AgentClient.loadHistory(histJson);
            String systemPrompt;
            List<Task> tasks;
            synchronized (db) {
                tasks = db.getTasks();
            }
            systemPrompt = AgentClient.buildChatPrompt(
                language, schedule, tasks, System.currentTimeMillis(), timezone);

            boolean done = false;
            while (!done) {
                try {
                    AgentClient.AgentResponse resp = new AgentClient()
                        .chat(apiKey, model, systemPrompt, history, text);

                    synchronized (db) {
                        ActionExecutor.execute(resp.actions, db, prefs);
                        NudgeScheduler.scheduleNext(MainActivity.this, db);
                    }

                    String reply = (resp.reply != null && !resp.reply.isEmpty())
                        ? resp.reply : "(no reply)";
                    Log.d(TAG, "chat: reply=\"" + reply + "\" actions=" + resp.actions.size());
                    String updatedHistory = AgentClient.saveHistory(history, text, resp.reply);
                    prefs.edit().putString("conversation_history", updatedHistory).apply();

                    runOnUiThread(() -> {
                        hideTypingIndicator();
                        addBotMessage(reply);
                        setInputEnabled(true);
                    });
                    done = true;

                } catch (AgentClient.RateLimitException rle) {
                    Log.d(TAG, "chat: rate limited, waiting " + rle.retryAfterSeconds + "s");
                    final int total = rle.retryAfterSeconds;
                    for (int remaining = total; remaining >= 0; remaining--) {
                        final int r = remaining;
                        final int progress = ((total - r) * 100) / total;
                        runOnUiThread(() -> {
                            if (typingMessage != null) {
                                typingMessage.content = r > 0 ? "Retrying in " + r + "s" : "Retrying\u2026";
                                typingMessage.progress = progress;
                                typingMessage.maxProgress = total;
                                adapter.notifyDataSetChanged();
                            }
                        });
                        if (remaining > 0) {
                            try { Thread.sleep(1000); } catch (InterruptedException ie) {
                                done = true;
                                break;
                            }
                        }
                    }
                    if (!done) {
                        runOnUiThread(() -> {
                            if (typingMessage != null) {
                                typingMessage.content = DOTS[0];
                                typingMessage.maxProgress = 0;
                                dotStep = 0;
                                adapter.notifyDataSetChanged();
                                dotsHandler.postDelayed(dotsRunnable, 400);
                            }
                        });
                    }

                } catch (Exception e) {
                    Log.e(TAG, "chat error", e);
                    final String msg = e.getMessage();
                    runOnUiThread(() -> {
                        hideTypingIndicator();
                        addBotMessage("Error: " + msg);
                        setInputEnabled(true);
                    });
                    done = true;
                }
            }
        });
    }

    // ---- Overflow menu ----

    private void showOverflowMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Tasks");
        menu.getMenu().add(0, 2, 1, "Today");
        menu.getMenu().add(0, 3, 2, "This week");
        menu.getMenu().add(0, 4, 3, "Debug");
        menu.setOnMenuItemClickListener((MenuItem item) -> {
            switch (item.getItemId()) {
                case 1: showTasks();              return true;
                case 2: showPeriodSummary(false); return true;
                case 3: showPeriodSummary(true);  return true;
                case 4: showDebug();              return true;
            }
            return false;
        });
        menu.show();
    }

    private void showTasks() {
        List<Task> tasks;
        synchronized (db) { tasks = db.getTasks(); }
        if (tasks.isEmpty()) {
            addBotMessage("No active tasks.");
            return;
        }
        StringBuilder sb = new StringBuilder("Active tasks (" + tasks.size() + "):\n");
        for (Task t : tasks) {
            String nudge = (t.nextNudgeAt != null && !t.nextNudgeAt.isEmpty())
                ? t.nextNudgeAt : "not set";
            sb.append("\n  ").append(t.id).append(". ");
            if (t.recurring) sb.append("\u21bb ");
            sb.append(t.description).append("\n     \u2192 ").append(nudge);
        }
        addBotMessage(sb.toString().trim());
    }

    private void showPeriodSummary(boolean week) {
        String tzId = prefs.getString("timezone", "");
        TimeZone zone = (tzId != null && !tzId.isEmpty())
            ? TimeZone.getTimeZone(tzId) : TimeZone.getDefault();
        Calendar cal = Calendar.getInstance(zone);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (week) {
            int dow = cal.get(Calendar.DAY_OF_WEEK);
            int toMonday = (dow == Calendar.SUNDAY) ? -6 : Calendar.MONDAY - dow;
            cal.add(Calendar.DAY_OF_MONTH, toMonday);
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        sdf.setTimeZone(zone);
        String from = sdf.format(cal.getTime());
        if (week) cal.add(Calendar.DAY_OF_MONTH, 6);
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        String to = sdf.format(cal.getTime());

        List<Task> tasks;
        synchronized (db) { tasks = db.getTasksForPeriod(from, to); }

        String label = week ? "This week:" : "Today:";
        if (tasks.isEmpty()) {
            addBotMessage(label + "\nNothing scheduled.");
            return;
        }
        StringBuilder sb = new StringBuilder(label + "\n");
        for (Task t : tasks) {
            String time = "";
            if (t.nextNudgeAt != null && t.nextNudgeAt.length() >= 16) {
                time = " \u2014 " + t.nextNudgeAt.substring(11, 16);
                if (week && t.nextNudgeAt.length() >= 10) {
                    time = " \u2014 " + t.nextNudgeAt.substring(5, 10) + " " + t.nextNudgeAt.substring(11, 16);
                }
            }
            sb.append("\n  ").append(t.recurring ? "\u21bb " : "\u2022 ")
              .append(t.description).append(time);
        }
        addBotMessage(sb.toString().trim());
    }

    private void showDebug() {
        StringBuilder sb = new StringBuilder("Debug:\n\n");
        sb.append("model:    ").append(prefs.getString("model", "\u2014")).append("\n");
        sb.append("timezone: ").append(prefs.getString("timezone", "\u2014")).append("\n");
        sb.append("language: ").append(prefs.getString("language", "\u2014")).append("\n");
        sb.append("schedule: ").append(prefs.getString("schedule", "not set")).append("\n");
        List<AgentClient.Message> hist = AgentClient.loadHistory(
            prefs.getString("conversation_history", ""));
        sb.append("history:  ").append(hist.size() / 2).append(" turns\n");
        String next;
        List<Task> tasks;
        synchronized (db) {
            next = db.getNextScheduledTime();
            tasks = db.getTasks();
        }
        sb.append("next alarm: ").append(next != null ? next : "none").append("\n");
        sb.append("\ntasks (").append(tasks.size()).append("):\n");
        for (Task t : tasks) {
            sb.append("\n  [").append(t.id).append("] ").append(t.description);
            if (t.recurring) sb.append(" [\u21bb]");
            sb.append("\n       next: ")
              .append(t.nextNudgeAt != null ? t.nextNudgeAt : "not set");
        }
        addBotMessage(sb.toString().trim());
    }

    // ---- Typing indicator ----

    private void showTypingIndicator() {
        dotStep = 0;
        typingMessage = new ChatMessage(ChatMessage.ROLE_TYPING, DOTS[0]);
        messages.add(typingMessage);
        adapter.notifyDataSetChanged();
        chatList.smoothScrollToPosition(messages.size() - 1);
        dotsHandler.postDelayed(dotsRunnable, 400);
    }

    private void hideTypingIndicator() {
        dotsHandler.removeCallbacks(dotsRunnable);
        if (typingMessage != null) {
            messages.remove(typingMessage);
            typingMessage = null;
            adapter.notifyDataSetChanged();
        }
    }

    private void addUserMessage(String text) {
        messages.add(new ChatMessage(ChatMessage.ROLE_USER, text));
        adapter.notifyDataSetChanged();
        chatList.smoothScrollToPosition(messages.size() - 1);
    }

    private void addBotMessage(String text) {
        messages.add(new ChatMessage(ChatMessage.ROLE_BOT, text));
        adapter.notifyDataSetChanged();
        chatList.smoothScrollToPosition(messages.size() - 1);
    }

    private void setInputEnabled(boolean enabled) {
        inputField.setEnabled(enabled);
        sendBtn.setEnabled(enabled);
        sendBtn.setText(enabled ? "Send" : "\u2026");
    }
}
