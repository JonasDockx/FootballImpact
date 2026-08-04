package com.goalimpact.data;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

// The read side of the missing-birthday worklist (#45, stage 1 in #53), and a
// third door beside WorklistReader's two rather than a mode of either: the lists
// share no rows, no key and no verb (#52 decision 1). Andreas Ulmer is the
// second name here and can never appear in the repair worklist at all - he is
// missing from no match; one fact about him is missing.
//
// Two files, both read-only, exactly as WorklistReader opens them: the minutes
// come from the disposable results DB, which a designated run rewrites whole,
// and the names and clubs from the vendor snapshot, attached and joined at
// display time so a refresh can never leave the list quoting a stale club.
//
// THE SIDECAR IS NOT OPENED HERE, and that is load-bearing rather than tidiness.
// This reader lives as long as the window does, and DuckDB refuses to open a
// file that is already attached elsewhere in the JVM ("Unique file handle
// conflict"), so attaching the sidecar for the sake of one display column would
// break EVERY sidecar write for as long as the tab existed - including the
// repair tool's own saves, in a tab this one has nothing to do with. It cost a
// working feature to learn; the test is below.
//
// So everything the sidecar knows is read per-operation through SidecarStore
// instead, which opens and closes in milliseconds, and merged by the pane. That
// is ClubPane's precedent with statuses, and it suits the two answers' different
// lifetimes anyway: this list changes only when a designated run does, and what
// has been typed changes while you sit at the screen.
//
// The whole population comes back in one list, uncapped and unthresholded (#52
// decision 2). 55,185 rows measured 0.09s and scroll fine; a cap or a minutes
// floor would hide the very rows a search is for.
public class BirthdayReader implements AutoCloseable {

    // The ranking is career minutes, which is the whole of #45's design: the
    // head is a sitting's work (28 men past 5,000 minutes) and the tail is
    // 52,000 rows nobody will touch, so "stop here" is a decision rather than a
    // surrender.
    //
    // "No date of birth" means the VENDOR has none. A man the register already
    // dates stays in the list and shows what was typed (#52 decision 6) - the
    // confirmation of what you just did is the row itself - so the register is
    // deliberately NOT part of this WHERE. That is also why the limit lives here
    // rather than in the precedence rule (#51 decision 4): the register always
    // wins at replay time, whether or not the vendor had a date.
    //
    // TRY_CAST, not CAST: date_of_birth is VARCHAR in the snapshot and a handful
    // of rows are unparseable, which the loader's birthDates() also treats as
    // absent. A LEFT JOIN, so the 55,157 men with no `players` row at all are in
    // the list rather than joined out of it - they are its top, not its tail.
    //
    // The name falls back twice: the vendor's players row, then whatever the
    // lineups called him. 142 rated ids are named by neither and get a label,
    // because an unnamed row is an id the operator cannot search on. The
    // register's name beats both (ADR 0012 decision 7) and the pane layers it on
    // top, for the reason in the class comment.
    private static final String RANKED_SQL = """
        WITH total AS (
            SELECT player_id, sum(minutes_played) AS mins, count(*) AS apps,
                   min(match_date) AS first_d, max(match_date) AS last_d
            FROM rating_history GROUP BY player_id
        ),
        club_names AS (
            SELECT cid AS club_id,
                   coalesce(max(nm), 'club ' || CAST(cid AS VARCHAR)) AS club_name
            FROM (SELECT home_club_id AS cid, home_club_name AS nm FROM vendor.games
                  UNION ALL SELECT away_club_id, away_club_name FROM vendor.games)
            GROUP BY cid
        ),
        named AS (
            SELECT player_id, min(player_name) AS lineup_name,
                   arg_max(club_id, n) AS main_club, count(*) AS n_clubs
            FROM (SELECT player_id, club_id, count(*) AS n, min(player_name) AS player_name
                  FROM vendor.game_lineups GROUP BY player_id, club_id)
            GROUP BY player_id
        )
        SELECT t.player_id,
               coalesce(p.name, n.lineup_name,
                        'player ' || CAST(t.player_id AS VARCHAR)) AS player_name,
               t.mins, t.apps, year(t.first_d) AS first_y, year(t.last_d) AS last_y,
               coalesce(c.club_name, 'unknown club') AS main_club,
               coalesce(n.n_clubs, 0) AS n_clubs,
               (p.player_id IS NOT NULL) AS has_vendor_row
        FROM total t
        LEFT JOIN vendor.players p ON p.player_id = t.player_id
        LEFT JOIN named n ON n.player_id = t.player_id
        LEFT JOIN club_names c ON c.club_id = n.main_club
        WHERE TRY_CAST(p.date_of_birth AS DATE) IS NULL
        ORDER BY t.mins DESC, player_name
        """;

    private final Connection connection;

    public BirthdayReader(Path results, Path snapshot) throws SQLException {
        Properties readOnly = new Properties();
        readOnly.setProperty("duckdb.read_only", "true");
        this.connection = DriverManager.getConnection("jdbc:duckdb:" + results, readOnly);
        try (Statement statement = connection.createStatement()) {
            statement.execute(DataFiles.attachReadOnly(snapshot, "vendor"));
        }
    }

    // Every rated player the vendor has no date of birth for, most minutes
    // first. One query, one list, no paging - the list IS the answer, and the
    // pane's search box only narrows it.
    public List<BirthdayRow> rankedByMinutes() throws SQLException {
        List<BirthdayRow> out = new ArrayList<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(RANKED_SQL)) {
            while (rows.next()) {
                out.add(new BirthdayRow(rows.getLong("player_id"),
                    rows.getString("player_name"),
                    // Truncated, not rounded. Minutes accrue in halves (a
                    // substitution mid-minute), so a man on 999.5 rounds up to
                    // 1,000 and would be counted past ADR 0011's threshold he
                    // has not actually cleared - one man, measured. Truncation
                    // makes the Java test agree with the SQL one exactly.
                    (int) rows.getDouble("mins"), rows.getInt("apps"),
                    rows.getInt("first_y"), rows.getInt("last_y"),
                    rows.getString("main_club"), rows.getInt("n_clubs"),
                    rows.getBoolean("has_vendor_row")));
            }
        }
        return out;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
