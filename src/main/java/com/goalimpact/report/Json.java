package com.goalimpact.report;

import java.util.Locale;

// JSON string escaping for the short strings this package emits itself - a run
// id, a club name, a competition slug, a rank label. The populations' own rows
// come out of DuckDB already encoded and never pass through here.
//
// One copy, because there are two generated artefacts now (#22's page and
// #24's match log) and this was the same twenty lines in both. Control
// characters included: a page must not be breakable by whatever ends up in a
// vendor name or a run id.
final class Json {

    private Json() {
    }

    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }
}
