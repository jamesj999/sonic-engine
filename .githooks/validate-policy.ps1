[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Mode,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingArgs
)

$ErrorActionPreference = "Stop"
$script:GithubFileSizeLimitBytes = 100000000
$script:TraceCompressionThresholdBytes = 1048576
# Maintainer-approved 0.6 trailer-debt baseline; all later incoming commits are checked.
$script:ReleaseTrailerCutoverBase = "45cecf566825aa50612f5e687b2682fc9681aed1"
$script:ReleaseResourceBaseline = "45cecf566825aa50612f5e687b2682fc9681aed1"
$script:NextRolloverResourceBaseline = "218b8fff1a829c7a509331c453d2f3be7e51533b"
$script:NextRolloverTrailerBaseline = "218b8fff1a829c7a509331c453d2f3be7e51533b"
$script:PublishedReleaseTrailerBaseline = "37aeb6b84d6634457616c942187cdd40dd93c24f"
$script:ReleaseRangeBase = ""
$script:ResourcePolicyCutover = "ccdd33edf4f9cd4a7937791f1d4c2f37cbeeb5e0"
$script:RomLikeDenylistExtensions = @(".gen", ".smd", ".bin", ".sms", ".gg", ".32x")
$script:EmptyTreeOid = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
$script:AllZeroOid = "0000000000000000000000000000000000000000"
$script:PosixHomeRoot = "/home"
$script:VarHomeRoot = "/var/home"
$script:MacosHomeRoot = "/Users"
$script:WindowsUsersRoot = '[A-Za-z]:[\\/]+[Uu][Ss][Ee][Rr][Ss]'
$script:MachineLocalPathGrandfather =
        Join-Path $PSScriptRoot "machine-local-path-grandfather.sha256"

function Fail([string]$Message) {
    [Console]::Error.WriteLine("policy: $Message")
    exit 1
}

function Note([string]$Message) {
    [Console]::Error.WriteLine("policy: $Message")
}

function Invoke-GitResult([string[]]$Arguments) {
    $output = & git @Arguments 2>$null
    $exitCode = $LASTEXITCODE
    $text = if ($null -eq $output) {
        ""
    } else {
        ($output -join "`n").TrimEnd("`r", "`n")
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Text = $text
    }
}

function Invoke-GitText([string[]]$Arguments, [switch]$AllowFailure) {
    $result = Invoke-GitResult $Arguments
    if (-not $AllowFailure -and $result.ExitCode -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $($result.ExitCode)"
    }
    if ($result.ExitCode -ne 0) {
        return ""
    }
    return $result.Text
}

function Invoke-GitLines([string[]]$Arguments, [switch]$AllowFailure) {
    $text = Invoke-GitText $Arguments -AllowFailure:$AllowFailure
    if ([string]::IsNullOrEmpty($text)) {
        return @()
    }
    return ($text -split "`r?`n") | Where-Object { $_ -ne "" }
}

function Test-GitSuccess([string[]]$Arguments) {
    & git @Arguments *> $null
    return ($LASTEXITCODE -eq 0)
}

function Get-CurrentBranch() {
    $branch = Invoke-GitText @("symbolic-ref", "--quiet", "--short", "HEAD") -AllowFailure
    if ([string]::IsNullOrWhiteSpace($branch)) {
        return "HEAD"
    }
    return $branch
}

function Test-MergeInProgress() {
    & git rev-parse -q --verify MERGE_HEAD *> $null
    return ($LASTEXITCODE -eq 0)
}

function Get-MergeHeadOid() {
    return Invoke-GitText @("rev-parse", "-q", "--verify", "MERGE_HEAD") -AllowFailure
}

function Get-MasterTipOid() {
    return Invoke-GitText @("rev-parse", "-q", "--verify", "refs/heads/master") -AllowFailure
}

function Test-MergeFromMaster() {
    $mergeOid = Get-MergeHeadOid
    $masterOid = Get-MasterTipOid
    return (-not [string]::IsNullOrWhiteSpace($mergeOid) -and
        -not [string]::IsNullOrWhiteSpace($masterOid) -and
        $mergeOid -ceq $masterOid)
}

function Get-StagedFiles() {
    return Invoke-GitLines @("diff", "--cached", "--name-only", "--diff-filter=ACMRT")
}

function Get-StagedCandidates() {
    $mergeOid = Get-MergeHeadOid
    if (-not [string]::IsNullOrWhiteSpace($mergeOid)) {
        return Invoke-GitLines @("diff", "--cached", "--no-renames", "--name-only", "--diff-filter=AMT", $mergeOid)
    }
    if (Test-GitSuccess @("rev-parse", "-q", "--verify", "HEAD")) {
        return Invoke-GitLines @("diff", "--cached", "--no-renames", "--name-only", "--diff-filter=AMT", "HEAD")
    }
    return Invoke-GitLines @("diff", "--cached", "--no-renames", "--name-only", "--diff-filter=AMT")
}

function Get-CommitFiles([string]$Commit) {
    return Invoke-GitLines @("diff-tree", "--root", "--no-commit-id", "--name-only", "--diff-filter=ACMRT", "-r", $Commit)
}

function Get-CommitParentOrEmptyTree([string]$Commit) {
    $mergeParent = Invoke-GitText @("rev-parse", "-q", "--verify", "$Commit^2") -AllowFailure
    if (-not [string]::IsNullOrWhiteSpace($mergeParent)) {
        return $mergeParent
    }
    $parent = Invoke-GitText @("rev-parse", "-q", "--verify", "$Commit^1") -AllowFailure
    if ([string]::IsNullOrWhiteSpace($parent)) {
        return $script:EmptyTreeOid
    }
    return $parent
}

function Get-CommitCandidates([string]$Commit) {
    if (Test-GitSuccess @("rev-parse", "-q", "--verify", "$Commit^2")) {
        # A merge commit's own candidates are the paths whose content differs
        # from every parent. Anything matching either side was already
        # published on that side and is that branch's history, not content
        # this merge introduces.
        $first = Invoke-GitText @("rev-parse", "$Commit^1")
        $second = Invoke-GitText @("rev-parse", "$Commit^2")
        $firstFiles = @(Invoke-GitLines @("diff", "--no-renames", "--name-only", "--diff-filter=AMT", $first, $Commit))
        $secondFiles = @(Invoke-GitLines @("diff", "--no-renames", "--name-only", "--diff-filter=AMT", $second, $Commit))
        $secondSet = New-Object 'System.Collections.Generic.HashSet[string]' (,[string[]]$secondFiles)
        return @($firstFiles | Where-Object { $secondSet.Contains($_) })
    }
    $parent = Get-CommitParentOrEmptyTree $Commit
    return Invoke-GitLines @("diff", "--no-renames", "--name-only", "--diff-filter=AMT", $parent, $Commit)
}

$script:ModApiDescriptor = "mod-api-release-policy.properties"
$script:ModApiVersion = "src/main/java/com/openggf/mods/ModApiVersion.java"
$script:ModApiPinPrefix = "src/test/resources/mods/mod-api-signatures-"

