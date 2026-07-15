package com.goalimpact.engine;

import com.goalimpact.credit.FlatCreditRule;
import com.goalimpact.credit.Lineup;
import com.goalimpact.credit.LogisticLinkFunction;
import com.goalimpact.credit.RatingLookup;
import com.goalimpact.credit.ResidualCreditRule;
import com.goalimpact.credit.ResidualSource;
import com.goalimpact.model.MatchEvent;
import com.goalimpact.model.Player;
import com.goalimpact.model.Team;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchProcessorTest {
    
    private static final Team TEAM_A = new Team(1, "Team A");
    private static final Team TEAM_B = new Team(2, "Team B");

    // Players 1-11 play for Team A, 12-23 for Team B, in all tests.
    private static Player player(long id) {
        return new Player(id, "Player " + id);
    }

    // The first id is the designated goalkeeper - every real Starting XI has exactly one.
    private static MatchEvent.StartingXI xi(Team team, long... ids) {
        List<Player> players = new java.util.ArrayList<>();
        for (long id : ids) {
            players.add(player(id));
        }
        return new MatchEvent.StartingXI(1, 0, 0, team, players, player(ids[0]), false);
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
        MatchProcessor processor = new MatchProcessor(new FlatCreditRule(), m -> 1.0);
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
            new MatchProcessor(new ResidualCreditRule(new LogisticLinkFunction(1.0)), m -> 1.0);
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
            new MatchProcessor(new ResidualCreditRule(new LogisticLinkFunction(1.0)), m -> 1.0);
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
            new MatchProcessor(new ResidualCreditRule(new LogisticLinkFunction(1.0)), m -> 2.0);
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
        MatchProcessor processor = new MatchProcessor(new FlatCreditRule(), m -> 1.0);
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
        MatchProcessor processor = new MatchProcessor(new FlatCreditRule(), m -> 1.0);
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

    @Test
    void minutesRunToTheFinalWhistleNotTheLastAction() {
        MatchProcessor processor = new MatchProcessor(new FlatCreditRule(), m -> 1.0);
        Map<Long, PlayerTally> tallies = new HashMap<>();

        processor.process(List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            sub(70, TEAM_A, 2, 3),
            goal(80, TEAM_A),
            new MatchEvent.MatchEnd(2, 95, 0) // whistle 15' after the last action
        ), tallies);

        assertEquals(95.0, tallies.get(1L).minutes(), 1e-9);    // not 80
        assertEquals(70.0, tallies.get(2L).minutes(), 1e-9);    // subbed off unaffected
        assertEquals(25.0, tallies.get(3L).minutes(), 1e-9);    // 70' -> whistle
    }

    @Test
    void veteransMoveLessThanDebutants() {
        // Player 1 arrives with 1,000 career minutes, exactly the halving
        // exposure: K = 0.5. Player 2 debuts: K = 1.0. Both witness the same
        // 0.5 residual, so the veteran gains 0.25 and the debutant 0.5.
        MatchProcessor processor = new MatchProcessor(
            new ResidualCreditRule(new LogisticLinkFunction(1.0)),
            new SmoothFadeSchedule(1.0, 1000.0, 0.05));
        Map <Long, PlayerTally> tallies = new HashMap<>();

        PlayerTally veteran = new PlayerTally(player(1), TEAM_A);
        veteran.addSeconds(60_000); // 1,000 minutes
        tallies.put(1L, veteran);

        processor.process(List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            goal(30, TEAM_A)
        ), tallies);

        assertEquals(0.25, rating(tallies, 1), 1e-9);
        assertEquals(0.5, rating(tallies, 2), 1e-9);
    }

    @Test
    void exposureIsFrozenAtKickoffLikeRatings() {
        // Players 1 and 2 both debut (zero career minutes at kickoff) and both
        // witness the goal at 5'. Player 2 is then subbed off at 10', which
        // books his 10 minutes mid-match - player 1's land only after the
        // update loop. Identical pre-match exposure must mean identical
        // updates; reading exposure at the whistle instead would
        // halve player 2's K (H = 10 minutes makes the trap loud).
        MatchProcessor processor = new MatchProcessor(
            new ResidualCreditRule(new LogisticLinkFunction(1.0)), 
            new SmoothFadeSchedule(1.0, 10.0, 0.05));
        Map<Long, PlayerTally> tallies = new HashMap<>();

        processor.process(List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            goal(5, TEAM_A),
            sub(10, TEAM_A, 2, 3)
        ), tallies);

        assertEquals(0.5, rating(tallies, 1), 1e-9);
        assertEquals(0.5, rating(tallies, 2), 1e-9);
    }

    @Test
    void startingInGoalMarksAPlayerAsGoalkeeperForGood() {
        MatchProcessor processor = new MatchProcessor(new FlatCreditRule(), m -> 1.0);
        Map<Long, PlayerTally> tallies = new HashMap<>();

        // Match 1: player 1 keeps goal for team A; player 3 enters as a sub.
        processor.process(List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            sub(10, TEAM_A, 2, 3)
        ), tallies);

        // Match 2: player 2 takes over in goal; player 1 starts outfield
        processor.process(List.of(
            xi(TEAM_A, 2, 1),
            xi(TEAM_B, 12, 13)
        ), tallies);

        assertTrue(tallies.get(1L).isGoalkeeper()); // sticky: outfield in match 2, still a Goalkeeper
        assertTrue(tallies.get(2L).isGoalkeeper()); // earned late: outfield first, keeper second
        assertFalse(tallies.get(3L).isGoalkeeper()); // entered mid-match, never *started* in goal
        assertFalse(tallies.get(13L).isGoalkeeper()); // ordinary outfield starter
    }

    @Test
    void segmentsAreChoppedAtLineupChangesOnly() {
        List<Double> segmentLengths = new ArrayList<>();
        ResidualSource recorder = new ResidualSource() {
            @Override
            public Map<Player, Double> goal(Lineup scoring, Lineup conceding, RatingLookup ratings) {
                return Map.of();
            }
            @Override
            public Map<Player, Double> segment(Lineup teamA, Lineup teamB, double seconds, RatingLookup ratings) {
                segmentLengths.add(seconds);
                return Map.of();
            }
        };
        MatchProcessor processor = new MatchProcessor(recorder, m -> 1.0);

        processor.process(List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            goal(30, TEAM_A),       // goals must NOT chop segments
            sub(60, TEAM_A, 2, 3),
            new MatchEvent.MatchEnd(2, 90, 0)
        ), new HashMap<>());

        assertEquals(List.of(3600.0, 1800.0), segmentLengths);
    }
}
