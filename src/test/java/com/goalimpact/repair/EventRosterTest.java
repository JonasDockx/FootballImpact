package com.goalimpact.repair;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Item 17, slice 2, decision 1 and 8. Who a match's own events prove was
// playing, and which side he was on - the pure half of the derived lineup
// (glossary 'Derived lineup'), tested on plain event lists with no database.
class EventRosterTest {

    private static final long HOME = 100L;
    private static final long AWAY = 200L;

    private static EventRow sub(long club, long off, long on) {
        return new EventRow(1L, 60, EventRow.SUBSTITUTION, club, off, on, ", Tactical");
    }

    private static EventRow goal(long club, long scorer, String description) {
        return new EventRow(1L, 30, "Goals", club, scorer, null, description);
    }

    @Test
    void aSubstitutionNamesBothMenForTheClubThatMadeIt() {
        List<EventRoster.Slot> roster =
            EventRoster.from(List.of(sub(HOME, 11L, 12L)), HOME, AWAY);

        assertEquals(List.of(new EventRoster.Slot(HOME, 11L), new EventRoster.Slot(HOME, 12L)),
            roster);
    }

    // Decision 8. A Goals row carries the *scoring* club, so an own goal is the
    // one event type whose club_id is not the player's - measured wrong on 3.0%
    // of goal rows against real team sheets, against 0.03-0.13% everywhere else.
    @Test
    void anOwnGoalPutsTheScorerOnTheConcedingSide() {
        List<EventRoster.Slot> roster =
            EventRoster.from(List.of(goal(HOME, 21L, ", Own-goal")), HOME, AWAY);

        assertEquals(List.of(new EventRoster.Slot(AWAY, 21L)), roster);
    }

    @Test
    void anOrdinaryGoalLeavesTheScorerWithHisOwnClub() {
        List<EventRoster.Slot> roster =
            EventRoster.from(List.of(goal(HOME, 21L, ", Left-footed shot")), HOME, AWAY);

        assertEquals(List.of(new EventRoster.Slot(HOME, 21L)), roster);
    }

    // A busy player is named by several events; he is still one man.
    @Test
    void aManNamedByThreeEventsTakesOneSlot() {
        List<EventRoster.Slot> roster = EventRoster.from(List.of(
            goal(HOME, 21L, ", Left-footed shot"),
            new EventRow(1L, 40, "Cards", HOME, 21L, null, "1. Yellow card"),
            sub(HOME, 21L, 12L)), HOME, AWAY);

        assertEquals(List.of(new EventRoster.Slot(HOME, 21L), new EventRoster.Slot(HOME, 12L)),
            roster);
    }

    // The flip is not cosmetic: it is what stops one footballer becoming two
    // lineup rows on opposite sides, which would make both XIs count wrong.
    // Measured over the whole maybe set (2026-07-28): unflipped, 58 men land on
    // both sides of their match; flipped, zero do - and the slot count falls by
    // exactly those 58. This test is that shape.
    @Test
    void aManHisClubAlsoNamesIsNotDuplicatedOntoTheOtherSideByAnOwnGoal() {
        List<EventRoster.Slot> roster = EventRoster.from(List.of(
            sub(AWAY, 30L, 21L),
            goal(HOME, 21L, ", Own-goal")), HOME, AWAY);

        assertEquals(List.of(new EventRoster.Slot(AWAY, 30L), new EventRoster.Slot(AWAY, 21L)),
            roster);
    }
}
