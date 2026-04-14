package it.lo.exp.saturn;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;

public class ChatAdapter extends ArrayAdapter<ChatMessage> {

    private final LayoutInflater inflater;

    public ChatAdapter(Context context, List<ChatMessage> messages) {
        super(context, 0, messages);
        inflater = LayoutInflater.from(context);
    }

    @Override
    public int getViewTypeCount() {
        return 4;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).role;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ChatMessage msg = getItem(position);

        switch (msg.role) {
            case ChatMessage.ROLE_TYPING: {
                if (convertView == null) {
                    convertView = inflater.inflate(R.layout.item_message_typing, parent, false);
                }
                TextView text     = convertView.findViewById(R.id.typing_text);
                ProgressBar bar   = convertView.findViewById(R.id.typing_progress);
                TextView cancel   = convertView.findViewById(R.id.typing_cancel);
                text.setText(msg.content);
                if (msg.maxProgress > 0) {
                    bar.setVisibility(View.VISIBLE);
                    bar.setProgress(msg.progress);
                    if (msg.onCancel != null) {
                        cancel.setVisibility(View.VISIBLE);
                        cancel.setOnClickListener(v -> msg.onCancel.run());
                    } else {
                        cancel.setVisibility(View.GONE);
                    }
                } else {
                    bar.setVisibility(View.GONE);
                    cancel.setVisibility(View.GONE);
                }
                return convertView;
            }
            case ChatMessage.ROLE_SYSTEM: {
                if (convertView == null) {
                    convertView = inflater.inflate(R.layout.item_message_system, parent, false);
                }
                ((TextView) convertView.findViewById(R.id.message_text)).setText(msg.content);
                return convertView;
            }
            default: {
                int layout = (msg.role == ChatMessage.ROLE_USER)
                    ? R.layout.item_message_user
                    : R.layout.item_message_bot;
                if (convertView == null) {
                    convertView = inflater.inflate(layout, parent, false);
                }
                ((TextView) convertView.findViewById(R.id.message_text)).setText(msg.content);
                return convertView;
            }
        }
    }
}
