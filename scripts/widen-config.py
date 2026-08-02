#!/usr/bin/env python3
"""Item 30 pass 2: put ADR 0013's widening into the vendor's config, and build
the parents file the backfill drives from.

ADR 0013 fixed WHAT the spine holds; turning that into Transfermarkt competition
ids is mechanical, and this file is the record of it. It is the same kind of
thing as patch-acquire.py and throttle-scraper.py: our repo's edit to a vendor
tree that is not ours, written down so it survives a `poetry install` putting
the vendor's own file back.

Three outputs, and it is idempotent -- re-running changes nothing:

  config.yml                          the ids appended under competition_ids
  data/supplemental_competitions.json a record for each id the confederation
                                      crawl does not reach (see below)
  ~/spine/scrapes/comps-pass2.json    the parents file, ONLY the new ids

WHY A SUPPLEMENTAL FILE IS NEEDED, AND FOR MORE THAN PLAY-OFFS. config.yml
already warns that competitions outside Transfermarkt's confederation hierarchy
are not returned by `tfmkt competitions` even when allow-listed. Measured
2026-08-02 against a full 460-competition crawl: SEVEN of the countries in the
spine have their domestic cup outside it -- Belgium, England, France, Mexico,
Netherlands, Serbia and Turkiye. Their cups are reachable only from the
country's own competitions page, which is where the ids below were read off. An
id that is allow-listed but unreachable is silently scraped as nothing, so this
script asserts every id resolves to a record before it writes anything.

WHY THE PARENTS FILE HOLDS ONLY THE NEW IDS. The backfill driver chunks its
parents one competition at a time and skips finished chunks, so feeding it the
old 74 as well would cost nothing in correctness and ~11 seasons of re-scraping
in time. Pass 2 is new competitions across 2000-2025; the existing ones are
pass 3's business, over a different season range.

Usage, from the vendor repo, under its Poetry interpreter:

  cd ~/spine/transfermarkt-datasets
  ~/.cache/pypoetry/virtualenvs/transfermarkt-datasets-*/bin/python \
      /mnt/c/.../GoalImpact/scripts/widen-config.py [--dry-run]

It needs a full competition crawl at ~/spine/scrapes/all-competitions.json:

  tfmkt confederations | grep '^{' > ~/spine/scrapes/confederations.json
  tfmkt competitions -p ~/spine/scrapes/confederations.json | grep '^{' \
      > ~/spine/scrapes/all-competitions.json
"""

import argparse
import json
import os
import pathlib

HOME = pathlib.Path(os.path.expanduser("~"))
ALL_COMPS = HOME / "spine/scrapes/all-competitions.json"
PARENTS_OUT = HOME / "spine/scrapes/comps-pass2.json"

# ---------------------------------------------------------------------------
# ADR 0013's three groups. Each entry is (id, note) and the note is the reason
# it is here, not a description -- a reader asking "why is Coupe de la Ligue
# absent" should find the answer in this file.
# ---------------------------------------------------------------------------

# One domestic cup per scraped country that has none in config.yml. The cup is
# the second tier's bridge to the first (#9), so it arrives with the tier or
# the tier does not arrive. Countries already carrying a cup -- DE, ES, EN, IT,
# NL, RU, UA, SC, GR, DK -- are absent from this list, and so is Portugal's
# league cup POCP, which config pinned instead of the Taca.
CUPS = [
    ("ARCA", "Argentina - Copa Argentina"),
    ("FFAC", "Australia - Australia Cup"),
    ("ofb",  "Austria - OeFB-Cup (the vendor's id really is lower case)"),
    ("CCB",  "Belgium - Croky Cup (outside the confederation hierarchy)"),
    ("BRC",  "Brazil - Copa do Brasil"),
    ("COPO", "Colombia - Copa Colombia"),
    ("KRC",  "Croatia - Hrvatski nogometni kup"),
    ("TSP",  "Czech Republic - MOL Cup"),
    ("FRC",  "France - Coupe de France (outside the hierarchy)"),
    ("JEMP", "Japan - Emperor's Cup"),
    ("RKPO", "South Korea - Korea Cup"),
    ("CPMX", "Mexico - Copa MX (outside the hierarchy; defunct after 2019/20)"),
    ("NOPO", "Norway - NM-Cup"),
    ("POPU", "Poland - Polish Cup"),
    ("POPO", "Portugal - Taca de Portugal (config pinned only the league cup)"),
    ("ROMP", "Romania - Romanian Cup"),
    ("SAKC", "Saudi Arabia - King's Cup"),
    ("SERP", "Serbia - Kup Srbije (outside the hierarchy)"),
    ("SEC",  "Sweden - Svenska Cupen"),
    ("SCC",  "Switzerland - Swiss Cup"),
    ("TRP",  "Turkiye - Turkiye Kupasi (outside the hierarchy)"),
    ("MLSP", "United States - US Open Cup"),
]

