package it.lo.exp.saturn;

/** What an applied action actually did, as a code plus the data needed to
 *  describe it. {@link ActionExecutor} produces these; the UI layer formats
 *  them into (localized) strings, so the executor stays free of presentation
 *  and Android types. */
public class Receipt {

    public enum Kind {
        ADDED, ADDED_NO_TIME, ADDED_TIME_REJECTED,
        UPDATED, UPDATED_TIME_REJECTED, UPDATE_UNKNOWN,
        COMPLETED, COMPLETE_RECURRING, COMPLETE_UNKNOWN,
        DELETED, DELETE_UNKNOWN,
        SCHEDULE_UPDATED,
        SNOOZED, SNOOZE_UNKNOWN
    }

    public final Kind kind;
    public final String text;          // task description, or schedule for SCHEDULE_UPDATED
    public final String time;          // validated next_nudge_at (ADDED / UPDATED)
    public final String rejectedTime;  // raw next_nudge_at that failed validation
    public final Integer recurMinutes; // repeat interval, when set
    public final long id;              // for the *_UNKNOWN kinds
    public final int minutes;          // for SNOOZED

    private Receipt(Kind kind, String text, String time, String rejectedTime,
                    Integer recurMinutes, long id, int minutes) {
        this.kind = kind;
        this.text = text;
        this.time = time;
        this.rejectedTime = rejectedTime;
        this.recurMinutes = recurMinutes;
        this.id = id;
        this.minutes = minutes;
    }

    static Receipt added(String desc, String time, Integer recurMinutes) {
        return new Receipt(Kind.ADDED, desc, time, null, recurMinutes, 0, 0);
    }

    static Receipt addedNoTime(String desc) {
        return new Receipt(Kind.ADDED_NO_TIME, desc, null, null, null, 0, 0);
    }

    static Receipt addedTimeRejected(String desc, String rejectedTime) {
        return new Receipt(Kind.ADDED_TIME_REJECTED, desc, null, rejectedTime, null, 0, 0);
    }

    static Receipt updated(String desc, String time, Integer recurMinutes) {
        return new Receipt(Kind.UPDATED, desc, time, null, recurMinutes, 0, 0);
    }

    static Receipt updatedTimeRejected(String desc, String rejectedTime) {
        return new Receipt(Kind.UPDATED_TIME_REJECTED, desc, null, rejectedTime, null, 0, 0);
    }

    static Receipt completed(String desc) {
        return new Receipt(Kind.COMPLETED, desc, null, null, null, 0, 0);
    }

    static Receipt completeRecurring(String desc) {
        return new Receipt(Kind.COMPLETE_RECURRING, desc, null, null, null, 0, 0);
    }

    static Receipt deleted(String desc) {
        return new Receipt(Kind.DELETED, desc, null, null, null, 0, 0);
    }

    static Receipt scheduleUpdated(String schedule) {
        return new Receipt(Kind.SCHEDULE_UPDATED, schedule, null, null, null, 0, 0);
    }

    static Receipt snoozed(int minutes, String desc) {
        return new Receipt(Kind.SNOOZED, desc, null, null, null, 0, minutes);
    }

    static Receipt unknownTask(Kind kind, long id) {
        return new Receipt(kind, null, null, null, null, id, 0);
    }
}
