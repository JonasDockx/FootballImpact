package com.goalimpact.model;

import java.util.List;

public sealed interface MatchEvent {
    int period();
    int minute();
    int second();

    record StartingXI(int period, int minute, int second,
        Team team, List<Player> players, Player goalkeeper) implements MatchEvent {}

    record Substitution(int period, int minute, int second,
        Team team, Player playerOff, Player playerOn) implements MatchEvent {}
    
    record Goal(int period, int minute, int second,
        Team scoringTeam, Player scorer) implements MatchEvent {}
    
    record RedCard(int period, int minute, int second,
        Team team, Player player) implements MatchEvent {}

    record MatchEnd(int period, int minute, int second) implements MatchEvent {}
}
