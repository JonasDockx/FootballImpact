package com.goalimpact.credit;

import com.goalimpact.model.Player;

import java.util.Set;

// One side of a match as the residual seam sees it: who is on the pitch,
// whether this side plays at its own venue (ADR 0008), and which of its
// on-pitch players carry the career Goalkeeper tag (item 11) - empty when
// no keeper is on the pitch. At most one of a match's two lineups is ever
// home; on neutral ground neither is.

public record Lineup(Set<Player> players, boolean home, Set<Player> goalkeepers) {
}
