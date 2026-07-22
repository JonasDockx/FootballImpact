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
import java.util.Set;

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

    private static MatchEvent.StartingXI xi(Team team, long... ids) {
        return xi(team, false, ids);
    }

    private static MatchEvent.StartingXI xi(Team team, boolean home, long... ids) {
        List<Player> players = new ArrayList<>();
        for (long id : ids) {
            players.add(player(id));
        }
        return new MatchEvent.StartingXI(1, 0, 0, team, players, player(ids[0]), home);
    }

    // Records what the seam hears about home-ness. Sides arrive in no
    // particular order, so Team A is identified by membership, not position.
    private static ResidualSource homeRecorder(List<String> heard) {
        return new ResidualSource() {
            @Override
            public Map<Player, Double> goal(Lineup scoring, Lineup conceding, RatingLookup ratings) {
                heard.add("goal scoring=" + scoring.home() + " conceding=" + conceding.home());
                return Map.of();
            }
            @Override
            public Map<Player, Double> segment(Lineup teamA, Lineup teamB, double seconds, RatingLookup ratings) {
                Lineup a = teamA.players().contains(player(1)) ? teamA : teamB;
                Lineup b = a == teamA ? teamB : teamA;
                heard.add("segment A=" + a.home() + " B=" + b.home());
                return Map.of();
            }
        };
    }

    // Sorted ids, so assertions read stably regardless of set order.
    private static List<Long> ids(Set<Player> players) {
        return players.stream().map(Player::id).sorted().toList();
    }

    // Records what the seam hears about each side's goalkeepers. Sides
    // arrive in no particular order; per the ids convention above, the
    // lineup whose smallest id is below 12 is Team A.
    private static ResidualSource keeperRecorder(List<String> heard) {
        return new ResidualSource() {
            @Override
            public Map<Player, Double> goal(Lineup scoring, Lineup conceding, RatingLookup ratings) {
                heard.add("goal scoring=" + ids(scoring.goalkeepers())
                    + " conceding=" + ids(conceding.goalkeepers()));
                return Map.of();
            }
            @Override
            public Map<Player, Double> segment(Lineup teamA, Lineup teamB, double seconds, RatingLookup ratings) {
                Lineup a = ids(teamA.players()).get(0) < 12 ? teamA : teamB;
                Lineup b = a == teamA ? teamB : teamA;
                heard.add("segment A=" + ids(a.goalkeepers()) + " B=" + ids(b.goalkeepers()));
                return Map.of();
            }
        };
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

    @Test
    void homeFlagTravelsWithItsSideIntoTheSeam() {
        List<String> heard = new ArrayList<>();
        MatchProcessor processor = new MatchProcessor(homeRecorder(heard), m -> 1.0);

        processor.process(List.of(
            xi(TEAM_A, true, 1, 2),   // Team A plays at its own venue
            xi(TEAM_B, 12, 13),
            goal(30, TEAM_B),         // the away side scores
            new MatchEvent.MatchEnd(2, 90, 0)
        ), new HashMap<>());

        assertEquals(List.of(
            "goal scoring=false conceding=true",
            "segment A=true B=false"), heard);
    }

    @Test
    void neutralMatchFlagsNeitherSideAnywhere() {
        List<String> heard = new ArrayList<>();
        MatchProcessor processor = new MatchProcessor(homeRecorder(heard), m -> 1.0);

        processor.process(List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            goal(30, TEAM_A),
            new MatchEvent.MatchEnd(2, 90, 0)
        ), new HashMap<>());

        assertEquals(List.of(
            "goal scoring=false conceding=false",
            "segment A=false B=false"), heard);
    }

    @Test
    void startingGoalkeepersTravelWithTheirSideIntoTheSeam() {
        List<String> heard = new ArrayList<>();
        MatchProcessor processor = new MatchProcessor(keeperRecorder(heard), m -> 1.0);

        // All four players are career debutants: the tag is stamped at
        // kickoff, so even a first-ever-match keeper is in the subset.
        processor.process(List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            goal(30, TEAM_B),
            new MatchEvent.MatchEnd(2, 90, 0)
        ), new HashMap<>());

        assertEquals(List.of(
            "goal scoring=[12] conceding=[1]",
            "segment A=[1] B=[12]"), heard);
    }

    @Test
    void subbedOnKeeperIsInTheSubsetOnlyIfAlreadyTagged() {
        List<String> heard = new ArrayList<>();
        MatchProcessor processor = new MatchProcessor(keeperRecorder(heard), m -> 1.0);
        Map<Long, PlayerTally> tallies = new HashMap<>();

        // Match 1: player 3 starts in goal for A - the career tag is earned.
        processor.process(List.of(
            xi(TEAM_A, 3, 2),
            xi(TEAM_B, 12, 13)
        ), tallies);
        heard.clear(); // match 1 is scaffolding; assertions are about match 2

        // Match 2: keeper 1 is replaced by tagged keeper 3; debutant 4
        // comes on untagged - a substitution names no position.
        processor.process(List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            sub(10, TEAM_A, 1, 3),
            sub(20, TEAM_A, 2, 4),
            goal(30, TEAM_A),
            new MatchEvent.MatchEnd(2, 90, 0)
        ), tallies);

        assertEquals(List.of(
            "segment A=[1] B=[12]",
            "segment A=[3] B=[12]",
            "goal scoring=[3] conceding=[12]",
            "segment A=[3] B=[12]"), heard);
    }

    @Test
    void sentOffKeeperDropsOutOfTheSubset() {
        List<String> heard = new ArrayList<>();
        MatchProcessor processor = new MatchProcessor(keeperRecorder(heard), m -> 1.0);

        processor.process(List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            new MatchEvent.RedCard(1, 10, 0, TEAM_A, player(1)),
            goal(30, TEAM_B),
            new MatchEvent.MatchEnd(2, 90, 0)
        ), new HashMap<>());

        assertEquals(List.of(
            "segment A=[1] B=[12]",
            "goal scoring=[12] conceding=[]",
            "segment A=[] B=[12]"), heard);
    }

    @Test
    void aSquadThatSitsOutIsUntouched() {
        // The freeze covers this match's participants, not the population.
        // Match 2 shares no player with match 1, so under the old whole-map
        // freeze it was computed from a snapshot of players it never reads.
        // Freezing only its own players must give the identical answer, and
        // must leave match 1's squad bit-for-bit alone.

        MatchProcessor processor = 
            new MatchProcessor(new ResidualCreditRule(new LogisticLinkFunction(1.0)), m -> 1.0);
        Map<Long, PlayerTally> tallies = new HashMap<>();

        processor.process(List.of(
            xi(TEAM_A, 1, 2),
            xi(TEAM_B, 12, 13),
            goal(30, TEAM_A)
        ), tallies);

        double ratingOfOne = rating(tallies, 1);
        double minutesOfOne = tallies.get(1L).minutes();

        processor.process(List.of(
            xi(TEAM_A, 3, 4),
            xi(TEAM_B, 14, 15),
            goal(30, TEAM_A)
        ), tallies);

        assertEquals(ratingOfOne, rating(tallies, 1), 1e-12);
        assertEquals(minutesOfOne, tallies.get(1L).minutes(), 1e-12);
        // A pair meeting at all-zero ratings earns the same 0.5 the first
        // pair did, whoever else the run happens to have on its books.
        assertEquals(0.5, rating(tallies, 3), 1e-9);
    }
}
