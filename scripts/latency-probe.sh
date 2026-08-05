#!/bin/bash
# Item 30: is the scrape slow because of THEM, or because of US?
#
#   latency-probe.sh [label] [urls_file]
#
# WHY. On 2026-08-05 pass 2's season 2023 was measured at 4.06s average page
# fetch against 1.9s for seasons 2022, 2024 and 2025, with 527 `503`s in one
# season's log against 5 and 18 in the two before it. Chunk time went from ~280s
# to ~1900s over the course of the season. That is a real 4-6x slowdown, but the
# log cannot say WHY, and the three plausible causes have opposite remedies:
#
#   1. Transfermarkt is slow for everyone           -> wait; add a circuit breaker
#   2. Transfermarkt is soft-throttling THIS IP     -> change egress (the Linode)
#   3. Transfermarkt dislikes the CLIENT fingerprint -> CurlImpersonateHttpClient
#
# Guessing between those costs days. Measuring costs twenty minutes. Run this on
# the laptop and on the Linode AT THE SAME TIME, which controls for time of day -
# the one variable that would otherwise ruin the comparison - and then compare:
#
#   both slow            -> cause 1
#   laptop slow, VPS ok  -> cause 2
#   curl ok, tfmkt slow  -> cause 3   (run the tfmkt arm, below)
#
# POLITENESS. The URL list is twenty pages this project has ALREADY scraped, so
# the probe acquires nothing and learns nothing it did not have; it only times
# the response. Requests are spaced 2s apart rather than 1s so that two machines
# probing simultaneously still total one request per second between them, which
# is what ADR 0009 pins. Do not raise the count "to get a better sample" - twenty
# pages is enough to separate 1.9s from 7.6s, and this is the whole budget.
#
# SAFE ON A SHARED SERVER. This is the reason it is curl and not the crawler:
# no install, no root, no package manager, no network listener, nothing written
# outside $OUT, and one connection at a time. It cannot disturb a website running
# on the same box. Copy this file and the URL list over, run it, copy the CSV
# back, delete it. Nothing else on the Linode needs to change to run this arm.
#
# THE tfmkt ARM is separate and only runs where the vendor venv exists - that is
# the laptop today. It answers cause 3 by putting the real client against the
# same URLs at the same moment:
#
#   latency-probe.sh laptop-curl                    # arm 1, here
#   latency-probe.sh linode-curl                    # arm 2, there, same minute
#   PROBE_TFMKT=1 latency-probe.sh laptop-tfmkt     # arm 3, here, afterwards
set -uo pipefail

LABEL=${1:-probe}
HERE=$(cd "$(dirname "$0")" && pwd)
URLS=${2:-$HERE/latency-probe-urls.txt}
OUT=${PROBE_OUT:-/tmp/latency-probe-$LABEL.csv}
GAP=${PROBE_GAP:-2}

[ -s "$URLS" ] || { echo "missing URL list: $URLS"; exit 1; }
command -v curl >/dev/null || { echo "curl not found"; exit 1; }

# The crawler identifies itself as a browser, so a probe with curl's default
# User-Agent would be testing a different client than the one that is slow and
# could manufacture a cause-3 result out of nothing.
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36'

echo "n,http_code,time_total,bytes,url" > "$OUT"
echo "=== $LABEL: $(wc -l < "$URLS") urls, ${GAP}s apart, started $(date -Is) ==="

n=0
while read -r url; do
  [ -n "$url" ] || continue
  n=$((n + 1))
  # --max-time bounds a hung request so one stall cannot hold the probe open;
  # a timeout records as code 000, which is itself a finding worth seeing.
  read -r code t bytes <<< "$(curl -s -o /dev/null \
      -A "$UA" \
      --compressed \
      --max-time 60 \
      -w '%{http_code} %{time_total} %{size_download}' \
      "$url" 2>/dev/null || echo "000 0 0")"
  printf '%d,%s,%s,%s,%s\n' "$n" "$code" "$t" "$bytes" "$url" >> "$OUT"
  printf '  %2d/%s  %s  %6ss  %sb\n' "$n" "$(wc -l < "$URLS")" "$code" "$t" "$bytes"
  sleep "$GAP"
done < "$URLS"

echo
echo "=== $LABEL summary ==="
# Deliberately no gawk: Ubuntu 20.04 - which is what the Linode runs - defaults
# to mawk, and mawk has no asort(). Sorting with sort(1) keeps this working on
# both machines, which for a script whose whole purpose is comparing two machines
# is not a detail.
echo "  response codes:"
awk -F, 'NR>1 {print "    " $2}' "$OUT" | sort | uniq -c | sort -rn

awk -F, 'NR>1 && $2=="200" {print $3}' "$OUT" | sort -n > "$OUT.times"
n=$(wc -l < "$OUT.times")
if [ "$n" -gt 0 ]; then
  printf '  median        : %ss\n' "$(sed -n "$(( (n + 1) / 2 ))p" "$OUT.times")"
  awk '{s+=$1} END {printf "  mean          : %.2fs\n", s/NR}' "$OUT.times"
  printf '  min / max     : %ss / %ss\n' "$(head -1 "$OUT.times")" "$(tail -1 "$OUT.times")"
else
  echo "  no 200s at all - that is the finding; see the code counts above"
fi
rm -f "$OUT.times"

echo
echo "  csv: $OUT"
echo "  compare against 1.9s (healthy, seasons 2022/2024/2025) and 7.6s (season 2023 tail)"
