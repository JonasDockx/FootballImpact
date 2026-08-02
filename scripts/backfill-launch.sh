#!/bin/bash
# Item 30 stage 3: the one-argument-free entry point that spine-start.ps1 runs.
#
# WHY THIS FILE EXISTS AT ALL. It is three lines, and they could obviously live
# in the launcher's `bash -lc "..."` string. They cannot, because PowerShell 5.1
# `Start-Process -ArgumentList` joins its array elements with spaces and DOES NOT
# quote the ones that contain spaces. So
#
#   -ArgumentList '-d','Ubuntu','-e','bash','-lc','rm -f ~/spine/STOP; ... >> log 2>&1'
#
# reaches bash as `-c rm` plus a fistful of stray words: the redirect is dropped,
# the script never runs, and the window closes silently. That is exactly the
# failure this whole exercise started with, reproduced one layer up - measured
# 2026-07-31 by watching `date > file` print to stdout instead of to the file.
#
# With every argument free of spaces the naive join is harmless. That is the only
# property this file has to have, and putting the command here is what gives it.
#
# It also means the redirect and the stop-flag clear are written in bash, by
# bash, where they are read by whoever is debugging - not buried in a Windows
# string that three layers of quoting have to survive.
#
# ONE ARGUMENT IS ALLOWED, and it is a pass name with no spaces in it - which is
# the only reason it does not reintroduce the bug above. Everything that varies
# between passes is resolved here, in bash, from that one word.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
PASS=${1:-stage3}

case "$PASS" in
  stage3)
    # Left exactly as it ran, defaults and all. It is finished; this is the
    # form that would resume it.
    ARGS=(2022 2012 "$HOME/spine/scrapes/comps18.json" s3 stage3-backfill.log)
    NOHUP="$HOME/spine/logs/stage3-nohup.log"
    ;;
  pass2)
    # ADR 0013 pass 2: the 53 competitions config.yml gained, 2000-2025, newest
    # season first for the same reason stage 3 was -- a half-done backfill
    # should be the recent half.
    ARGS=(2025 2000 "$HOME/spine/scrapes/comps-pass2.json" p2 pass2-backfill.log)
    NOHUP="$HOME/spine/logs/pass2-nohup.log"
    ;;
  *)
    echo "unknown pass '$PASS' - expected stage3 or pass2"
    exit 1
    ;;
esac

# Resuming is starting: the flag must be gone or backfill.sh refuses, and every
# finished chunk is skipped anyway.
rm -f "$HOME/spine/STOP"

mkdir -p "$HOME/spine/logs"
exec "$HERE/backfill.sh" "${ARGS[@]}" >> "$NOHUP" 2>&1
