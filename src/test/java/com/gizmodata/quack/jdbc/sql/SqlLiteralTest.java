package com.gizmodata.quack.jdbc.sql;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlLiteralTest {

    @Test
    void rendersNullAndPrimitives() {
        assertEquals("NULL", SqlLiteral.render(null));
        assertEquals("true", SqlLiteral.render(Boolean.TRUE));
        assertEquals("false", SqlLiteral.render(Boolean.FALSE));
        assertEquals("42", SqlLiteral.render(42));
        assertEquals("42", SqlLiteral.render(42L));
        assertEquals("7", SqlLiteral.render((short) 7));
        assertEquals("5", SqlLiteral.render((byte) 5));
    }

    @Test
    void rendersFloatingPointIncludingSpecialValues() {
        assertEquals("3.5", SqlLiteral.render(3.5d));
        assertEquals("1.5", SqlLiteral.render(1.5f));
        assertEquals("'NaN'::DOUBLE", SqlLiteral.render(Double.NaN));
        assertEquals("'Infinity'::DOUBLE", SqlLiteral.render(Double.POSITIVE_INFINITY));
        assertEquals("'-Infinity'::DOUBLE", SqlLiteral.render(Double.NEGATIVE_INFINITY));
    }

    @Test
    void rendersBigNumbersWithoutExponent() {
        assertEquals("12.34", SqlLiteral.render(new BigDecimal("12.34")));
        assertEquals("100000000000", SqlLiteral.render(new BigDecimal("1E11")));
        assertEquals("123456789012345678901234567890",
                SqlLiteral.render(new BigInteger("123456789012345678901234567890")));
    }

    @Test
    void rendersSimpleStrings() {
        assertEquals("'abc'", SqlLiteral.render("abc"));
        assertEquals("''", SqlLiteral.render(""));
    }

    @Test
    void escapesSingleQuotesToPreventInjection() {
        assertEquals("'O''Brien'", SqlLiteral.render("O'Brien"));
        String malicious = "'); DROP TABLE users; --";
        String rendered = SqlLiteral.render(malicious);
        assertEquals("'''); DROP TABLE users; --'", rendered);
        assertTrue(rendered.startsWith("'") && rendered.endsWith("'"));
        String inner = rendered.substring(1, rendered.length() - 1);
        assertFalse(inner.replace("''", "").contains("'"),
                "every embedded quote must be doubled so the literal cannot be broken out of");
    }

    @Test
    void rendersBlobAsHexLiteral() {
        byte[] bytes = {(byte) 0xCA, (byte) 0xFE, 0x00, 0x7F};
        assertEquals("'\\xCA\\xFE\\x00\\x7F'::BLOB", SqlLiteral.render(bytes));
        assertEquals("''::BLOB", SqlLiteral.render(new byte[0]));
    }

    @Test
    void rendersTemporalAndUuidTypes() {
        assertEquals("DATE '2026-05-13'", SqlLiteral.render(LocalDate.of(2026, 5, 13)));
        assertEquals("TIME '14:30:15'", SqlLiteral.render(LocalTime.of(14, 30, 15)));
        assertEquals("TIMESTAMP '2026-05-13 14:30:15.000000'",
                SqlLiteral.render(LocalDateTime.of(2026, 5, 13, 14, 30, 15)));
        assertEquals("DATE '2026-05-13'", SqlLiteral.render(java.sql.Date.valueOf("2026-05-13")));
        assertEquals("UUID '01234567-89ab-cdef-0123-456789abcdef'",
                SqlLiteral.render(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")));
    }
}
