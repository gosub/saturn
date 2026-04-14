package it.lo.exp.saturn;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
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

    private ListView chatList;
    private EditText inputField;
    private Button sendBtn;
    private ChatAdapter adapter;
    private final List<ChatMessage> messages = new ArrayList<>();

    private Database db;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = new Database(this);
        prefs = getSharedPreferences("saturn", MODE_PRIVATE);

        chatList  = findViewById(R.id.chat_list);
        inputField = findViewById(R.id.input_field);
        sendBtn   = findViewById(R.id.send_btn);

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

        executor.execute(() -> {
            try {
                String model    = prefs.getString("model", "google/gemma-4-31b-it:free");
                String timezone = prefs.getString("timezone", "");
                String language = prefs.getString("language", "en");
                String schedule = prefs.getString("schedule", "");
                String histJson = prefs.getString("conversation_history", "");

                List<Task> tasks = db.getTasks();
                List<AgentClient.Message> history = AgentClient.loadHistory(histJson);
                String systemPrompt = AgentClient.buildChatPrompt(
                    language, schedule, tasks, System.currentTimeMillis(), timezone);

                AgentClient.AgentResponse resp = new AgentClient()
                    .chat(apiKey, model, systemPrompt, history, text);

                ActionExecutor.execute(resp.actions, db, prefs);
                NudgeScheduler.scheduleNext(MainActivity.this, db);

                String reply = (resp.reply != null && !resp.reply.isEmpty())
                    ? resp.reply : "(no reply)";

                String updatedHistory = AgentClient.saveHistory(history, text, resp.reply);
                prefs.edit().putString("conversation_history", updatedHistory).apply();

                runOnUiThread(() -> {
                    addBotMessage(reply);
                    setInputEnabled(true);
                });
            } catch (Exception e) {
                Log.e(TAG, "chat error", e);
                runOnUiThread(() -> {
                    addBotMessage("Error: " + e.getMessage());
                    setInputEnabled(true);
                });
            }
        });
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
