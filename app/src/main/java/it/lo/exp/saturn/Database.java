package it.lo.exp.saturn;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class Database extends SQLiteOpenHelper {

    private static final String DB_NAME = "saturn.db";
    private static final int DB_VERSION = 1;

    public Database(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE tasks (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "description TEXT NOT NULL, " +
            "next_nudge_at TEXT, " +
            "recurring INTEGER NOT NULL DEFAULT 0" +
            ")"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public List<Task> getTasks() {
        List<Task> tasks = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query("tasks", null, null, null, null, null, "id ASC");
        while (c.moveToNext()) {
            tasks.add(rowToTask(c));
        }
        c.close();
        return tasks;
    }

    public Task addTask(String description, boolean recurring) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("description", description);
        cv.put("recurring", recurring ? 1 : 0);
        long id = db.insert("tasks", null, cv);
        return new Task(id, description, null, recurring);
    }

    public void updateTask(long id, String description) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("description", description);
        db.update("tasks", cv, "id = ?", new String[]{String.valueOf(id)});
    }

    public void setNextNudgeAt(long id, String nextNudgeAt) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        if (nextNudgeAt != null && !nextNudgeAt.isEmpty()) {
            cv.put("next_nudge_at", nextNudgeAt);
        } else {
            cv.putNull("next_nudge_at");
        }
        db.update("tasks", cv, "id = ?", new String[]{String.valueOf(id)});
    }

    public void setRecurring(long id, boolean recurring) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("recurring", recurring ? 1 : 0);
        db.update("tasks", cv, "id = ?", new String[]{String.valueOf(id)});
    }

    public void completeTask(long id) {
        getWritableDatabase().delete("tasks", "id = ?", new String[]{String.valueOf(id)});
    }

    public void deleteTask(long id) {
        getWritableDatabase().delete("tasks", "id = ?", new String[]{String.valueOf(id)});
    }

    public List<Task> getDueTasks(String nowISO) {
        List<Task> tasks = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query("tasks", null,
            "next_nudge_at IS NOT NULL AND next_nudge_at <= ?",
            new String[]{nowISO}, null, null, "next_nudge_at ASC");
        while (c.moveToNext()) {
            tasks.add(rowToTask(c));
        }
        c.close();
        return tasks;
    }

    public String getNextScheduledTime() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query("tasks", new String[]{"next_nudge_at"},
            "next_nudge_at IS NOT NULL", null, null, null, "next_nudge_at ASC", "1");
        try {
            if (c.moveToFirst()) return c.getString(0);
            return null;
        } finally {
            c.close();
        }
    }

    public List<Task> getTasksForPeriod(String from, String to) {
        List<Task> tasks = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query("tasks", null,
            "next_nudge_at IS NOT NULL AND next_nudge_at >= ? AND next_nudge_at <= ?",
            new String[]{from, to}, null, null, "next_nudge_at ASC");
        while (c.moveToNext()) {
            tasks.add(rowToTask(c));
        }
        c.close();
        return tasks;
    }

    private Task rowToTask(Cursor c) {
        long id = c.getLong(c.getColumnIndexOrThrow("id"));
        String desc = c.getString(c.getColumnIndexOrThrow("description"));
        int nudgeCol = c.getColumnIndexOrThrow("next_nudge_at");
        String nudgeAt = c.isNull(nudgeCol) ? null : c.getString(nudgeCol);
        boolean recurring = c.getInt(c.getColumnIndexOrThrow("recurring")) != 0;
        return new Task(id, desc, nudgeAt, recurring);
    }
}
