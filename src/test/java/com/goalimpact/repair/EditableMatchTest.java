package com.goalimpact.repair;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // --- Item 17, slice 2: the events-derived lineup -------------------------

    // A maybe match derives no roster of its own: the caller hands over only the
    // men the events named (EventRoster), so a side arrives part-built - median
    // five of eleven, measured 2026-07-28 - and the picker finishes it.
    @Test
    void anEventsDerivedMatchStartsThoseTheEventsDoNotSubOn() {
        List<LineupEntry> roster = List.of(
            rosterEntry(HOME, 101, "Midfield"), rosterEntry(HOME, 102, "Midfield"),
            rosterEntry(HOME, 111, "Midfield"));
        List<EventRow> events = List.of(subOn(HOME, 111));

        EditableMatch match = EditableMatch.fromEvents(header(), roster, events, List.of());

        assertEquals(EditableMatch.Origin.EVENTS, match.origin());
        assertEquals(2, match.lineup().stream().filter(LineupEntry::starter).count());
        assertTrue(match.lineup().stream()
            .filter(e -> e.playerId() == 111)
            .noneMatch(LineupEntry::starter));
    }

    // The seed must describe the record it actually read. Saying "reconstructed
    // from the appearances roster" on a match that has no appearances roster
    // would write a falsehood into the precious sidecar.
    @Test
    void theEventsDerivedSeedNamesTheEventsAndNotTheAppearances() {
        List<LineupEntry> roster = List.of(rosterEntry(HOME, 101, "Goalkeeper"));

        String seed = EditableMatch.fromEvents(header(), roster, List.of(), List.of())
            .provenanceSummary();

        assertTrue(seed.contains("the match's own events"), seed);
        assertFalse(seed.contains("from the appearances roster"), seed);
    }

    // Decision 2: an id the vendor references but never names goes into the
    // lineup labelled by that id, because leaving him out would leave him addable
    // only by *creating* him - handing a footballer who already holds a vendor id
    // a second one, the split identity ADR 0012 exists to prevent.
    @Test
    void anUnnamedVendorIdIsLabelledByItsId() {
        LineupEntry row = new LineupEntry(HOME, 117799L,
            LineupEntry.unnamed(117799L), LineupEntry.UNKNOWN_POSITION, "substitutes");

        assertEquals("player 117799", row.playerName());
        assertTrue(row.unnamed());
    }

    // Decisions 2 and 3, and ADR 0012 decision 6: naming him registers him under
    // his own id. He is a Manual player of the *named* kind - nothing is minted,
    // so the allocator must not move, or the next created player would skip an id
    // for no reason.
    @Test
    void namingAVendorIdRegistersHimUnderThatIdAndMintsNothing() {
        EditableMatch match = EditableMatch
            .fromEvents(header(), List.of(rosterEntry(HOME, 117799L, "Midfield")),
                List.of(), List.of())
            .name(117799L, "Marc Dupont", LocalDate.of(1979, 3, 4), "matchday programme");

        assertEquals(List.of(new ManualPlayer(117799L, "Marc Dupont",
            LocalDate.of(1979, 3, 4), "matchday programme")), match.created());
        assertEquals("Marc Dupont", match.lineup().get(0).playerName());
        assertFalse(match.isCreated(117799L));
        assertEquals(ManualPlayer.FIRST_ID,
            match.create(HOME, "Somebody", "Midfield", null, null).created().get(1).playerId());
    }

    // --- Item 17, slice 1: adding, creating and removing --------------------

    // A side one man short: ten starters and no eleventh anywhere.
    private static List<LineupEntry> tenAndAFullSide() {
        List<LineupEntry> lineup = new ArrayList<>(cleanSide(HOME, 100));
        lineup.remove(10);
        lineup.addAll(cleanSide(AWAY, 200));
        return lineup;
    }

    // Decision 7: the role defaults to starter while the side is under eleven,
    // which is what makes the 57 ten-starter rows and the 139 absent sides a run
    // of clicks rather than a click and a correction each.
    @Test
    void anAddedPlayerStartsWhileTheSideIsShortOfEleven() {
        EditableMatch match = match(tenAndAFullSide());
        assertEquals(List.of("XI is not 11"), match.problems());

        EditableMatch filled = match.add(HOME, 999, "Marc Dupont", "Winger");
        assertTrue(filled.problems().isEmpty(), filled.problems().toString());
        assertTrue(filled.lineup().stream()
            .filter(e -> e.playerId() == 999).allMatch(LineupEntry::starter));
    }

    @Test
    void aTwelfthAddedPlayerLandsOnTheBench() {
        EditableMatch match = match(twoCleanSides()).add(HOME, 999, "Marc Dupont", "Winger");

        assertTrue(match.problems().isEmpty(), match.problems().toString());
        assertTrue(match.lineup().stream()
            .filter(e -> e.playerId() == 999).noneMatch(LineupEntry::starter));
    }

    // Decision 8: remove is an undo of this session's adds, not an editing power.
    // No recorded player may be dropped, however wrong he looks.
    @Test
    void removeUndoesAnAddButNeverTouchesARecordedPlayer() {
        EditableMatch added = match(tenAndAFullSide()).add(HOME, 999, "Marc Dupont", "Winger");
        assertTrue(added.isAdded(999));
        assertFalse(added.isAdded(100));

        EditableMatch undone = added.remove(999);
        assertEquals(21, undone.lineup().size());

        assertEquals(added.lineup(), added.remove(100).lineup());
    }

    // Decision 2 / ADR 0012: ids come from the reserved range, max+1 from a single
    // read, so two men created in one repair never collide with each other or with
    // anyone the register already holds.
    @Test
    void createdPlayersTakeConsecutiveIdsAboveTheKnownCeiling() {
        EditableMatch match = match(twoCleanSides())
            .withManualIdCeiling(ManualPlayer.FIRST_ID + 4)
            .create(HOME, "Marc Dupont", "Winger", LocalDate.of(1975, 3, 1), "club programme")
            .create(HOME, "Jan Peeters", "Goalkeeper", null, "club programme");

        List<Long> created = match.created().stream().map(ManualPlayer::playerId).toList();
        assertEquals(List.of(ManualPlayer.FIRST_ID + 5, ManualPlayer.FIRST_ID + 6), created);
        assertTrue(created.stream().allMatch(ManualPlayer::isManual));
        assertTrue(match.lineup().stream()
            .anyMatch(e -> e.playerId() == ManualPlayer.FIRST_ID + 5));
    }

    // The id counter only ever rises. Were it created.size(), a create, a remove
    // and a second create would hand one id to two different men - the same split
    // career ADR 0012 exists to prevent, arriving from the other side.
    @Test
    void anIdFreedByRemoveIsNeverHandedOutAgain() {
        EditableMatch match = match(twoCleanSides())
            .create(HOME, "Marc Dupont", "Winger", null, "")
            .create(HOME, "Jan Peeters", "Winger", null, "");
        match = match.remove(ManualPlayer.FIRST_ID)
            .create(HOME, "Piet Claes", "Winger", null, "");

        List<Long> ids = match.created().stream().map(ManualPlayer::playerId).toList();
        assertEquals(List.of(ManualPlayer.FIRST_ID + 1, ManualPlayer.FIRST_ID + 2), ids);
    }

    // A blank position is the editor's to resolve, not the picker's: a hand-made
    // player has no vendor row to look one up in, and the sidecar must never store
    // a null.
    @Test
    void aCreatedPlayerWithNoPositionGetsTheUnknownStandIn() {
        EditableMatch match = match(twoCleanSides()).create(HOME, "Marc Dupont", "  ", null, "");
        assertEquals(LineupEntry.UNKNOWN_POSITION, match.lineup()
            .get(match.lineup().size() - 1).position());
    }

    @Test
    void theFirstEverCreatedPlayerOpensTheReservedRange() {
        EditableMatch match = match(twoCleanSides())
            .create(HOME, "Marc Dupont", "Winger", null, "");
        assertEquals(ManualPlayer.FIRST_ID, match.created().get(0).playerId());
    }

    // Decision 4: a date of birth is optional and never blocks a release.
    @Test
    void aCreatedPlayerWithoutADateOfBirthStillReleases() {
        EditableMatch match = match(tenAndAFullSide())
            .create(HOME, "Marc Dupont", "Winger", null, "");
        assertTrue(match.problems().isEmpty(), match.problems().toString());
        assertNull(match.created().get(0).dateOfBirth());
    }

    // Removing a created player must take his register row with him, or save would
    // write a man who appears in no match - the orphan ADR 0012 decision 3 rules out.
    @Test
    void removingACreatedPlayerAlsoDropsHisRegisterRow() {
        EditableMatch match = match(twoCleanSides())
            .create(HOME, "Marc Dupont", "Winger", null, "");
        long id = match.created().get(0).playerId();

        assertTrue(match.remove(id).created().isEmpty());
    }

    // Decision 9's mitigation: no note is required before releasing, so the seed
    // is the only place the addition is recorded - it must name every added and
    // created player by name and id.
    @Test
    void theProvenanceSeedNamesAddedAndCreatedPlayers() {
        String seed = match(tenAndAFullSide())
            .add(HOME, 999, "Marc Dupont", "Winger")
            .create(AWAY, "Jan Peeters", "Left Winger", null, "club programme")
            .provenanceSummary();

        assertTrue(seed.contains("Marc Dupont (999)"), seed);
        assertTrue(seed.contains("added"), seed);
        assertTrue(seed.contains("Jan Peeters (" + ManualPlayer.FIRST_ID + ")"), seed);
        assertTrue(seed.contains("created"), seed);
    }

    // --- Per-side status: which XI is short, without counting rows by hand ------

    @Test
    void bothSidesReportCompleteWhenTheyAre() {
        List<SideStatus> sides = match(twoCleanSides()).sides();

        assertEquals(2, sides.size());
        assertEquals("Olympiakos Volos", sides.get(0).clubName());
        assertEquals("Panathinaikos", sides.get(1).clubName());
        assertTrue(sides.stream().allMatch(SideStatus::complete));
        assertTrue(sides.stream().allMatch(s -> s.shortOfEleven() == 0));
    }

    // The whole point: the side that is short is named, and by how many, so the
    // screen never asks the operator to count rows.
    @Test
    void aShortSideIsNamedAndCounted() {
        List<SideStatus> sides = match(tenAndAFullSide()).sides();

        SideStatus home = sides.get(0);
        assertEquals(10, home.starters());
        assertEquals(1, home.shortOfEleven());
        assertFalse(home.complete());
        assertTrue(home.summary().contains("Olympiakos Volos"), home.summary());
        assertTrue(home.summary().contains("1 short"), home.summary());

        assertTrue(sides.get(1).complete());
    }

    @Test
    void addingTheMissingManClearsTheShortfall() {
        EditableMatch filled = match(tenAndAFullSide()).add(HOME, 999, "Marc Dupont", "Winger");
        assertEquals(0, filled.sides().get(0).shortOfEleven());
        assertTrue(filled.sides().get(0).complete());
    }

    @Test
    void aTwelfthStarterReadsAsOneTooMany() {
        List<LineupEntry> lineup = twoCleanSides();
        lineup.add(starter(HOME, 199, "Winger"));

        SideStatus home = match(lineup).sides().get(0);
        assertEquals(-1, home.shortOfEleven());
        assertTrue(home.summary().contains("1 too many"), home.summary());
    }

    // The keeper is the other thing the gate checks, so the same line has to carry
    // it - a side can be eleven strong and still Held.
    @Test
    void theStatusCarriesTheGoalkeeperCount() {
        List<LineupEntry> broken = new ArrayList<>(cleanSide(HOME, 100));
        broken.set(0, starter(HOME, 100, "Centre-Forward"));
        broken.addAll(cleanSide(AWAY, 200));

        SideStatus home = match(broken).sides().get(0);
        assertEquals(0, home.startingGoalkeepers());
        assertTrue(home.summary().contains("no starting goalkeeper"), home.summary());
        assertTrue(match(broken).sides().get(1).summary().contains("1 goalkeeper"));
    }

    @Test
    void theBenchIsCountedSeparatelyFromTheXi() {
        EditableMatch match = match(twoCleanSides()).add(HOME, 999, "Marc Dupont", "Winger");
        assertEquals(11, match.sides().get(0).starters());
        assertEquals(1, match.sides().get(0).bench());
    }

    // Decision 7 again: the picker greys a man already named, and the reason has to
    // say which side and which role, because the same name in the other XI is a
    // different mistake from the same name on this bench.
    @Test
    void membershipNamesTheSideAndTheRoleOfEveryPlayerInTheMatch() {
        EditableMatch match = match(twoCleanSides()).asBench(210);

        assertTrue(match.membership().get(100L).contains("Olympiakos Volos"),
            match.membership().get(100L));
        assertTrue(match.membership().get(100L).contains("XI"));
        assertTrue(match.membership().get(210L).contains("Panathinaikos"));
        assertTrue(match.membership().get(210L).contains("bench"));
        assertNull(match.membership().get(999L));
    }
}
