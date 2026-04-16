package it.lo.exp.saturn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

import java.util.TimeZone;

public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private EditText apiKeyField, modelField, timezoneField, scheduleField;
    private Spinner languageSpinner;
    private String selectedLanguage = "en";
    private Database settingsDb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("saturn", MODE_PRIVATE);

        apiKeyField   = findViewById(R.id.field_api_key);
        modelField    = findViewById(R.id.field_model);
        timezoneField = findViewById(R.id.field_timezone);
        scheduleField = findViewById(R.id.field_schedule);
        languageSpinner   = findViewById(R.id.spinner_language);

        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, new String[]{"English", "Italian"});
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(langAdapter);

        String lang = prefs.getString("language", "en");
        selectedLanguage = lang;
        languageSpinner.setSelection("it".equals(lang) ? 1 : 0);
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedLanguage = pos == 1 ? "it" : "en";
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        apiKeyField.setText(KeystoreHelper.readApiKey(prefs));
        modelField.setText(prefs.getString("model", "openai/gpt-oss-120b:free"));
        timezoneField.setText(prefs.getString("timezone", TimeZone.getDefault().getID()));
        scheduleField.setText(prefs.getString("schedule", ""));

        Button toggleKeyBtn = findViewById(R.id.toggle_key_visibility);
        toggleKeyBtn.setOnClickListener(v -> {
            int variation = apiKeyField.getInputType() & InputType.TYPE_MASK_VARIATION;
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                apiKeyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            } else {
                apiKeyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
            apiKeyField.setSelection(apiKeyField.getText().length());
        });

        Button saveBtn = findViewById(R.id.save_btn);
        saveBtn.setOnClickListener(v -> save());

        Button clearHistoryBtn = findViewById(R.id.clear_history_btn);
        clearHistoryBtn.setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle("Clear conversation history")
                .setMessage("This will erase the chat context sent to the model. Tasks are not affected.")
                .setPositiveButton("Clear", (d, w) -> {
                    prefs.edit().remove("conversation_history").apply();
                    settingsDb.clearMessages();
                })
                .setNegativeButton("Cancel", null)
                .show());

        settingsDb = new Database(this);
        Button clearTasksBtn = findViewById(R.id.clear_tasks_btn);
        clearTasksBtn.setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle("Clear all tasks")
                .setMessage("This will permanently delete all tasks and cancel all reminders.")
                .setPositiveButton("Clear", (d, w) -> {
                    settingsDb.clearAllTasks();
                    NudgeScheduler.cancel(this);
                })
                .setNegativeButton("Cancel", null)
                .show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        settingsDb.close();
    }

    private void save() {
        String apiKey   = apiKeyField.getText().toString().trim();
        String model    = modelField.getText().toString().trim();
        String timezone = timezoneField.getText().toString().trim();
        String schedule = scheduleField.getText().toString().trim();

        if (model.isEmpty())    model    = "openai/gpt-oss-120b:free";
        if (timezone.isEmpty()) timezone = TimeZone.getDefault().getID();

        String storedKey = apiKey;
        if (!apiKey.isEmpty()) {
            try {
                storedKey = KeystoreHelper.encrypt(apiKey);
            } catch (Exception e) {
                android.util.Log.e("Saturn", "keystore encrypt failed", e);
            }
        }

        prefs.edit()
            .putString("api_key",  storedKey)
            .putString("model",    model)
            .putString("timezone", timezone)
            .putString("language", selectedLanguage)
            .putString("schedule", schedule)
            .apply();

        finish();
    }
}
