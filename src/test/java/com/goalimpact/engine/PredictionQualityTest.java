package com.goalimpact.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionQualityTest {

    @Test
    void uninformativePredictionsScoreLnTwo() {
        PredictionQuality q = new PredictionQuality();
        q.observe(0.5);
        q.observe(0.5);

        assertEquals(Math.log(2), q.meanLogLoss(), 1e-9);
    }

    @Test
    void confidentCorrectPredictionsScoreNearZero() {
        PredictionQuality q = new PredictionQuality();
        q.observe(0.99);

        assertTrue(q.meanLogLoss() < 0.02);
    }

    @Test
    void confidentWrongPredictionsArePunished() {
        PredictionQuality q = new PredictionQuality();
        q.observe(0.01); // the side we gave 1% actually scored

        assertTrue(q.meanLogLoss() > 4.0);
    }

    @Test
    void countsTheGoalsItSaw() {
        PredictionQuality q = new PredictionQuality();
        q.observe(0.5);
        q.observe(0.7);

        assertEquals(2, q.goals());
    }

    @Test
    void noGoalsMeansNoScore() {
        assertTrue(Double.isNaN(new PredictionQuality().meanLogLoss()));
    }
}
