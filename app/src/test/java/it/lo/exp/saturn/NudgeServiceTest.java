package it.lo.exp.saturn;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

public class NudgeServiceTest {

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

    private static long epoch(String iso) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso).getTime();
    }

    @Test
    public void nextOccurrenceStaysAnchoredWhenCycleRunsLate() throws Exception {
        // daily 09:00 task fires late at 09:37 — next is tomorrow 09:00, not 09:37
        String next = NudgeService.nextOccurrence(
            "2030-01-01T09:00:00", 1440, epoch("2030-01-01T09:37:00"));
        assertEquals("2030-01-02T09:00:00", next);
    }

    @Test
    public void nextOccurrenceSkipsMissedIntervals() throws Exception {
        // missed for three days: next future occurrence, still on the 09:00 grid
        String next = NudgeService.nextOccurrence(
            "2030-01-01T09:00:00", 1440, epoch("2030-01-04T14:00:00"));
        assertEquals("2030-01-05T09:00:00", next);
    }

    @Test
    public void nextOccurrenceHourlyGrid() throws Exception {
        String next = NudgeService.nextOccurrence(
            "2030-01-01T09:00:00", 60, epoch("2030-01-01T09:10:00"));
        assertEquals("2030-01-01T10:00:00", next);
    }

    @Test
    public void nextOccurrenceFallsBackOnBadAnchor() throws Exception {
        String next = NudgeService.nextOccurrence(
            "garbage", 60, epoch("2030-01-01T09:00:00"));
        assertEquals("2030-01-01T10:00:00", next);
    }

    @Test
    public void formatIntervalPicksLargestUnit() {
        assertEquals("1d", ActionExecutor.formatInterval(1440));
        assertEquals("2d", ActionExecutor.formatInterval(2880));
        assertEquals("3h", ActionExecutor.formatInterval(180));
        assertEquals("45m", ActionExecutor.formatInterval(45));
    }
}
