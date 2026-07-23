package com.goalimpact.gui;

import com.goalimpact.data.AppearedRow;
import com.goalimpact.data.CertainRow;
import com.goalimpact.data.MatchFacts;
import com.goalimpact.data.MaybeRow;
import com.goalimpact.data.Worklist;
import com.goalimpact.data.WorklistPlayer;
import com.goalimpact.data.WorklistReader;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;

// The whole screen (item 26, stage 4b-1), and deliberately dumb: it holds no
// SQL, makes no judgement, and draws only what WorklistReader hands it. The
// three rungs are drawn as three sections and never merged - a row's meaning
// differs per rung and each earns its own last column, the same reason stage 4a
// wrote two tables rather than one with blank-when-not-applicable columns.
// Read-only throughout: nothing here can reach the sidecar, which is not even
// opened until stage 4b-2.
class WorklistPane extends BorderPane {

    private final WorklistReader reader;

    private final TextField search = new TextField();
    private final ListView<WorklistPlayer> players = new ListView<>();
    private final Label message = new Label();
    private final Label playerName = new Label();

    private final Label certainHeader = new Label();
    private final Label appearedHeader = new Label();
    private final Label maybeHeader = new Label();

    private final TableView<CertainRow> certain = new TableView<>();
    private final TableView<AppearedRow> appeared = new TableView<>();
    private final TableView<MaybeRow> maybe = new TableView<>();

    WorklistPane(WorklistReader reader, String stamp) {
        this.reader = reader;

        search.setPromptText("player name, then Enter");
        search.setPrefWidth(240);
        search.setOnAction(e -> runSearch());

        players.setPrefWidth(280);
        players.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(WorklistPlayer hit, boolean empty) {
                super.updateItem(hit, empty);
                setText(empty || hit == null
                    ? null : hit.playerName() + "  (" + hit.missingMatches() + ")");
            }
        });
        players.getSelectionModel().selectedItemProperty()
            .addListener((o, was, hit) -> showWorklist(hit));

        buildTables();
        headers(0, 0, 0);
        message.setWrapText(true);

        HBox top = new HBox(10, new Label("Search:"), search, new Label(stamp));
        top.setPadding(new Insets(8));
        setTop(top);

        VBox left = new VBox(6, message, players);
        left.setPadding(new Insets(8));
        VBox.setVgrow(players, Priority.ALWAYS);
        setLeft(left);

        VBox centre = new VBox(6, playerName,
            certainHeader, certain, appearedHeader, appeared, maybeHeader, maybe);
        centre.setPadding(new Insets(8));
        VBox.setVgrow(certain, Priority.ALWAYS);
        VBox.setVgrow(appeared, Priority.ALWAYS);
        VBox.setVgrow(maybe, Priority.ALWAYS);
        setCenter(centre);
    }

    // Searching the worklist's own names can only offer a player there is work
    // for; the price is that a miss cannot tell a complete career from a typo,
    // so the screen says exactly that rather than implying the former.
    private void runSearch() {
        String typed = search.getText().trim();
        if (typed.isEmpty()) {
            return;
        }
        try {
            List<WorklistPlayer> hits = reader.searchPlayers(typed);
            players.setItems(FXCollections.observableArrayList(hits));
            message.setText(hits.isEmpty()
                ? "No missing matches found for '" + typed + "' - either this "
                    + "player's data is complete, or his name is spelt "
                    + "differently in the vendor's data."
                : hits.size() + " player(s) with missing matches");
            if (hits.size() == 1) {
                players.getSelectionModel().selectFirst();
            }
        } catch (SQLException e) {
            message.setText("Search failed: " + e.getMessage());
        }
    }

    private void showWorklist(WorklistPlayer hit) {
        if (hit == null) {
            return;
        }
        try {
            Worklist w = reader.worklistFor(hit.playerId());
            playerName.setText(w.playerName() + "   (player " + w.playerId() + ")");
            certain.setItems(FXCollections.observableArrayList(w.certain()));
            appeared.setItems(FXCollections.observableArrayList(w.appeared()));
            maybe.setItems(FXCollections.observableArrayList(w.maybe()));
            headers(w.certain().size(), w.appeared().size(), w.maybe().size());
        } catch (SQLException e) {
            message.setText("Could not read the worklist: " + e.getMessage());
        }
    }

    private void headers(int certainRows, int appearedRows, int maybeRows) {
        certainHeader.setText(
            "CERTAIN - named in a broken team sheet  (" + certainRows + ")");
        appearedHeader.setText(
            "APPEARED - the appearances record names him  (" + appearedRows + ")");
        maybeHeader.setText(
            "MAYBE - his club played nearby, he may have been in it  (" + maybeRows + ")");
    }

    private void buildTables() {
        certain.setPlaceholder(new Label("none"));
        addMatchColumns(certain, CertainRow::match);
        addColumn(certain, "club", 70, r -> String.valueOf(r.clubId()));
        addColumn(certain, "started", 70, r -> r.started() ? "yes" : "no");
        addColumn(certain, "reason", 150, CertainRow::reason);

        appeared.setPlaceholder(new Label("none"));
        addMatchColumns(appeared, AppearedRow::match);
        addColumn(appeared, "club", 70, r -> String.valueOf(r.clubId()));
        addColumn(appeared, "minutes", 80, r -> String.valueOf(r.minutes()));

        maybe.setPlaceholder(new Label("none"));
        addMatchColumns(maybe, MaybeRow::match);
        addColumn(maybe, "club", 70, r -> String.valueOf(r.clubId()));
        addColumn(maybe, "nearby matches", 120, r -> String.valueOf(r.nearbyMatches()));
    }

    // The five vendor match facts are identical in all three rungs, so they are
    // described once; only the last column differs, which is the point.
    private static <T> void addMatchColumns(TableView<T> table, Function<T, MatchFacts> facts) {
        addColumn(table, "date", 95, r -> facts.apply(r).date().toString());
        addColumn(table, "comp", 60, r -> facts.apply(r).competition());
        addColumn(table, "round", 130, r -> facts.apply(r).round());
        addColumn(table, "match", 300,
            r -> facts.apply(r).homeName() + " v " + facts.apply(r).awayName());
    }

    private static <T> void addColumn(TableView<T> table, String title, double width,
        Function<T, String> value) {

        TableColumn<T, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(
            cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        table.getColumns().add(column);
    }
}
