package com.goalimpact.data;

import com.goalimpact.model.MatchEvent;
import com.goalimpact.model.Player;
import com.goalimpact.model.Team;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class EventOrderingTest {

    private static final Team ALPHA = new Team(100, "Alpha FC");
    private static final Player OFF = new Player(1, "Player Off");
    private static final Player ON = new Player(2, "Player On");
    private static final Player OTHER = new Player(3, "Someone Else");

    // Everything of labelled minute m lands at the same instant: the
    // midpoint of the minute, which is minute m-1 second 30 on the
    // playing clock (ADR 0009).
    private static int clock(int labelledMinute) {
        return (labelledMinute - 1) * 60 + 30;
    }

    private static MatchEvent.Goal goal(int labelledMinute, Player scorer) {
        return new MatchEvent.Goal(2, clock(labelledMinute) / 60, 30, ALPHA, scorer);
    }

    private static MatchEvent.Substitution substitution(int labelledMinute, Player on) {
        return new MatchEvent.Substitution(2, clock(labelledMinute) / 60, 30, ALPHA, OFF, on);
    }

    private static MatchEvent.RedCard redCard(int labelledMinute) {
        return new MatchEvent.RedCard(2, clock(labelledMinute) / 60, 30, ALPHA, OFF);
    }

    @Test
    void goalsComeBeforeSubstitutionsInTheSameMinute() {
        List<MatchEvent> events = new ArrayList<>(List.of(substitution(60, ON), goal(60, OTHER)));

        EventOrdering.sort(events);

        assertInstanceOf(MatchEvent.Goal.class, events.get(0));
        assertInstanceOf(MatchEvent.Substitution.class, events.get(1));
    }

    @Test
    void redCardsComeBeforeGoalsInTheSameMinute() {
        List<MatchEvent> events = new ArrayList<>(List.of(goal(70, OTHER), redCard(70)));

        EventOrdering.sort(events);

        assertInstanceOf(MatchEvent.RedCard.class, events.get(0));
    }

    @Test
    void aSubstituteWhoScoresArrivedFirst() {
        List<MatchEvent> events = new ArrayList<>(List.of(goal(80, ON), substitution(80, ON)));

        EventOrdering.sort(events);

        assertInstanceOf(MatchEvent.Substitution.class, events.get(0));
        assertInstanceOf(MatchEvent.Goal.class, events.get(1));
    }

    @Test
    void theExceptionSurvivesASecondGoalInTheSameMinute() {
        // The case a pairwise comparator cannot express: this second goal
        // wants to precede the substitution that must precede the first.
        List<MatchEvent> events = new ArrayList<>(
            List.of(goal(80, OTHER), goal(80, ON), substitution(80, ON)));

        EventOrdering.sort(events);

        assertInstanceOf(MatchEvent.Substitution.class, events.get(0));
        assertEquals(3, events.size());
    }

    @Test
    void minutesNeverInterleave() {
        List<MatchEvent> events = new ArrayList<>(
            List.of(substitution(45, ON), goal(46, OTHER), redCard(44)));

        EventOrdering.sort(events);

        assertEquals(43, events.get(0).minute());
        assertEquals(44, events.get(1).minute());
        assertEquals(45, events.get(2).minute());
    }

    @Test
    void ownGoalsWithNoScorerDoNotBreakTheRule() {
        List<MatchEvent> events = new ArrayList<>(List.of(substitution(30, ON), goal(30, null)));

        EventOrdering.sort(events);

        assertInstanceOf(MatchEvent.Goal.class, events.get(0));
    }
}
