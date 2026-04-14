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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;
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
                    String updatedHistory = AgentClient.saveHistory(history, text, resp.reply);
                    prefs.edit().putString("conversation_history", updatedHistory).apply();

                    runOnUiThread(() -> {
                        hideTypingIndicator();
                        addBotMessage(reply);
                        setInputEnabled(true);
                    });
                    done = true;

                } catch (AgentClient.RateLimitException rle) {
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
