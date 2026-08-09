param(
    [Parameter(Mandatory = $true)]
    [string]$StartCommit,

    [Parameter(Mandatory = $true)]
    [string]$EndCommit,

    [Parameter(Mandatory = $true)]
    [string]$ResultsFile,

    [double]$Coverage = 0.80,

    [string]$Repository = (git rev-parse --show-toplevel)
)

$ErrorActionPreference = 'Stop'

if ($Coverage -le 0 -or $Coverage -gt 1) {
    throw 'Coverage must be greater than 0 and no greater than 1.'
}

$resultsByCommit = @{}
foreach ($line in Get-Content -LiteralPath $ResultsFile) {
    if ($line -match '^METHOD_CALL_RESULT commit=([0-9a-f]{40}) frames=(\d+) ticks=(\d+) calls=(\d+)$') {
        $resultsByCommit[$Matches[1]] = [pscustomobject]@{
            Commit = $Matches[1]
            Frames = [long]$Matches[2]
            Ticks  = [long]$Matches[3]
            Calls  = [long]$Matches[4]
        }
    }
}

$range = @(& git -C $Repository rev-list --first-parent --reverse "$StartCommit^..$EndCommit")
if ($LASTEXITCODE -ne 0 -or $range.Count -eq 0) {
    throw 'Unable to resolve the requested first-parent commit range.'
}

$missing = @($range | Where-Object { -not $resultsByCommit.ContainsKey($_) })
if ($missing.Count -gt 0) {
    throw "Missing method-call results for $($missing.Count) commits: $($missing -join ', ')"
}

$deltas = for ($index = 1; $index -lt $range.Count; $index++) {
    $parent = $resultsByCommit[$range[$index - 1]]
    $current = $resultsByCommit[$range[$index]]
    $delta = $current.Calls - $parent.Calls
    $subject = (& git -C $Repository show -s --format=%s $current.Commit)
    [pscustomobject]@{
        Commit       = $current.Commit
        Parent       = $parent.Commit
        Subject      = $subject
        Calls        = $current.Calls
        Ticks        = $current.Ticks
        DeltaTicks   = $current.Ticks - $parent.Ticks
        DeltaCalls   = $delta
        DeltaPercent = if ($parent.Calls -eq 0) { 0.0 } else { 100.0 * $delta / $parent.Calls }
        AddedCalls   = [math]::Max([long]0, $delta)
        CallsPerTick = if ($current.Ticks -eq 0) { 0.0 } else { $current.Calls / [double]$current.Ticks }
    }
}

$positive = @($deltas | Where-Object AddedCalls -gt 0 | Sort-Object AddedCalls -Descending)
$totalAdded = [long](($positive | Measure-Object AddedCalls -Sum).Sum)
if ($totalAdded -eq 0) {
    Write-Output 'COMPLEXITY_SUMMARY coverage=0 n=0 total_added_calls=0'
    exit 0
}

$selected = [System.Collections.Generic.List[object]]::new()
$cumulative = [long]0
foreach ($row in $positive) {
    $selected.Add($row)
    $cumulative += $row.AddedCalls
    if ($cumulative / [double]$totalAdded -ge $Coverage) {
        break
    }
}

# Include every commit tied with the cutoff so n does not depend on Git ordering.
$cutoff = $selected[$selected.Count - 1].AddedCalls
foreach ($row in $positive) {
    if ($row.AddedCalls -eq $cutoff -and -not $selected.Contains($row)) {
        $selected.Add($row)
        $cumulative += $row.AddedCalls
    }
}

$actualCoverage = $cumulative / [double]$totalAdded
Write-Output ("COMPLEXITY_SUMMARY coverage={0:F6} n={1} total_added_calls={2}" -f `
        $actualCoverage, $selected.Count, $totalAdded)

$running = [long]0
for ($rank = 0; $rank -lt $selected.Count; $rank++) {
    $row = $selected[$rank]
    $running += $row.AddedCalls
    $share = $row.AddedCalls / [double]$totalAdded
    $runningShare = $running / [double]$totalAdded
    $line = ("COMPLEXITY_COMMIT rank={0} commit={1} parent={2} added_calls={3} " +
            "delta_percent={4:F6} delta_ticks={5} calls_per_tick={6:F6} " +
            "share={7:F6} cumulative={8:F6} subject={9}") -f `
            ($rank + 1), $row.Commit, $row.Parent, $row.AddedCalls, $row.DeltaPercent,
            $row.DeltaTicks, $row.CallsPerTick, $share, $runningShare, $row.Subject
    Write-Output $line
}
