package it.lo.exp.saturn;

public class ChatMessage {
    public static final int ROLE_USER   = 0;
    public static final int ROLE_BOT    = 1;
    public static final int ROLE_TYPING = 2;
    public static final int ROLE_SYSTEM = 3;

    public final int role;
    public String content;
    public int progress;    // 0-100, used when maxProgress > 0
    public int maxProgress; // > 0 means countdown bar is visible
    public Runnable onCancel; // set during retry countdown

    public ChatMessage(int role, String content) {
        this.role = role;
        this.content = content;
    }
}
