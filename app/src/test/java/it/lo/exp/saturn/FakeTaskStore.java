package it.lo.exp.saturn;

import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory {@link TaskStore} for unit-testing {@link ActionExecutor} without
 *  an Android database. getTask returns copies, mirroring how the real store
 *  hands back fresh rows per query. */
class FakeTaskStore implements TaskStore {

    final Map<Long, Task> tasks = new LinkedHashMap<>();
    private long nextId = 1;
    int transactions = 0;

    @Override
    public Task addTask(String description, boolean recurring) {
        long id = nextId++;
        Task t = new Task(id, description, null, recurring, null);
        tasks.put(id, t);
        return t;
    }

    @Override
    public Task getTask(long id) {
        Task t = tasks.get(id);
        if (t == null) return null;
        return new Task(t.id, t.description, t.nextNudgeAt, t.recurring, t.recurMinutes);
    }

    @Override
    public void updateTask(long id, String description) {
        Task t = tasks.get(id);
        if (t != null) t.description = description;
    }

    @Override
    public void setNextNudgeAt(long id, String nextNudgeAt) {
        Task t = tasks.get(id);
        if (t != null) t.nextNudgeAt = nextNudgeAt;
    }

    @Override
    public void setRecurring(long id, boolean recurring) {
        Task t = tasks.get(id);
        if (t != null) t.recurring = recurring;
    }

    @Override
    public void setRecurMinutes(long id, Integer minutes) {
        Task t = tasks.get(id);
        if (t != null) t.recurMinutes = minutes;
    }

    @Override
    public void completeTask(long id) {
        tasks.remove(id);
    }

    @Override
    public void deleteTask(long id) {
        tasks.remove(id);
    }

    @Override
    public void runInTransaction(Runnable work) {
        transactions++;
        work.run();
    }
}
