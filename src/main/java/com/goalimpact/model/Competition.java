package com.goalimpact.model;

// Which competition a match belongs to, and what kind of football it is.
//
// The id is the spine's own stable competition id ("GB1", "FAC"), the same
// identifier item 9 will hang a per-competition level term off. The kind is a
// domain judgement, mapped at the loader boundary (ADR 0004) rather than the
// vendor's own word carried through: Transfermarkt names five competition
// types and gets some of them wrong, StatsBomb names none of them, and neither
// vocabulary belongs in the engine.
//
// Only three kinds, because only three distinctions are load-bearing. LEAGUE
// is what makes a club PRICED - a run that sees a club play its league sees it
// repeatedly, against the same pool, so it learns what the club is worth.
// NATIONAL_TEAM is carved out because a national side plays no league and yet
// is nobody's cup minnow: the whole point of the carve-out is that "plays no
// league football" must not be read as "unknown" there. Everything else -
// cups, super cups, European ties - is OTHER, which says only that it is not
// evidence about a club's level in its own right.
public record Competition(String id, Competition.Kind kind) {

    public enum Kind { LEAGUE, NATIONAL_TEAM, OTHER }

    public boolean isLeague() {
        return kind == Kind.LEAGUE;
    }
}
