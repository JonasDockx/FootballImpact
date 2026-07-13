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
        all.sort(Comparator.comparingDouble(PlayerTally::per90).reversed());

        try (Writer writer = Files.newBufferedWriter(file)) {
            writer.write("player,team,minutes,raw_total,per90\n");
            for (PlayerTally pt : all) {
                writer.write(String.format(Locale.ROOT, "%s,%s,%.1f,%.1f,%.2f",
                    escape(pt.player().name()), escape(pt.team().name()),
                    pt.minutes(), pt.rawTotal(), pt.per90()));
                writer.write("\n");
            }
        }
    }

    private String escape(String value) {
        if (value.contains(",") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
