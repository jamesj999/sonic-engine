$ErrorActionPreference = 'Stop'

try {
    $worktree = (git rev-parse --show-toplevel).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($worktree)) {
        throw 'Git did not return a worktree root.'
    }
    git -C $worktree config --local core.hooksPath .githooks
    if ($LASTEXITCODE -ne 0) {
        throw 'Git configuration was not writable.'
    }
    Write-Host "OpenGGF hooks installed for $worktree (core.hooksPath=.githooks)"
}
catch {
    Write-Error "Unable to install OpenGGF hooks: $($_.Exception.Message)"
    exit 1
}
