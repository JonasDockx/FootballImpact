package com.goalimpact.gui;

import com.goalimpact.data.BirthdayReader;
import com.goalimpact.data.BirthdayRow;
import com.goalimpact.data.SidecarStore;
import com.goalimpact.data.TypedBirthDate;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

// The third door (#45, #52), and the one that reaches a player the repair
// worklist can never offer: Andreas Ulmer is second on this list and is missing
// from no match at all - one FACT about him is missing. A tab rather than a mode
// of WorklistPane, which is #29's call made again and on a stronger argument:
// the two lists share no rows, no key and no verb.
//
// STAGE 1 (#53) IS INERT. The list is real and the typed dates are read back,
// but there is no edit path - the born and note cells display and nothing more,
// and this pane never calls SidecarStore.setBirthDate. Stage 2 (#54) turns the
// cells into fields. The gate for stage 1 is that the replay's output is
// byte-identical, which it is by construction: nothing here writes.
//
// Dumb like WorklistPane and ClubPane: no SQL, no judgement, it draws what the
// reader hands it. The one thing it does decide is the merge - the ranked list
// comes from the results file and what has been typed comes from the sidecar,
// exactly as ClubPane merges statuses into held matches, so a row filled this
// afternoon stops looking like work without a replay.
class BirthdayPane extends BorderPane {

    private static final String HINT =
        "Every rated player the vendor has no date of birth for, most career "
        + "minutes first. Each is charged the population-average ageing penalty "
        + "today, so the top of this list is where a typed birthday buys the "
        + "most. The search box only narrows the list - it never empties it. "
        + "Reading only for now: typing a date arrives with #54.";

    // The list is the whole population and the box only filters it (#52 decision
    // 2), which inverts WorklistPane's contract on purpose: there, nothing shows
    // until you type; here, the ranking IS the answer and must survive an empty
    // box.
    private final TextField search = new TextField();
    private final Label message = new Label();
    private final TableView<BirthdayRow> players = new TableView<>();
    private final ObservableList<BirthdayRow> shown = FXCollections.observableArrayList();

    private final List<BirthdayRow> all;
    private final Map<Long, TypedBirthDate> typed;

    // The three totals the status line quotes. Computed once: they are facts
    // about the population, and the population does not change while the window
    // is open - only how much of it is shown does.
    private final String totals;

    BirthdayPane(BirthdayReader reader, SidecarStore store) throws SQLException {
        // The register's name wins over the vendor's and the lineups' (ADR 0012
        // decision 7), and the reader deliberately cannot ask for it - it must
        // not hold the sidecar open. So it is layered on here, from the same
        // short-lived read that fetches what has been typed.
        this.all = named(reader.rankedByMinutes(), store.registeredNames());
        this.typed = store.typedBirthDates();
        this.totals = String.format("%,d past 1,000 minutes, %,d past 5,000, %,d already typed",
            all.stream().filter(r -> r.minutes() >= 1000).count(),
            all.stream().filter(r -> r.minutes() >= 5000).count(),
            all.stream().filter(r -> typed.containsKey(r.playerId())).count());

        search.setPromptText("part of a name, a club or a season");
        search.setPrefWidth(280);
        search.textProperty().addListener((o, was, now) -> narrow());

        buildTable();
        narrow();

        HBox top = new HBox(10, new Label("Search:"), search);
        top.setPadding(new Insets(8));
        setTop(top);

        Label hint = new Label(HINT);
        hint.setWrapText(true);
        VBox centre = new VBox(6, hint, message, players);
        centre.setPadding(new Insets(8));
        VBox.setVgrow(players, Priority.ALWAYS);
        setCenter(centre);
    }

