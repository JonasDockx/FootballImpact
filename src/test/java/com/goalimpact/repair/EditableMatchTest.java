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
}
