# Live API walkthrough against the RUNNING app - colour-coded, one line per check.
# Built for a ~30-second screen recording (README "Visual proof" section).
# Prereqs: Postgres up, app running on :8080, JAVA_HOME = JDK 21 and mvn on PATH (for token minting).
# Usage (from reconcile-ai/):
#   .\scripts\demo-api-walkthrough.ps1
param([string]$BaseUrl = "http://localhost:8080")
$ErrorActionPreference = "Stop"

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$tick  = [char]0x2713
$cross = [char]0x2717
$script:ok = 0; $script:bad = 0

function Check([string]$label, [int]$expected, [scriptblock]$call) {
    try { $status = & $call } catch { $status = [int]$_.Exception.Response.StatusCode }
    if ($status -eq $expected) {
        $script:ok++
        Write-Host ("  {0} {1,-58} {2}" -f $tick, $label, $status) -ForegroundColor Green
    } else {
        $script:bad++
        Write-Host ("  {0} {1,-58} {2} (expected {3})" -f $cross, $label, $status, $expected) -ForegroundColor Red
    }
}
function Status($method, $url, $token, $body) {
    $headers = @{}; if ($token) { $headers.Authorization = "Bearer $token" }
    (Invoke-WebRequest -Method $method -Uri $url -Headers $headers -Body $body `
        -ContentType "application/json" -UseBasicParsing).StatusCode
}
function Upload($url, $token, $file) {
    [int](curl.exe -s -o NUL -w "%{http_code}" -H "Authorization: Bearer $token" -F "file=@$file" $url)
}

Write-Host ""
Write-Host "  reconcile-ai - live API walkthrough ($BaseUrl)" -ForegroundColor White
Write-Host "  ================================================" -ForegroundColor DarkGray

Write-Host "  Minting local dev JWTs (RS256, tenant + role claims)..." -ForegroundColor DarkGray
$analystA  = & "$PSScriptRoot\generate-local-jwt.ps1" -Subject analyst-a  -Tenant TENANT_A -Role RECON_ANALYST
$analystB  = & "$PSScriptRoot\generate-local-jwt.ps1" -Subject analyst-b  -Tenant TENANT_B -Role RECON_ANALYST
$approverA = & "$PSScriptRoot\generate-local-jwt.ps1" -Subject approver-a -Tenant TENANT_A -Role APPROVER_L1

Write-Host ""
Write-Host "  Happy path - TENANT_A analyst" -ForegroundColor Cyan
Check "Health endpoint is public" 200 { Status GET "$BaseUrl/actuator/health" }

$ref  = "BRK-DEMO-" + (Get-Random -Minimum 10000 -Maximum 99999)
$body = @{ breakReference = $ref; ledgerAmount = "50500.00"; externalAmount = "50000.00"; currency = "USD" } | ConvertTo-Json
$created = Invoke-RestMethod -Method Post "$BaseUrl/api/v1/breaks" -Headers @{ Authorization = "Bearer $analystA" } `
    -Body $body -ContentType "application/json"
$script:ok++
Write-Host ("  {0} {1,-58} {2}" -f $tick, "Create break $ref (variance $($created.variance) USD)", 201) -ForegroundColor Green

Check "List own tenant's breaks"   200 { Status GET "$BaseUrl/api/v1/breaks" $analystA }
Check "Get break by id"            200 { Status GET "$BaseUrl/api/v1/breaks/$($created.id)" $analystA }

$evidence = Join-Path $PSScriptRoot "..\samples\evidence\bank-statement-BRK-3001.csv"
$evUrl    = "$BaseUrl/api/v1/breaks/$($created.id)/evidence"
Check "Upload evidence (new file)"                 201 { Upload $evUrl $analystA $evidence }
Check "Upload same file again (SHA-256 dedup)"     200 { Upload $evUrl $analystA $evidence }
Check "List evidence for break"                    200 { Status GET $evUrl $analystA }

Write-Host ""
Write-Host "  Security - must be rejected" -ForegroundColor Cyan
Check "No token"                                          401 { Status GET "$BaseUrl/api/v1/breaks" }
Check "Garbage token"                                     401 { Status GET "$BaseUrl/api/v1/breaks" "not-a-jwt" }
Check "APPROVER_L1 tries to create a break (wrong role)"  403 { Status POST "$BaseUrl/api/v1/breaks" $approverA $body }
Check "TENANT_B reads TENANT_A's break (404, not 403)"    404 { Status GET "$BaseUrl/api/v1/breaks/$($created.id)" $analystB }
Check "TENANT_B uploads to TENANT_A's break"              404 { Upload $evUrl $analystB $evidence }

Write-Host ""
Write-Host "  ------------------------------------------------" -ForegroundColor DarkGray
$color = if ($script:bad -eq 0) { "Green" } else { "Red" }
Write-Host ("  {0} passed, {1} failed" -f $script:ok, $script:bad) -ForegroundColor $color
Write-Host ""
