package com.goalimpact.repair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

// One certain-tier match, open for repair (item 26, stage 4b-2). It holds the
// current lineup and the vendor original side by side: the current lineup is
// what problems() judges and the writer will release, and the original is kept
// only so provenanceSummary() can say what changed. Everything else - the
// header, the events, the appearances - is carried through untouched, because a
// released match replaces the vendor's copy whole (decision 11).
//
// The object is immutable: every edit returns a fresh EditableMatch and the
// screen replaces its reference, so no cell is ever mutated underneath the view.
// The original is threaded through each edit unchanged, so it always means "the
// vendor" however many edits have been made.
public final class EditableMatch {

    private final MatchHeader header;
    private final List<LineupEntry> lineup;
    private final List<LineupEntry> original;
    private final List<EventRow> events;
    private final List<AppearanceRow> appearances;

    // The vendor entry point: the lineup as loaded is both the working copy and
    // the baseline provenance diffs against.
    public EditableMatch(MatchHeader header, List<LineupEntry> lineup,
        List<EventRow> events, List<AppearanceRow> appearances) {
        this(header, lineup, lineup, events, appearances);
    }

    private EditableMatch(MatchHeader header, List<LineupEntry> lineup,
        List<LineupEntry> original, List<EventRow> events, List<AppearanceRow> appearances) {
        this.header = header;
        this.lineup = List.copyOf(lineup);
        this.original = List.copyOf(original);
        this.events = List.copyOf(events);
        this.appearances = List.copyOf(appearances);
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

    // The usability gate, restated (decision 4). TransfermarktLoader throws the
    // first reason it hits; this returns every reason so the screen can show them
    // all, ordered so the first element is always the one the loader would raise.
    // That order is the loader's: two goalkeepers is thrown mid-loop, before
    // either side's XI is examined, so it comes first; then per side the count is
    // checked before the keeper, and the keeper line is never reached when the
    // count is wrong. An empty list means the loader would rate this match.
    //
    // This assumes a match that has a starting XI - the certain tier, the only
    // tier the editor opens (decision 8). The loader's "no lineups" reason, for a
    // match with no starters at all, is the maybe tier's and is never reached here.
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
        return new EditableMatch(header, next, original, events, appearances);
    }

    // The seed for the provenance box (decision 9): every cell that differs from
    // the vendor, named. It records what changed, which a diff could also
    // reconstruct; the why - the part a diff cannot - is what the user is meant to
    // add before releasing, and the box is left editable for exactly that.
    public String provenanceSummary() {
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
        if (changes.isEmpty()) {
            return "Game " + header.gameId() + ": no change from the vendor lineup.";
        }
        return "Game " + header.gameId() + ": " + String.join("; ", changes) + ".";
    }
}
