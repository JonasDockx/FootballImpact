package com.goalimpact.credit;

import com.goalimpact.model.Player;

import java.util.Set;

// One side of a match as the residual seam sees it: who is on the pitch,
// and whether this side plays at its own venue (ADR 0008). At most one of
// a match's two lineups is ever home; on neutral ground neither is.

public record Lineup(Set<Player> players, boolean home) {
}
