package com.goalimpact.repair;

// One game_events row, carried through the editor untouched (item 26, stage
// 4b-2, decision 11). A released match replaces the vendor's copy whole, so its
// events must be copied verbatim or the replay would see a 0-0 with no
// substitutions; the editor shows them read-only as the cheapest guard that the
// right game id was materialised. description is kept whole - unlike the loader,
// which reduces it on read - because the writer's job is to preserve, not judge.
//
// playerInId is the ONLY nullable cell in the three copied-through tables
// (measured 2026-07-23: 630,547 of 1,274,469 event rows carry one; club_id and
// player_id never null; lineups and appearances never null). It is boxed so a
// vendor NULL survives the round trip as NULL rather than becoming 0 - which the
// stage-4 reproduction diff would catch as a spurious difference.
public record EventRow(long gameId, int minute, String type, long clubId,
    long playerId, Long playerInId, String description) {

    // The vendor's event-type string for a substitution (measured 2026-07-26:
    // "Substitutions", the only sub-type in the 4,694 events-bearing appeared
    // games). The appeared reconstruction reads it to tell a starter from a sub:
    // a player named as this event's playerInId came on, so he did not start.
    public static final String SUBSTITUTION = "Substitutions";
}
