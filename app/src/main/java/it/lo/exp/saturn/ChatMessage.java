package it.lo.exp.saturn;

public class ChatMessage {
    public static final int ROLE_USER = 0;
    public static final int ROLE_BOT = 1;

    public final int role;
    public final String content;

    public ChatMessage(int role, String content) {
        this.role = role;
        this.content = content;
    }
}
