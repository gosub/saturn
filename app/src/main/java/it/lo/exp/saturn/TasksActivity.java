package it.lo.exp.saturn;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

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
