param(
    [switch]$Json,
    [switch]$Sql,
    [switch]$Backend,
    [string]$BaseUrl = "http://localhost:8080"
)

$debugUrl = "$BaseUrl/world/debug"
if ($Backend) {
    $debugUrl = "$BaseUrl/world/debug/backend"
} elseif ($Sql) {
    $debugUrl = "$BaseUrl/world/debug/sql"
}

if ($Json) {
    Invoke-RestMethod $debugUrl | ConvertTo-Json -Depth 6
    exit $LASTEXITCODE
}

$response = Invoke-WebRequest -Uri $debugUrl -Headers @{ Accept = "text/plain" }
$response.Content
