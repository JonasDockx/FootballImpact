package com.goalimpact.data;

// One player named in a Held match (CONTEXT 'Match state', item 25) - a row of
// the certain-tier worklist. It carries only what the loader observed: the
// lineup row, plus the gate's verdict. A worklist row is "what we saw and what
// we decided"; the match's own facts (date, competition, opponent) stay in the
// vendor games table and are joined at display time, so a snapshot refresh can
// never leave the worklist quoting a stale date. reason is the
// UnusableMatchException category verbatim, which is what makes the per-run
// reconciliation a GROUP BY reason against the skip report.
public record HeldAppearance(long gameId, long clubId, long playerId,
    String playerName, boolean started, String reason) {
}
