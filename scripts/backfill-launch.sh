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
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)

# Resuming is starting: the flag must be gone or backfill.sh refuses, and every
# finished chunk is skipped anyway.
rm -f "$HOME/spine/STOP"

mkdir -p "$HOME/spine/logs"
exec "$HERE/backfill.sh" >> "$HOME/spine/logs/stage3-nohup.log" 2>&1
