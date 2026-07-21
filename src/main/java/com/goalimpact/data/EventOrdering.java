package com.goalimpact.data;

import com.goalimpact.model.MatchEvent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// ADR 0009: the source stamps every event of a minute at the same instant
// and its event ids are hashes, so within a minute there is nothing to
// order by. The order is a convention instead - red card, then goal, then
// substitution - with one evidence-driven exception: a substitute who
// scores in the minute he arrives must have arrived before the goal.
//
// Each event is given a rank and the list is sorted by it, rather than
// compared pairwise: the exception makes pairwise comparison intransitive
// (a second goal in the same minute would sort before the substitution
// that must sort before the first goal), and Java refuses such a sort.
final class EventOrdering {

    private EventOrdering() {
    }

    private static final int STARTING_XI = 0;
    private static final int RED_CARD = 1;
    private static final int SCORING_SUBSTITUTE = 2;
    private static final int GOAL = 3;
    private static final int SUBSTITUTION = 4;

    // Sorts in place and stably: events the convention does not separate
    // keep the order the source listed them in.
    static void sort(List<MatchEvent> events) {
        Map<Integer, Set<Long>> scorersByInstant = new HashMap<>();
        for (MatchEvent event : events) {
            if (event instanceof MatchEvent.Goal goal && goal.scorer() != null) {
                scorersByInstant
                    .computeIfAbsent(instant(goal), key -> new HashSet<>())
                    .add(goal.scorer().id());
            }
        }
        events.sort(Comparator.comparingInt(EventOrdering::instant)
            .thenComparingInt(event -> rank(event, scorersByInstant)));
    }

    private static int instant(MatchEvent event) {
        return event.minute() * 60 + event.second();
    }

    private static int rank(MatchEvent event, Map<Integer, Set<Long>> scorersByInstant) {
        return switch (event) {
            case MatchEvent.RedCard card -> RED_CARD;
            case MatchEvent.Goal goal -> GOAL;
            case MatchEvent.Substitution substitution ->
                scorersByInstant.getOrDefault(instant(substitution), Set.of())
                    .contains(substitution.playerOn().id())
                        ? SCORING_SUBSTITUTE
                        : SUBSTITUTION;
            default -> STARTING_XI;
        };
    }
}
