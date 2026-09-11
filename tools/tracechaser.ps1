param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$RelativePath
)

$ErrorActionPreference = "Stop"
$root = (git rev-parse --show-toplevel)
if ($LASTEXITCODE -ne 0) { exit 4 }
$target = & bash "$root/tools/tracechaser-bootstrap.sh" --require $RelativePath
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output $target
