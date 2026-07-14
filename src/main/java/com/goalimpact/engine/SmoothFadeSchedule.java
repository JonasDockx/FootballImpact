package com.goalimpact.engine;

// The exposure-based update factor of ADR 0006: K(m) = K0 * H / (H + m),
// never below floorFraction * K0. H (halvingMinutes) is the exposure at which
// updates halve; floorFraction = 1.0 switches the fade off entirely - that is
// the uniform-K baseline the grid search compares against.

public final class SmoothFadeSchedule implements UpdateSchedule {
    
    private final double k0;
    private final double halvingMinutes;
    private final double floorFraction;

    public SmoothFadeSchedule(double k0, double halvingMinutes, double floorFraction) {
        if (k0 <= 0) {
            throw new IllegalArgumentException("starting factor K0 must be positive, was " + k0);
        }
        if (halvingMinutes <= 0) {
            throw new IllegalArgumentException("halving exposure H must be positive, was " + halvingMinutes);
        }
        if (floorFraction <= 0 || floorFraction > 1) {
            throw new IllegalArgumentException("floor fraction must be in [0, 1], was " + floorFraction);
        }
        this.k0 = k0;
        this.halvingMinutes = halvingMinutes;
        this.floorFraction = floorFraction;
    }

    @Override
    public double factor(double minutes) {
        double fade = k0 * halvingMinutes / (halvingMinutes + minutes);
        return Math.max(fade, floorFraction * k0);
    }
}
