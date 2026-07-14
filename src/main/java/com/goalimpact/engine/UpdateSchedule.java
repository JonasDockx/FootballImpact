package com.goalimpact.engine;

// Maps a player's exposure (career minutes on pitch, frozen at kickoff) to
// the update factor K applied to their summed match residuals at the final
// whistle (ADR 0006). Implementations must return a positive factor.

public interface UpdateSchedule {
    double factor(double careerMinutes);
}