    // A hand-typed name replaces the one the query resolved. Only the register's
    // 19 rows are affected, and only the handful of them the vendor never named
    // - but those are exactly the men who would otherwise read "player 1000000003"
    // in a list whose whole job is to be searchable by name.
    private static List<BirthdayRow> named(List<BirthdayRow> rows, Map<Long, String> register) {
        if (register.isEmpty()) {
            return rows;
        }
        return rows.stream()
            .map(r -> register.containsKey(r.playerId())
                ? new BirthdayRow(r.playerId(), register.get(r.playerId()), r.minutes(),
                    r.appearances(), r.firstYear(), r.lastYear(), r.mainClub(), r.clubs(),
                    r.hasVendorRow())
                : r)
            .toList();
    }

    // No paging and no minutes threshold: 55,185 rows scroll fine, and a
    // threshold would hide the very rows a search is for. The filter spans the
    // three columns you would actually recognise a man by, so "qarabag" and
    // "2013" both narrow the list as readily as a surname does.
    private void narrow() {
        String needle = search.getText().trim().toLowerCase();
        List<BirthdayRow> keep = all.stream().filter(r -> matches(r, needle)).toList();
        shown.setAll(keep);
        players.refresh();
        message.setText(String.format("%,d of %,d shown   -   %s",
            keep.size(), all.size(), totals));
    }

    private static boolean matches(BirthdayRow row, String needle) {
        return needle.isEmpty()
            || row.name().toLowerCase().contains(needle)
            || row.mainClub().toLowerCase().contains(needle)
            || (row.firstYear() + "-" + row.lastYear()).contains(needle);
    }

    private void buildTable() {
        players.setItems(shown);
        players.setPlaceholder(new Label("nobody here - either everyone is dated, "
            + "or the search matches no one"));

        // A filled row stays and greys out (#52 decision 6). It does not vanish:
        // the confirmation of what you just did is the row itself, and a
        // vanishing row re-creates the "absent means never captured, or already
        // fixed?" ambiguity #29 decision 6 was written to kill.
        players.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(BirthdayRow row, boolean empty) {
                super.updateItem(row, empty);
                boolean done = !empty && row != null && typed.containsKey(row.playerId());
                setStyle(done ? "-fx-opacity: 0.45;" : "");
            }
        });

        column("player", 210, BirthdayRow::name);
        // Numbers, not text. #52 decision 4 rejected a rank column BECAUSE the
        // table is sortable, so the sort has to be honest: as strings, "1,000"
        // orders before "999" and the one column that carries the whole design's
        // ranking would lie the moment its header was clicked.
        number("minutes", 80, BirthdayRow::minutes);
        number("apps", 55, BirthdayRow::appearances);
        column("seasons", 90, r -> r.firstYear() + "-" + r.lastYear());
        column("main club", 200, BirthdayRow::mainClub);
        number("clubs", 55, BirthdayRow::clubs);
        // Which record he is missing from, not which tab he belongs to: both
        // populations are one list and, after #51, one write.
        column("record", 110, r -> r.hasVendorRow() ? "vendor row" : "lineups only");
        // Read-only in stage 1. #52 decision 5 settled that the date and its
        // note are typed straight into these two cells, spreadsheet-style,
        // because the source is in front of you at exactly that moment and
        // nowhere else - but that is the edit path, and stage 1 lands inert.
        column("born", 100, r -> {
            TypedBirthDate date = typed.get(r.playerId());
            return date == null ? "" : date.dateOfBirth().toString();
        });
        column("note", 200, r -> {
            TypedBirthDate date = typed.get(r.playerId());
            return date == null || date.note() == null ? "" : date.note();
        });
    }

    // A column that holds an int and merely SHOWS it grouped, so the header
    // sorts on the number rather than on its rendering.
    private void number(String title, double width, ToIntFunction<BirthdayRow> value) {
        TableColumn<BirthdayRow, Integer> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(
            cell -> new SimpleObjectProperty<>(value.applyAsInt(cell.getValue())));
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Integer count, boolean empty) {
                super.updateItem(count, empty);
                setText(empty || count == null ? null : String.format("%,d", count));
            }
        });
        players.getColumns().add(column);
    }

    private void column(String title, double width, Function<BirthdayRow, String> value) {
        TableColumn<BirthdayRow, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(
            cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        players.getColumns().add(column);
    }
}
