package it.lo.exp.saturn;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentClientTest {

    // ---- stripCodeFences ----

    @Test
    public void stripCodeFencesPassesPlainJsonThrough() {
        assertEquals("{\"reply\": \"hi\"}",
            AgentClient.stripCodeFences("{\"reply\": \"hi\"}"));
    }

    @Test
    public void stripCodeFencesRemovesJsonFence() {
        assertEquals("{\"reply\": \"hi\"}",
            AgentClient.stripCodeFences("```json\n{\"reply\": \"hi\"}\n```"));
    }

    @Test
    public void stripCodeFencesRemovesBareFence() {
        assertEquals("{}", AgentClient.stripCodeFences("```\n{}\n```"));
    }

    @Test
    public void stripCodeFencesTrimsWhitespace() {
        assertEquals("{}", AgentClient.stripCodeFences("  {}  \n"));
    }

    @Test
    public void stripCodeFencesHandlesNull() {
        assertEquals(null, AgentClient.stripCodeFences(null));
    }

    // ---- buildChatPrompt ----

    @Test
    public void chatPromptListsTasksWithIdsAndRecurrenceMarker() {
        List<Task> tasks = Arrays.asList(
            new Task(1, "buy milk", "2026-06-13T09:00:00", false),
            new Task(2, "morning run", "2026-06-13T07:00:00", true));
        String p = AgentClient.buildChatPrompt("en", "weekdays 9-18", tasks,
            System.currentTimeMillis());
        assertTrue(p.contains("Active tasks (2):"));
        assertTrue(p.contains("1. buy milk"));
        assertTrue(p.contains("2. ↻ morning run"));
        assertTrue(p.contains("weekdays 9-18"));
        assertTrue(p.contains("Respond ONLY with a JSON object"));
    }

    @Test
    public void chatPromptUsesItalianWhenConfigured() {
        String p = AgentClient.buildChatPrompt("it", "", Collections.emptyList(),
            System.currentTimeMillis());
        assertTrue(p.contains("Italian"));
        assertTrue(p.contains("Active tasks: none"));
    }

    // ---- buildNudgePrompt ----

    @Test
    public void nudgePromptMarksLateTasks() {
        long now = System.currentTimeMillis();
        String scheduled = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",
            java.util.Locale.US).format(new java.util.Date(now - 60 * 60_000L));
        List<Task> due = Collections.singletonList(new Task(7, "call mom", scheduled, false));
        String p = AgentClient.buildNudgePrompt("en", "", due, now);
        assertTrue(p.contains("7. call mom"));
        assertTrue(p.contains("[LATE by"));
    }

    @Test
    public void nudgePromptDoesNotOfferCompleteOrDelete() {
        List<Task> due = Collections.singletonList(
            new Task(1, "buy milk", "2026-06-13T09:00:00", false));
        String p = AgentClient.buildNudgePrompt("en", "", due, System.currentTimeMillis());
        assertFalse(p.contains("complete_task ("));
        assertFalse(p.contains("delete_task ("));
        assertTrue(p.contains("snooze_task"));
    }

    // ---- buildEditTaskPrompt ----

    @Test
    public void editPromptScopesToTheSingleTaskId() {
        Task task = new Task(5, "water the plants", "2026-06-13T18:00:00", true, 1440);
        String p = AgentClient.buildEditTaskPrompt("en", "weekdays 9-18", task,
            System.currentTimeMillis());
        assertTrue(p.contains("↻ water the plants"));
        assertTrue(p.contains("(every 1440 min)"));
        // Every offered action is bound to this id.
        assertTrue(p.contains("\"id\": 5"));
        assertTrue(p.contains("Do not add or touch any other task."));
        assertFalse(p.contains("add_task"));
    }

    @Test
    public void nudgePromptRequestsOneNudgePerTaskWithIds() {
        List<Task> due = Arrays.asList(
            new Task(1, "buy milk", "2026-06-13T09:00:00", false),
            new Task(2, "call mom", "2026-06-13T09:00:00", false));
        String p = AgentClient.buildNudgePrompt("en", "", due, System.currentTimeMillis());
        assertTrue(p.contains("one short nudge per task"));
        assertTrue(p.contains("\"nudges\""));
        // The single-reply schema is gone; each nudge carries its task id.
        assertFalse(p.contains("\"reply\""));
    }
}
