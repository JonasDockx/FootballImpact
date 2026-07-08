package com.goalimpact;

import com.goalimpact.data.DataLoader;
import com.goalimpact.model.Match;

import java.nio.file.Path;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of("C:/Users/dockx/Documents/Programmeren/DataStatsbomb/open-data/data");

        DataLoader loader = new DataLoader(dataDir);
        List<Match> matches = loader.loadMatches(55, 282);

        System.out.println("Matches loaded: " + matches.size());
        for (Match m : matches) {
            System.out.printf("%s %d-%d %s%n",
                m.home().name(), m.homeScore(), m.awayScore(), m.away().name());
        }
    }
}
