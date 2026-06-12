package it.lo.exp.saturn;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ActionExecutorTest {

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
}