# ADR 0013 names exactly six. Not "every second tier we can reach": the rule is
# frozen, and a seventh is a fresh proposal.
SECOND_TIERS = [
    ("BE2", "Belgium - Challenger Pro League"),
    ("GB2", "England - Championship"),
    ("ES2", "Spain - LaLiga2"),
    ("IT2", "Italy - Serie B"),
    ("L2",  "Germany - 2. Bundesliga"),
    ("FR2", "France - Ligue 2"),
]

# Dial C's quality half: the fixtures where a league that is otherwise an island
# meets the rest of the world. ADR 0013 rejected UEFA-only qualifying for
# exactly this reason -- CONMEBOL and AFC qualifying are the only competitive
# matches where Brazilian, Argentine, Saudi, Japanese, Korean and Australian
# players face European ones. Friendlies stay out (#15).
QUALIFIERS = [
    # World Cup qualifying, all six confederations, plus the inter-confederation
    # play-offs that decide the last places.
    ("WMQ6", "World Cup qualifying - Europe"),
    ("WMQ2", "World Cup qualifying - Africa"),
    ("WMQ1", "World Cup qualifying - Asia"),
    ("WMQ3", "World Cup qualifying - CONCACAF"),
    ("WMQ4", "World Cup qualifying - South America"),
    ("WMQ5", "World Cup qualifying - Oceania"),
    ("POWM", "World Cup qualifying - inter-confederation play-offs"),
    # Continental qualifying. CONMEBOL runs none - the Copa America invites -
    # which is why five confederations appear here and six above.
    ("EMQ",  "European Qualifiers (Euro)"),
    ("POEM", "European Qualifiers play-offs"),
    ("AFCQ", "Africa Cup of Nations qualification"),
    ("ACQU", "AFC Asian Cup qualification"),
    ("GCQU", "Gold Cup qualification"),
    ("GCQ5", "Gold Cup play-off"),
    ("CNLQ", "CONCACAF Nations League qualification"),
    # The Nations Leagues. The UEFA one is named in ADR 0013; CONCACAF's is
    # included because its qualification is above, and pinning a qualifying
    # round without the competition it qualifies for would bank the bridge and
    # drop the matches.
    ("UNLA", "UEFA Nations League A"),
    ("UNLB", "UEFA Nations League B"),
    ("UNLC", "UEFA Nations League C"),
    ("UNLD", "UEFA Nations League D"),
    ("UNPO", "UEFA Nations League play-off"),
    ("UNFI", "UEFA Nations League finals"),
    ("CNLA", "CONCACAF Nations League A"),
    ("CNLB", "CONCACAF Nations League B"),
    ("CNLC", "CONCACAF Nations League C"),
    ("CNNF", "CONCACAF Nations League finals"),
    # The one finals tournament ADR 0013's list implies and config.yml lacks.
    # The spine holds MLS and Liga MX; the Gold Cup is their continental finals,
    # and its qualifying is pinned three lines up.
    ("GOCU", "Gold Cup"),
]

GROUPS = [("Domestic cups", CUPS),
          ("Second tiers", SECOND_TIERS),
          ("Qualifiers, Nations Leagues and the Gold Cup", QUALIFIERS)]

# Hand-minted records for the ids no confederation crawl returns. Slug and id
# were read off the country's own competitions page on 2026-08-02; the shape
# copies a record the vendor's own crawl produced (see data/competitions.json,
# FAC). Only the fields base_competitions reads are populated -- the market
# value and squad-size fields are cosmetic and are left null rather than
# invented.
SUPPLEMENTAL = {
    "CCB":  ("europa", "19",  "Belgium",       "BE1",  "croky-cup",      "Croky Cup"),
    "FRC":  ("europa", "50",  "France",        "FR1",  "coupe-de-france", "Coupe de France"),
    "SERP": ("europa", "215", "Serbia",        "SER1", "kup-srbije",     "Kup Srbije"),
    "TRP":  ("europa", "174", "Turkiye",       "TR1",  "turkiye-kupasi", "Turkiye Kupasi"),
    "CPMX": ("amerika", "110", "Mexico",       "MEX1", "copa-mx",        "Copa MX"),
}


