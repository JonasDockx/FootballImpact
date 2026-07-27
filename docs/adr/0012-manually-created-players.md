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
