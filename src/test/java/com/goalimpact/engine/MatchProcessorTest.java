package com.goalimpact.engine;

import com.goalimpact.credit.FlatCreditRule;
import com.goalimpact.credit.LogisticLinkFunction;
import com.goalimpact.credit.ResidualCreditRule;
import com.goalimpact.model.MatchEvent;
import com.goalimpact.model.Player;
import com.goalimpact.model.Team;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MatchProcessorTest {
    
    private static final Team TEAM_A = new Team(1, "Team A");
    private static final Team TEAM_B = new Team(2, "Team B");

    // Players 1-11 play for Team A, 12-23 for Team B, in all tests.
    private static Player player(long id) {
        return new Player(id, "Player " + id);
    }

    private static MatchEvent.StartingXI xi(Team team, long... ids) {
        List<Player> players = new java.util.ArrayList<>();
        for (long id : ids) {
            players.add(player(id));
        }
        return new MatchEvent.StartingXI(1, 0, 0, team, players);
    }

    private static MatchEvent.Goal goal(int minute, Team scoringTeam) {
        return new MatchEvent.Goal(1, minute, 0, scoringTeam, null);
    }

    private static MatchEvent.Substitution sub(int minute, Team team, long offId, long onId) {
        return new MatchEvent.Substitution(1, minute, 0, team, player(offId), player(onId));
    }

    private static double rating(Map<Long, PlayerTally> tallies, long id) {
        return tallies.get(id).rating();
    }

    @Test
    void flatRuleWithOneMatchesOriginalBehaviour() {
        MatchProcessor processor = new MatchProcessor(new FlatCreditRule(), 1.0);
        Map<Long, PlayerTally> tallies = new HashMap<>();

        processor.process(List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            goal(30, TEAM_A)
        ), tallies);

        assertEquals(1.0, rating(tallies, 1), 1e-9);
        assertEquals(1.0, rating(tallies, 2), 1e-9);
        assertEquals(-1.0, rating(tallies, 12), 1e-9);
        assertEquals(-1.0, rating(tallies, 13), 1e-9);
    }

    @Test
    void ratingsAreFrozenWithinMatch() {
        // Two identical goals in one match. Against all frozen all-zero ratings each
        // is worth exactly 0.5, so each scorer ends on exactly 1.0. If the first
        // goal's residual leaked into the second goal's expectation, the second
        // would be worth less than 0.5 and this total would be below 1.0.

        MatchProcessor processor = 
            new MatchProcessor(new ResidualCreditRule(new LogisticLinkFunction(1.0)), 1.0);
        Map<Long, PlayerTally> tallies = new HashMap<>();

        processor.process(List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            goal(30, TEAM_A),
            goal(60, TEAM_A)
        ), tallies);

        assertEquals(1.0, rating(tallies, 1), 1e-9);
        assertEquals(-1.0, rating(tallies, 12), 1e-9);
    }

    @Test
    void ratingsCarryAcrossMatches() {
        // Match 1: A scores once at all-zero rating -> A players +0.5, B -0.5.
        // Match 2, same lineups: gap 0.5 - (-0.5) = 1.0, so P = sigma(1.0)
        // and A's second goal is only worth 1 - P = 0.2689... An expected result
        // moves ratings less.

        MatchProcessor processor = 
            new MatchProcessor(new ResidualCreditRule(new LogisticLinkFunction(1.0)), 1.0);
        Map<Long, PlayerTally> tallies = new HashMap<>();

        List<MatchEvent> match = List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            goal(30, TEAM_A)
        );
        processor.process(match, tallies);
        processor.process(match, tallies);

        assertEquals(0.5 + 0.2689414213699951, rating(tallies, 1), 1e-9);
        assertEquals(-(0.5 + 0.2689414213699951), rating(tallies, 12), 1e-9);
    }

    @Test
    void kFactorScalesTheUpdate() {
        MatchProcessor processor = 
            new MatchProcessor(new ResidualCreditRule(new LogisticLinkFunction(1.0)), 2.0);
        Map<Long, PlayerTally> tallies = new HashMap<>();

        processor.process(List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            goal(30, TEAM_A)
        ), tallies);

        assertEquals(1.0, rating(tallies, 1), 1e-9); // 2.0 * 0.5
    }

    @Test
    void substitutedOffPlayerMissesLaterGoals() {
        MatchProcessor processor = new MatchProcessor(new FlatCreditRule(), 1.0);
        Map<Long, PlayerTally> tallies = new HashMap<>();

        processor.process(List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            sub(10, TEAM_A, 2, 3),
            goal(30, TEAM_A)
        ), tallies);

        assertEquals(1.0, rating(tallies, 1), 1e-9);
        assertEquals(0.0, rating(tallies, 2), 1e-9); // off before the goal
        assertEquals(1.0, rating(tallies, 3), 1e-9); // on before the goal
    }

    @Test
    void minutesAreStillTracked() {
        MatchProcessor processor = new MatchProcessor(new FlatCreditRule(), 1.0);
        Map<Long, PlayerTally> tallies = new HashMap<>();

        processor.process(List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            sub(10, TEAM_A, 2, 3),
            goal(90, TEAM_A) // last event: match ends at 90'
        ), tallies);

        assertEquals(90.0, tallies.get(1L).minutes(), 1e-9);
        assertEquals(10.0, tallies.get(2L).minutes(), 1e-9);
        assertEquals(80.0, tallies.get(3L).minutes(), 1e-9);
    }
}
