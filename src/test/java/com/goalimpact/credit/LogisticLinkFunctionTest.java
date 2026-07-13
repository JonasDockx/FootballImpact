package com.goalimpact.credit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogisticLinkFunctionTest {
    
    @Test
    void equalStrengthsAreACoinFlip() {
        LinkFunction f = new LogisticLinkFunction(1.0);
        assertEquals(0.5, f.expected(0.0), 1e-9);
    }

    @Test
    void knownValueAtGapOne() {
        LinkFunction f = new LogisticLinkFunction(1.0);
        assertEquals(0.73105857863000049, f.expected(1.0), 1e-9);
    }

    @Test
    void symmetricAroundZero() {
        LinkFunction f = new LogisticLinkFunction(1.0);
        assertEquals(1.0, f.expected(0.3) + f.expected(-0.3), 1e-9);
        assertEquals(1.0, f.expected(1.0) + f.expected(-1.0), 1e-9);
        assertEquals(1.0, f.expected(5.0) + f.expected(-5.0), 1e-9);
    }

    @Test
    void monotonicInGap() {
        LinkFunction f = new LogisticLinkFunction(1.0);
        assertTrue(f.expected(2.0) > f.expected(1.0));
        assertTrue(f.expected(1.0) > f.expected(0.5));
    }

    @Test
    void higherGainSteepensTheCurve() {
        LinkFunction steep = new LogisticLinkFunction(2.0);
        LinkFunction gentle = new LogisticLinkFunction(1.0);
        assertTrue(steep.expected(1.0) > gentle.expected(1.0));
    }

    @Test
    void staysStrictlyBelowOne() {
        LinkFunction f = new LogisticLinkFunction(1.0);
        assertTrue(f.expected(30.0) < 1.0);
    }

    @Test
    void rejectsNonPositiveGain() {
        assertThrows(IllegalArgumentException.class, () -> new LogisticLinkFunction(0.0));
        assertThrows(IllegalArgumentException.class, () -> new LogisticLinkFunction(-1.0));
    }
}
