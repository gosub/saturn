package it.lo.exp.saturn;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ActionExecutorTest {

    private TimeZone originalTz;

    @Before
    public void fixTimezone() {
        originalTz = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Rome"));
    }

    @After
    public void restoreTimezone() {
        TimeZone.setDefault(originalTz);
    }

    private static String iso(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .format(new Date(millis));
    }

    @Test
    public void acceptsFutureTime() {
        String future = iso(System.currentTimeMillis() + 60 * 60_000L);
        assertEquals(future, ActionExecutor.validatedFutureTime(future));
    }

    @Test
    public void rejectsPastTime() {
        String past = iso(System.currentTimeMillis() - 60 * 60_000L);
        assertNull(ActionExecutor.validatedFutureTime(past));
    }

    @Test
    public void rejectsGarbage() {
        assertNull(ActionExecutor.validatedFutureTime("tomorrow at nine"));
    }

    @Test
    public void rejectsNullAndEmpty() {
        assertNull(ActionExecutor.validatedFutureTime(null));
        assertNull(ActionExecutor.validatedFutureTime(""));
    }

    // Rome is +01:00 in January (no DST), so offsets convert predictably.

    @Test
    public void normalizesMatchingOffsetToPlainLocal() {
        assertEquals("2030-01-01T10:00:00",
            ActionExecutor.validatedFutureTime("2030-01-01T10:00:00+01:00"));
    }

    @Test
    public void convertsUtcSuffixToLocal() {
        assertEquals("2030-01-01T11:00:00",
            ActionExecutor.validatedFutureTime("2030-01-01T10:00:00Z"));
    }

    @Test
    public void convertsForeignOffsetToLocal() {
        assertEquals("2030-01-01T18:00:00",
            ActionExecutor.validatedFutureTime("2030-01-01T10:00:00-07:00"));
    }

    @Test
    public void plainTimePassesThroughUnchanged() {
        assertEquals("2030-01-01T10:00:00",
            ActionExecutor.validatedFutureTime("2030-01-01T10:00:00"));
    }

    // ---- execute() branching, against an in-memory store ----

    private static AgentClient.Action action(String type) {
        AgentClient.Action a = new AgentClient.Action();
        a.type = type;
        return a;
    }

    private static String future() {
        return iso(System.currentTimeMillis() + 60 * 60_000L);
    }

    private static class CaptureSchedule implements ActionExecutor.ScheduleWriter {
        String value;
        @Override public void setSchedule(String s) { value = s; }
    }

    private static List<Receipt> run(FakeTaskStore store, AgentClient.Action a) {
        return ActionExecutor.execute(Collections.singletonList(a), store, s -> {});
    }

    @Test
    public void addTaskWithIntervalMarksRecurringAndStoresInterval() {
        FakeTaskStore store = new FakeTaskStore();
        AgentClient.Action a = action("add_task");
        a.description = "drink water";
        a.nextNudgeAt = future();
        a.recurMinutes = 1440;
        List<Receipt> r = run(store, a);

        Task t = store.tasks.get(1L);
        assertTrue(t.recurring);
        assertEquals(Integer.valueOf(1440), t.recurMinutes);
        assertEquals(future(), t.nextNudgeAt);
        assertEquals(Receipt.Kind.ADDED, r.get(0).kind);
    }

    @Test
    public void addTaskWithPastTimeIsAddedWithoutReminder() {
        FakeTaskStore store = new FakeTaskStore();
        AgentClient.Action a = action("add_task");
        a.description = "stale";
        a.nextNudgeAt = iso(System.currentTimeMillis() - 60 * 60_000L);
        List<Receipt> r = run(store, a);

        assertNull(store.tasks.get(1L).nextNudgeAt);
        assertEquals(Receipt.Kind.ADDED_TIME_REJECTED, r.get(0).kind);
    }

    @Test
    public void completeRecurringTaskIsRefused() {
        FakeTaskStore store = new FakeTaskStore();
        store.tasks.put(1L, new Task(1, "morning run", null, true, 1440));
        AgentClient.Action a = action("complete_task");
        a.id = 1;
        List<Receipt> r = run(store, a);

        assertNotNull("recurring task must survive a complete", store.tasks.get(1L));
        assertEquals(Receipt.Kind.COMPLETE_RECURRING, r.get(0).kind);
    }

    @Test
    public void completeOneTimeTaskRemovesIt() {
        FakeTaskStore store = new FakeTaskStore();
        store.tasks.put(1L, new Task(1, "buy milk", null, false));
        List<Receipt> r = run(store, withId(action("complete_task"), 1));

        assertNull(store.tasks.get(1L));
        assertEquals(Receipt.Kind.COMPLETED, r.get(0).kind);
    }

    @Test
    public void updateRecurringFalseClearsInterval() {
        FakeTaskStore store = new FakeTaskStore();
        store.tasks.put(1L, new Task(1, "stretch", null, true, 60));
        AgentClient.Action a = withId(action("update_task"), 1);
        a.recurring = false;
        List<Receipt> r = run(store, a);

        Task t = store.tasks.get(1L);
        assertFalse(t.recurring);
        assertNull("recurring:false must clear the interval", t.recurMinutes);
        assertEquals(Receipt.Kind.UPDATED, r.get(0).kind);
    }

    @Test
    public void unknownIdsProduceFailureReceiptsWithoutCrashing() {
        FakeTaskStore store = new FakeTaskStore();
        assertEquals(Receipt.Kind.UPDATE_UNKNOWN,
            run(store, withId(action("update_task"), 99)).get(0).kind);
        assertEquals(Receipt.Kind.COMPLETE_UNKNOWN,
            run(store, withId(action("complete_task"), 99)).get(0).kind);
        assertEquals(Receipt.Kind.DELETE_UNKNOWN,
            run(store, withId(action("delete_task"), 99)).get(0).kind);
        assertEquals(Receipt.Kind.SNOOZE_UNKNOWN,
            run(store, withId(action("snooze_task"), 99)).get(0).kind);
    }

    @Test
    public void snoozeMovesNextNudgeIntoTheFuture() {
        FakeTaskStore store = new FakeTaskStore();
        store.tasks.put(1L, new Task(1, "call mom", null, false));
        AgentClient.Action a = withId(action("snooze_task"), 1);
        a.minutes = 30;
        List<Receipt> r = run(store, a);

        String nudge = store.tasks.get(1L).nextNudgeAt;
        assertNotNull(nudge);
        assertTrue("snooze must be in the future",
            ActionExecutor.validatedFutureTime(nudge) != null);
        assertEquals(Receipt.Kind.SNOOZED, r.get(0).kind);
        assertEquals(30, r.get(0).minutes);
    }

    @Test
    public void updateScheduleGoesThroughTheWriter() {
        FakeTaskStore store = new FakeTaskStore();
        CaptureSchedule capture = new CaptureSchedule();
        AgentClient.Action a = action("update_schedule");
        a.schedule = "weekdays 9-18";
        List<Receipt> r = ActionExecutor.execute(Collections.singletonList(a), store, capture);

        assertEquals("weekdays 9-18", capture.value);
        assertEquals(Receipt.Kind.SCHEDULE_UPDATED, r.get(0).kind);
    }

    @Test
    public void eachMutatingActionRunsInOneTransaction() {
        FakeTaskStore store = new FakeTaskStore();
        AgentClient.Action a = action("add_task");
        a.description = "x";
        a.nextNudgeAt = future();
        run(store, a);
        assertEquals(1, store.transactions);
    }

    private static AgentClient.Action withId(AgentClient.Action a, long id) {
        a.id = id;
        return a;
    }
}
