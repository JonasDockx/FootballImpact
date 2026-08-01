package com.goalimpact.engine;

import com.goalimpact.model.Competition;
import com.goalimpact.model.Match;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Where each club sits in THIS run's rating landscape, read off the run's own
// fixture list and nothing else (item 16).
//
// Two verdicts come out of one pass, because both are the same fact asked two
// ways - which leagues has the run seen this club play:
//
//   UNPRICED - none at all. The model never watches the club against a pool it
//     knows, so it never learns what the club is worth, and its players sit at
//     0, which this model reads as exactly world-average rather than unknown.
//     2,074 of 2,867 clubs and 11.4% of appearances on the designated run of
//     85,050 matches, 2012-07-09 to 2026-07-06 - nearly all of them cup
//     opposition. (#16 records 2,501 of 3,274 and 16%, counted in SQL over the
//     whole snapshot before this class existed: that population is every game
//     in the file rather than the ones that replay, and its rule read
//     competition_type literally, so it swept in the 20 Colombian clubs and
//     the 109 national sides that the two corrections below exclude.)
//   BRIDGE - the two sides share no league, so the strength gap the prediction
//     reads is a gap BETWEEN pools. The scoped population item 39's gate is
//     judged on: inside one league a whole-league mispricing moves both sides
//     equally and a correct fix scores as a tie.
//
// This is a fact about COVERAGE, not about results - which competitions the
// run happens to carry, the same class of fact as "this run covers 65
// competitions". No rating and no outcome is read, so it is not the acausal
// warm-up pass ADR 0009 rejected.
public final class ClubPools {

    // clubId -> the league competition ids this run sees it play. Present and
    // empty for a club seen only in cups; absent for a club never seen at all,
    // and the two are deliberately different answers.
    private final Map<Long, Set<String>> leagues;

    // The sides that are not clubs. A national team plays no league football
    // either, so without this it would read as the biggest minnow in the run.
    private final Set<Long> nationalTeams;

    private ClubPools(Map<Long, Set<String>> leagues, Set<Long> nationalTeams) {
        this.leagues = leagues;
        this.nationalTeams = nationalTeams;
    }

    public static ClubPools of(List<Match> matches) {
        Map<Long, Set<String>> leagues = new HashMap<>();
        Set<Long> nationalTeams = new HashSet<>();
        for (Match m : matches) {
            for (long club : new long[] {m.home().id(), m.away().id()}) {
                Set<String> played = leagues.computeIfAbsent(club, k -> new HashSet<>());
                if (m.competition().isLeague()) {
                    played.add(m.competition().id());
                } else if (m.competition().kind() == Competition.Kind.NATIONAL_TEAM) {
                    nationalTeams.add(club);
                }
            }
        }
        return new ClubPools(leagues, nationalTeams);
    }

    // A club this run never prices: seen, never in a league, and a club.
    public boolean unpriced(long clubId) {
        Set<String> played = leagues.get(clubId);
        return played != null && played.isEmpty() && !nationalTeams.contains(clubId);
    }

    // Matches between clubs from different leagues (#39). Two sides that share
    // no league at all - including a league club against an unpriced one,
    // which is item 16's own population - and never two sides that have no
    // league between them, since nothing club-level moves that gap.
    public boolean isBridge(Match match) {
        Set<String> home = leagues.getOrDefault(match.home().id(), Set.of());
        Set<String> away = leagues.getOrDefault(match.away().id(), Set.of());
        if (home.isEmpty() && away.isEmpty()) {
            return false;
        }
        return Collections.disjoint(home, away);
    }

    // Clubs, so national sides are out of the denominator as well as out of
    // the numerator - "2,074 of 2,867 clubs" has to be counting one thing.
    public int clubs() {
        return leagues.size() - nationalTeams.size();
    }

    public int unpricedClubs() {
        int n = 0;
        for (long club : leagues.keySet()) {
            if (unpriced(club)) {
                n++;
            }
        }
        return n;
    }
}
