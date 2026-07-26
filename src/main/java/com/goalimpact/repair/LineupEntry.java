package com.goalimpact.repair;

// One game_lineups row, seen from the editor's side (item 26, stage 4b-2). This
// is where the sidecar's two load-bearing strings live: a row is a starter when
// its type is exactly "starting_lineup", and a goalkeeper when its position is
// exactly "Goalkeeper". TransfermarktLoader makes the identical two tests when it
// reads the vendor rows, and the two must never disagree - a match the loader
// would rate is one this editor must call clean - so the strings are pinned here
// as constants and an agreement test (stage 4b-2, step 4) drives real matches
// through both.
//
// A position-only repair is a new position string on one row; moving a player
// between the XI and the bench is a new type string. Both return a fresh row -
// the editor holds an EditableMatch and replaces it, so no cell is ever mutated
// underneath the screen.
public record LineupEntry(long clubId, long playerId, String playerName,
    String position, String type) {

    // The three vendor spellings, exact-match. "substitutes" is the only other
    // type in 3.18M rows (measured 2026-07-23); a row is benched when its type
    // is not the starter string, and the writer copies the string through.
    public static final String STARTER = "starting_lineup";
    public static final String BENCH = "substitutes";
    public static final String GOALKEEPER = "Goalkeeper";

    public boolean starter() {
        return STARTER.equals(type);
    }

    public boolean goalkeeper() {
        return GOALKEEPER.equals(position);
    }

    public LineupEntry withPosition(String newPosition) {
        return new LineupEntry(clubId, playerId, playerName, newPosition, type);
    }

    public LineupEntry asStarter() {
        return new LineupEntry(clubId, playerId, playerName, position, STARTER);
    }

    public LineupEntry asBench() {
        return new LineupEntry(clubId, playerId, playerName, position, BENCH);
    }
}
