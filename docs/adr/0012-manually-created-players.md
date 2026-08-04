# Manually created players in the sidecar

The repair tool must sometimes name a player no vendor record names — an absent
side in a 1998 third-division match has eleven men the snapshot has never heard
of. Such a player is a **Manual player**: he takes a `player_id` from the
reserved range **≥ 1,000,000,000**, which cannot collide with a Transfermarkt
id, and he is recorded in a new sidecar table `manual_players`, written in the
same transaction as the match that first names him.

## Why an identity problem exists at all

Nothing at replay time joins a lineup row to a `players` row. `TransfermarktLoader`
builds its players straight from `game_lineups` — id, name, position — so a made-up
id in a lineup row *just works*, with no registration anywhere. That is exactly
what makes this dangerous rather than easy.

The failure it invites: you repair a 1998 match and type "Marc Dupont", who
becomes 1,000,000,001. A fortnight later you repair another 1998 match with the
same man in it, the tool has no memory of him, and he becomes 1,000,000,002. One
man now holds two careers. Each carries half the exposure, so each draws a
*larger* update factor than he has earned (see *Exposure*), and neither rating is
his. Both matches look perfectly well-formed in the sidecar; nothing anywhere
reports a problem. This is the precise failure ADR 0009 keyed identity on stable
ids to prevent, arriving through the one door that ADR left open.

So the picker must be able to find a player created in an earlier session, and
that requires the set of manual players to be knowable.

## Decisions

1. **The reserved range is `player_id ≥ 1,000,000,000`.** A range test, not a
   flag column: any code anywhere can ask "is this man hand-made?" of an id it
   already holds, without a join. Transfermarkt's own ids are six and seven
   figures, so the range has an order of magnitude of headroom.

2. **A register, not a derivation.** `manual_players` carries
   `player_id, player_name, date_of_birth, created_on, note`. The alternative —
   deriving the population from `SELECT DISTINCT player_id FROM game_lineups
   WHERE player_id >= 1000000000` — needs no new table and cannot drift, and was
   the recommendation. It was rejected because a lineup row has nowhere to put
   the facts a hand-made player needs: a date of birth, and a note saying where
   he was found. Those are captured at the one moment they are ever in front of
   the operator, or they are lost.

3. **The register is written with the match, in one transaction.** A created
   player is provisional inside the editor; his register row inserts inside the
   transaction `SidecarStore.save` already opens, alongside his lineup row. This
   buys the invariant that makes the register trustworthy: **every row in
   `manual_players` is a player who appears in at least one sidecar match.**
   Writing on the "create" click instead would leave orphans behind every
   abandoned repair, and nothing later could tell an orphan from a real one.
   The obvious objection — two sessions minting the same id — does not arise:
   `RepairEditor` is `APPLICATION_MODAL`, so exactly one repair is ever open, and
   within one repair ids are handed out max+1, max+2 from a single read.

4. **The register is authoritative for the name; identity is the id.** A
   released match is a whole-match snapshot, so a name corrected in the register
   later does not rewrite matches already saved. That is cosmetic drift in a
   display column while identity stays fixed — the tolerable kind. No rename is
   offered yet.

5. **Date of birth is optional and never blocks a release.** A manual player
   without one simply cannot be plotted on ADR 0011's career chart, joining the
   69,975 players already in vendor lineups with no `players` row. Requiring it
   would turn one unfindable birthday into a match that cannot be repaired,
   inverting the point of the tool.

## Amendment (2026-07-28, item 17 slice 2): the register also holds vendor ids

Building the maybe tier turned up a second population this ADR did not foresee.
The vendor's `game_events` reference **2,969 player ids that no vendor table ever
names** — not `players`, not `game_lineups`, not `appearances` — across 1,329
games. They hold no rating today, because the engine builds its players from
lineup rows alone and these men appear in none; they enter the ratings for the
first time when a maybe match naming them is released.

Such a man is a *Manual player* by every part of the definition except one: the
vendor already gave him an id. Decisions 6 and 7 follow from taking that
seriously.

6. **`manual_players` holds him too, under his vendor id.** The register becomes
   the one place a hand-typed name lives, and the reserved range stops being what
   the table *means* — it is only how an id is minted when the vendor never
   supplied one. `ManualPlayer.isManual` stays a true range test and keeps its
   meaning ("was this id invented here?"); `highestManualPlayerId` gains a
   `WHERE player_id >= FIRST_ID` so a vendor id can never seed the allocator.
   The alternative — a second table for name overrides, leaving this ADR
   untouched — was rejected for splitting one concept across two writes, two
   joins and two things to keep in step.

7. **The register's name beats the name frozen in a lineup row, at replay time
   too.** This narrows decision 4, which tolerated a corrected name not
   reaching matches already saved. It cannot survive naming: the whole point of
   naming a man once is that his other games show it. So `TransfermarktLoader`
   left-joins `sidecar.manual_players` and prefers its name — the loader learns a
   fifth sidecar table, and the "four tables, the fifth invisible" simplicity
   that kept the replay trivially byte-identical ends here. Identity is still the
   id and nothing about a rating changes; only the display name moves. Decision
   4's second half stands: a *created* player's name is his register row's, and
   the invariant of decision 3 is unchanged, since a vendor id is registered
   inside the same `save` transaction as the match that first names him.

## Amendment (2026-08-04, #45): the register also holds a typed date of birth

Until #41 a missing date of birth cost nothing at replay time. It moved the
ageing effect *inside* the replay, so lineup strength is now `P − D(age)` and
every player on the pitch needs an age; 55,185 rated men have none and are all
charged ADR 0016's population-average penalty. #45 asks for those birthdays to
be typed in, ranked by career minutes.

