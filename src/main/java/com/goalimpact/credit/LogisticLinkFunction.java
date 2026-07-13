package com.goalimpact.credit;

// P = 1 / (1 + e^(-l * gap)) - the logistic curve, gain k controls steepness.

public final class LogisticLinkFunction implements LinkFunction {
    
    private final double k;

    public LogisticLinkFunction(double k) {
        if (k <= 0) {
            throw new IllegalArgumentException("gain k must be positive, was " + k);
        }
        this.k = k;
    }

    @Override
    public double expected(double strengthGap) {
        return 1.0 / (1.0 + Math.exp(-k * strengthGap));
    }
}
