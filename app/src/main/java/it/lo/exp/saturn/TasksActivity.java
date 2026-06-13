package it.lo.exp.saturn;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Direct, mostly LLM-free task management: the active tasks grouped by when
 *  they next fire. */
public class TasksActivity extends Activity {

    private static final int SNOOZE_MINUTES = 30;

    private Database db;
    private ListView list;
    private TextView empty;
    private TaskListAdapter adapter;
    private final List<TaskListAdapter.Row> rows = new ArrayList<>();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tasks);
        db = Database.get(this);
        list = findViewById(R.id.tasks_list);
        empty = findViewById(R.id.tasks_empty);
        adapter = new TaskListAdapter(this, rows);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            TaskListAdapter.Row row = rows.get(position);
            if (!row.isHeader()) showTaskActions(row.task);
        });
        findViewById(R.id.tasks_back).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<Task> tasks;
        synchronized (db) { tasks = db.getTasks(); }
        rows.clear();
        rows.addAll(buildRows(tasks));
        adapter.notifyDataSetChanged();
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showTaskActions(Task t) {
        List<String> labels = new ArrayList<>();
        List<Runnable> handlers = new ArrayList<>();

        // Completing a recurring task is meaningless (it repeats); use Delete to
        // stop it, mirroring the executor's complete-recurring guard.
        if (!t.recurring) {
            labels.add(getString(R.string.action_complete));
            handlers.add(() -> apply(() -> db.completeTask(t.id), R.string.toast_completed));
        }
        labels.add(getString(R.string.action_snooze));
        handlers.add(() -> {
            String next = NudgeService.isoPlusMinutes(System.currentTimeMillis(), SNOOZE_MINUTES);
            apply(() -> db.setNextNudgeAt(t.id, next), R.string.toast_snoozed);
        });
        labels.add(getString(R.string.action_edit_time));
        handlers.add(() -> editTime(t));
        labels.add(getString(R.string.action_edit_desc));
        handlers.add(() -> editDescription(t));
        labels.add(getString(R.string.action_delete));
        handlers.add(() -> apply(() -> db.deleteTask(t.id), R.string.toast_deleted));

        new AlertDialog.Builder(this)
            .setTitle((t.recurring ? "↻ " : "") + t.description)
            .setItems(labels.toArray(new String[0]), (d, which) -> handlers.get(which).run())
            .show();
    }

    /** Apply one mutation atomically, reschedule the alarm, refresh and confirm.
     *  No LLM, no network. */
    private void apply(Runnable mutation, int toastRes) {
        synchronized (db) {
            db.runInTransaction(mutation);
            NudgeScheduler.scheduleNext(this, db);
        }
        refresh();
        Toast.makeText(this, toastRes, Toast.LENGTH_SHORT).show();
    }

    /** Pick a date then a time; store it as next_nudge_at if it's in the future. */
    private void editTime(Task t) {
        Calendar seed = Calendar.getInstance();
        long existing = parse(t.nextNudgeAt);
        if (existing > 0) seed.setTimeInMillis(existing);

        new DatePickerDialog(this, (view, year, month, day) ->
            new TimePickerDialog(this, (tv, hour, minute) -> {
                Calendar picked = Calendar.getInstance();
                picked.set(year, month, day, hour, minute, 0);
                picked.set(Calendar.MILLISECOND, 0);
                String iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    .format(picked.getTime());
                String valid = ActionExecutor.validatedFutureTime(iso);
                if (valid == null) {
                    Toast.makeText(this, R.string.toast_time_past, Toast.LENGTH_SHORT).show();
                    return;
                }
                apply(() -> db.setNextNudgeAt(t.id, valid), R.string.toast_time_set);
            }, seed.get(Calendar.HOUR_OF_DAY), seed.get(Calendar.MINUTE), true).show(),
            seed.get(Calendar.YEAR), seed.get(Calendar.MONTH), seed.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void editDescription(Task t) {
        EditText input = new EditText(this);
        input.setText(t.description);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
            .setTitle(R.string.action_edit_desc)
            .setView(input)
            .setPositiveButton(R.string.save, (d, w) -> {
                String desc = input.getText().toString().trim();
                if (desc.isEmpty()) return;
                synchronized (db) { db.runInTransaction(() -> db.updateTask(t.id, desc)); }
                refresh();
                Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    /** Sort by next reminder, then group into Today / This week / Later /
     *  No reminder. Overdue tasks fall into Today. */
    private List<TaskListAdapter.Row> buildRows(List<Task> tasks) {
        long now = System.currentTimeMillis();
        long endToday = endOf(now, false);
        long endWeek = endOf(now, true);

        List<Task> today = new ArrayList<>();
        List<Task> week = new ArrayList<>();
        List<Task> later = new ArrayList<>();
        List<Task> none = new ArrayList<>();
        for (Task t : tasks) {
            long when = parse(t.nextNudgeAt);
            if (when < 0) none.add(t);
            else if (when <= endToday) today.add(t);
            else if (when <= endWeek) week.add(t);
            else later.add(t);
        }
        Comparator<Task> byTime = Comparator.comparingLong(t -> parse(t.nextNudgeAt));
        Collections.sort(today, byTime);
        Collections.sort(week, byTime);
        Collections.sort(later, byTime);

        List<TaskListAdapter.Row> out = new ArrayList<>();
        addBucket(out, getString(R.string.menu_today), today);
        addBucket(out, getString(R.string.menu_week), week);
        addBucket(out, getString(R.string.bucket_later), later);
        addBucket(out, getString(R.string.bucket_no_reminder), none);
        return out;
    }

    private void addBucket(List<TaskListAdapter.Row> out, String label, List<Task> tasks) {
        if (tasks.isEmpty()) return;
        out.add(TaskListAdapter.Row.header(label));
        for (Task t : tasks) out.add(TaskListAdapter.Row.task(t));
    }

    private static long parse(String iso) {
        if (iso == null || iso.isEmpty()) return -1;
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso).getTime();
        } catch (Exception e) {
            return -1;
        }
    }

    /** End of today, or end of the current week (Sunday 23:59:59). */
    private static long endOf(long now, boolean week) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        if (week) {
            int dow = cal.get(Calendar.DAY_OF_WEEK);
            int toSunday = (dow == Calendar.SUNDAY) ? 0 : (8 - dow);
            cal.add(Calendar.DAY_OF_MONTH, toSunday);
        }
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
        return cal.getTimeInMillis();
    }
}
