package com.goalimpact.gui;

import com.goalimpact.data.SidecarStore;
import com.goalimpact.repair.EditableMatch;
import com.goalimpact.repair.EventRow;
import com.goalimpact.repair.LineupEntry;
import com.goalimpact.repair.MatchHeader;
import com.goalimpact.repair.SideStatus;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

// The editor (item 26, stage 4b-2), and the only window that can reach the
// sidecar's write path. It stays as dumb as the read-only pane: every judgement
// - whether the match is fixed, what the provenance seed says - comes from the
// EditableMatch (the pure repair layer), and every file touch goes through
// SidecarStore (the data layer). This class holds no SQL and no rule of its own.
//
// The match is held in one mutable field because EditableMatch is immutable: an
// edit replaces it and refresh() redraws from the new value, so the screen and
// the object can never disagree. Stage 4b-2's two moves - retag a position, move
// a player between the XI and the bench - act on whichever lineup row is selected.
//
// Item 17, slice 1 adds the third move: put a player in. It is two club-named
// buttons rather than one, because the side cannot be inferred from a selected row
// - the hardest case is a side absent entirely, where there is no row to select
// (decision 7). Remove undoes an add and nothing else (decision 8): a recorded
// player can never be dropped, however wrong he looks.
class RepairEditor extends Stage {

    private final SidecarStore store;
    private EditableMatch match;

    private final TableView<LineupEntry> lineup = new TableView<>();
    private final Label problems = new Label();
    private final Label sides = new Label();
    private final TextField position = new TextField();
    private final TextArea provenance = new TextArea();
    private final Button release = new Button("Release");
    private final Button remove = new Button("Remove (undo add)");
    private final Button nameHim = new Button("Name this player");

    // Once the provenance box is edited by hand it is never overwritten by the
    // generated seed; seeding guards against the programmatic setText being
    // mistaken for a hand edit.
    private boolean provenanceEdited = false;
    private boolean seeding = false;

    RepairEditor(SidecarStore store, long gameId) throws SQLException {
        this.store = store;
        // The manual-id ceiling is read once, here, and threaded through every
        // edit: ids are handed out max+1, max+2 from that single read, and the
        // window is APPLICATION_MODAL so no second repair can be minting at the
        // same time (ADR 0012, decision 3).
        this.match = store.load(gameId).withManualIdCeiling(store.highestManualPlayerId());
        initModality(Modality.APPLICATION_MODAL);
        setTitle("Repair - game " + gameId);
        setScene(new Scene(build(), 1000, 740));
        refresh();
    }

    private Parent build() {
        MatchHeader h = match.header();
        Label header = new Label(h.date() + "   " + h.competitionId() + " " + h.season()
            + "   " + h.homeClubLabel() + " v " + h.awayClubLabel()
            + "   (" + h.homeClubGoals() + "-" + h.awayClubGoals() + ")");

        buildLineupTable();
        TableView<EventRow> events = buildEventsTable();

        position.setPromptText("new position, e.g. Goalkeeper");
        position.setPrefWidth(240);
        Button setPosition = new Button("Set position");
        setPosition.setOnAction(e -> setPosition());
        Button toXi = new Button("To starting XI");
        toXi.setOnAction(e -> edit(m -> m.asStarter(selectedId())));
        Button toBench = new Button("To bench");
        toBench.setOnAction(e -> edit(m -> m.asBench(selectedId())));
        remove.setOnAction(e -> removeSelected());
        nameHim.setOnAction(e -> nameSelected());
        HBox edits = new HBox(8, new Label("Selected player:"),
            position, setPosition, toXi, toBench, remove, nameHim);

        Button addHome = new Button("Add to " + h.homeClubLabel());
        addHome.setOnAction(e -> addTo(h.homeClubId(), h.homeClubLabel()));
        Button addAway = new Button("Add to " + h.awayClubLabel());
        addAway.setOnAction(e -> addTo(h.awayClubId(), h.awayClubLabel()));
        HBox adds = new HBox(8, new Label("Add a player:"), addHome, addAway);

        provenance.setPrefRowCount(4);
        provenance.setWrapText(true);
        provenance.textProperty().addListener((o, was, now) -> {
            if (!seeding) {
                provenanceEdited = true;
            }
        });

        Button draft = new Button("Save draft");
        draft.setOnAction(e -> save("draft"));
        release.setOnAction(e -> save("released"));
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> close());
        HBox buttons = new HBox(8, draft, release, cancel);

        problems.setWrapText(true);
        sides.setWrapText(true);

