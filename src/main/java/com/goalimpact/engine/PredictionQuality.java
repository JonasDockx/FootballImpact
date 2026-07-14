package com.goalimpact.engine;

// Mean log-loss of a rule's goal predictions: for each goal, the penalty is
// -ln(P assigned to the side that actually scored). Lower is better. An
// uninformative rule (P always 0.5) scores ln 2 = 0.6931 - beat that or the
// model has no signal.
public class PredictionQuality {

    private double logLossSum;
    private long goals;

    public void observe(double p) {
        // Clamp: fp saturation can yield exactly 0, and ln(0) is -infinity.
        logLossSum += -Math.log(Math.max(p, 1e-15));
        goals++;
    }

    public double meanLogLoss() {
        if (goals == 0) {
            return Double.NaN;
        }
        return logLossSum / goals;
    }

    public long goals() { return goals; }
}
