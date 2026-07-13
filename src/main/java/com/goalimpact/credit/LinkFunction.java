package com.goalimpact.credit;

// Maps the strength gap between two lineups to the expected probability
// that a goal belongs to the first (scoring) side.
// Contract: results is strictly between 0 and 1 for representable gaps,
// expected(0) == 0.5. At extreme gaps the value may saturate to exactly
// 0.0 or 1.0 in double arithmetic. This is harmless, since the resiudal (1 - P)
// correctly goes to zero there.

public interface LinkFunction {
    double expected(double strengthGap);
}