function Get-BlobText([string]$Ref, [string]$Path) {
    if ($Ref -eq "EMPTY") { return "" }
    $spec = if ($Ref -eq "INDEX") { ":$Path" } else { "${Ref}:$Path" }
    return Invoke-GitText @("show", $spec) -AllowFailure
}

function Get-DescriptorPins([string]$Ref) {
    $values = @{}
    foreach ($line in ((Get-BlobText $Ref $script:ModApiDescriptor) -split "`r?`n")) {
        if ($line -match '^([^=]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
    }
    $pins = New-Object System.Collections.Generic.List[string]
    foreach ($version in (($values["publishedBaselines"] -split ',') | Where-Object { $_ })) {
        $pins.Add("$($script:ModApiPinPrefix)$version.txt") | Out-Null
    }
    if ($values["currentStatus"] -eq "candidate" -and $values["currentApi"] -match '^(\d+\.\d+)\.') {
        $pins.Add("$($script:ModApiPinPrefix)$($Matches[1]).txt") | Out-Null
    }
    return @($pins | Sort-Object)
}

function Get-ActualPins([string]$Ref) {
    if ($Ref -eq "INDEX") {
        return @(Invoke-GitLines @("ls-files", "--cached") |
                Where-Object { $_.StartsWith($script:ModApiPinPrefix, [StringComparison]::Ordinal) -and $_.EndsWith(".txt") } | Sort-Object)
    }
    if ($Ref -eq "EMPTY") { return @() }
    return @(Invoke-GitLines @("ls-tree", "-r", "--name-only", $Ref) |
            Where-Object { $_.StartsWith($script:ModApiPinPrefix, [StringComparison]::Ordinal) -and $_.EndsWith(".txt") } | Sort-Object)
}

function Get-CurrentApiValue([string]$Ref) {
    $text = Get-BlobText $Ref $script:ModApiVersion
    if ($text -match '(?m)^\s*(?:public\s+)?static\s+final[^\r\n]*\bCURRENT\s*=.*?"([0-9]+(?:\.[0-9]+)+)"') { return $Matches[1] }
    return ""
}

function Test-ContainsModApiAnnotation([string]$Text) {
    return $Text -match '(?m)^\s*@ModApi(?:[.(\s]|$)'
}

function Validate-ModApiCoupling([string]$OldRef, [string]$NewRef, [string[]]$DiffArguments) {
    $args = if ($NewRef -eq "INDEX") { @("diff", "--cached", "--name-status", "-M") } else { @("diff", "--name-status", "-M") + $DiffArguments }
    $candidate = (Get-DescriptorPins $NewRef | Where-Object { $_ -match 'mod-api-signatures-\d+\.\d+\.txt$' } | Select-Object -First 1)
    $descriptorChanged = $false; $currentChanged = $false; $apiDelta = $false
    $candidateContent = $false; $publishedContent = $false; $pinStructural = $false; $candidateStructural = $false
    foreach ($record in (Invoke-GitLines $args)) {
        $fields = $record -split "`t"
        $status = $fields[0]
        $paths = @($fields[1..($fields.Count - 1)])
        if ($paths -contains $script:ModApiDescriptor) { $descriptorChanged = $true }
        if ($paths -contains $script:ModApiVersion) {
            $currentChanged = (Get-CurrentApiValue $OldRef) -ne (Get-CurrentApiValue $NewRef)
        }
        if ($paths | Where-Object { $_.StartsWith($script:ModApiPinPrefix, [StringComparison]::Ordinal) }) {
            if ($status.StartsWith("M")) {
                if ($fields[1] -eq $candidate) { $candidateContent = $true } else { $publishedContent = $true }
            } else {
                $pinStructural = $true
                if ($candidate -and $paths -contains $candidate) { $candidateStructural = $true }
            }
        }
        $before = if ($status.StartsWith("A")) { "EMPTY" } else { $OldRef }
        $after = if ($status.StartsWith("D")) { "EMPTY" } else { $NewRef }
        foreach ($path in $paths) {
            if (-not $path.EndsWith(".java", [StringComparison]::Ordinal)) { continue }
            $oldText = Get-BlobText $before $path
            $newText = Get-BlobText $after $path
            if (((Test-ContainsModApiAnnotation $oldText) -or (Test-ContainsModApiAnnotation $newText)) -and $oldText -ne $newText) { $apiDelta = $true }
        }
    }
    if ($currentChanged -and (-not $descriptorChanged -or (-not $candidateContent -and -not $candidateStructural))) {
        Add-ValidationError "ModApiVersion.CURRENT changes require the release descriptor and its normalized signature-pin operation."
    }
    if ($publishedContent) {
        Add-ValidationError "published full-version signature pins are immutable and may never be edited in place."
    }
    if ($apiDelta -and (-not $candidate -or (-not $candidateContent -and -not $candidateStructural))) {
        Add-ValidationError "detectable @ModApi surface changes require updating the current candidate signature pin."
    }
    if ($candidateContent -and -not $apiDelta) {
        Add-ValidationError "candidate signature-pin content changes require a detectable @ModApi surface change; descriptor edits are not a substitute."
    }
    if ($candidateContent -and $descriptorChanged -and -not $pinStructural) {
        Add-ValidationError "ordinary candidate signature regeneration must not edit the release descriptor."
    }
    if ($pinStructural -and -not $descriptorChanged) {
        Add-ValidationError "signature-pin additions, deletions, and renames require a descriptor publication or promotion edit."
    }
    $oldPins = (Get-DescriptorPins $OldRef) -join "`n"
    $newPins = (Get-DescriptorPins $NewRef) -join "`n"
    $actualOldPins = (Get-ActualPins $OldRef) -join "`n"
    $actualNewPins = (Get-ActualPins $NewRef) -join "`n"
    $bootstrapNormalized = [string]::IsNullOrEmpty((Get-BlobText $OldRef $script:ModApiDescriptor)) -and
            $actualOldPins -eq $newPins -and $actualNewPins -eq $newPins
    if ($descriptorChanged -and $oldPins -ne $newPins -and -not $pinStructural -and -not $bootstrapNormalized) {
        Add-ValidationError "descriptor topology/status changes must include the normalized signature-pin add, delete, or rename implied by the new state."
    }
    if (($descriptorChanged -or $pinStructural) -and $actualNewPins -ne $newPins) {
        Add-ValidationError "the resulting signature-pin inventory does not match the descriptor's normalized pin map."
    }
}

function Get-StagedBlobSize([string]$Path) {
    $size = Invoke-GitText @("cat-file", "-s", ":$Path") -AllowFailure
    if ([string]::IsNullOrWhiteSpace($size)) {
        return $null
    }
    return [long]$size
}

function Get-CommitBlobSize([string]$Commit, [string]$Path) {
    $spec = "${Commit}:$Path"
    $size = Invoke-GitText @("cat-file", "-s", $spec) -AllowFailure
    if ([string]::IsNullOrWhiteSpace($size)) {
        return $null
    }
    return [long]$size
}

function Get-StagedEntryMode([string]$Path) {
    foreach ($entry in (Invoke-GitLines @("ls-files", "--stage", "--", ":(literal)$Path"))) {
        if ($entry -match '^([0-9]{6})\s+[0-9a-f]+\s+0\t') {
            return $Matches[1]
        }
    }
    return ""
}

function Get-CommitEntryMode([string]$Commit, [string]$Path) {
    foreach ($entry in (Invoke-GitLines @("ls-tree", $Commit, "--", ":(literal)$Path"))) {
        if ($entry -match '^([0-9]{6})\s+') {
            return $Matches[1]
        }
    }
    return ""
}

function Get-StagedBlob([string]$Path) {
    return Invoke-GitResult @("cat-file", "blob", ":$Path")
}

function Get-CommitBlob([string]$Commit, [string]$Path) {
    return Invoke-GitResult @("cat-file", "blob", "${Commit}:$Path")
}

function Test-ProtectedResourcePath([string]$Path) {
    if ($Path -ceq "config.yaml" -or $Path.EndsWith(".gen", [System.StringComparison]::Ordinal)) {
        return $true
    }
    foreach ($protectedPath in @(
            "docs/s1disasm",
            "docs/s2disasm",
            "docs/kis2disasm",
            "docs/scddisasm",
            "docs/skdisasm"
        )) {
        if ([string]::Equals($Path, $protectedPath, [System.StringComparison]::Ordinal)) {
            return $true
        }
    }
    return $false
}

function Test-AbsoluteLinkTarget([string]$Target) {
    return $Target.StartsWith("/", [System.StringComparison]::Ordinal) `
        -or $Target -match '^[A-Za-z]:[\\/]' `
        -or $Target.StartsWith("\\", [System.StringComparison]::Ordinal)
}

function Test-RootScratchPath([string]$Path) {
    if ($Path.Contains("/")) {
        return $false
    }
    return $Path -cmatch '^(MERGE-STATUS.*|HANDOVER.*)\.md$'
}

function Get-MachineLocalHomeGrepArguments([string]$Path, [string]$Commit) {
    $arguments = New-Object System.Collections.Generic.List[string]
    $arguments.Add("grep") | Out-Null
    if ([string]::IsNullOrWhiteSpace($Commit)) {
        $arguments.Add("--cached") | Out-Null
    }
    foreach ($argument in @(
        "-I",
        "-q",
        "-E",
        "-e",
        ($script:PosixHomeRoot + '/[^/$<[:space:]][^/[:space:]]*/'),
        "-e",
        ($script:VarHomeRoot + '/[^/$<[:space:]][^/[:space:]]*/'),
        "-e",
        ($script:MacosHomeRoot + '/[^/$<[:space:]][^/[:space:]]*/'),
        "-e",
        ($script:WindowsUsersRoot + '[\\/]+[^\\/$<%[:space:]][^\\/[:space:]]*[\\/]')
    )) {
        $arguments.Add($argument) | Out-Null
    }
    if (-not [string]::IsNullOrWhiteSpace($Commit)) {
        $arguments.Add($Commit) | Out-Null
    }
    $arguments.Add("--") | Out-Null
    $arguments.Add(":(literal)$Path") | Out-Null
    return $arguments.ToArray()
}

function Test-StagedBlobHasMachineLocalHome([string]$Path) {
    $result = Invoke-GitResult (Get-MachineLocalHomeGrepArguments $Path "")
    return $result.ExitCode
}

function Test-CommitBlobHasMachineLocalHome([string]$Commit, [string]$Path) {
    $result = Invoke-GitResult (Get-MachineLocalHomeGrepArguments $Path $Commit)
    return $result.ExitCode
}

function Get-StagedCandidateBase() {
    $mergeOid = Get-MergeHeadOid
    if (-not [string]::IsNullOrWhiteSpace($mergeOid)) {
        return $mergeOid
    }
    $head = Invoke-GitText @("rev-parse", "-q", "--verify", "HEAD") -AllowFailure
    if ([string]::IsNullOrWhiteSpace($head)) {
        return $script:EmptyTreeOid
    }
    return $head
}

function Test-BaselineHasPath([string]$Baseline, [string]$Path) {
    return Test-GitSuccess @("cat-file", "-e", "${Baseline}:$Path")
}

function Test-TextHasMachineLocalHome([string]$Text) {
    $pattern = '(?:/home|/var/home|/Users)/[^/$<\s][^/\s]*/|' +
            '[A-Za-z]:[\\/]+[Uu][Ss][Ee][Rr][Ss][\\/]+[^\\/$<%\s][^\\/\s]*[\\/]'
    return $Text -cmatch $pattern
}

function Get-IntroducedLines(
        [string]$Baseline,
        [string]$Commit,
        [string]$Path,
        [string]$Source) {
    $arguments = New-Object System.Collections.Generic.List[string]
    $arguments.Add("diff") | Out-Null
    if ($Source -eq "staged") {
        $arguments.Add("--cached") | Out-Null
    }
    foreach ($argument in @("--no-ext-diff", "--unified=0", $Baseline)) {
        $arguments.Add($argument) | Out-Null
    }
    if ($Source -eq "commit") {
        $arguments.Add($Commit) | Out-Null
    }
    $arguments.Add("--") | Out-Null
    $arguments.Add(":(literal)$Path") | Out-Null

    $diff = Invoke-GitResult $arguments.ToArray()
    if ($diff.ExitCode -ne 0) {
        throw "could not inspect introduced lines for $Path"
    }
    return @($diff.Text -split "`r?`n" |
            Where-Object { $_.StartsWith("+") -and -not $_.StartsWith("+++") } |
            ForEach-Object { $_.Substring(1) })
}

function Get-LineSha256([string]$Line) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Line)
        return [Convert]::ToHexString($sha.ComputeHash($bytes)).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-GitBlobBytes([string]$Source, [string]$Commit, [string]$Path) {
    $spec = if ($Source -eq "commit") { "${Commit}:$Path" } else { ":$Path" }
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = "git"
    $startInfo.Arguments = "cat-file blob `"$spec`""
    $startInfo.WorkingDirectory = (Get-Location).Path
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $memory = New-Object System.IO.MemoryStream
    try {
        $process.StandardOutput.BaseStream.CopyTo($memory)
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw "could not read final blob bytes for $Path"
        }
        return $memory.ToArray()
    } finally {
        $memory.Dispose()
        $process.Dispose()
    }
}

function Get-GrandfatherPrefix([string]$Path) {
    if (-not (Test-Path -LiteralPath $script:MachineLocalPathGrandfather -PathType Leaf)) {
        return $null
    }
    foreach ($line in (Get-Content -LiteralPath $script:MachineLocalPathGrandfather)) {
        # PowerShell's Max-substrings is not .NET's: a NEGATIVE value returns that
        # many substrings from the END, so ", -1" yielded the whole line as one
        # field and every entry looked malformed. ", 0" is PowerShell's "return
        # all", which is what the shell parser and the Java/.NET `split(s, -1)`
        # idiom this was written from actually mean.
        $fields = $line -split "`t", 0
        if ($fields.Count -eq 4 -and
                $fields[0] -ceq "# baseline-prefix" -and
                $fields[3] -ceq $Path) {
            if ($fields[1] -cnotmatch '^[1-9][0-9]*$' -or
                    $fields[2] -cnotmatch '^[0-9a-f]{64}$') {
                throw "machine-local path grandfather contains malformed prefix metadata"
            }
            return [pscustomobject]@{
                Length = [int64]$fields[1]
                Hash = $fields[2]
            }
        }
    }
    return $null
}

