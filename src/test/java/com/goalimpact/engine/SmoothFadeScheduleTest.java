package com.goalimpact.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SmoothFadeScheduleTest {
    
    @Test
    void aDebutantMovesAtFullStrength() {
        SmoothFadeSchedule schedule = new SmoothFadeSchedule(1.0, 1000.0, 0.05);
        assertEquals(1.0, schedule.factor(0.0), 1e-9);
    }

    @Test
    void updatesHalveAtTheHalvingExposureAndKeepFading() {
        // K(m) = K0 * H / (H + m): half size at m = H, a quarter at m = 3H.
        SmoothFadeSchedule schedule = new SmoothFadeSchedule(1.0, 1000.0, 0.05);
        assertEquals(0.5, schedule.factor(1000.0), 1e-9);
        assertEquals(0.25, schedule.factor(3000.0), 1e-9);
    }

    @Test
    void neverFadesBelowTheFloor() {
        // Unfloored, a 45,000-minute career fades to ~0.02 - below the 5% floor
        SmoothFadeSchedule schedule = new SmoothFadeSchedule(1.0, 1000.0, 0.05);
        assertEquals(0.05, schedule.factor(45000.0), 1e-9);
        assertEquals(0.05, schedule.factor(1_000_000.0), 1e-9);
    }

    @Test
    void floorFractionOfOneIsUniformK() {
        // The grid search embeds the uniform-K baseline as floorFraction = 1.0
        // (ADR 0006) the fade must vanish entirely.
        SmoothFadeSchedule schedule = new SmoothFadeSchedule(0.25, 1000.0, 1.0);
        assertEquals(0.25, schedule.factor(0.0), 1e-9);
        assertEquals(0.25, schedule.factor(9000.0), 1e-9);
    }

    @Test
    void rejectNonsenseKnobs() {
        assertThrows(IllegalArgumentException.class, () -> new SmoothFadeSchedule(0.0, 1000.0, 0.05));
        assertThrows(IllegalArgumentException.class, () -> new SmoothFadeSchedule(1.0, 0.0, 0.05));
        assertThrows(IllegalArgumentException.class, () -> new SmoothFadeSchedule(1.0, 1000.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new SmoothFadeSchedule(1.0, 1000.0, 1.1));
    }
}
