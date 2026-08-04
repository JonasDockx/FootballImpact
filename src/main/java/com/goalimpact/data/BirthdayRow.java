package com.goalimpact.data;

// One line of the missing-birthday worklist (#45, #52): a rated player with no
// date of birth, and the evidence you would search an outside source by.
//
// Everything here except the id is display, and it is flattened into the row on
// purpose (#52 decision 4). NamePlayerDialog's evidence panel is right when the
// screen must convince you WHO a man is; here you already know, and the screen
// only has to take a date - so the seasons, the main club and the club count sit
// where your eye already is rather than in a second pane.
//
// No rank number: the table is sortable and searchable, so a stored rank would
// renumber on every filter and lie. Minutes is the ranking and it is on the row.
//
// hasVendorRow tells the two populations apart (#52 decision 3) - 28 men have a
// vendor `players` row with an empty date, and 55,157 have no row at all, which
// is the European-qualifier population #30's widened era brought in. They are
// one worklist because they are one question and, after #51, one write.
public record BirthdayRow(long playerId, String name, int minutes, int appearances,
    int firstYear, int lastYear, String mainClub, int clubs, boolean hasVendorRow) {
}
