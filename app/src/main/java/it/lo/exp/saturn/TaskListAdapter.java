package it.lo.exp.saturn;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

/** Renders the bucketed task list: section headers (non-clickable) interleaved
 *  with task rows. */
public class TaskListAdapter extends BaseAdapter {

    static class Row {
        final String header;  // non-null => section header
        final Task task;      // non-null => task row

        private Row(String header, Task task) {
            this.header = header;
            this.task = task;
        }

        static Row header(String label) { return new Row(label, null); }
        static Row task(Task t) { return new Row(null, t); }
        boolean isHeader() { return header != null; }
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_TASK = 1;

    private final LayoutInflater inflater;
    private final List<Row> rows;

    TaskListAdapter(Context context, List<Row> rows) {
        this.inflater = LayoutInflater.from(context);
        this.rows = rows;
    }

    @Override public int getCount() { return rows.size(); }
    @Override public Row getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int position) {
        return rows.get(position).isHeader() ? TYPE_HEADER : TYPE_TASK;
    }

    @Override public boolean areAllItemsEnabled() { return false; }
    @Override public boolean isEnabled(int position) { return !rows.get(position).isHeader(); }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Row row = rows.get(position);
        Context ctx = parent.getContext();

        if (row.isHeader()) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_task_header, parent, false);
            }
            ((TextView) convertView).setText(row.header);
            return convertView;
        }

        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_task, parent, false);
        }
        Task t = row.task;
        TextView title = convertView.findViewById(R.id.task_title);
        TextView sub = convertView.findViewById(R.id.task_sub);

        title.setText((t.recurring ? "↻ " : "") + t.description);
        String when = (t.nextNudgeAt != null && !t.nextNudgeAt.isEmpty())
            ? t.nextNudgeAt : ctx.getString(R.string.not_set);
        if (t.recurMinutes != null && t.recurMinutes > 0) {
            when += " (" + ActionExecutor.formatInterval(t.recurMinutes) + ")";
        }
        sub.setText(when);
        return convertView;
    }
}
