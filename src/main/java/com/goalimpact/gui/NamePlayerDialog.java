package com.goalimpact.gui;

import com.goalimpact.data.SidecarStore;
import com.goalimpact.repair.LineupEntry;
import com.goalimpact.repair.PlayerSighting;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
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

// Put a name to a player the vendor references but never names (item 17, slice
// 2, decision 9). 2,969 ids are in that state - named by no vendor table at all,
// so they hold no rating today and enter the ratings for the first time when a
// match naming them is released.
//
// The dialog exists where the operator meets the problem: on the lineup row. Its
// real content is the evidence panel - every other match whose events name this
// man - because a name can only honestly come from an outside source, and a
// date, a competition and two clubs are what you search that source by. The list
// is short by nature: a median of one game, fifteen at the most.
//
// Like the picker, this window holds no rule of its own. The name goes to
// EditableMatch, which registers him under his existing vendor id (ADR 0012,
// decision 6) and writes the register row inside save's transaction, so a
// cancelled repair leaves nothing behind.
final class NamePlayerDialog extends Stage {

    // What the operator typed: a name, and the two optional facts ADR 0012
    // decision 2 captures at the one moment they are in front of him.
    record Naming(String name, LocalDate dateOfBirth, String note) {
    }

    private final TextField name = new TextField();
    private final TextField dateOfBirth = new TextField();
    private final TextField note = new TextField();
    private final Label message = new Label();
    private Naming chosen;

    private NamePlayerDialog(Stage owner, LineupEntry row, List<PlayerSighting> sightings) {
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setTitle("Name player " + row.playerId());

        name.setPromptText("the player's name");
        dateOfBirth.setPromptText("date of birth, yyyy-mm-dd (optional)");
        note.setPromptText("where you found him (optional)");

        ListView<String> games = new ListView<>();
        games.setPrefHeight(160);
        games.getItems().setAll(sightings.stream().map(PlayerSighting::summary).toList());
        games.setPlaceholder(new Label(
            "This match is the only one whose events name him."));

        Button save = new Button("Name him");
        save.setOnAction(e -> confirm());
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> close());
        save.disableProperty().bind(name.textProperty().isEmpty());

        VBox box = new VBox(8,
            new Label("The vendor references id " + row.playerId()
                + " but never names him. Naming him here names him in every match"
                + " he is in, now and later."),
            new Label("Other matches whose events name him ("
                + sightings.size() + "):"),
            games,
            new HBox(8, new Label("Name:"), name),
            new HBox(8, new Label("Born:"), dateOfBirth),
            new HBox(8, new Label("Note:"), note),
            message,
            new HBox(8, save, cancel));
        box.setPadding(new Insets(10));
        VBox.setVgrow(games, Priority.ALWAYS);
        setScene(new Scene(box, 620, 420));
    }

    // A date of birth is optional and never blocks anything (ADR 0012, decision
    // 5), but a date typed wrongly must not be silently dropped - that is a fact
    // lost at the one moment it was in hand.
    private void confirm() {
        String born = dateOfBirth.getText().trim();
        LocalDate parsed = null;
        if (!born.isEmpty()) {
            try {
                parsed = LocalDate.parse(born);
            } catch (DateTimeParseException badDate) {
                message.setText("Date of birth must read yyyy-mm-dd, or be left empty.");
                return;
            }
        }
        chosen = new Naming(name.getText().trim(), parsed, note.getText().trim());
        close();
    }

    static Optional<Naming> name(Stage owner, SidecarStore store, LineupEntry row,
        long gameId) throws SQLException {

        NamePlayerDialog dialog =
            new NamePlayerDialog(owner, row, store.otherGames(row.playerId(), gameId));
        dialog.showAndWait();
        return Optional.ofNullable(dialog.chosen);
    }
}