        VBox box = new VBox(8, header, sides, problems, lineup, edits, adds,
            new Label("Events (read-only - copied through unchanged):"), events,
            new Label("Provenance (seeded from the edits; add why before releasing):"),
            provenance, buttons);
        box.setPadding(new Insets(10));
        VBox.setVgrow(lineup, Priority.ALWAYS);
        return box;
    }

    private void setPosition() {
        String typed = position.getText().trim();
        if (typed.isEmpty()) {
            problems.setText("Type a position first.");
            return;
        }
        edit(m -> m.withPosition(selectedId(), typed));
    }

    // Open the ranked picker for one side and put back whatever it returned. A
    // named player is added by his own id; a hand-made one is minted into the
    // reserved range by EditableMatch, which also carries his register row until
    // save writes it (ADR 0012).
    private void addTo(long clubId, String clubName) {
        PlayerPicker.pick(this, store, match, clubId, clubName).ifPresent(pick -> {
            match = pick.isNew()
                ? match.create(clubId, pick.name(), pick.position(), pick.dateOfBirth(),
                    pick.note())
                : match.add(clubId, pick.playerId(), pick.name(), pick.position());
            refresh();
        });
    }

    // Decision 8: an undo of this session's adds, not an editing power. A recorded
    // player - including one added into a draft and reloaded, which comes back
    // through the vendor constructor as recorded - stays put.
    private void removeSelected() {
        LineupEntry selected = lineup.getSelectionModel().getSelectedItem();
        if (selected == null) {
            problems.setText("Select a player row first.");
            return;
        }
        if (!match.isAdded(selected.playerId())) {
            problems.setText(selected.playerName()
                + " is in the record for this match, so he cannot be removed -"
                + " only players added in this session can.");
            return;
        }
        match = match.remove(selected.playerId());
        refresh();
    }

    // Put a name to a player the vendor references but never names (decision 9).
    // Offered only on such a row: a man the vendor already names is not this
    // problem, and overwriting his name here would be a second, undecided feature.
    private void nameSelected() {
        LineupEntry selected = lineup.getSelectionModel().getSelectedItem();
        if (selected == null) {
            problems.setText("Select a player row first.");
            return;
        }
        if (!selected.unnamed()) {
            problems.setText(selected.playerName()
                + " is already named by the vendor's own records, so there is"
                + " nothing to supply here.");
            return;
        }
        try {
            NamePlayerDialog.name(this, store, selected, match.header().gameId())
                .ifPresent(naming -> {
                    match = match.name(selected.playerId(), naming.name(),
                        naming.dateOfBirth(), naming.note());
                    refresh();
                });
        } catch (SQLException e) {
            problems.setText("Could not read his other matches: " + e.getMessage());
        }
    }

    // Apply an edit to the selected player, or say why nothing happened.
    private void edit(UnaryOperator<EditableMatch> op) {
        if (lineup.getSelectionModel().getSelectedItem() == null) {
            problems.setText("Select a player row first.");
            return;
        }
        match = op.apply(match);
        refresh();
    }

    private long selectedId() {
        LineupEntry selected = lineup.getSelectionModel().getSelectedItem();
        return selected == null ? -1 : selected.playerId();
    }

    // Redraw everything that depends on the current match: the lineup, the live
    // verdict, the Release gate, and (unless hand-edited) the provenance seed.
    private void refresh() {
        LineupEntry keep = lineup.getSelectionModel().getSelectedItem();
        lineup.setItems(FXCollections.observableArrayList(match.lineup()));
        if (keep != null) {
            match.lineup().stream()
                .filter(e -> e.playerId() == keep.playerId())
                .findFirst()
                .ifPresent(e -> lineup.getSelectionModel().select(e));
        }

        // Always on, not only when the match is Held: problems() speaks the
        // loader's words and cannot name a side, and counting rows on screen is
        // exactly what the operator should never have to do.
        sides.setText(match.sides().stream()
            .map(SideStatus::summary)
            .collect(Collectors.joining("\n")));

        List<String> reasons = match.problems();
        if (reasons.isEmpty()) {
            problems.setText("No problems - this match would rate. Ready to release.");
            release.setDisable(false);
        } else {
            problems.setText("Held: " + String.join("; ", reasons));
            release.setDisable(true);
        }

        if (!provenanceEdited) {
            seeding = true;
            provenance.setText(match.provenanceSummary());
            seeding = false;
        }
    }

    private void save(String status) {
        try {
            store.save(match, status, provenance.getText());
            alert(Alert.AlertType.INFORMATION, status.equals("released")
                ? "Released. Re-run the replay (mvn compile exec:java) to see it move."
                : "Draft saved.");
            close();
        } catch (SQLException e) {
            alert(Alert.AlertType.ERROR, "Save failed: " + e.getMessage());
        }
    }

    private static void alert(Alert.AlertType type, String message) {
        new Alert(type, message).showAndWait();
    }

    private void buildLineupTable() {
        lineup.setPlaceholder(new Label("no lineup"));
        column(lineup, "club", 70, e -> String.valueOf(e.clubId()));
        column(lineup, "player id", 90, e -> String.valueOf(e.playerId()));
        column(lineup, "name", 220, LineupEntry::playerName);
        column(lineup, "position", 160, LineupEntry::position);
        column(lineup, "role", 80, e -> e.starter() ? "XI" : "bench");
        column(lineup, "GK?", 60, e -> e.goalkeeper() ? "GK" : "");
        // Which rows this session put here, so the operator can see at a glance
        // what is his own work and what came from the record - and which rows
        // Remove will accept.
        column(lineup, "added", 90, e -> match.isNamed(e.playerId()) ? "named"
            : !match.isAdded(e.playerId()) ? ""
            : match.isCreated(e.playerId()) ? "created" : "added");
    }

    private TableView<EventRow> buildEventsTable() {
        TableView<EventRow> events = new TableView<>();
        events.setPlaceholder(new Label("no events"));
        events.setPrefHeight(150);
        column(events, "min", 50, e -> String.valueOf(e.minute()));
        column(events, "type", 130, EventRow::type);
        column(events, "club", 70, e -> String.valueOf(e.clubId()));
        column(events, "player", 90, e -> String.valueOf(e.playerId()));
        column(events, "in", 90, e -> e.playerInId() == null ? "" : String.valueOf(e.playerInId()));
        column(events, "description", 320, EventRow::description);
        events.setItems(FXCollections.observableArrayList(match.events()));
        return events;
    }

    private static <T> void column(TableView<T> table, String title, double width,
        Function<T, String> value) {

        TableColumn<T, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(
            cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        table.getColumns().add(column);
    }
}
