package com.goalimpact.gui;

import com.goalimpact.data.ClubHit;
import com.goalimpact.data.ClubMatchRow;
import com.goalimpact.data.RepairSource;
import com.goalimpact.data.SidecarStore;
import com.goalimpact.data.WorklistReader;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

// The other door into the worklist (item 29, slice 2), and the one that reaches
// every Held match. The player door can only offer a match some tier names a
// player for; the maybe tier draws its candidates from the club's squad within
// a month, and an isolated fixture has no such squad, so it named nobody and
// could not be opened at all - 1,809 of 3,909 matches, measured 2026-07-28. A
// match always knows its two clubs, so this door reaches 100% by construction.
//
// Dumb like WorklistPane: no SQL, no judgement, it draws what the reader hands
// it. One table rather than three, because every row here means the same thing -
// the three rungs of the player pane exist because a row's meaning changes per
// rung, and a club row has no tier at all (CONTEXT 'Repair source').
class ClubPane extends BorderPane {

    private static final String HINT =
        "Every Held match of this club, newest first. 'source' is what the "
        + "repair would start from; a match with none cannot be opened yet - "
        + "its events cannot be rebuilt, and releasing it would claim nothing "
        + "happened in it.";

    private final WorklistReader reader;
    private final SidecarStore store;

    private final TextField search = new TextField();
    private final ListView<ClubHit> clubs = new ListView<>();
    private final Label message = new Label();
    private final Label clubName = new Label();
    private final TableView<ClubMatchRow> matches = new TableView<>();

    // Filled from the sidecar each time a club is opened, so a match repaired
    // this afternoon stops looking like work at once - held_matches is only
    // rewritten by a designated run, and a release never throws.
    private Map<Long, String> statuses = new HashMap<>();

    ClubPane(WorklistReader reader, SidecarStore store) {
        this.reader = reader;
        this.store = store;

        search.setPromptText("club or country name, then Enter");
        search.setPrefWidth(240);
        search.setOnAction(e -> runSearch());

        clubs.setPrefWidth(280);
        clubs.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ClubHit hit, boolean empty) {
                super.updateItem(hit, empty);
                setText(empty || hit == null
                    ? null : hit.clubName() + "  (" + hit.heldMatches() + ")");
            }
        });
        clubs.getSelectionModel().selectedItemProperty()
            .addListener((o, was, hit) -> showMatches(hit));

        buildTable();
        message.setWrapText(true);

        HBox top = new HBox(10, new Label("Club:"), search);
        top.setPadding(new Insets(8));
        setTop(top);

        VBox left = new VBox(6, message, clubs);
        left.setPadding(new Insets(8));
        VBox.setVgrow(clubs, Priority.ALWAYS);
        setLeft(left);

        Label hint = new Label(HINT);
        hint.setWrapText(true);
        VBox centre = new VBox(6, clubName, hint, matches);
        centre.setPadding(new Insets(8));
        VBox.setVgrow(matches, Priority.ALWAYS);
        setCenter(centre);
    }

    // Every club the vendor has a fixture for, not only those with work: a count
    // of zero is a real answer meaning the club is complete, which is the one
    // thing the player search can never say about a career.
    private void runSearch() {
        String typed = search.getText().trim();
        if (typed.isEmpty()) {
            return;
        }
        try {
            List<ClubHit> hits = reader.searchClubs(typed);
            clubs.setItems(FXCollections.observableArrayList(hits));
            message.setText(hits.isEmpty()
                ? "No club found for '" + typed + "' - the vendor spells it "
                    + "differently, or has no fixtures for it."
                : hits.size() + " club(s), most work first");
            if (hits.size() == 1) {
                clubs.getSelectionModel().selectFirst();
            }
        } catch (SQLException e) {
            message.setText("Search failed: " + e.getMessage());
        }
    }

    private void showMatches(ClubHit hit) {
        if (hit == null) {
            return;
        }
        try {
            statuses = store.sidecarStatuses();
            List<ClubMatchRow> rows = reader.heldMatchesFor(hit.clubId());
            clubName.setText(hit.clubName() + "   (club " + hit.clubId() + ")   "
                + rows.size() + " held match(es)");
            matches.setItems(FXCollections.observableArrayList(rows));
        } catch (SQLException e) {
            message.setText("Could not read this club's matches: " + e.getMessage());
        }
    }

    // A match with no surviving record is listed but not opened. Slice 2 of item
    // 17 derives a lineup from a match's own events, and this one has none;
    // events are not editable yet (item 17, slice 3), and a released match
    // replaces the vendor's copy wholesale - so naming its players and releasing
    // it would assert a goalless, cardless, substitution-free match and move
    // every one of those ratings on a fact nobody established.
    private void openEditor(ClubMatchRow row) {
        if (row.repairSource() == RepairSource.NOTHING) {
            message.setText("No surviving record for this match - it needs event "
                + "editing (item 17, slice 3) before it can be repaired.");
            return;
        }
        try {
            new RepairEditor(store, row.match().gameId()).showAndWait();
            statuses = store.sidecarStatuses();      // the row may have just been done
            matches.refresh();
        } catch (SQLException e) {
            message.setText("Could not open the match: " + e.getMessage());
        }
    }

    private void buildTable() {
        matches.setPlaceholder(new Label("no held matches - this club is complete"));
        matches.setRowFactory(t -> {
            TableRow<ClubMatchRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    openEditor(row.getItem());
                }
            });
            return row;
        });
        column("date", 95, r -> r.match().date().toString());
        column("comp", 60, r -> r.match().competition());
        column("round", 130, r -> r.match().round());
        column("match", 300, r -> r.match().homeName() + " v " + r.match().awayName());
        column("source", 100, r -> label(r.repairSource()));
        column("reason", 150, ClubMatchRow::reason);
        column("state", 80, r -> statuses.getOrDefault(r.match().gameId(), ""));
    }

    private static String label(RepairSource source) {
        return switch (source) {
            case TEAM_SHEET -> "team sheet";
            case APPEARANCES -> "appearances";
            case EVENTS -> "events";
            case NOTHING -> "nothing";
        };
    }

    private void column(String title, double width, Function<ClubMatchRow, String> value) {
        TableColumn<ClubMatchRow, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(
            cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        matches.getColumns().add(column);
    }
}
