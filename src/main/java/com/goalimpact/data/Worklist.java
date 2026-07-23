package com.goalimpact.data;

import java.util.List;

// Everything one player's worklist knows: his three rungs of shrinking
// confidence (CONTEXT 'Worklist tier'), each already ordered, most recent match
// first. Three lists rather than one tagged list because a row's meaning differs
// per rung and each rung carries its own ranking signal - the same reason stage
// 4a wrote two tables rather than one with blank-when-not-applicable columns
// (item 26). The repair GUI draws them as three sections and never merges them.
public record Worklist(long playerId, String playerName,
    List<CertainRow> certain, List<AppearedRow> appeared,
    List<MaybeRow> maybe) {
}
