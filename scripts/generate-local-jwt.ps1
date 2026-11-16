# Generates a local dev JWT signed with the throwaway dev keypair.
# Usage:
#   .\scripts\generate-local-jwt.ps1 -Tenant TENANT_A -Role RECON_ANALYST -Subject analyst-1
# Requires the project to have been compiled once (mvn test-compile) so the classes exist.
param(
    [string]$Subject = "dev-user",
    [string]$Tenant  = "TENANT_A",
    [string]$Role    = "RECON_ANALYST"
)
$ErrorActionPreference = "Stop"

# Build the test classpath (compiled test classes + dependencies).
# -D properties must be quoted: unquoted, PowerShell splits them at the dot and Maven fails.
mvn -q dependency:build-classpath "-Dmdep.outputFile=cp.txt" "-DincludeScope=test" 2>$null | Out-Null
if (-not (Test-Path cp.txt)) { throw "mvn dependency:build-classpath failed - is mvn on PATH and JAVA_HOME set to JDK 21?" }
$deps = Get-Content cp.txt
Remove-Item cp.txt -ErrorAction SilentlyContinue
$sep = [System.IO.Path]::PathSeparator
$fullCp = "target\test-classes$sep$deps"

# The classpath is longer than the Windows command-line limit (32 K), so it goes through a Java @argfile.
# Inside an argfile backslashes must be doubled, and the value is quoted because paths can contain spaces.
$argFile = Join-Path $env:TEMP "reconcile-jwt-cp.txt"
Set-Content -Path $argFile -Value ('-cp "' + ($fullCp -replace '\\', '\\\\') + '"') -Encoding ASCII
try {
    java "@$argFile" com.reconcileai.support.JwtTestSupport $Subject $Tenant $Role
} finally {
    Remove-Item $argFile -ErrorAction SilentlyContinue
}
