package com.goalimpact.report;

import com.goalimpact.engine.PlayerTally;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class CsvWriter {
    public void write(Path file, Collection<PlayerTally> tallies) throws IOException {
        List<PlayerTally> all = new ArrayList<>(tallies);
        all.sort(Comparator.comparingDouble(PlayerTally::rating).reversed());

        try (Writer writer = Files.newBufferedWriter(file)) {
            writer.write("player,team,minutes,rating,goalkeeper\n");
            for (PlayerTally pt : all) {
                writer.write(String.format(Locale.ROOT, "%s,%s,%.1f,%.2f,%s",
                    escape(pt.player().name()), escape(pt.team().name()),
                    pt.minutes(), pt.rating(), pt.isGoalkeeper()));
                writer.write("\n");
            }
        }
    }

    // 86 clubs in the snapshot have no name at all. An unnamed club is
    // still a real club with a real rating; only its label is missing, so
    // it gets an empty cell rather than stopping the run on its last line.
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

}
