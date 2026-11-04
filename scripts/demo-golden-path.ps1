# Golden path demo against the RUNNING app: evidence -> investigation -> policy route -> approval -> RESOLVED,
# then the audit chain is verified. Built for a screen recording: each step pauses when -Pause is set.
#
# Prereqs: Postgres up (docker compose -f docker/docker-compose.yml up -d), app running on :8080
#   (mvn spring-boot:run, or with -Dspring-boot.run.profiles=openai and OPENAI_API_KEY set),
#   JAVA_HOME = JDK 21 and mvn on PATH (for token minting).
# Usage (from reconcile-ai/):
#   .\scripts\demo-golden-path.ps1
#   .\scripts\demo-golden-path.ps1 -Pause
param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$Pause
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$tenant   = "TENANT_DEMO"
$evidence = Join-Path $PSScriptRoot "..\samples\evidence\counterparty-confirmation-BRK-3001.txt"
if (-not (Test-Path $evidence)) { throw "Sample evidence not found: $evidence" }

function Step([string]$title) {
    Write-Host ""
    Write-Host "== $title" -ForegroundColor Cyan
    if ($Pause) { Read-Host "   press Enter to continue" | Out-Null }
}
function Token([string]$subject, [string]$role) {
    $t = & "$PSScriptRoot\generate-local-jwt.ps1" -Subject $subject -Tenant $tenant -Role $role | Select-Object -Last 1
    if (-not $t) { throw "Could not mint a token for $role" }
    return $t
}
function Api([string]$method, [string]$path, [string]$token, $body = $null) {
    $headers = @{ Authorization = "Bearer $token" }
    if ($null -ne $body) {
        return Invoke-RestMethod -Method $method -Uri "$BaseUrl$path" -Headers $headers `
            -ContentType "application/json" -Body ($body | ConvertTo-Json -Depth 5)
    }
    return Invoke-RestMethod -Method $method -Uri "$BaseUrl$path" -Headers $headers
}

Step "1. Mint tokens: analyst, first approver, second approver, SRE admin"
$analyst  = Token "demo-analyst"  "RECON_ANALYST"
$approver = Token "demo-approver" "APPROVER_L1"
$sre      = Token "demo-sre"      "SRE_ADMIN"
Write-Host "   tokens minted for tenant $tenant"

Step "2. Create a break: ledger 50,500.00 vs external 50,000.00 USD"
$ref = "BRK-DEMO-" + (Get-Date -Format "yyyyMMddHHmmss")
$break = Api POST "/api/v1/breaks" $analyst @{
    breakReference = $ref; ledgerAmount = "50500.00"; externalAmount = "50000.00"; currency = "USD"
}
Write-Host "   break $($break.id) ($ref), status $($break.status)"

Step "3. Upload the counterparty confirmation as evidence"
$up = & curl.exe -s -X POST "$BaseUrl/api/v1/breaks/$($break.id)/evidence" `
    -H "Authorization: Bearer $analyst" -F "file=@$evidence"
Write-Host "   evidence: $up"

Step "4. Start the AI investigation (async job)"
$job = Api POST "/api/v1/breaks/$($break.id)/investigations" $analyst @{ analystNote = "Variance is 500 USD; check fees." }
Write-Host "   job $($job.id) queued"

Step "5. Poll the job until it finishes"
$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 2
    $job = Api GET "/api/v1/investigations/$($job.id)" $analyst
    Write-Host "   status=$($job.status) stage=$($job.stage)"
} while ($job.status -notin @("SUCCEEDED", "FAILED") -and (Get-Date) -lt $deadline)
if ($job.status -ne "SUCCEEDED") { throw "Investigation ended as $($job.status): $($job.failureReason)" }

Step "6. Read the proposal: root cause, citations and policy route"
$p = Api GET "/api/v1/proposals/$($job.proposalId)" $analyst
Write-Host "   root cause : $($p.rootCause)"
Write-Host "   confidence : $($p.confidence)"
Write-Host "   citations  : valid=$($p.citationsValid)"
Write-Host "   route      : $($p.route) ($($p.routeReason))"

if ($p.route -eq "MANUAL_REVIEW") {
    Write-Host "   policy sent this to manual review, so there is no approval step. Demo ends here." -ForegroundColor Yellow
} else {
    Step "7. Approver decides (a different person from the analyst who started it)"
    $role = if ($p.route -eq "L2_APPROVAL") { "APPROVER_L2" } else { "APPROVER_L1" }
    $approverToken = if ($role -eq "APPROVER_L1") { $approver } else { Token "demo-approver-l2" "APPROVER_L2" }
    $task = Api GET "/api/v1/approvals" $approverToken | Where-Object { $_.proposalId -eq $p.id } | Select-Object -First 1
    if (-not $task) { throw "No approval task for proposal $($p.id) (required role $role)" }
    $done = Api POST "/api/v1/approvals/$($task.id)/decision" $approverToken @{ decision = "APPROVE"; comment = "Fee deduction confirmed." }
    Write-Host "   task $($task.id): $($done.status), decided by $($done.decidedBy)"

    Step "8. Break is now RESOLVED"
    $b = Api GET "/api/v1/breaks/$($break.id)" $analyst
    Write-Host "   break status: $($b.status)"
    if ($b.status -ne "RESOLVED") { throw "Expected RESOLVED, got $($b.status)" }
}

Step "9. Verify the hash-chained audit log for this tenant"
$v = Api GET "/api/v1/audit/verify" $sre
Write-Host "   audit chain: $($v | ConvertTo-Json -Compress)"

Write-Host ""
Write-Host "Golden path complete." -ForegroundColor Green