function Test-GrandfatherPrefix([string]$Source, [string]$Commit, [string]$Path) {
    $prefix = Get-GrandfatherPrefix $Path
    if ($null -eq $prefix) {
        return $true
    }
    [byte[]]$bytes = Get-GitBlobBytes $Source $Commit $Path
    if ($bytes.Length -lt $prefix.Length) {
        return $false
    }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $actual = [Convert]::ToHexString(
                $sha.ComputeHash($bytes, 0, [int]$prefix.Length)).ToLowerInvariant()
        return $actual -ceq $prefix.Hash
    } finally {
        $sha.Dispose()
    }
}

function Get-GrandfatherEntries() {
    $entries = @{}
    if (-not (Test-Path -LiteralPath $script:MachineLocalPathGrandfather -PathType Leaf)) {
        return $entries
    }
    foreach ($line in (Get-Content -LiteralPath $script:MachineLocalPathGrandfather)) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) {
            continue
        }
        # PowerShell's Max-substrings is not .NET's: a NEGATIVE value returns that
        # many substrings from the END, so ", -1" yielded the whole line as one
        # field and every entry looked malformed. ", 0" is PowerShell's "return
        # all", which is what the shell parser and the Java/.NET `split(s, -1)`
        # idiom this was written from actually mean.
        $fields = $line -split "`t", 0
        if ($fields.Count -ne 3 -or
                $fields[0] -cnotmatch '^[0-9a-f]{64}$' -or
                $fields[1] -cnotmatch '^[1-9][0-9]*$') {
            throw "machine-local path grandfather contains a malformed entry"
        }
        $key = $fields[2] + "`0" + $fields[0]
        if ($entries.ContainsKey($key)) {
            throw "machine-local path grandfather contains a duplicate entry"
        }
        $entries[$key] = [int]$fields[1]
    }
    return $entries
}

