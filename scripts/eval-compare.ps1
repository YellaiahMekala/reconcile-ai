# Compares two eval reports written by the eval tests (target/eval/report-<label>.json).
#
#   .\scripts\eval-compare.ps1 target\eval\report-mock-llm.json target\eval\report-openai-gpt-4o-mini.json
#
# Prints the headline numbers side by side with the difference, then the cases whose root cause or route differ.
param(
    [Parameter(Mandatory = $true)][string]$Baseline,
    [Parameter(Mandatory = $true)][string]$Candidate
)

$a = Get-Content $Baseline -Raw | ConvertFrom-Json
$b = Get-Content $Candidate -Raw | ConvertFrom-Json

function Row($name, $x, $y, [switch]$Percent) {
    $delta = [double]$y - [double]$x
    if ($Percent) {
        $left = ([double]$x * 100).ToString("0.0") + "%"
        $right = ([double]$y * 100).ToString("0.0") + "%"
        $change = ($delta * 100).ToString("+0.0;-0.0;0.0") + " pts"
    } else {
        $left = "$x"; $right = "$y"
        $change = $delta.ToString("+0.######;-0.######;0")
    }
    "{0,-30} {1,16} {2,16} {3,12}" -f $name, $left, $right, $change
}

"{0,-30} {1,16} {2,16} {3,12}" -f "", $a.label, $b.label, "change"
Row "Job success rate"            $a.summary.jobSuccessRate     $b.summary.jobSuccessRate -Percent
Row "Root-cause accuracy"         $a.summary.rootCauseAccuracy  $b.summary.rootCauseAccuracy -Percent
Row "Route accuracy"              $a.summary.routeAccuracy      $b.summary.routeAccuracy -Percent
Row "Citations verified"          $a.summary.citationValidRate  $b.summary.citationValidRate -Percent
Row "Required evidence cited"     $a.summary.citationRecall     $b.summary.citationRecall -Percent
Row "Latency p50 (ms)"            $a.summary.p50WallMs          $b.summary.p50WallMs
Row "Latency p95 (ms)"            $a.summary.p95WallMs          $b.summary.p95WallMs
Row "Cost per case (USD)"         $a.summary.costPerCaseUsd     $b.summary.costPerCaseUsd

""
"Cases that differ:"
$byId = @{}
foreach ($c in $a.cases) { $byId[$c.caseId] = $c }
$diff = 0
foreach ($c in $b.cases) {
    $o = $byId[$c.caseId]
    if ($null -ne $o -and ($o.rootCause -ne $c.rootCause -or $o.route -ne $c.route)) {
        "  {0}: {1} / {2}  ->  {3} / {4}  (expected {5} / {6})" -f $c.caseId, $o.rootCause, $o.route, $c.rootCause, $c.route, $c.expectedRootCause, $c.expectedRoute
        $diff++
    }
}
if ($diff -eq 0) { "  none" }
