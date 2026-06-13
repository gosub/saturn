package it.lo.exp.saturn;

/** The persistence operations {@link ActionExecutor} performs. Backed by
 *  {@link Database} in the app and by an in-memory fake in tests, so the
 *  executor's branching can be verified without an Android database. */
public interface TaskStore {
    Task addTask(String description, boolean recurring);
    Task getTask(long id);
    void updateTask(long id, String description);
    void setNextNudgeAt(long id, String nextNudgeAt);
    void setRecurring(long id, boolean recurring);
    void setRecurMinutes(long id, Integer minutes);
    void completeTask(long id);
    void deleteTask(long id);
}
