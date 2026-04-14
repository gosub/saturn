package it.lo.exp.saturn;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
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
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).role;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ChatMessage msg = getItem(position);
        int layout = (msg.role == ChatMessage.ROLE_USER)
            ? R.layout.item_message_user
            : R.layout.item_message_bot;

        if (convertView == null) {
            convertView = inflater.inflate(layout, parent, false);
        }

        TextView tv = convertView.findViewById(R.id.message_text);
        tv.setText(msg.content);
        return convertView;
    }
}
