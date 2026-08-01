# PROTOTYPE — find one player among 94,807 (#35)

Throwaway. Three variants of the "find a player" page, switchable with
`?variant=A|B|C` or the arrow keys / the floating bar at the bottom. Built to be
reacted to, not to be promoted — no tests, no error handling, no abstractions.

| | shape | y-axis | where Peak Impact lives | delivery it implies |
|---|---|---|---|---|
| **A** | Spotlight — search *is* the page, nothing else on it until you pick | auto-fits the career | dashed second line across the chart | one file, everything embedded |
| **B** | Browse rail — the population is the page, search is one filter | 70–215, floor **drops** when the career goes under it | dashed line, re-estimated every update | index embedded, careers on click |
| **C** | Player page + `/` palette, the URL *is* a player | fixed 70–215, warns when it clips | shaded envelope above the line | one career fetched per view |

## Run it

Open `index.html`. That is all — `data.js` is a `window.DATA = [...]` global, so
it works from `file://` with no server.

## Regenerate the data

`data.js` is ~9 MB and is **not** committed. Rebuild it from the designated run:

```sh
duckdb -c ".read build.sql"          # writes proto-data.json
printf 'window.DATA=' > data.js && cat proto-data.json >> data.js && printf ';\n' >> data.js
```

`build.sql` reads `goalimpact-results.duckdb` and `transfermarkt-datasets.duckdb`
read-only, and emits every one of the 25,970 players past 1,000 career minutes:
identity, last club, position, and the Impact index (ADR 0011) sampled at the end
of each month of their career.

## B is the chosen shape

User picked B (2026-08-01) with three changes, now built into it:

- **Hover the blue line** and it reads out the Impact index at that point, the
  month, and the age. It snaps to the nearest sampled month rather than
  interpolating — the model only has an opinion after a match.
- **Age along the top, calendar years along the bottom.** Whole-year ticks, every
  2 years once a career spans more than 14. 2,982 of the 25,970 eligible players
  have no date of birth in the snapshot and say so instead.
- **The y-axis floor drops below 70** when a career goes under it, and says by
  how much. The 215 ceiling stays — nobody reaches it (top is 201.6).
- **Peak Impact is a moving line**, not the horizontal marker first drawn here
  (user, 2026-08-01): the original re-estimates the projected peak at every
  rating update and those shifts are visible on the chart. A flat marker also
  contradicted #41, which made the peak the latent parameter the drawn rating is
  derived *from*. The number beside the chart is the **latest** estimate with its
  drift over the last year, and "highest Impact reached" is a separate, smaller
  figure — the two are different things and used to be conflated here.

## Faked on purpose

- **Peak Impact does not exist yet** — #41 re-specified it and it is unbuilt.
  #41 makes the engine store the estimated peak `P` and draw `P − D(age)`, so
  rearranged, `P = Impact + D(age)`: the stand-in here is that identity with an
  **invented** piecewise-linear ageing curve. It therefore behaves the right way
  — re-estimated at every update, revisable downwards — while its numbers mean
  nothing. Fitting the real curve is #41's own work.
- **Variant C's fetch is a `setTimeout`**, not a server. The question is what
  one-career-at-a-time *feels* like, not whether a server works.
