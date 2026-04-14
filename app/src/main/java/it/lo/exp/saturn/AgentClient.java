package it.lo.exp.saturn;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.TimeZone;

public class AgentClient {

    private static final String TAG = "Saturn";
    private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final int MAX_HISTORY = 20;
    private static final Gson GSON = new Gson();

    // ---- JSON models ----

    public static class Message {
        public String role;
        public String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public static class Action {
        public String type;
        public String description;
        public long id;
        @SerializedName("next_nudge_at") public String nextNudgeAt;
        public String schedule;
        public boolean recurring;
    }

    public static class AgentResponse {
        public String reply;
        public List<Action> actions;
    }

    public static class RateLimitException extends IOException {
        public final int retryAfterSeconds;
        public RateLimitException(int seconds) {
            super("rate limited, retry after " + seconds + "s");
            this.retryAfterSeconds = seconds;
        }
    }

    private static class ChatRequest {
        String model;
        List<Message> messages;

        ChatRequest(String model, List<Message> messages) {
            this.model = model;
            this.messages = messages;
        }
    }

    private static class Choice {
        Message message;
    }

    private static class ChatResponse {
        List<Choice> choices;
    }

    // ---- HTTP ----

    public AgentResponse chat(String apiKey, String model, String systemPrompt,
                              List<Message> history, String userMessage) throws IOException {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));
        if (history != null) messages.addAll(history);
        messages.add(new Message("user", userMessage));

        String requestBody = GSON.toJson(new ChatRequest(model, messages));
        Log.d(TAG, "openrouter request: model=" + model + " body_bytes=" + requestBody.length());

        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        Scanner scanner = new Scanner(
            status == 200 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8");
        StringBuilder sb = new StringBuilder();
        while (scanner.hasNextLine()) sb.append(scanner.nextLine());
        scanner.close();

        if (status == 429) {
            String retryAfter = conn.getHeaderField("Retry-After");
            int seconds = 30;
            if (retryAfter != null) {
                try { seconds = Integer.parseInt(retryAfter.trim()); } catch (NumberFormatException ignored) {}
            }
            Log.d(TAG, "rate limited, retry after " + seconds + "s");
            throw new RateLimitException(seconds);
        }

        if (status != 200) {
            throw new IOException("API error " + status + ": " + sb);
        }

        ChatResponse chatResp = GSON.fromJson(sb.toString(), ChatResponse.class);
        if (chatResp.choices == null || chatResp.choices.isEmpty()) {
            throw new IOException("No choices in response");
        }

        String content = stripCodeFences(chatResp.choices.get(0).message.content);
        Log.d(TAG, "openrouter response: bytes=" + content.length());

        try {
            AgentResponse resp = GSON.fromJson(content, AgentResponse.class);
            if (resp.actions == null) resp.actions = new ArrayList<>();
            return resp;
        } catch (JsonSyntaxException e) {
            Log.w(TAG, "response not valid JSON, using as plain reply");
            AgentResponse fallback = new AgentResponse();
            fallback.reply = content;
            fallback.actions = new ArrayList<>();
            return fallback;
        }
    }

    // ---- Prompt builders ----

    public static String buildChatPrompt(String language, String schedule,
                                          List<Task> tasks, long nowMillis, String timezone) {
        String now = formatNow(nowMillis, timezone);
        StringBuilder sb = new StringBuilder();
        sb.append("You are Saturn, an intelligent task and nudge assistant.\n");
        sb.append("You help the user track tasks, remember commitments, and get things done.\n");
        sb.append("Be concise and direct.\n");
        sb.append("Always respond in ").append(langName(language)).append(".\n\n");
        sb.append("Current time: ").append(now).append("\n\n");
        if (schedule != null && !schedule.isEmpty()) {
            sb.append("User's schedule: ").append(schedule).append("\n\n");
        } else {
            sb.append("User's schedule: not set\n\n");
        }
        if (tasks == null || tasks.isEmpty()) {
            sb.append("Active tasks: none\n\n");
        } else {
            sb.append("Active tasks (").append(tasks.size()).append("):\n");
            for (Task t : tasks) {
                String nudge = (t.nextNudgeAt != null && !t.nextNudgeAt.isEmpty())
                    ? t.nextNudgeAt : "not set";
                String prefix = t.recurring ? "\u21bb " : "";
                sb.append("  ").append(t.id).append(". ").append(prefix)
                  .append(t.description).append(" \u2014 next nudge: ").append(nudge).append("\n");
            }
            sb.append("\n");
        }
        sb.append("Respond ONLY with a JSON object: {\"reply\": \"...\", \"actions\": [...]}\n");
        sb.append("No text outside the JSON. If no actions are needed, use \"actions\": [].\n\n");
        sb.append("Available actions:\n");
        sb.append("  {\"type\": \"add_task\",        \"description\": \"...\", \"next_nudge_at\": \"ISO8601\", \"recurring\": true}\n");
        sb.append("  {\"type\": \"update_task\",     \"id\": N, \"description\": \"...\", \"next_nudge_at\": \"ISO8601\", \"recurring\": true}\n");
        sb.append("  {\"type\": \"complete_task\",   \"id\": N}\n");
        sb.append("  {\"type\": \"delete_task\",     \"id\": N}\n");
        sb.append("  {\"type\": \"update_schedule\", \"schedule\": \"...\"}\n");
        sb.append("Always use numeric id from the task list. Include next_nudge_at when adding a timed task.\n");
        sb.append("next_nudge_at must be ISO 8601 (e.g. 2026-03-21T09:00:00). Respect the user's schedule.\n");
        sb.append("Set recurring: true for habitual/repeating tasks.\n");
        sb.append("Recurring tasks (\u21bb) must never be completed \u2014 use update_task with the next next_nudge_at.\n");
        return sb.toString();
    }

