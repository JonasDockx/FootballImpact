package com.goalimpact.data;

import java.time.LocalDate;

// A date of birth somebody typed, with where they found it (ADR 0012, amendment
// of 2026-08-04, decisions 8 and 9). It is one row of the sidecar register read
// back for display, never a rating decision - the register always wins at replay
// time, and this record only says what it currently holds.
//
// The note may be null: it is optional and never blocks a save (#52 decision 5),
// because a date with no stated source is still better than being charged ADR
// 0016's population-average penalty. Its value is a year from now, when nothing
// else would say whether a date came from Wikipedia or from a guess.
public record TypedBirthDate(LocalDate dateOfBirth, String note) {
}
