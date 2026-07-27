package com.goalimpact.gui;

import com.goalimpact.data.SidecarStore;
import com.goalimpact.repair.CandidateRanker;
import com.goalimpact.repair.EditableMatch;
import com.goalimpact.repair.MatchHeader;
import com.goalimpact.repair.PlayerCandidate;
import com.goalimpact.repair.RankedCandidate;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

// The ranked player picker (item 17, slice 1). It is what lets a repair *add* a
// player, and so what unlocks every incomplete-XI case at once - the certain rows
// with ten starters, the imperfect appeared reconstructions, and the sides the
// vendor omits entirely.
//
// Like the rest of gui it holds no rule of its own: SidecarStore gathers the
// evidence, CandidateRanker turns it into 'Candidate rank', and this only draws
// the result and reports what was chosen. The order it draws is a typing aid and
// never evidence (decision 1) - verification comes from a source outside the tool,
// so a wrong guess here costs a keystroke.
//
// With nothing typed the list is the club's squad around the match date, which
// makes an absent side eleven clicks down a pre-ranked list. Typing filters and
// reveals the other two rungs. The create panel sits at the bottom and is never
// preselected; it is a panel and not a list row because a hand-made player's date
// of birth and note have nowhere else to be captured, and are lost the moment the
// source page is closed (ADR 0012, decision 2).
final class PlayerPicker extends Stage {

    // Rank 2 is 114,893 players. The reader caps each SQL arm and the ranker caps
    // what is drawn; both are this number, so the screen and the query agree.
    private static final int CAP = 50;

    // What the picker came back with: an existing player by id, or - when playerId
    // is null - a new one to be minted into the reserved range by EditableMatch.
    record Pick(Long playerId, String name, String position, LocalDate dateOfBirth,
        String note) {

        boolean isNew() {
            return playerId == null;
        }
    }

    private final SidecarStore store;
    private final EditableMatch match;
    private final long clubId;

    private final TextField search = new TextField();
    private final TableView<RankedCandidate> results = new TableView<>();
    private final Label status = new Label();

    private final TextField newPosition = new TextField();
    private final TextField newDateOfBirth = new TextField();
    private final TextField newNote = new TextField();
    private final Button create = new Button("Create");

    private Pick pick;

    private PlayerPicker(SidecarStore store, EditableMatch match, long clubId,
        String clubName) {
        this.store = store;
        this.match = match;
        this.clubId = clubId;
        initModality(Modality.APPLICATION_MODAL);
        setTitle("Add a player to " + clubName);
        setScene(new Scene(build(), 900, 620));
        reload();
    }

    // Open the picker for one side and wait. Empty when it was cancelled.
    static Optional<Pick> pick(Stage owner, SidecarStore store, EditableMatch match,
        long clubId, String clubName) {

        PlayerPicker picker = new PlayerPicker(store, match, clubId, clubName);
        picker.initOwner(owner);
        picker.showAndWait();
        return Optional.ofNullable(picker.pick);
    }

