package it.lo.exp.saturn;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