function Get-FinalBlobLines([string]$Source, [string]$Commit, [string]$Path) {
    $blob = if ($Source -eq "commit") {
        Get-CommitBlob $Commit $Path
    } else {
        Get-StagedBlob $Path
    }
    if ($blob.ExitCode -ne 0) {
        throw "could not read final blob for $Path"
    }
    return @($blob.Text -split "`r?`n")
}

function Test-IntroducedLinesAreAllowed(
        [string]$Baseline,
        [string]$Commit,
        [string]$Path,
        [string]$Source) {
    $offending = @(Get-IntroducedLines $Baseline $Commit $Path $Source |
            Where-Object { Test-TextHasMachineLocalHome $_ })
    if ($offending.Count -eq 0) {
        return $true
    }

    $entries = Get-GrandfatherEntries
    $finalLines = @(Get-FinalBlobLines $Source $Commit $Path)
    foreach ($line in $offending) {
        $key = $Path + "`0" + (Get-LineSha256 $line)
        if (-not $entries.ContainsKey($key)) {
            return $false
        }
        $occurrences = @($finalLines | Where-Object {
            [string]::Equals($_, $line, [System.StringComparison]::Ordinal)
        }).Count
        if ($occurrences -gt $entries[$key]) {
            return $false
        }
    }
    return $true
}

function Test-HasExact([string[]]$Files, [string]$Needle) {
    return $Files -contains $Needle
}

function Test-HasPrefix([string[]]$Files, [string]$Prefix) {
    foreach ($path in $Files) {
        if ($path.StartsWith($Prefix, [System.StringComparison]::Ordinal)) {
            return $true
        }
    }
    return $false
}

function Get-TrailerValue([string]$Key, [string]$Message) {
    $value = ""
    foreach ($line in ($Message -split "`r?`n")) {
        $text = [string]$line
        if ($text.StartsWith("${Key}:", [System.StringComparison]::Ordinal)) {
            $value = $text.Substring($Key.Length + 1).Trim()
        }
    }
    return $value
}

function Get-DecisionKind([string]$Value) {
    $normalized = $Value.ToLowerInvariant()
    if ($normalized -eq "updated" -or $normalized.StartsWith("updated ") -or $normalized.StartsWith("updated:") -or $normalized.StartsWith("updated-")) {
        return "updated"
    }
    if ($normalized -eq "n/a" -or $normalized.StartsWith("n/a ") -or $normalized.StartsWith("n/a:") -or $normalized.StartsWith("n/a-")) {
        return "na"
    }
    return "invalid"
}

