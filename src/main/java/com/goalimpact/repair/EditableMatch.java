package com.goalimpact.repair;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

// One match open for repair (item 26, stages 4b-2 and 4b-3). It holds the
// current lineup and an original side by side: the current lineup is what
// problems() judges and the writer will release, and the original is kept only
// so provenanceSummary() can say what changed. Everything else - the header, the
// events, the appearances - is carried through untouched, because a released
// match replaces the vendor's copy whole (decision 11).
//
// A match reaches the editor two ways, recorded in its origin. A certain-tier
// match is a VENDOR_SHEET: the vendor's own game_lineups, edited, and the
// original is that sheet so a diff names the correction. An appeared-tier match
// has no sheet, so it is RECONSTRUCTED from the appearances roster and the
// substitution events (fromAppearances, stage 4b-3); the original is the
// reconstruction itself, so a diff names only the operator's later fixes and the
// provenance says the eleven came from the record, not a team sheet.
//
// The object is immutable: every edit returns a fresh EditableMatch and the
// screen replaces its reference, so no cell is ever mutated underneath the view.
// The original and the origin are threaded through each edit unchanged.
public final class EditableMatch {

    public enum Origin { VENDOR_SHEET, RECONSTRUCTED }

    private final MatchHeader header;
    private final List<LineupEntry> lineup;
    private final List<LineupEntry> original;
    private final List<EventRow> events;
    private final List<AppearanceRow> appearances;
    private final Origin origin;

    // Item 17, slice 1. The manual players minted during this repair, waiting to
    // be written to the register inside save's transaction (ADR 0012, decision 3),
    // and the highest manual id known when the editor opened - read once, so ids
    // are handed out max+1, max+2 from a single read and cannot collide.
    private final List<ManualPlayer> created;
    private final long manualIdCeiling;

    // The vendor entry point: the lineup as loaded is both the working copy and
    // the baseline provenance diffs against.
    public EditableMatch(MatchHeader header, List<LineupEntry> lineup,
        List<EventRow> events, List<AppearanceRow> appearances) {
        this(header, lineup, lineup, events, appearances, Origin.VENDOR_SHEET,
            List.of(), ManualPlayer.FIRST_ID - 1);
    }

    // The appeared entry point (stage 4b-3, decision 1): a match with no team
    // sheet, rebuilt from its records. The roster is everyone the appearances
    // named (with a position looked up), and the starting eleven a side is
    // everyone the substitution events do not name coming on - so a starter is a
    // roster player who was never a sub's playerInId. Nothing is invented; the
    // split is read from the very events that will later drive the replay, so the
    // reconstruction is consistent with the engine by construction. The result is
    // its own baseline: a released-as-is match shows no change, and a hand fix to
    // the ~3% that do not derive to eleven diffs against it.
    public static EditableMatch fromAppearances(MatchHeader header,
        List<LineupEntry> roster, List<EventRow> events, List<AppearanceRow> appearances) {
        Set<Long> cameOn = new HashSet<>();
        for (EventRow e : events) {
            if (EventRow.SUBSTITUTION.equals(e.type()) && e.playerInId() != null) {
                cameOn.add(e.playerInId());
            }
        }
        List<LineupEntry> lineup = new ArrayList<>(roster.size());
        for (LineupEntry r : roster) {
            lineup.add(cameOn.contains(r.playerId()) ? r.asBench() : r.asStarter());
        }
        return new EditableMatch(header, lineup, lineup, events, appearances,
            Origin.RECONSTRUCTED, List.of(), ManualPlayer.FIRST_ID - 1);
    }

    private EditableMatch(MatchHeader header, List<LineupEntry> lineup,
        List<LineupEntry> original, List<EventRow> events, List<AppearanceRow> appearances,
        Origin origin, List<ManualPlayer> created, long manualIdCeiling) {
        this.header = header;
        this.lineup = List.copyOf(lineup);
        this.original = List.copyOf(original);
        this.events = List.copyOf(events);
        this.appearances = List.copyOf(appearances);
        this.origin = origin;
        this.created = List.copyOf(created);
        this.manualIdCeiling = manualIdCeiling;
    }

    private EditableMatch with(List<LineupEntry> nextLineup, List<ManualPlayer> nextCreated) {
        return new EditableMatch(header, nextLineup, original, events, appearances,
            origin, nextCreated, manualIdCeiling);
    }

    public MatchHeader header() {
        return header;
    }

    public List<LineupEntry> lineup() {
        return lineup;
    }

    public List<EventRow> events() {
        return events;
    }

    public List<AppearanceRow> appearances() {
        return appearances;
    }

