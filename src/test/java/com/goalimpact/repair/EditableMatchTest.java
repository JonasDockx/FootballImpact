package com.goalimpact.repair;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditableMatchTest {

    private static final long HOME = 100L;
    private static final long AWAY = 200L;

    private static MatchHeader header() {
        return new MatchHeader(2501210L, LocalDate.of(2014, 4, 30), "GRP", "2013",
            "Quarter-Finals", "domestic_cup", HOME, "Olympiakos Volos",
            AWAY, "Panathinaikos", 0, 1);
    }

    private static LineupEntry starter(long club, long id, String position) {
        return new LineupEntry(club, id, "P" + id, position, "starting_lineup");
    }

    // A lawful side: one starting keeper and ten outfield starters.
    private static List<LineupEntry> cleanSide(long club, long base) {
        List<LineupEntry> side = new ArrayList<>();
        side.add(starter(club, base, "Goalkeeper"));
        for (int i = 1; i < 11; i++) {
            side.add(starter(club, base + i, "Centre-Back"));
        }
        return side;
    }

    private static EditableMatch match(List<LineupEntry> lineup) {
        return new EditableMatch(header(), lineup, List.of(), List.of());
    }

    private static List<LineupEntry> twoCleanSides() {
        List<LineupEntry> lineup = new ArrayList<>(cleanSide(HOME, 100));
        lineup.addAll(cleanSide(AWAY, 200));
        return lineup;
    }

    @Test
    void twoLawfulSidesHaveNoProblems() {
        assertTrue(match(twoCleanSides()).problems().isEmpty());
    }

    @Test
    void aTwelfthStarterFailsTheElevenCheck() {
        List<LineupEntry> lineup = twoCleanSides();
        lineup.add(starter(HOME, 199, "Winger"));
        assertEquals(List.of("XI is not 11"), match(lineup).problems());
    }

    @Test
    void benchingTheExtraStarterClearsIt() {
        List<LineupEntry> lineup = twoCleanSides();
        lineup.add(starter(HOME, 199, "Winger"));
        assertTrue(match(lineup).asBench(199).problems().isEmpty());
    }

    @Test
    void aSecondStartingKeeperIsTwoGoalkeepers() {
        EditableMatch match = match(twoCleanSides()).withPosition(101, "Goalkeeper");
        assertEquals(List.of("two starting goalkeepers"), match.problems());
    }

    // The stage-3 repair in miniature: the vendor tagged the keeper as a forward,
    // leaving the side with no starting goalkeeper; retagging fixes it, and the
    // provenance seed names exactly that change.
    @Test
    void retaggingAMistaggedKeeperFixesTheSideAndIsRecorded() {
        List<LineupEntry> broken = new ArrayList<>(cleanSide(HOME, 100));
        broken.set(0, starter(HOME, 100, "Centre-Forward"));
        broken.addAll(cleanSide(AWAY, 200));

        EditableMatch defect = match(broken);
        assertEquals(List.of("no starting goalkeeper"), defect.problems());

        EditableMatch fixed = defect.withPosition(100, "Goalkeeper");
        assertTrue(fixed.problems().isEmpty());

        String provenance = fixed.provenanceSummary();
        assertTrue(provenance.contains("100"), provenance);
        assertTrue(provenance.contains("Centre-Forward"), provenance);
        assertTrue(provenance.contains("Goalkeeper"), provenance);
    }

    // --- Stage 4b-3: the appeared reconstruction ------------------------------

    private static LineupEntry rosterEntry(long club, long id, String position) {
        // The type is irrelevant on the way in: fromAppearances sets start/bench
        // from the events, so the caller's placeholder is overwritten.
        return new LineupEntry(club, id, "P" + id, position, "substitutes");
    }

    // A full side's roster as appearances would hand it over: eleven who started
    // plus the given number of substitutes, all before any start/bench is decided.
    private static List<LineupEntry> rosterSide(long club, long base, int subs) {
        List<LineupEntry> side = new ArrayList<>();
        side.add(rosterEntry(club, base, "Goalkeeper"));
        for (int i = 1; i < 11 + subs; i++) {
            side.add(rosterEntry(club, base + i, "Midfield"));
        }
        return side;
    }

    private static EventRow subOn(long club, long playerInId) {
        return new EventRow(2501210L, 70, EventRow.SUBSTITUTION, club, 0L, playerInId, "");
    }

    @Test
    void reconstructionMarksTheSubbedOnAsBenchAndTheRestAsStarters() {
        List<LineupEntry> roster = new ArrayList<>(rosterSide(HOME, 100, 3));
        roster.addAll(rosterSide(AWAY, 200, 2));
        // The last three home entries and last two away entries came on.
        List<EventRow> events = List.of(
            subOn(HOME, 111), subOn(HOME, 112), subOn(HOME, 113),
            subOn(AWAY, 211), subOn(AWAY, 212));

        EditableMatch match = EditableMatch.fromAppearances(header(), roster, events, List.of());

        assertEquals(EditableMatch.Origin.RECONSTRUCTED, match.origin());
        assertTrue(match.problems().isEmpty(), match.problems().toString());
        long starters = match.lineup().stream().filter(LineupEntry::starter).count();
        assertEquals(22, starters);
        assertTrue(match.lineup().stream()
            .filter(e -> e.playerId() == 111 || e.playerId() == 212)
            .noneMatch(LineupEntry::starter));
    }

    // The ~3% the derivation cannot resolve: no substitution names the twelfth
    // home player, so twelve are left starting and the count check fires - exactly
    // the case the operator finishes with the bench button.
    @Test
    void anUnexplainedTwelfthLeavesTheSideHeld() {
        List<LineupEntry> roster = new ArrayList<>(rosterSide(HOME, 100, 1));
        roster.addAll(rosterSide(AWAY, 200, 0));
        // Only the away game is fully explained; the home sub has no event.
        EditableMatch match = EditableMatch.fromAppearances(header(), roster, List.of(), List.of());

        assertEquals(List.of("XI is not 11"), match.problems());
        assertTrue(match.asBench(111).problems().isEmpty());
    }

    @Test
    void reconstructedProvenanceNamesTheSourceAndBothKeepers() {
        List<LineupEntry> roster = new ArrayList<>(rosterSide(HOME, 100, 1));
        roster.addAll(rosterSide(AWAY, 200, 1));
        List<EventRow> events = List.of(subOn(HOME, 111), subOn(AWAY, 211));

        String seed = EditableMatch.fromAppearances(header(), roster, events, List.of())
            .provenanceSummary();

        assertTrue(seed.contains("reconstructed"), seed);
        assertTrue(seed.contains("P100"), seed);   // home keeper, id 100
        assertTrue(seed.contains("P200"), seed);   // away keeper, id 200
    }
}
