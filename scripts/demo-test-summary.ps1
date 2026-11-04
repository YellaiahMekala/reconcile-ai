# Prints a readable, colour-coded summary of the last `mvn verify` run, one line per test.
# Built for screenshots / screen recordings (README "Visual proof" section).
# Reads the real surefire (unit) and failsafe (integration) XML reports - nothing is hard-coded.
# Usage (from reconcile-ai/):
#   .\scripts\demo-test-summary.ps1          # summarise the existing reports
#   .\scripts\demo-test-summary.ps1 -Run     # run `mvn clean verify` first, then summarise
param([switch]$Run)
$ErrorActionPreference = "Stop"

# Symbols built from code points so this file stays pure ASCII (Windows PowerShell 5.1 safe).
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$tick  = [char]0x2713
$cross = [char]0x2717

if ($Run) {
    Write-Host "Running mvn clean verify (unit + integration tests, real Postgres via Testcontainers)..." -ForegroundColor Cyan
    mvn -q clean verify | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host "mvn clean verify FAILED (exit $LASTEXITCODE)" -ForegroundColor Red; exit $LASTEXITCODE }
}

# "crossTenantGetIs404" -> "cross tenant get is 404"
function Humanize([string]$name) {
    # Parameterized tests arrive as "rejectsUnknown(String)[3]" - drop the signature, keep the index.
    $name = $name -replace '\(.*?\)', ' '
    ($name -creplace '([a-z0-9])([A-Z])', '$1 $2' -creplace '([a-zA-Z])([0-9])', '$1 $2').ToLower().Trim()
}

$suites = @(
    @{ Label = "Unit tests (surefire)";                    Dir = "target\surefire-reports" },
    @{ Label = "Integration tests (failsafe, Testcontainers Postgres)"; Dir = "target\failsafe-reports" }
)

$total = 0; $failed = 0; $seconds = 0.0
Write-Host ""
Write-Host "  reconcile-ai - test results" -ForegroundColor White
Write-Host "  ============================" -ForegroundColor DarkGray

foreach ($suite in $suites) {
    $files = Get-ChildItem $suite.Dir -Filter "TEST-*.xml" -ErrorAction SilentlyContinue
    if (-not $files) { Write-Host "  (no reports in $($suite.Dir) - run with -Run or mvn verify first)" -ForegroundColor Yellow; continue }

    Write-Host ""
    Write-Host "  $($suite.Label)" -ForegroundColor Cyan
    foreach ($f in $files) {
        [xml]$xml = Get-Content $f.FullName
        $seconds += [double]$xml.testsuite.time
        # Group by class so nested JUnit classes (Outer$Inner) show as their own heading.
        $xml.testsuite.testcase | Group-Object classname | ForEach-Object {
            $heading = ($_.Name -replace '^com\.reconcileai\.', '') -replace '\$', ' > '
            Write-Host "    $heading" -ForegroundColor White
            foreach ($tc in $_.Group) {
                $total++
                $bad = $tc.failure -or $tc.error
                if ($bad) {
                    $failed++
                    Write-Host "      $cross $(Humanize $tc.name)" -ForegroundColor Red
                } else {
                    Write-Host "      $tick $(Humanize $tc.name)" -ForegroundColor Green
                }
            }
        }
    }
}

Write-Host ""
Write-Host "  ----------------------------" -ForegroundColor DarkGray
if ($failed -eq 0 -and $total -gt 0) {
    Write-Host ("  {0} {1} tests passed, 0 failed  ({2:N1}s)" -f $tick, $total, $seconds) -ForegroundColor Green
} else {
    Write-Host ("  {0} {1} of {2} tests failed" -f $cross, $failed, $total) -ForegroundColor Red
}
Write-Host ""
