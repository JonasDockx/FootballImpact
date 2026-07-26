package com.goalimpact.repair;

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

    // The vendor entry point: the lineup as loaded is both the working copy and
    // the baseline provenance diffs against.
    public EditableMatch(MatchHeader header, List<LineupEntry> lineup,
        List<EventRow> events, List<AppearanceRow> appearances) {
        this(header, lineup, lineup, events, appearances, Origin.VENDOR_SHEET);
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
            Origin.RECONSTRUCTED);
    }

    private EditableMatch(MatchHeader header, List<LineupEntry> lineup,
        List<LineupEntry> original, List<EventRow> events, List<AppearanceRow> appearances,
        Origin origin) {
        this.header = header;
        this.lineup = List.copyOf(lineup);
        this.original = List.copyOf(original);
        this.events = List.copyOf(events);
        this.appearances = List.copyOf(appearances);
        this.origin = origin;
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
        return new EditableMatch(header, next, original, events, appearances, origin);
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
        List<String> changes = new ArrayList<>();
        for (LineupEntry now : lineup) {
            LineupEntry was = before.get(now.playerId());
            if (was == null) {
                continue;   // no players are added in this slice (decision 1)
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
