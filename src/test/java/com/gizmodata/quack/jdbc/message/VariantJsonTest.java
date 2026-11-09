package com.gizmodata.quack.jdbc.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Text formats VARIANT values render with; each expectation is what
 * DuckDB 2.0's own {@code variant::JSON} cast printed for the same value.
 */
class VariantJsonTest {

    @Test
    void doublesFollowYyjson() {
        assertEquals("1.5", VariantJson.formatDouble(1.5));
        assertEquals("100000000000000000000.0", VariantJson.formatDouble(1e20));
        assertEquals("1e21", VariantJson.formatDouble(1e21));
        assertEquals("1e-7", VariantJson.formatDouble(1e-7));
        assertEquals("0.000001", VariantJson.formatDouble(1e-6));
        assertEquals("123456789012.0", VariantJson.formatDouble(123456789012.0));
        assertEquals("-1.5e300", VariantJson.formatDouble(-1.5e300));
        assertEquals("0.10000000149011612", VariantJson.formatDouble(0.1f));
        assertEquals("123.45600128173828", VariantJson.formatDouble(123.456f));
        assertEquals("NaN", VariantJson.formatDouble(Double.NaN));
        assertEquals("Infinity", VariantJson.formatDouble(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", VariantJson.formatDouble(Double.NEGATIVE_INFINITY));
        assertEquals("0.0", VariantJson.formatDouble(0.0));
    }

    @Test
    void temporalTextMatchesDuckDb() {
        assertEquals("2024-01-02", VariantJson.formatDate(19724));
        assertEquals("0044-03-15 (BC)", VariantJson.formatDate((int) java.time.LocalDate.of(-43, 3, 15).toEpochDay()));
        assertEquals("infinity", VariantJson.formatDate(Integer.MAX_VALUE));
        assertEquals("12:34:56.789", VariantJson.formatTimeOfDay(45_296_789_000L, 6));
        assertEquals("12:00:00", VariantJson.formatTimeOfDay(43_200_000_000L, 6));
        assertEquals("24:00:00", VariantJson.formatTimeOfDay(86_400_000_000L, 6));
        assertEquals("12:34:56.1", VariantJson.formatTimeOfDay(45_296_100_000_000L, 9));
        assertEquals("2024-01-02 03:04:05.5",
                VariantJson.formatTimestamp(1_704_164_645_500_000L, 1_000_000L, 6));
        assertEquals("2024-01-02 03:04:05", VariantJson.formatTimestamp(1_704_164_645L, 1L, 0));
        assertEquals("-infinity", VariantJson.formatTimestamp(-Long.MAX_VALUE, 1_000_000L, 6));
        long bits = (3_723_000_000L << 24) | (16 * 60 * 60 - 1 - (-(8 * 3600 + 30)));
        assertEquals("01:02:03-08:00:30", VariantJson.formatTimeTz(bits));
    }

    @Test
    void intervalsMatchDuckDb() {
        assertEquals("1 year 2 months 3 days 04:05:06.7", VariantJson.formatInterval(14, 3, 14_706_700_000L));
        assertEquals("-1 day", VariantJson.formatInterval(0, -1, 0));
        assertEquals("00:00:00", VariantJson.formatInterval(0, 0, 0));
        assertEquals("-03:02:00", VariantJson.formatInterval(0, 0, -10_920_000_000L));
        assertEquals("30 days", VariantJson.formatInterval(0, 30, 0));
        assertEquals("-9 months", VariantJson.formatInterval(-9, 0, 0));
    }
}
