package it.lo.exp.saturn;

public class Task {
    public long id;
    public String description;
    public String nextNudgeAt;   // ISO 8601, null if not set
    public boolean recurring;
    public Integer recurMinutes; // fixed repeat interval, null = model-managed

    public Task(long id, String description, String nextNudgeAt, boolean recurring) {
        this(id, description, nextNudgeAt, recurring, null);
    }

    public Task(long id, String description, String nextNudgeAt, boolean recurring,
                Integer recurMinutes) {
        this.id = id;
        this.description = description;
        this.nextNudgeAt = nextNudgeAt;
        this.recurring = recurring;
        this.recurMinutes = recurMinutes;
    }
}
