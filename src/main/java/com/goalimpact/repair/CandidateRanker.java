package com.goalimpact.repair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// The picker's order (item 17, slice 1, decisions 6 and 10). SQL selects, Java
// ranks: the reader in data hands over candidates tagged with their evidence and
// this turns that evidence into the three rungs of 'Candidate rank', with no
// database and no screen anywhere near it. That seam exists because this rule
// *will* be tuned, and a rule buried in a SQL string can only be exercised by the
// real snapshot.
//
// It is a typing aid and never evidence (decision 1). Verification comes from a
// source outside the tool, so the ranking may be aggressive - being wrong costs a
// keystroke, not a wrong rating.
public final class CandidateRanker {

    public static final int RANK_NEARBY = 0;
    public static final int RANK_EVER = 1;
    public static final int RANK_EVERYONE = 2;

    private CandidateRanker() {
    }

    // Rank, filter and de-duplicate the pool.
    //
    // With nothing typed only rank 0 is offered - the club's squad around this
    // date, which is what makes an absent side eleven clicks down a pre-ranked
    // list. Typing reveals rank 1 and then rank 2; the filter applies across all
    // three rungs, and the cap applies to rank 2 alone, which is 114,893 players
    // where the other two are a squad.
    //
    // alreadyIn maps a player id to why he cannot be added - see RankedCandidate.
    public static List<RankedCandidate> rank(List<PlayerCandidate> pool, String typed,
        Map<Long, String> alreadyIn, int everyoneCap) {

        String filter = typed == null ? "" : typed.trim().toLowerCase();
        boolean typing = !filter.isEmpty();

        Map<Long, PlayerCandidate> merged = new LinkedHashMap<>();
        for (PlayerCandidate c : pool) {
            merged.merge(c.playerId(), c, PlayerCandidate::merge);
        }

        List<PlayerCandidate> matching = new ArrayList<>();
        for (PlayerCandidate c : merged.values()) {
            if (!typing || c.playerName().toLowerCase().contains(filter)) {
                matching.add(c);
            }
        }
        matching.sort(withinRank());

        List<RankedCandidate> ranked = new ArrayList<>();
        int everyoneShown = 0;
        for (PlayerCandidate c : matching) {
            int rank = rankOf(c);
            if (rank != RANK_NEARBY && !typing) {
                continue;
            }
            if (rank == RANK_EVERYONE && everyoneShown++ >= everyoneCap) {
                continue;
            }
            ranked.add(new RankedCandidate(c, rank, alreadyIn.get(c.playerId())));
        }
        ranked.sort(Comparator.comparingInt(RankedCandidate::rank));
        return ranked;
    }

    private static int rankOf(PlayerCandidate c) {
        if (c.nearbyMatches() > 0) {
            return RANK_NEARBY;
        }
        return c.everPlayedForClub() ? RANK_EVER : RANK_EVERYONE;
    }

    // Nearby matches descending, then name (decision 10). The name tiebreak is
    // what keeps two equal players from swapping places between keystrokes.
    private static Comparator<PlayerCandidate> withinRank() {
        return Comparator.comparingInt(PlayerCandidate::nearbyMatches).reversed()
            .thenComparing(PlayerCandidate::playerName,
                Comparator.nullsLast(String::compareToIgnoreCase))
            .thenComparingLong(PlayerCandidate::playerId);
    }
}
