package com.goalimpact.gui;

import com.goalimpact.data.WorklistReader;

import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;

// The repair tool's entry point (item 17; item 26, stage 4b-1). Stage 4b-1 is
// read-only - it browses the three-rung worklist and edits nothing - so this
// program is decoupled from the engine in the strongest possible sense: it
// opens two files read-only and never opens the sidecar at all.
//
// The worklist lives in the DISPOSABLE results DB, so the tool depends on a
// designated run having happened. A missing file or an absent table is a normal
// situation with a plain answer, not a stack trace.
public class GuiMain extends Application {

    // Mirrors Main's constants; the two programs read the same two files.
    private static final Path SNAPSHOT = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/transfermarkt-datasets.duckdb");
    private static final Path RESULTS = Path.of(
        "C:/Users/dockx/Documents/Programmeren/FootballData/goalimpact-results.duckdb");

    private WorklistReader reader;

    @Override
    public void start(Stage stage) {
        stage.setTitle("GoalImpact - repair worklist");
        stage.setScene(new Scene(root(), 1200, 760));
        stage.show();
    }

    private Parent root() {
        if (!Files.exists(RESULTS) || !Files.exists(SNAPSHOT)) {
            return message("No worklist found. Run 'mvn compile exec:java' first.");
        }
        try {
            reader = new WorklistReader(RESULTS, SNAPSHOT);
            // Touches all three worklist tables, so an un-run results file
            // fails here with a clear message rather than on the first search.
            String stamp = "run " + reader.runId() + "   worklist written "
                + Files.getLastModifiedTime(RESULTS).toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0);
            return new WorklistPane(reader, stamp);
        } catch (Exception e) {
            return message("Could not open the worklist: " + e.getMessage()
                + "\nRun 'mvn compile exec:java' to build it.");
        }
    }

    private static Parent message(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        BorderPane pane = new BorderPane(label);
        pane.setPrefSize(600, 200);
        return pane;
    }

    @Override
    public void stop() throws Exception {
        if (reader != null) {
            reader.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