    public static String buildNudgePrompt(String language, String schedule,
                                           List<Task> tasks, long nowMillis, String timezone) {
        String now = formatNow(nowMillis, timezone);
        StringBuilder sb = new StringBuilder();
        sb.append("You are Saturn, a nudge agent.\n");
        sb.append("Current time: ").append(now).append("\n");
        if (schedule != null && !schedule.isEmpty()) {
            sb.append("User's schedule: ").append(schedule).append("\n\n");
        }
        sb.append("Always respond in ").append(langName(language)).append(".\n\n");
        sb.append("The following tasks are due for a nudge:\n");
        for (Task t : tasks) {
            String prefix = t.recurring ? "\u21bb " : "";
            sb.append("  ").append(t.id).append(". ").append(prefix).append(t.description).append("\n");
        }
        sb.append("\nSend the user a short nudge. One task, one sentence, no fluff.\n");
        sb.append("If multiple tasks are due, pick the most urgent one.\n");
        sb.append("After nudging a recurring task (\u21bb), always use update_task to set the next next_nudge_at.\n");
        sb.append("If no nudge is appropriate right now, return empty reply.\n\n");
        sb.append("Respond: {\"reply\": \"...\", \"actions\": [...]}\n");
        sb.append("Actions: update_task (id, description optional, next_nudge_at optional), complete_task (id), delete_task (id).\n");
        return sb.toString();
    }

    public static String buildSchedulePrompt(String language, String schedule,
                                              List<Task> tasks, long nowMillis, String timezone) {
        String now = formatNow(nowMillis, timezone);
        StringBuilder sb = new StringBuilder();
        sb.append("You are Saturn, a scheduling assistant.\n");
        sb.append("Current time: ").append(now).append("\n");
        if (schedule != null && !schedule.isEmpty()) {
            sb.append("User's schedule: ").append(schedule).append("\n\n");
        }
        sb.append("Always respond in ").append(langName(language)).append(".\n\n");
        sb.append("The following tasks have no scheduled reminder time:\n");
        for (Task t : tasks) {
            sb.append("  ").append(t.id).append(". ").append(t.description).append("\n");
        }
        sb.append("\nAssign each task a next_nudge_at using update_task actions.\n");
        sb.append("Base the time on the task description and the user's schedule. If unclear, schedule within 24 hours.\n");
        sb.append("Respond: {\"reply\": \"\", \"actions\": [...]}\n");
        sb.append("Actions: update_task (id, next_nudge_at required). No reply text.\n");
        return sb.toString();
    }

    // ---- History helpers ----

    public static List<Message> loadHistory(String json) {
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            Message[] arr = GSON.fromJson(json, Message[].class);
            List<Message> list = new ArrayList<>();
            for (Message m : arr) list.add(m);
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static String saveHistory(List<Message> history, String userMsg, String reply) {
        if (userMsg != null && !userMsg.isEmpty()) {
            history.add(new Message("user", userMsg));
        }
        if (reply != null && !reply.isEmpty()) {
            history.add(new Message("assistant", reply));
        }
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        return GSON.toJson(history);
    }

    // ---- Helpers ----

    private static String stripCodeFences(String s) {
        if (s == null) return s;
        s = s.trim();
        if (s.startsWith("```")) {
            int newline = s.indexOf('\n');
            if (newline != -1) s = s.substring(newline + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }
        return s;
    }

    private static String formatNow(long millis, String timezone) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss (EEEE)", Locale.ENGLISH);
        if (timezone != null && !timezone.isEmpty()) {
            sdf.setTimeZone(TimeZone.getTimeZone(timezone));
        }
        return sdf.format(new Date(millis));
    }

    private static String langName(String code) {
        if ("it".equals(code)) return "Italian";
        return "English";
    }
}
