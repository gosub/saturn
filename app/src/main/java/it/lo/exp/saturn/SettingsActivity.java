package it.lo.exp.saturn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private EditText apiKeyField, modelField, scheduleField;
    private Spinner languageSpinner;
    private String selectedLanguage = "en";
    private Database settingsDb;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("saturn", MODE_PRIVATE);

        apiKeyField   = findViewById(R.id.field_api_key);
        modelField    = findViewById(R.id.field_model);
        scheduleField = findViewById(R.id.field_schedule);
        languageSpinner   = findViewById(R.id.spinner_language);

        ArrayAdapter<CharSequence> langAdapter = ArrayAdapter.createFromResource(this,
            R.array.language_names, android.R.layout.simple_spinner_item);
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
                .setTitle(R.string.clear_history)
                .setMessage(R.string.clear_history_msg)
                .setPositiveButton(R.string.clear, (d, w) -> {
                    settingsDb.clearMessages();
                    Toast.makeText(this, R.string.toast_conversation_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());

        settingsDb = Database.get(this);
        Button clearTasksBtn = findViewById(R.id.clear_tasks_btn);
        clearTasksBtn.setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle(R.string.clear_tasks)
                .setMessage(R.string.clear_tasks_msg)
                .setPositiveButton(R.string.clear, (d, w) -> {
                    settingsDb.clearAllTasks();
                    NudgeScheduler.cancel(this);
                    Toast.makeText(this, R.string.toast_tasks_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
    }

    private void save() {
        String apiKey   = apiKeyField.getText().toString().trim();
        String model    = modelField.getText().toString().trim();
        String schedule = scheduleField.getText().toString().trim();

        if (model.isEmpty()) model = "openai/gpt-oss-120b:free";

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
            .putString("language", selectedLanguage)
            .putString("schedule", schedule)
            .remove("timezone")
            .apply();

        Toast.makeText(this, R.string.toast_settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