    public Origin origin() {
        return origin;
    }

    // The usability gate, restated (decision 4). TransfermarktLoader throws the
    // first reason it hits; this returns every reason so the screen can show them
    // all, ordered so the first element is always the one the loader would raise.
    // That order is the loader's: two goalkeepers is thrown mid-loop, before
    // either side's XI is examined, so it comes first; then per side the count is
    // checked before the keeper, and the keeper line is never reached when the
    // count is wrong. An empty list means the loader would rate this match.
    //
    // This assumes a lineup with a starting XI to judge: either the vendor's own
    // (certain tier) or the reconstructed one (appeared tier, which marks starters
    // from the substitution events before the editor ever opens). The loader's "no
    // lineups" reason, for a match with no starters at all, is the maybe tier's -
    // still not offered - and is never reached here.
    public List<String> problems() {
        List<String> reasons = new ArrayList<>();
        if (startingGoalkeepers(header.homeClubId()) > 1) {
            reasons.add("two starting goalkeepers");
        }
        if (startingGoalkeepers(header.awayClubId()) > 1) {
            reasons.add("two starting goalkeepers");
        }
        addSideProblems(header.homeClubId(), reasons);
        addSideProblems(header.awayClubId(), reasons);
        return reasons;
    }

    private void addSideProblems(long clubId, List<String> reasons) {
        if (starters(clubId) != 11) {
            reasons.add("XI is not 11");
        } else if (startingGoalkeepers(clubId) == 0) {
            reasons.add("no starting goalkeeper");
        }
    }

    private long starters(long clubId) {
        return lineup.stream()
            .filter(e -> e.clubId() == clubId && e.starter())
            .count();
    }

    private long startingGoalkeepers(long clubId) {
        return lineup.stream()
            .filter(e -> e.clubId() == clubId && e.starter() && e.goalkeeper())
            .count();
    }

    public EditableMatch withPosition(long playerId, String position) {
        return replace(playerId, entry -> entry.withPosition(position));
    }

    public EditableMatch asStarter(long playerId) {
        return replace(playerId, LineupEntry::asStarter);
    }

    public EditableMatch asBench(long playerId) {
        return replace(playerId, LineupEntry::asBench);
    }

    private EditableMatch replace(long playerId, UnaryOperator<LineupEntry> edit) {
        List<LineupEntry> next = new ArrayList<>(lineup.size());
        for (LineupEntry entry : lineup) {
            next.add(entry.playerId() == playerId ? edit.apply(entry) : entry);
        }
        return with(next, created);
    }

    // --- Item 17, slice 1: adding, creating and removing a player -----------

    public List<ManualPlayer> created() {
        return created;
    }

    // The highest manual id the sidecar's register already holds, read once when
    // the editor opens (ADR 0012, decision 3). Below the reserved range it is
    // ignored, so an empty register opens the range at its first id.
    public EditableMatch withManualIdCeiling(long ceiling) {
        return new EditableMatch(header, lineup, original, events, appearances,
            origin, created, Math.max(ManualPlayer.FIRST_ID - 1, ceiling));
    }

    // Add a player the record does name - a vendor id chosen in the picker. The
    // role defaults to starter while that side is short of eleven and to the bench
    // once it is full (decision 7), so filling an absent side is eleven clicks and
    // no corrections, and the To XI / To bench buttons settle the rest.
    public EditableMatch add(long clubId, long playerId, String playerName, String position) {
        List<LineupEntry> next = new ArrayList<>(lineup);
        next.add(new LineupEntry(clubId, playerId, playerName, position,
            starters(clubId) < 11 ? LineupEntry.STARTER : LineupEntry.BENCH));
        return with(next, created);
    }

    // Name a player no source names (glossary 'Manual player'). The id is the next
    // one above the ceiling read at open, so two men created in one repair take
    // consecutive ids; the register row rides along until save writes it inside its
    // own transaction, and a cancelled repair leaves nothing behind.
    public EditableMatch create(long clubId, String playerName, String position,
        LocalDate dateOfBirth, String note) {

        long playerId = manualIdCeiling + 1 + created.size();
        List<ManualPlayer> nextCreated = new ArrayList<>(created);
        nextCreated.add(new ManualPlayer(playerId, playerName, dateOfBirth, note));
        return with(add(clubId, playerId, playerName, position).lineup(), nextCreated);
    }

    // Whether this row was put here in this session - exactly the ids the original
    // does not hold. Decision 8 makes remove an undo of those and nothing else: no
    // recorded player may be dropped, however wrong he looks.
    public boolean isAdded(long playerId) {
        return lineup.stream().anyMatch(e -> e.playerId() == playerId)
            && original.stream().noneMatch(e -> e.playerId() == playerId);
    }