    private Parent build() {
        search.setPromptText("type a name to search beyond the nearby squad");
        search.textProperty().addListener((o, was, now) -> reload());

        buildResultsTable();
        results.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                addSelected();
            }
        });

        Button add = new Button("Add selected");
        add.setOnAction(e -> addSelected());
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> close());

        newPosition.setPromptText("position, e.g. Goalkeeper");
        newDateOfBirth.setPromptText("date of birth (optional, yyyy-mm-dd)");
        newNote.setPromptText("note - where you found him");
        create.setOnAction(e -> createTyped());

        HBox createRow = new HBox(8, create, newPosition, newDateOfBirth, newNote);
        HBox.setHgrow(newNote, Priority.ALWAYS);

        status.setWrapText(true);
        VBox box = new VBox(8, search, results, status,
            new Label("Nobody here is him? Name him by hand - he takes a reserved id"
                + " and joins the register:"),
            createRow, new HBox(8, add, cancel));
        box.setPadding(new Insets(10));
        VBox.setVgrow(results, Priority.ALWAYS);
        return box;
    }

    // Re-ask on every keystroke. The club arm is a squad and the search arms are
    // capped, so the query stays small enough to run inline.
    private void reload() {
        String typed = search.getText() == null ? "" : search.getText();
        MatchHeader h = match.header();
        try {
            List<PlayerCandidate> pool =
                store.candidates(clubId, h.gameId(), h.date(), typed, CAP);
            List<RankedCandidate> ranked =
                CandidateRanker.rank(pool, typed, match.membership(), CAP);
            results.setItems(FXCollections.observableArrayList(ranked));
            status.setText(typed.isBlank()
                ? ranked.size() + " in the squad around " + h.date()
                    + " - type a name to search wider"
                : ranked.size() + " matching \"" + typed + "\"");
        } catch (SQLException failed) {
            results.setItems(FXCollections.observableArrayList());
            status.setText("Candidate lookup failed: " + failed.getMessage());
        }
        create.setText(typed.isBlank() ? "Create" : "Create \"" + typed.trim() + "\"");
        create.setDisable(typed.isBlank());
    }

    private void addSelected() {
        RankedCandidate selected = results.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status.setText("Select a name first, or create one below.");
            return;
        }
        if (selected.unavailable()) {
            status.setText(selected.candidate().playerName() + " is "
                + selected.alreadyIn() + ".");
            return;
        }
        PlayerCandidate c = selected.candidate();
        pick = new Pick(c.playerId(), c.playerName(), c.position(), c.dateOfBirth(), null);
        close();
    }

    private void createTyped() {
        String name = search.getText() == null ? "" : search.getText().trim();
        if (name.isEmpty()) {
            status.setText("Type the name first - it is what he will be created as.");
            return;
        }
        LocalDate dateOfBirth;
        try {
            String typed = newDateOfBirth.getText() == null ? "" : newDateOfBirth.getText().trim();
            dateOfBirth = typed.isEmpty() ? null : LocalDate.parse(typed);
        } catch (DateTimeParseException badDate) {
            // Optional and never a release gate (ADR 0012, decision 5), but a date
            // that was typed and misread would be worse than none.
            status.setText("Date of birth must be yyyy-mm-dd, or empty.");
            return;
        }
        // A blank position is passed on as blank: what an unnamed position becomes
        // is EditableMatch's rule, not this screen's.
        pick = new Pick(null, name,
            newPosition.getText() == null ? "" : newPosition.getText().trim(), dateOfBirth,
            newNote.getText() == null ? "" : newNote.getText().trim());
        close();
    }

    private void buildResultsTable() {
        results.setPlaceholder(new Label("nobody - type a name to search wider"));
        column("rank", 55, r -> String.valueOf(r.rank()));
        column("name", 240, r -> r.candidate().playerName());
        column("born", 100, r -> r.candidate().dateOfBirth() == null
            ? "" : r.candidate().dateOfBirth().toString());
        column("position", 140, r -> r.candidate().position());
        column("nearby", 70, r -> r.candidate().nearbyMatches() == 0
            ? "" : String.valueOf(r.candidate().nearbyMatches()));
        column("id", 90, r -> String.valueOf(r.candidate().playerId())
            + (r.candidate().manual() ? " (hand-made)" : ""));
        column("", 200, r -> r.alreadyIn() == null ? "" : r.alreadyIn());

        // A player already in the match is greyed rather than hidden: a name that
        // vanishes reads as "not found", and the next move would be to create a
        // second copy of a man who is already there.
        results.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(RankedCandidate item, boolean empty) {
                super.updateItem(item, empty);
                setDisable(item != null && item.unavailable());
                setOpacity(item != null && item.unavailable() ? 0.45 : 1.0);
            }
        });
    }

    private void column(String title, double width, Function<RankedCandidate, String> value) {
        TableColumn<RankedCandidate, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(
            cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        results.getColumns().add(column);
    }
}
