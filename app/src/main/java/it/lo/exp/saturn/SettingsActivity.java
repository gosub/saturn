package it.lo.exp.saturn;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

import java.util.TimeZone;

public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private EditText apiKeyField, modelField, timezoneField, nudgeIntervalField, scheduleField;
    private Spinner languageSpinner;
    private String selectedLanguage = "en";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("saturn", MODE_PRIVATE);

        apiKeyField       = findViewById(R.id.field_api_key);
        modelField        = findViewById(R.id.field_model);
        timezoneField     = findViewById(R.id.field_timezone);
        nudgeIntervalField = findViewById(R.id.field_nudge_interval);
        scheduleField     = findViewById(R.id.field_schedule);
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

        apiKeyField.setText(prefs.getString("api_key", ""));
        modelField.setText(prefs.getString("model", "openai/gpt-4o-mini"));
        timezoneField.setText(prefs.getString("timezone", TimeZone.getDefault().getID()));
        nudgeIntervalField.setText(String.valueOf(prefs.getInt("nudge_interval_m", 30)));
        scheduleField.setText(prefs.getString("schedule", ""));

        Button saveBtn = findViewById(R.id.save_btn);
        saveBtn.setOnClickListener(v -> save());

        Button clearHistoryBtn = findViewById(R.id.clear_history_btn);
        clearHistoryBtn.setOnClickListener(v ->
            prefs.edit().remove("conversation_history").apply());
    }

    private void save() {
        String apiKey   = apiKeyField.getText().toString().trim();
        String model    = modelField.getText().toString().trim();
        String timezone = timezoneField.getText().toString().trim();
        String schedule = scheduleField.getText().toString().trim();

        int intervalM = 30;
        try {
            intervalM = Integer.parseInt(nudgeIntervalField.getText().toString().trim());
        } catch (NumberFormatException ignored) {}

        if (model.isEmpty())    model    = "openai/gpt-4o-mini";
        if (timezone.isEmpty()) timezone = TimeZone.getDefault().getID();

        prefs.edit()
            .putString("api_key",         apiKey)
            .putString("model",           model)
            .putString("timezone",        timezone)
            .putString("language",        selectedLanguage)
            .putInt("nudge_interval_m",   intervalM)
            .putString("schedule",        schedule)
            .apply();

        NudgeScheduler.schedule(this, intervalM);
        finish();
    }
}