    // Undo an add. A recorded player is left exactly where he is. A created player
    // takes his pending register row with him, so save can never write a manual
    // player who appears in no match (ADR 0012, decision 3).
    public EditableMatch remove(long playerId) {
        if (!isAdded(playerId)) {
            return this;
        }
        List<LineupEntry> next = new ArrayList<>(lineup.size());
        for (LineupEntry entry : lineup) {
            if (entry.playerId() != playerId) {
                next.add(entry);
            }
        }
        List<ManualPlayer> nextCreated = new ArrayList<>(created);
        nextCreated.removeIf(p -> p.playerId() == playerId);
        return with(next, nextCreated);
    }

    // Why each player already in the match cannot be picked again (decision 7).
    // The side is named because the same man in the other XI is a different
    // mistake from the same man on this bench.
    public Map<Long, String> membership() {
        Map<Long, String> reasons = new HashMap<>();
        for (LineupEntry entry : lineup) {
            String club = entry.clubId() == header.homeClubId()
                ? header.homeClubName() : header.awayClubName();
            reasons.put(entry.playerId(), entry.starter()
                ? "already in " + club + "'s XI"
                : "already on " + club + "'s bench");
        }
        return reasons;
    }

    // The seed for the provenance box (decision 9): what a diff can reconstruct,
    // named, so the operator is left to add the why - the part a diff cannot - and
    // the box stays editable for exactly that. A vendor sheet diffs against the
    // vendor and reads as a correction; a reconstruction states that it was built
    // from the record (stage 4b-3, decision 5) and, since it is its own baseline,
    // any later hand fix reads as a change "since reconstruction".
    public String provenanceSummary() {
        List<String> changes = handEdits();
        if (origin == Origin.RECONSTRUCTED) {
            String seed = "Game " + header.gameId()
                + ": reconstructed from the appearances roster and the substitution"
                + " events (a starter is a listed player never subbed on). Home GK "
                + goalkeeper(header.homeClubId()) + ", away GK "
                + goalkeeper(header.awayClubId()) + ".";
            return changes.isEmpty()
                ? seed
                : seed + " Since reconstruction: " + String.join("; ", changes) + ".";
        }
        if (changes.isEmpty()) {
            return "Game " + header.gameId() + ": no change from the vendor lineup.";
        }
        return "Game " + header.gameId() + ": " + String.join("; ", changes) + ".";
    }

    // Every current cell that differs from the original, named. For a vendor sheet
    // the original is the vendor's; for a reconstruction it is the reconstruction,
    // so this is empty until the operator fixes something by hand.
    private List<String> handEdits() {
        Map<Long, LineupEntry> before = new HashMap<>();
        for (LineupEntry entry : original) {
            before.put(entry.playerId(), entry);
        }
        Set<Long> createdIds = new HashSet<>();
        for (ManualPlayer p : created) {
            createdIds.add(p.playerId());
        }
        List<String> changes = new ArrayList<>();
        for (LineupEntry now : lineup) {
            LineupEntry was = before.get(now.playerId());
            if (was == null) {
                // Item 17, slice 1, decision 9: no note is required before a
                // release, so this line is the only record the addition leaves.
                // It names every added and created player, so the *what* is in the
                // file even when the *how I knew* is not.
                changes.add(now.playerName() + " (" + now.playerId() + "): "
                    + (createdIds.contains(now.playerId()) ? "created and added" : "added")
                    + " to the " + (now.starter() ? "starting XI" : "bench"));
                continue;
            }
            if (!Objects.equals(was.position(), now.position())) {
                changes.add(now.playerName() + " (" + now.playerId() + "): position "
                    + was.position() + " -> " + now.position());
            }
            if (was.starter() != now.starter()) {
                changes.add(now.playerName() + " (" + now.playerId() + "): moved to the "
                    + (now.starter() ? "starting XI" : "bench"));
            }
        }
        return changes;
    }

    // The starting goalkeeper of a side, "name (id)", for the reconstruction seed;
    // "unresolved" when the derivation left the side without exactly one, which the
    // ~3% edge does and problems() flags for a hand fix before release.
    private String goalkeeper(long clubId) {
        return lineup.stream()
            .filter(e -> e.clubId() == clubId && e.starter() && e.goalkeeper())
            .map(e -> e.playerName() + " (" + e.playerId() + ")")
            .findFirst()
            .orElse("unresolved");
    }
}