function Print-CommitTemplate() {
    # Single-quote here-string so the backtick before `updated` on the final
    # line is treated as a literal character, not a PowerShell escape (the
    # double-quote here-string parser would otherwise see `u and try to
    # consume a Unicode escape, causing a parse error on PS 5.1+).
    [Console]::Error.WriteLine(@'
Use these trailers on non-master branch commits:

Changelog: updated|n/a
Guide: updated|n/a
Known-Discrepancies: updated|n/a
S3K-Known-Discrepancies: updated|n/a
Agent-Docs: updated|n/a
Configuration-Docs: updated|n/a
Skills: updated|n/a

If a trailer says `updated`, the matching files must be staged in the same commit.
'@)
}

$script:Errors = New-Object System.Collections.Generic.List[string]

function Reset-ValidationErrors() {
    $script:Errors = New-Object System.Collections.Generic.List[string]
}

function Add-ValidationError([string]$Message) {
    $script:Errors.Add("- $Message") | Out-Null
}

function Test-RomLikeTrackedPath([string]$Path) {
    $lower = $Path.ToLowerInvariant()
    foreach ($extension in $script:RomLikeDenylistExtensions) {
        if ($lower.EndsWith($extension)) {
            return $true
        }
    }
    return $false
}

function Validate-FileSizePolicyForFiles([string[]]$Files, [scriptblock]$SizeResolver) {
    foreach ($path in $Files) {
        if (Test-RomLikeTrackedPath $path) {
            Add-ValidationError "``$path`` looks like a ROM/binary asset. Keep user-supplied ROMs and ROM-derived binary assets untracked."
        }
        $size = & $SizeResolver $path
        if ($null -eq $size) {
            continue
        }
        $fileName = [System.IO.Path]::GetFileName($path)
        if ((($fileName -like "aux_state*.jsonl") -or ($fileName -like "physics*.csv")) -and
                $size -ge $script:TraceCompressionThresholdBytes) {
            $message = ("``$path`` is an uncompressed trace payload ({0} bytes). " +
                    "Commit the ``.gz`` instead: the native harness " +
                    "(tools/bizhawk-headless) compresses at capture time by default, " +
                    "and ``tools/traces/compress-traces.ps1`` does it for a Lua " +
                    "capture directory.") -f $size
            Add-ValidationError $message
        }
        if ($size -ge $script:GithubFileSizeLimitBytes) {
            $message = ("``$path`` is {0} bytes; GitHub rejects files >= {1} bytes.") -f `
                    $size, $script:GithubFileSizeLimitBytes
            Add-ValidationError $message
        }
    }
}

function Validate-ContentCandidates([string[]]$Files, [string]$Source, [string]$Commit) {
    $baseline = if ($Source -eq "commit") {
        Get-CommitParentOrEmptyTree $Commit
    } else {
        Get-StagedCandidateBase
    }
    foreach ($path in $Files) {
        if ([string]::IsNullOrWhiteSpace($path)) {
            continue
        }

        if (Test-RootScratchPath $path) {
            Add-ValidationError "``$path`` is a root-level merge/handover scratch artifact. Classify retained engineering material under ``docs/architecture/``."
        }

        $mode = if ($Source -eq "commit") {
            Get-CommitEntryMode $Commit $path
        } else {
            Get-StagedEntryMode $path
        }
        if ([string]::IsNullOrWhiteSpace($mode)) {
            Add-ValidationError "``$path`` could not be read from the $Source candidate set."
            continue
        }

        if ($mode -eq "120000") {
            if (Test-ProtectedResourcePath $path) {
                Add-ValidationError "``$path`` is a generated worktree resource and must not be committed as a symlink."
            }

            $blob = if ($Source -eq "commit") {
                Get-CommitBlob $Commit $path
            } else {
                Get-StagedBlob $path
            }
            if ($blob.ExitCode -ne 0) {
                if ($Source -ceq "commit") {
                    Add-ValidationError "``$path`` is a symlink whose committed target blob could not be read."
                } else {
                    Add-ValidationError "``$path`` is a symlink whose staged target blob could not be read."
                }
                continue
            }
            if (Test-AbsoluteLinkTarget $blob.Text) {
                Add-ValidationError "``$path`` has an absolute symlink target. Use a repository-relative target or keep the link untracked."
            }
        }

        if (Test-BaselineHasPath $baseline $path) {
            if (-not (Test-GrandfatherPrefix $Source $Commit $path)) {
                Add-ValidationError "``$path`` does not preserve its verified historic prefix byte-for-byte."
                continue
            }
            if (-not (Test-IntroducedLinesAreAllowed $baseline $Commit $path $Source)) {
                Add-ValidationError "``$path`` contains a machine-local user-home path. Use a repository-relative path, environment variable, or neutral placeholder."
            }
            continue
        }

        $grepStatus = if ($Source -eq "commit") {
            Test-CommitBlobHasMachineLocalHome $Commit $path
        } else {
            Test-StagedBlobHasMachineLocalHome $path
        }
        if ($grepStatus -eq 0) {
            Add-ValidationError "``$path`` contains a machine-local user-home path. Use a repository-relative path, environment variable, or neutral placeholder."
        } elseif ($grepStatus -gt 1) {
            Add-ValidationError "``$path`` could not be inspected for machine-local paths."
        }
    }
}

function Write-ValidationErrors() {
    foreach ($entry in $script:Errors) {
        [Console]::Error.WriteLine($entry)
    }
}

function Validate-StagedContent() {
    $files = @(Get-StagedCandidates)
    Reset-ValidationErrors
    Validate-FileSizePolicyForFiles $files { param($path) Get-StagedBlobSize $path }
    Validate-ContentCandidates $files "staged" ""
    if ($script:Errors.Count -gt 0) {
        Note "staged content violates the repository resource policy."
        Write-ValidationErrors
        exit 1
    }
}

function Validate-ExactTrailer([string]$Message, [string[]]$Files, [string]$Key, [string]$Path, [string]$Label) {
    $value = Get-TrailerValue $Key $Message
    if ([string]::IsNullOrWhiteSpace($value)) {
        Add-ValidationError "Missing ``$Key`` trailer."
        return
    }

    $kind = Get-DecisionKind $value
    $changed = Test-HasExact $Files $Path

    switch ($kind) {
        "updated" {
            if (-not $changed) {
                Add-ValidationError "``$Key`` says updated, but ``$Label`` is not staged."
            }
        }
        "na" {
            if ($changed) {
                Add-ValidationError "``$Key`` says n/a, but ``$Label`` is staged."
            }
        }
        default {
            Add-ValidationError "``$Key`` must start with ``updated`` or ``n/a``."
        }
    }
}

function Validate-PrefixTrailer([string]$Message, [string[]]$Files, [string]$Key, [string]$Prefix, [string]$Label) {
    $value = Get-TrailerValue $Key $Message
    if ([string]::IsNullOrWhiteSpace($value)) {
        Add-ValidationError "Missing ``$Key`` trailer."
        return
    }

    $kind = Get-DecisionKind $value
    $changed = Test-HasPrefix $Files $Prefix

    switch ($kind) {
        "updated" {
            if (-not $changed) {
                Add-ValidationError "``$Key`` says updated, but ``$Label`` has no staged changes."
            }
        }
        "na" {
            if ($changed) {
                Add-ValidationError "``$Key`` says n/a, but ``$Label`` has staged changes."
            }
        }
        default {
            Add-ValidationError "``$Key`` must start with ``updated`` or ``n/a``."
        }
    }
}

function Validate-AgentDocsTrailer([string]$Message, [string[]]$Files) {
    $key = "Agent-Docs"
    $value = Get-TrailerValue $key $Message
    if ([string]::IsNullOrWhiteSpace($value)) {
        Add-ValidationError "Missing ``$key`` trailer."
        return
    }

    $kind = Get-DecisionKind $value
    $agentsChanged = Test-HasExact $Files "AGENTS.md"
    $claudeChanged = Test-HasExact $Files "CLAUDE.md"

    switch ($kind) {
        "updated" {
            if (-not ($agentsChanged -and $claudeChanged)) {
                Add-ValidationError "``Agent-Docs`` says updated, but both ``AGENTS.md`` and ``CLAUDE.md`` must be staged together."
            }
        }
        "na" {
            if ($agentsChanged -or $claudeChanged) {
                Add-ValidationError "``Agent-Docs`` says n/a, but agent docs are staged."
            }
        }
        default {
            Add-ValidationError "``Agent-Docs`` must start with ``updated`` or ``n/a``."
        }
    }
}

function Validate-SkillsTrailer([string]$Message, [string[]]$Files) {
    $key = "Skills"
    $value = Get-TrailerValue $key $Message
    if ([string]::IsNullOrWhiteSpace($value)) {
        Add-ValidationError "Missing ``$key`` trailer."
        return
    }

    $kind = Get-DecisionKind $value
    $agentsChanged = Test-HasPrefix $Files ".agents/skills/"
    $claudeChanged = Test-HasPrefix $Files ".claude/skills/"

    switch ($kind) {
        "updated" {
            if (-not ($agentsChanged -and $claudeChanged)) {
                Add-ValidationError "``Skills`` says updated, but both ``.agents/skills/`` and ``.claude/skills/`` must have staged changes."
            }
        }
        "na" {
            if ($agentsChanged -or $claudeChanged) {
                Add-ValidationError "``Skills`` says n/a, but skill changes are staged."
            }
        }
        default {
            Add-ValidationError "``Skills`` must start with ``updated`` or ``n/a``."
        }
    }
}

# A feat/fix/perf commit that touches engine source (src/main/) is almost always
# changelog-worthy. The base trailer gate only checks staged<->trailer consistency,
# so it cannot catch a wrong `Changelog: n/a`. This requires such commits to either
# set `Changelog: updated` or justify the skip with a reason, e.g. `Changelog: n/a: test-only helper`.
function Test-ChangelogJustified([string]$Value) {
    $rest = $Value
    $rest = [System.Text.RegularExpressions.Regex]::Replace($rest, '^\s*n/a', '', 'IgnoreCase')
    $rest = [System.Text.RegularExpressions.Regex]::Replace($rest, '^[\s:,_-]+', '')
    return -not [string]::IsNullOrWhiteSpace($rest.Trim())
}

function Validate-ChangelogJustification([string]$Message, [string[]]$Files) {
    $subject = ($Message -split "`r?`n")[0]
    if ($subject -notmatch '^(feat|fix|perf)(\(.+\))?!?:') {
        return
    }

    if (-not (Test-HasPrefix $Files "src/main/")) {
        return
    }

    $value = Get-TrailerValue "Changelog" $Message
    if ([string]::IsNullOrWhiteSpace($value)) {
        return
    }

    if ((Get-DecisionKind $value) -ne "na") {
        return
    }

    if (-not (Test-ChangelogJustified $value)) {
        $type = ($subject -split '[:(!]')[0]
        Add-ValidationError "``Changelog`` is ``n/a`` on a ``$type`` commit touching ``src/main/``. Set ``Changelog: updated`` (and stage CHANGELOG.md) or justify the skip, e.g. ``Changelog: n/a: <reason>``."
    }
}

function Validate-NonMasterCommitMessage([string]$Message, [string[]]$Files) {
    Reset-ValidationErrors

    Validate-FileSizePolicyForFiles $Files { param($path) Get-StagedBlobSize $path }
    Validate-ExactTrailer $Message $Files "Changelog" "CHANGELOG.md" "CHANGELOG.md"
    Validate-ChangelogJustification $Message $Files
    Validate-PrefixTrailer $Message $Files "Guide" "docs/guide/" "docs/guide/"
    Validate-ExactTrailer $Message $Files "Known-Discrepancies" "docs/status/known-discrepancies.md" "docs/status/known-discrepancies.md"
    Validate-ExactTrailer $Message $Files "S3K-Known-Discrepancies" "docs/S3K_KNOWN_DISCREPANCIES.md" "docs/S3K_KNOWN_DISCREPANCIES.md"
    Validate-AgentDocsTrailer $Message $Files
    Validate-ExactTrailer $Message $Files "Configuration-Docs" "CONFIGURATION.md" "CONFIGURATION.md"
    Validate-SkillsTrailer $Message $Files
    Validate-ModApiCoupling "HEAD" "INDEX" @()

    if ($script:Errors.Count -gt 0) {
        Note "non-master branch commits must declare the documentation/discrepancy policy explicitly."
        foreach ($entry in $script:Errors) {
            [Console]::Error.WriteLine($entry)
        }
        Print-CommitTemplate
        exit 1
    }
}

function Validate-MergeIntoDevelop() {
    if ((Get-CurrentBranch) -cne "develop") {
        return
    }

    if (-not (Test-MergeInProgress)) {
        return
    }

    if (Test-MergeFromMaster) {
        return
    }

    # The README release section is a handful of version themes, not a change
    # log. A merge touches it only when it adds or changes a theme, which is a
    # review judgement rather than a staged-file check, so no file is required.
    return
}

function Prepare-CommitMessage([string]$MessageFile, [string]$Source) {
    if ((Get-CurrentBranch) -ceq "master") {
        return
    }

    if ($Source -in @("merge", "squash")) {
        return
    }

    if (Test-MergeInProgress) {
        return
    }

    $message = Get-Content -LiteralPath $MessageFile -Raw

    $append = New-Object System.Collections.Generic.List[string]
    foreach ($key in @(
        "Changelog",
        "Guide",
        "Known-Discrepancies",
        "S3K-Known-Discrepancies",
        "Agent-Docs",
        "Configuration-Docs",
        "Skills"
    )) {
        if ([string]::IsNullOrWhiteSpace((Get-TrailerValue $key $message))) {
            $append.Add("${key}: TODO") | Out-Null
        }
    }

    if ($append.Count -eq 0) {
        return
    }

    Add-Content -LiteralPath $MessageFile -Value ("`n" + ($append -join "`n"))
}

function Validate-CommitMsgHook([string]$MessageFile) {
    Validate-StagedContent

    if ((Get-CurrentBranch) -ceq "master") {
        return
    }

    if (Test-MergeInProgress) {
        Validate-MergeIntoDevelop
        return
    }

    $message = Get-Content -LiteralPath $MessageFile -Raw
    Validate-NonMasterCommitMessage $message (Get-StagedFiles)
}

$script:PublishedHistoryRefs = @("refs/remotes/origin/develop", "refs/remotes/origin/master")

function Test-PublishedHistory([string]$Commit) {
    foreach ($ref in $script:PublishedHistoryRefs) {
        if (-not (Test-GitSuccess @("rev-parse", "-q", "--verify", $ref))) { continue }
        if (Test-GitSuccess @("merge-base", "--is-ancestor", $Commit, $ref)) {
            return $true
        }
    }
    return $false
}

function Validate-CommitContent([string]$Commit) {
    # Commits already published on a protected branch were accepted there.
    # Merging that history forward must not re-litigate it.
    if (Test-PublishedHistory $Commit) {
        return
    }
    $files = @(Get-CommitCandidates $Commit)
    Reset-ValidationErrors
    Validate-FileSizePolicyForFiles $files { param($path) Get-CommitBlobSize $Commit $path }
    Validate-ContentCandidates $files "commit" $Commit
    if ($script:Errors.Count -gt 0) {
        Note "commit $Commit violates the repository resource policy."
        Write-ValidationErrors
        exit 1
    }
}

function Validate-TipTreeLinks([string]$Tip) {
    if (-not (Test-GitSuccess @("cat-file", "-e", "$Tip^{commit}"))) {
        Fail "required pushed tip $Tip is not available as a commit."
    }
    $canonicalTip = Invoke-GitText @("rev-parse", "$Tip^{commit}") -AllowFailure
    if ([string]::IsNullOrWhiteSpace($canonicalTip)) {
        Fail "could not resolve delivered tip $Tip to its full object id."
    }
    $expectedOidLength = $canonicalTip.Length

    try {
        $treeEntries = @(Invoke-GitLines @("ls-tree", "-r", $Tip))
    } catch {
        Fail "could not enumerate delivered tip tree $Tip."
    }
    Reset-ValidationErrors
    foreach ($entry in $treeEntries) {
        if ([string]::IsNullOrWhiteSpace($entry)) {
            continue
        }
        if ($entry -notmatch '^([^\t]*)\t(.*)$') {
            Fail "delivered tip tree $Tip contains a malformed entry: $entry"
        }
        $metadata = $Matches[1]
        $path = $Matches[2]
        if ($metadata -notmatch '^([^ ]+) ([^ ]+) ([^ ]+)$' -or
                [string]::IsNullOrEmpty($path)) {
            Fail "delivered tip tree $Tip contains malformed metadata for $path."
        }
        $mode = $Matches[1]
        $objectType = $Matches[2]
        $objectOid = $Matches[3]

        if ($objectOid -cnotmatch '^[0-9a-f]+$') {
            Fail "delivered tip tree $Tip contains a malformed object id for $path."
        }
        if ($objectOid.Length -ne $expectedOidLength) {
            Fail "delivered tip tree $Tip contains a truncated object id for $path."
        }

        $entryKind = "${mode}:${objectType}"
        if ($entryKind -cin @("100644:blob", "100755:blob", "160000:commit")) {
            continue
        }
        if ($entryKind -cne "120000:blob") {
            Fail "delivered tip tree $Tip contains unsupported metadata ``$metadata`` for $path."
        }

        if (Test-ProtectedResourcePath $path) {
            Add-ValidationError "``$path`` is a generated worktree resource symlink in delivered tip $Tip."
        }
        $blob = Invoke-GitResult @("cat-file", "blob", $objectOid)
        if ($blob.ExitCode -ne 0) {
            Add-ValidationError "``$path`` is a symlink whose delivered target blob could not be read."
            continue
        }
        if (Test-AbsoluteLinkTarget $blob.Text) {
            Add-ValidationError "``$path`` has an absolute symlink target in delivered tip $Tip."
        }
    }

    if ($script:Errors.Count -gt 0) {
        Note "delivered tip $Tip violates the repository resource policy."
        Write-ValidationErrors
        exit 1
    }
}

function Validate-ContentCommitList([string[]]$Commits, [string]$Tip) {
    # Audit the reviewed snapshot once, keeping per-commit checks on later work.
    $contentCutovers = @($script:ResourcePolicyCutover)
    $auditSnapshot = $false
    if (Test-GitSuccess @("merge-base", "--is-ancestor", $script:ReleaseResourceBaseline, $Tip)) {
        $contentCutovers = @($script:ReleaseResourceBaseline)
        $auditSnapshot = $true
    }
    if (Test-GitSuccess @("merge-base", "--is-ancestor", $script:NextRolloverResourceBaseline, $Tip)) {
        $contentCutovers += $script:NextRolloverResourceBaseline
        $auditSnapshot = $true
    }
    if ($auditSnapshot) {
        $python = Get-Command python3 -ErrorAction SilentlyContinue
        if (-not $python) { $python = Get-Command python -ErrorAction SilentlyContinue }
        if (-not $python) { Fail "Python 3 is required for the release snapshot audit." }
        & $python.Source (Join-Path $PSScriptRoot "audit-release-tree.py") $Tip
        if ($LASTEXITCODE -ne 0) { Fail "release snapshot audit rejected $Tip." }
    }
    $legacyCommits = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($contentCutover in $contentCutovers) {
        if (Test-GitSuccess @("merge-base", "--is-ancestor", $contentCutover, $Tip)) {
            foreach ($legacy in (Invoke-GitLines @("rev-list", $contentCutover))) {
                [void]$legacyCommits.Add($legacy)
            }
        }
    }
    foreach ($commit in $Commits) {
        if ([string]::IsNullOrWhiteSpace($commit)) {
            continue
        }
        if (-not (Test-GitSuccess @("cat-file", "-e", "$commit^{commit}"))) {
            Fail "required pushed commit $commit is not available."
        }
        if ($legacyCommits.Contains($commit)) {
            continue
        }
        Validate-CommitContent $commit
    }
    Validate-TipTreeLinks $Tip
}

function Get-CommitsInRange([string]$Base, [string]$Head) {
    if (-not (Test-GitSuccess @("cat-file", "-e", "$Base^{commit}"))) {
        Fail "required range base $Base is not available as a commit."
    }
    if (-not (Test-GitSuccess @("cat-file", "-e", "$Head^{commit}"))) {
        Fail "required range head $Head is not available as a commit."
    }
    try {
        if ($script:ReleaseRangeBase) {
            return Invoke-GitLines @("rev-list", "--reverse", "$Base..$Head", "^$script:ReleaseRangeBase")
        }
        return Invoke-GitLines @("rev-list", "--reverse", "$Base..$Head")
    } catch {
        Fail "could not enumerate commit range $Base..$Head."
    }
}

function Validate-ContentRange([string]$Base, [string]$Head) {
    $commits = @(Get-CommitsInRange $Base $Head)
    Validate-ContentCommitList $commits $Head
}

function Validate-CiPr([string]$BaseSha, [string]$HeadSha, [string]$BaseRef, [string]$HeadRef) {
    if ($BaseRef -cne "next" -and $BaseRef -cne "develop" -and $BaseRef -cne "master") {
        return
    }

    if ($BaseRef -ceq "master") {
        $script:ReleaseRangeBase = $BaseSha
    }
    $effectiveBaseSha = Get-EffectiveBaseForCiPr $BaseSha $HeadSha $BaseRef $HeadRef
    $rangeFiles = Invoke-GitLines @("diff", "--name-only", "--diff-filter=ACMRD", "$effectiveBaseSha...$HeadSha")

    if ($BaseRef -ceq "develop") {
        # No README.md requirement: the release section changes only when a
        # version theme does, and that is judged in review, not by file presence.
        if ($HeadRef -ceq "master") {
            Validate-ContentRange $effectiveBaseSha $HeadSha
            return
        }
    }

    Validate-CiCommitRange $effectiveBaseSha $HeadSha
}

function Validate-CiCommitRange([string]$EffectiveBaseSha, [string]$HeadSha) {
    $commits = @(Get-CommitsInRange $EffectiveBaseSha $HeadSha)
    # Trailer debt never changes the independent resource-policy boundary.
    if ($script:ReleaseRangeBase) {
        $contentCommits = @(Get-CommitsInRange $script:ReleaseRangeBase $HeadSha)
        Validate-ContentCommitList $contentCommits $HeadSha
    } else {
        Validate-ContentCommitList $commits $HeadSha
    }

    $reviewedTrailerCommits = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($trailerBaseline in @($script:NextRolloverTrailerBaseline, $script:PublishedReleaseTrailerBaseline)) {
        if (Test-GitSuccess @("merge-base", "--is-ancestor", $trailerBaseline, $HeadSha)) {
            foreach ($reviewed in (Invoke-GitLines @("rev-list", $trailerBaseline))) {
                [void]$reviewedTrailerCommits.Add($reviewed)
            }
        }
    }
    foreach ($commit in $commits) {
        if ($reviewedTrailerCommits.Contains($commit)) { continue }
        $parentLine = Invoke-GitText @("rev-list", "--parents", "-n", "1", $commit)
        $parentFields = @($parentLine -split '\s+')
        if ($parentFields.Count -gt 2) {
            continue
        }

        $message = Invoke-GitText @("show", "-s", "--format=%B", $commit)
        $files = @(Get-CommitCandidates $commit)
        Reset-ValidationErrors
        Validate-ExactTrailer $message $files "Changelog" "CHANGELOG.md" "CHANGELOG.md"
        Validate-ChangelogJustification $message $files
        Validate-PrefixTrailer $message $files "Guide" "docs/guide/" "docs/guide/"
        Validate-ExactTrailer $message $files "Known-Discrepancies" "docs/status/known-discrepancies.md" "docs/status/known-discrepancies.md"
        Validate-ExactTrailer $message $files "S3K-Known-Discrepancies" "docs/S3K_KNOWN_DISCREPANCIES.md" "docs/S3K_KNOWN_DISCREPANCIES.md"
        Validate-AgentDocsTrailer $message $files
        Validate-ExactTrailer $message $files "Configuration-Docs" "CONFIGURATION.md" "CONFIGURATION.md"
        Validate-SkillsTrailer $message $files
        $parent = Invoke-GitText @("rev-parse", "$commit^")
        Validate-ModApiCoupling $parent $commit @($parent, $commit)
        if ($script:Errors.Count -gt 0) {
            Note "commit $commit violates the non-master branch documentation policy."
            Write-ValidationErrors
            Print-CommitTemplate
            exit 1
        }
    }
}

function Validate-PrePush([string]$RemoteName) {
    if ([string]::IsNullOrWhiteSpace($RemoteName) -or
            -not (Test-GitSuccess @("remote", "get-url", $RemoteName))) {
        $displayName = if ([string]::IsNullOrWhiteSpace($RemoteName)) { "<empty>" } else { $RemoteName }
        Fail "pre-push could not resolve remote name ``$displayName``; refusing to guess the published-history boundary."
    }

    while ($null -ne ($line = [Console]::In.ReadLine())) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $fields = @($line.Trim() -split '\s+')
        if ($fields.Count -lt 4) {
            Fail "pre-push received a malformed ref update: $line"
        }
        $localRef = $fields[0]
        $localOid = $fields[1]
        $remoteRef = $fields[2]
        $remoteOid = $fields[3]

        if ($localOid -ceq $script:AllZeroOid) {
            continue
        }
        if (-not (Test-GitSuccess @("cat-file", "-e", "$localOid^{commit}"))) {
            Fail "required local object $localOid for $localRef is not available as a commit."
        }

        if ($remoteOid -ceq $script:AllZeroOid) {
            try {
                $commits = @(Invoke-GitLines @(
                    "rev-list",
                    "--reverse",
                    $localOid,
                    "--not",
                    "--remotes=$RemoteName"
                ))
            } catch {
                Fail "could not enumerate unpublished commits for new ref $remoteRef."
            }
            Validate-ContentCommitList $commits $localOid
            continue
        }

        if (-not (Test-GitSuccess @("cat-file", "-e", "$remoteOid^{commit}"))) {
            Fail "required remote object $remoteOid for $remoteRef is not available as a commit."
        }
        Validate-ContentRange $remoteOid $localOid
    }
}

function Validate-CiNewRef([string]$AfterSha) {
    if (-not (Test-GitSuccess @("cat-file", "-e", "$script:ResourcePolicyCutover^{commit}"))) {
        Fail "resource-policy cutover $script:ResourcePolicyCutover is not available as a commit."
    }
    if (-not (Test-GitSuccess @("cat-file", "-e", "$AfterSha^{commit}"))) {
        Fail "required pushed tip $AfterSha is not available as a commit."
    }
    if (-not (Test-GitSuccess @(
            "merge-base",
            "--is-ancestor",
            $script:ResourcePolicyCutover,
            $AfterSha
        ))) {
        Fail "resource-policy cutover $script:ResourcePolicyCutover is not an ancestor of new-ref tip $AfterSha."
    }
    Validate-ContentRange $script:ResourcePolicyCutover $AfterSha
}

function Validate-CiPush(
        [string]$BeforeSha,
        [string]$AfterSha,
        [string]$RefName) {
    if ($BeforeSha -ceq $script:AllZeroOid) {
        Validate-CiNewRef $AfterSha
        return
    }

    if ($RefName -ceq "master") {
        $script:ReleaseRangeBase = $BeforeSha
        $BeforeSha = Get-EffectiveBaseForCiPr $BeforeSha $AfterSha "master" "develop"
    }
    if ($RefName -ceq "next" -or $RefName -ceq "develop" -or $RefName -ceq "master") {
        Validate-CiCommitRange $BeforeSha $AfterSha
        return
    }
    Validate-ContentRange $BeforeSha $AfterSha
}

function Get-EffectiveBaseForCiPr([string]$BaseSha, [string]$HeadSha, [string]$BaseRef, [string]$HeadRef) {
    if ($BaseRef -cne "master") {
        return $BaseSha
    }
    if ([string]::IsNullOrWhiteSpace($script:ReleaseTrailerCutoverBase)) {
        return $BaseSha
    }
    if (-not (Test-GitSuccess @("merge-base", "--is-ancestor", $script:ReleaseTrailerCutoverBase, $HeadSha))) {
        Fail "release trailer cutover baseline $script:ReleaseTrailerCutoverBase is not reachable from PR head $HeadSha."
    }
    if (-not (Test-GitSuccess @("merge-base", "--is-ancestor", $script:ReleaseTrailerCutoverBase, $BaseSha))) {
        return $script:ReleaseTrailerCutoverBase
    }
    return $BaseSha
}

switch -CaseSensitive ($Mode) {
    "prepare-commit-msg" {
        if ($RemainingArgs.Count -lt 1) {
            Fail "usage: validate-policy.ps1 prepare-commit-msg <message-file> [source]"
        }
        $source = if ($RemainingArgs.Count -ge 2) { $RemainingArgs[1] } else { "" }
        Prepare-CommitMessage $RemainingArgs[0] $source
    }
    "commit-msg" {
        if ($RemainingArgs.Count -lt 1) {
            Fail "usage: validate-policy.ps1 commit-msg <message-file>"
        }
        Validate-CommitMsgHook $RemainingArgs[0]
    }
    "pre-commit" {
        Validate-StagedContent
    }
    "pre-push" {
        $remoteName = if ($RemainingArgs.Count -ge 1) { $RemainingArgs[0] } else { "" }
        Validate-PrePush $remoteName
    }
    "pre-merge-commit" {
        Validate-MergeIntoDevelop
    }
    "ci-pr" {
        if ($RemainingArgs.Count -lt 4) {
            Fail "usage: validate-policy.ps1 ci-pr <base-sha> <head-sha> <base-ref> <head-ref>"
        }
        Validate-CiPr $RemainingArgs[0] $RemainingArgs[1] $RemainingArgs[2] $RemainingArgs[3]
    }
    "ci-push" {
        if ($RemainingArgs.Count -lt 3) {
            Fail "usage: validate-policy.ps1 ci-push <before-sha> <after-sha> <ref-name>"
        }
        Validate-CiPush $RemainingArgs[0] $RemainingArgs[1] $RemainingArgs[2]
    }
    default {
        Fail "usage: validate-policy.ps1 {prepare-commit-msg|pre-commit|commit-msg|pre-merge-commit|pre-push|ci-pr|ci-push} ..."
    }
}
