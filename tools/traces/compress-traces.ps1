$ErrorActionPreference = "Stop"
$root = (git rev-parse --show-toplevel)
$target = & bash "$root/tools/tracechaser-bootstrap.sh" --require "traces/compress-traces.ps1"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $target @args
exit $LASTEXITCODE
