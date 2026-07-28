package com.goalimpact.repair;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Who a match's own events prove was playing (item 17, slice 2, decision 1).
// A maybe-tier match has neither a team sheet nor an appearances record, but 94%
// of them carry events, and an event names a man: he scored, was booked, went
// off or came on. That is a median of 18 of the 22, with his club, and it is a
// reading of the record rather than a guess - the same record that will later
// drive the replay (glossary 'Derived lineup').
//
// This is the pure half. It answers only *who and which side*; the names,
// positions and the starter/bench split are somebody else's job.
public final class EventRoster {

    // One man an event named, and the side he was on.
    public record Slot(long clubId, long playerId) {
    }

    private EventRoster() {
    }

    public static List<Slot> from(List<EventRow> events, long homeClubId, long awayClubId) {
        Set<Slot> slots = new LinkedHashSet<>();
        for (EventRow event : events) {
            slots.add(new Slot(clubOf(event, homeClubId, awayClubId), event.playerId()));
            // A substitution's own club is always right for both men, own goals
            // being the only exception and never a substitution.
            if (EventRow.SUBSTITUTION.equals(event.type()) && event.playerInId() != null) {
                slots.add(new Slot(event.clubId(), event.playerInId()));
            }
        }
        return new ArrayList<>(slots);
    }

    // Decision 8. club_id is the player's club everywhere but here: a Goals row
    // carries the club the goal *counted for*, so an own goal's scorer plays for
    // the other side. Measured against real team sheets, that is 3.0% of goal
    // rows wrong if taken at face value, against 0.03-0.13% for cards and
    // substitutions - and it would seed a man into the wrong XI in silence.
    private static long clubOf(EventRow event, long homeClubId, long awayClubId) {
        if (!GOAL.equals(event.type()) || !ownGoal(event.description())) {
            return event.clubId();
        }
        return event.clubId() == homeClubId ? awayClubId : homeClubId;
    }

    private static boolean ownGoal(String description) {
        return description != null && description.toLowerCase().contains(OWN_GOAL);
    }

    // The vendor's spellings, exact-match, beside the ones LineupEntry pins.
    private static final String GOAL = "Goals";
    private static final String OWN_GOAL = "own-goal";
}
