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
    private static final int DB_VERSION = 2;
    private static final int MAX_MESSAGES = 200;

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
        db.execSQL(
            "CREATE TABLE messages (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "role INTEGER NOT NULL, " +
            "content TEXT NOT NULL, " +
            "ts INTEGER NOT NULL" +
            ")"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL(
                "CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "role INTEGER NOT NULL, " +
                "content TEXT NOT NULL, " +
                "ts INTEGER NOT NULL" +
                ")"
            );
        }
    }

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

    public void clearAllTasks() {
        getWritableDatabase().delete("tasks", null, null);
    }

    public void completeTask(long id) {
        getWritableDatabase().delete("tasks", "id = ?", new String[]{String.valueOf(id)});
    }

    public void deleteTask(long id) {
        getWritableDatabase().delete("tasks", "id = ?", new String[]{String.valueOf(id)});
    }

    public Task getTask(long id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query("tasks", null, "id = ?", new String[]{String.valueOf(id)}, null, null, null);
        try {
            if (c.moveToFirst()) return rowToTask(c);
            return null;
        } finally {
            c.close();
        }
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

    public void saveMessage(int role, String content, long ts) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("role", role);
        cv.put("content", content);
        cv.put("ts", ts);
        db.insert("messages", null, cv);
        // Trim to keep only the most recent MAX_MESSAGES
        db.execSQL(
            "DELETE FROM messages WHERE id NOT IN " +
            "(SELECT id FROM messages ORDER BY id DESC LIMIT " + MAX_MESSAGES + ")"
        );
    }

    public List<ChatMessage> loadMessages() {
        List<ChatMessage> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query("messages", null, null, null, null, null, "id ASC");
        while (c.moveToNext()) {
            int role    = c.getInt(c.getColumnIndexOrThrow("role"));
            String text = c.getString(c.getColumnIndexOrThrow("content"));
            long ts     = c.getLong(c.getColumnIndexOrThrow("ts"));
            ChatMessage m = new ChatMessage(role, text);
            m.ts = ts;
            list.add(m);
        }
        c.close();
        return list;
    }

    public void clearMessages() {
        getWritableDatabase().delete("messages", null, null);
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