def supplemental_record(cid):
    conf, country_id, country, code, slug, name = SUPPLEMENTAL[cid]
    return {
        "type": "competition",
        "parent": {"type": "confederation", "href": f"/wettbewerbe/{conf}",
                   "seasoned_href": f"https://www.transfermarkt.co.uk/wettbewerbe/{conf}"},
        "country_id": country_id,
        "country_name": country,
        "country_code": code,
        "competition_type": "domestic_cup",
        "competition_name": name,
        "href": f"/{slug}/startseite/wettbewerb/{cid}",
    }


def comp_id(record):
    return record["href"].rstrip("/").split("/")[-1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--config", default="config.yml")
    ap.add_argument("--supplemental", default="data/supplemental_competitions.json")
    args = ap.parse_args()

    if not ALL_COMPS.exists():
        raise SystemExit(f"missing {ALL_COMPS} - see this file's header for the two "
                         f"tfmkt commands that build it")
    crawled = {}
    for line in ALL_COMPS.open():
        if line.strip():
            r = json.loads(line)
            crawled[comp_id(r)] = r
    print(f"--> {len(crawled)} competitions in the crawl")

    wanted = [(cid, note) for _, group in GROUPS for cid, note in group]
    ids = [cid for cid, _ in wanted]
    if len(set(ids)) != len(ids):
        raise SystemExit("duplicate id in the lists above")

    # An allow-listed id that resolves to nothing is scraped as silence, which
    # on this pipeline is indistinguishable from a competition that genuinely
    # did not run that season. Resolve everything first, write nothing until it
    # all resolves.
    records, minted, unresolved = [], [], []
    for cid, note in wanted:
        if cid in crawled:
            records.append(crawled[cid])
        elif cid in SUPPLEMENTAL:
            records.append(supplemental_record(cid))
            minted.append(cid)
        else:
            unresolved.append((cid, note))
    if unresolved:
        for cid, note in unresolved:
            print(f"    UNRESOLVED  {cid:<6}{note}")
        raise SystemExit(f"{len(unresolved)} id(s) resolve to no record - refusing to write")

    print(f"--> {len(records)} competitions resolved, {len(minted)} hand-minted: "
          + ", ".join(minted))

    if args.dry_run:
        for title, group in GROUPS:
            print(f"\n{title} ({len(group)})")
            for cid, note in group:
                src = "crawled" if cid in crawled else "supplemental"
                print(f"    {cid:<6}{src:<14}{note}")
        return

    # config.yml, appended as text rather than re-serialised: the file carries
    # comments and an anchor/alias logging block, and yaml.safe_dump would eat
    # both. Insert directly after the last competition id.
    cfg_path = pathlib.Path(args.config)
    cfg_lines = cfg_path.read_text(encoding="utf-8").splitlines()
    present = {line.split("#")[0].strip().lstrip("- ").strip()
               for line in cfg_lines if line.strip().startswith("- ")}
    new = [(cid, note) for cid, note in wanted if cid not in present]
    if not new:
        print("--> config.yml already has every id")
    else:
        last = max(i for i, line in enumerate(cfg_lines) if line.strip().startswith("- "))
        block = ["    # --- GoalImpact item 30 pass 2 (ADR 0013) ---"]
        for title, group in GROUPS:
            block.append(f"    # {title}")
            block += [f"    - {cid:<6}# {note}" for cid, note in group if cid in dict(new)]
        cfg_lines[last + 1:last + 1] = block
        cfg_path.write_text("\n".join(cfg_lines) + "\n", encoding="utf-8")
        print(f"--> config.yml: added {len(new)} ids")

    # supplemental_competitions.json, appended only for ids not already in it.
    supp_path = pathlib.Path(args.supplemental)
    existing = set()
    if supp_path.exists():
        existing = {comp_id(json.loads(l)) for l in supp_path.open() if l.strip()}
    to_add = [c for c in minted if c not in existing]
    if to_add:
        with supp_path.open("a", encoding="utf-8") as f:
            for cid in to_add:
                f.write(json.dumps(supplemental_record(cid), ensure_ascii=False) + "\n")
        print(f"--> supplemental_competitions.json: added {len(to_add)}")
    else:
        print("--> supplemental_competitions.json already complete")

    PARENTS_OUT.parent.mkdir(parents=True, exist_ok=True)
    with PARENTS_OUT.open("w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"--> {PARENTS_OUT}: {len(records)} parents")


if __name__ == "__main__":
    main()