A date of birth is **one fact about one person**, true across every match he ever
played — so it is not the match-level replacement ADR 0009 governs. It is the
same shape as decision 6's typed *name*, one column over, and decisions 8 and 9
follow from saying so.

8. **A typed date of birth is a `manual_players` row under the player's own id,
   with `player_name` NULL**, written by a **standalone write** that may
   overwrite. NULL rather than a copy of the vendor's name because decision 7
   makes that column authoritative: a copy would freeze a name the vendor may
   later correct, re-creating the "two places able to disagree about who a
   player is" defect the 2026-07-28 amendment existed to remove. Every name
   reader splices `mp.player_name` as the first arm of a `COALESCE`, so a NULL
   falls through to the vendor's name and naming is untouched.
   **Decision 3's invariant narrows to minted ids**: "every row in
   `manual_players` is a player who appears in at least one sidecar match" now
   holds for ids ≥ `FIRST_ID` only. That is where it was ever load-bearing —
   decision 3's stated fear was orphan *minted* ids from abandoned repairs
   splitting one man's career in two. A vendor-id row with no sidecar match
   behind it splits nothing: the vendor supplied the id and the career is
   already whole.

9. **Precedence is unchanged and uniform — the register always wins** — and
   provenance reuses the existing `note` and `created_on` columns, so the
   **net schema change is none**. `TransfermarktLoader.birthDates()` already
   reads the register second, for created and vendor players alike; nothing in
   the loader moves. The "only players with no date of birth" limit lives in the
   worklist query, not in the precedence rule, which leaves a *wrong* vendor date
   fixable later by typing over it with no data-layer change. Safe by
   construction: `readBirthDatesInto` skips null dates, so a name-only register
   row can never erase a vendor date — only a real typed date overwrites. The
   existence of the register row is itself the flag that a date was typed, so no
   `source` column is needed; that is decision 1's preference for a test over a
   flag, applied again.

Consequence, accepted deliberately: a later Transfermarkt snapshot that fills in
a date contradicting a typed one is **shadowed forever**. #56 settled that the
register keeps winning and the disagreement is *reported, never applied*, by a
birthday section in `scripts/reconcile-sidecar.py` — the same stance that script
already takes on team sheets.

## Considered options

- **Derive the manual population from sidecar lineup rows (rejected).** No new
  table, no second write path, and structurally incapable of disagreeing with
  the matches — a genuinely better shape, and the recommendation at the grill.
  Rejected because it has nowhere to hold a date of birth or a note, and those
  facts are unrecoverable once the source page is closed.
- **No lookup at all — every creation mints a fresh id (rejected).** The
  cheapest to build and the reason this ADR exists: it silently splits exactly
  the careers the repair tool was built to mend.
- **A `manual` boolean column instead of a reserved range (rejected).** Requires
  a join to answer a question about an id, and admits the state where the column
  and the id disagree.
- **A second sidecar table for name overrides (rejected, 2026-07-28).** Would
  have kept this ADR's original text exact — `manual_players` for minted ids
  only, `player_names` for vendor ids. Rejected because the two tables would hold
  the same fact ("the name a human typed for this player id"), so every reader
  would join both and every writer would have to pick, for no gain but leaving a
  paragraph unedited.
- **Keep the register out of the replay (rejected, 2026-07-28).** The loader
  would stay untouched and the byte-identical gate stay trivial; a renamed player
  would be fixed by re-opening and re-releasing his matches, of which he has a
  median of one. Rejected because it leaves two places able to disagree about who
  a player is — the exact defect the slice-1 review had already had to fix once
  in the picker's `COALESCE`.
- **A new ADR for player-attribute overrides (rejected, 2026-08-04).** Would have
  left this document's text exact. Rejected for spreading the meaning of
  `manual_players` over two documents a reader of that table would have to find
  both of, with nothing general to hang it on while #50 rules every other player
  attribute out of scope.
- **Route a typed birthday through a match save, to keep decision 3's invariant
  verbatim (rejected, 2026-08-04).** Kills #45's whole point: fixing a vendor
  player's birthday would mean materialising a match you had no reason to touch.
  Dropping the invariant outright was rejected for the opposite reason — it
  re-opens the orphan hole for minted ids that this ADR exists to close.
- **Make the loader a `COALESCE` with the vendor first (rejected, 2026-08-04).**
  Would limit the register to filling gaps. Rejected because it reverses today's
  behaviour for created players and contradicts decision 4's "the register is
  authoritative".
- **Write the register on the "create" click (rejected).** Simpler write path,
  and the id is real the instant it exists. Rejected for the orphan rows: after a
  year the register stops meaning "everyone I created" and starts meaning
  "everyone I created, plus some false starts".

## Consequences

- **The sidecar gains a fifth table**, and with it the first sidecar state that
  is not a whole match. `manual_players` is small, append-only in practice, and
  as precious as the rest of the file — it is never auto-wiped on a vendor
  refresh (see *Sidecar*).
- **Manual players are invisible to the vendor's `players` join**, so they carry
  no vendor position. The picker types one in at creation; a miss becomes
  `Unknown`, exactly as `ROSTER_SQL` already handles the case.
- **A manual player's sidecar lineup rows are his appearance record.** The
  picker's ranking counts vendor appearances and sidecar lineups together, so a
  man created on one matchday ranks normally on the next — which is what makes
  entering a season the vendor does not cover tractable at all.
- **Merging two manual players is not solved.** If the split this ADR prevents
  happens anyway — through a typo the picker's search does not match — there is
  no merge tool. The fix would be a rewrite of the affected sidecar matches. Left
  open deliberately; build it when it is needed, not before.
