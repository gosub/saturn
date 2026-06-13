package it.lo.exp.saturn;

import android.content.Context;

import java.util.List;

/** Formats structured {@link Receipt}s into localized status lines. Shared by
 *  the chat and task screens so the wording lives in one place. */
public class ReceiptFormatter {

    static String join(Context ctx, List<Receipt> receipts) {
        StringBuilder sb = new StringBuilder();
        for (Receipt r : receipts) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(format(ctx, r));
        }
        return sb.toString();
    }

    static String format(Context ctx, Receipt r) {
        switch (r.kind) {
            case ADDED:
                return ctx.getString(R.string.receipt_added, r.text, r.time, intervalSuffix(ctx, r.recurMinutes));
            case ADDED_NO_TIME:
                return ctx.getString(R.string.receipt_added_no_time, r.text);
            case ADDED_TIME_REJECTED:
                return ctx.getString(R.string.receipt_added_time_rejected, r.rejectedTime, r.text);
            case UPDATED:
                return r.time != null
                    ? ctx.getString(R.string.receipt_updated_time, r.text, r.time, intervalSuffix(ctx, r.recurMinutes))
                    : ctx.getString(R.string.receipt_updated, r.text);
            case UPDATED_TIME_REJECTED:
                return ctx.getString(R.string.receipt_updated_time_rejected, r.rejectedTime, r.text);
            case UPDATE_UNKNOWN:
                return ctx.getString(R.string.receipt_update_unknown, r.id);
            case COMPLETED:
                return ctx.getString(R.string.receipt_completed, r.text);
            case COMPLETE_RECURRING:
                return ctx.getString(R.string.receipt_complete_recurring, r.text);
            case COMPLETE_UNKNOWN:
                return ctx.getString(R.string.receipt_complete_unknown, r.id);
            case DELETED:
                return ctx.getString(R.string.receipt_deleted, r.text);
            case DELETE_UNKNOWN:
                return ctx.getString(R.string.receipt_delete_unknown, r.id);
            case SCHEDULE_UPDATED:
                return ctx.getString(R.string.receipt_schedule_updated, r.text);
            case SNOOZED:
                return ctx.getString(R.string.receipt_snoozed, r.minutes, r.text);
            case SNOOZE_UNKNOWN:
                return ctx.getString(R.string.receipt_snooze_unknown, r.id);
        }
        return "";
    }

    private static String intervalSuffix(Context ctx, Integer minutes) {
        if (minutes == null || minutes <= 0) return "";
        return ctx.getString(R.string.interval_suffix, ActionExecutor.formatInterval(minutes));
    }
}
