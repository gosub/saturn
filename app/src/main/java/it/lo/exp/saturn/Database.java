package it.lo.exp.saturn;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class Database extends SQLiteOpenHelper implements TaskStore {

    private static final String DB_NAME = "saturn.db";
    private static final int DB_VERSION = 3;
    private static final int MAX_MESSAGES = 200;

    private static Database instance;

    /** Process-wide instance, never closed. SQLite serializes access internally. */
    public static synchronized Database get(Context context) {
        if (instance == null) {
            instance = new Database(context.getApplicationContext());
        }
        return instance;
    }

    private Database(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE tasks (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "description TEXT NOT NULL, " +
            "next_nudge_at TEXT, " +
            "recurring INTEGER NOT NULL DEFAULT 0, " +
            "recur_minutes INTEGER" +
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
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN recur_minutes INTEGER");
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

    public void setRecurMinutes(long id, Integer minutes) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        if (minutes != null && minutes > 0) {
            cv.put("recur_minutes", minutes);
        } else {
            cv.putNull("recur_minutes");
        }
        db.update("tasks", cv, "id = ?", new String[]{String.valueOf(id)});
    }

    @Override
    public void runInTransaction(Runnable work) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            work.run();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
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
            list.add(rowToMessage(c));
        }
        c.close();
        return list;
    }

    /** The most recent user/bot messages, oldest first. Source of the model's context. */
    public List<ChatMessage> loadRecentChat(int limit) {
        List<ChatMessage> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query("messages", null,
            "role IN (" + ChatMessage.ROLE_USER + "," + ChatMessage.ROLE_BOT + ")",
            null, null, null, "id DESC", String.valueOf(limit));
        while (c.moveToNext()) {
            list.add(0, rowToMessage(c));
        }
        c.close();
        return list;
    }

    private ChatMessage rowToMessage(Cursor c) {
        int role    = c.getInt(c.getColumnIndexOrThrow("role"));
        String text = c.getString(c.getColumnIndexOrThrow("content"));
        long ts     = c.getLong(c.getColumnIndexOrThrow("ts"));
        ChatMessage m = new ChatMessage(role, text);
        m.ts = ts;
        return m;
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
        int recurCol = c.getColumnIndexOrThrow("recur_minutes");
        Integer recurMinutes = c.isNull(recurCol) ? null : c.getInt(recurCol);
        return new Task(id, desc, nudgeAt, recurring, recurMinutes);
    }
}
