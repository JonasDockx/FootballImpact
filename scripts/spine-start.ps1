# Item 30 stage 3: start or resume the backfill, and prove it actually started.
#
#   powershell -File C:\Users\dockx\Documents\Programmeren\GoalImpact\scripts\spine-start.ps1
#
# WHY THIS EXISTS. The documented launch used to be
#
#   wsl -d Ubuntu -e bash -lc "setsid nohup .../backfill.sh >> log 2>&1 < /dev/null & echo started"
#
# and it does not work on this machine. The launching wsl.exe exits as soon as it
# prints `started`, the Ubuntu distro is torn down with it, and the detached job
# is killed before it writes a single byte. Measured 2026-07-31: with a probe
# script in place of backfill.sh the log file was never created, no process
# remained, and `ps -o etime= -p 1` showed init one second old - the distro had
# rebooted. `started` reported that bash had forked, nothing more. A whole
# evening was lost to believing it.
#
# So: one wsl.exe is held open in a minimised window for the entire run, and
# nothing depends on detach semantics. Closing that window stops the run, which
# is safe - it costs the one chunk in flight, exactly like Ctrl-C.
#
# And `started` is never printed on faith again. This waits for the crawler
# process to appear and says PASS or FAIL.

$ErrorActionPreference = 'Stop'

# Every argument below must be free of spaces. PowerShell 5.1's Start-Process
# joins -ArgumentList elements with a space and does not quote the ones that
# contain any, so a `bash -lc "a; b >> log"` written here arrives at bash as
# `-c a` plus loose words - silently, with the window closing on the spot. The
# command therefore lives in backfill-launch.sh, which takes no arguments. See
# that file's header for how it was measured.
$LaunchScript = '/mnt/c/Users/dockx/Documents/Programmeren/GoalImpact/scripts/backfill-launch.sh'
$NohupLog = '~/spine/logs/stage3-nohup.log'

# NOT named `Wsl`: PowerShell resolves a function ahead of an external command,
# so a function called `Wsl` calling `wsl` calls itself until the call depth
# overflows. Hence the name, and hence wsl.exe spelled out at the call site.
function Invoke-InDistro([string]$cmd) {
    # -e bash -lc, one place, so quoting is got right once.
    #
    # The null guard is an explicit statement, not a `[string]` cast. A native
    # command that produces NO output lines does not come back as an empty
    # string - `return [string](wsl.exe ...)` still hands the caller $null, and
    # the `.Trim()` there dies with "You cannot call a method on a null-valued
    # expression". That is the normal case here, not the rare one: `pgrep`
    # prints nothing on every poll until the crawler finally appears. Trimming
    # inside the function means no call site can forget.
    #
    # Nothing clever with the quoting either - an `echo "$( ... )"` wrapper to
    # force a line out of bash does not survive being passed through wsl.exe.
    $out = wsl.exe -d Ubuntu -e bash -lc $cmd
    if ($null -eq $out) { return '' }
    return ([string]$out).Trim()
}

# Two crawlers is one request per second each against the one ADR 0009 pins, so
# this refuses rather than adds. Same detection rule as spine-status.sh: the
# crawler process itself, never the name of whatever is driving it.
#
# The bracket in `tfmk[t]` is not a typo. `pgrep -f` matches whole command lines,
# and this pattern is passed to `bash -lc`, so the wrapper's own command line
# contains the pattern and pgrep reports its own parent. That cost a false
# "1 crawler(s) already running" first time out. `tfmk[t]` matches the crawler
# and not the literal text `tfmk[t]` in the shell's arguments.
$running = Invoke-InDistro 'pgrep -f "bin/tfmk[t]" | wc -l'
if ($running -ne '0') {
    Write-Host "REFUSING TO START: $running crawler(s) already running." -ForegroundColor Red
    Write-Host "Check with spine-status.sh before starting anything."
    exit 1
}

Write-Host "starting stage 3 backfill (minimised window - leave it open)..."
Start-Process -WindowStyle Minimized wsl -ArgumentList `
    '-d', 'Ubuntu', '-e', 'bash', $LaunchScript

# The first chunk has to get through poetry's startup and the crawler's own boot
# before `tfmkt` is visible in the process table, which is tens of seconds on a
# cold distro. Ninety seconds is generous; a real failure shows up long before.
Write-Host "waiting for the crawler to appear (up to 90s)" -NoNewline
$pidFound = ''
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 3
    Write-Host "." -NoNewline
    $pidFound = Invoke-InDistro 'pgrep -f "bin/tfmk[t]" | head -1'
    if ($pidFound -ne '') { break }
}
Write-Host ""

if ($pidFound -ne '') {
    Write-Host "PASS: crawler is running (pid $pidFound)." -ForegroundColor Green
    Write-Host "Leave the minimised 'wsl' window open. Closing it stops the run,"
    Write-Host "costing at most the chunk in flight."
    exit 0
}

Write-Host "FAIL: no crawler after 90s. The run did not start." -ForegroundColor Red
Write-Host ""
Write-Host "last lines of $NohupLog :"
Invoke-InDistro "tail -15 $NohupLog"
exit 1
