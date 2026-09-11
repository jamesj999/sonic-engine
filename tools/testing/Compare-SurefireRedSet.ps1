[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string] $ReportsPath,

    [string] $ExpectedPath,

    [string] $WriteActualPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Fail-Contract {
    param([Parameter(Mandatory)] [string] $Message)

    Write-Error $Message
    exit 1
}

function Get-OrdinalDuplicates {
    param([AllowEmptyCollection()] [string[]] $Identities = @())

    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $duplicates = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($identity in $Identities) {
        if (-not $seen.Add($identity)) {
            [void] $duplicates.Add($identity)
        }
    }
    return @($duplicates)
}

function Sort-Ordinal {
    param([AllowEmptyCollection()] [string[]] $Identities = @())

    $sorted = [string[]] @($Identities)
    [System.Array]::Sort($sorted, [System.StringComparer]::Ordinal)
    return $sorted
}

function Get-ExpectedIdentities {
    param([Parameter(Mandatory)] [string] $Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Fail-Contract "Expected red-set file does not exist: $Path"
    }

    $identities = @(
        Get-Content -LiteralPath $Path | ForEach-Object {
            $identity = $_.Trim()
            if ($identity.Length -gt 0 -and -not $identity.StartsWith('#', [System.StringComparison]::Ordinal)) { $identity }
        }
    )
    $duplicates = @(Get-OrdinalDuplicates -Identities $identities)
    if ($duplicates.Count -gt 0) {
        Fail-Contract ("Expected red-set contains duplicate identities: " + ((Sort-Ordinal -Identities $duplicates) -join ', '))
    }
    return @(Sort-Ordinal -Identities $identities)
}

function Get-SurefireTestCases {
    param([Parameter(Mandatory)] [string] $Path)

    $settings = [System.Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = $null
    try {
        $reader = [System.Xml.XmlReader]::Create($Path, $settings)
        $document = [System.Xml.XmlDocument]::new()
        $document.XmlResolver = $null
        $document.Load($reader)
    }
    catch {
        Fail-Contract "Malformed Surefire XML '$Path': $($_.Exception.Message)"
    }
    finally {
        if ($null -ne $reader) { $reader.Dispose() }
    }

    return @($document.SelectNodes('//testcase'))
}

if (-not (Test-Path -LiteralPath $ReportsPath -PathType Container)) {
    Fail-Contract "Reports path does not exist or is not a directory: $ReportsPath"
}

$reports = @(Get-ChildItem -LiteralPath $ReportsPath -Filter 'TEST-*.xml' -File -Recurse)
if ($reports.Count -eq 0) {
    Fail-Contract "No TEST-*.xml files found under: $ReportsPath"
}

$actual = [System.Collections.Generic.List[string]]::new()
$failureCount = 0
$errorCount = 0
$skippedDisabledCount = 0
$skippedDisabledIdentities = [System.Collections.Generic.List[string]]::new()
foreach ($report in $reports) {
    foreach ($testCase in (Get-SurefireTestCases -Path $report.FullName)) {
        $className = $testCase.GetAttribute('classname')
        $testName = $testCase.GetAttribute('name')
        if ([string]::IsNullOrWhiteSpace($className) -or [string]::IsNullOrWhiteSpace($testName)) {
            Fail-Contract "Surefire testcase in '$($report.FullName)' is missing classname or name."
        }

        $children = @($testCase.ChildNodes | Where-Object { $_.NodeType -eq [System.Xml.XmlNodeType]::Element })
        $disabled = @($children | Where-Object { $_.LocalName -in @('skipped', 'disabled') })
        $disabledAttribute = $testCase.GetAttribute('disabled')
        $statusAttribute = $testCase.GetAttribute('status')
        if ($disabled.Count -gt 0 -or $disabledAttribute -eq 'true' -or $statusAttribute -in @('skipped', 'disabled')) {
            $skippedDisabledCount++
            [void] $skippedDisabledIdentities.Add("$className#$testName in '$($report.FullName)'")
            continue
        }

        $outcomeNodes = @($children | Where-Object { $_.LocalName -in @('failure', 'error') })
        foreach ($outcome in $outcomeNodes) {
            [void] $actual.Add("$className#$testName")
            if ($outcome.LocalName -eq 'failure') { $failureCount++ } else { $errorCount++ }
        }
    }
}

Write-Output "Surefire red-set summary: failures=$failureCount errors=$errorCount skipped_disabled=$skippedDisabledCount"
if ($skippedDisabledCount -gt 0) {
    Fail-Contract ("Skipped or disabled testcase found: " + ((Sort-Ordinal -Identities $skippedDisabledIdentities) -join ', ') + '.')
}

$actual = @(Sort-Ordinal -Identities $actual)
$actualDuplicates = @(Get-OrdinalDuplicates -Identities $actual)
if ($actualDuplicates.Count -gt 0) {
    Fail-Contract ("Actual red set contains duplicate identities: " + ((Sort-Ordinal -Identities $actualDuplicates) -join ', '))
}

if ($WriteActualPath) {
    $parent = Split-Path -Parent $WriteActualPath
    if ($parent -and -not (Test-Path -LiteralPath $parent -PathType Container)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Set-Content -LiteralPath $WriteActualPath -Value $actual
}

if ($ExpectedPath) {
    $expected = @(Get-ExpectedIdentities -Path $ExpectedPath)
    $matches = [System.Linq.Enumerable]::SequenceEqual(
        [string[]] $expected,
        [string[]] $actual,
        [System.StringComparer]::Ordinal
    )
    if (-not $matches) {
        $actualSet = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
        $expectedSet = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
        foreach ($identity in $actual) { [void] $actualSet.Add($identity) }
        foreach ($identity in $expected) { [void] $expectedSet.Add($identity) }
        $missing = @($expected | Where-Object { -not $actualSet.Contains($_) })
        $extra = @($actual | Where-Object { -not $expectedSet.Contains($_) })
        $details = [System.Collections.Generic.List[string]]::new()
        if ($missing.Count -gt 0) { [void] $details.Add("missing expected: $($missing -join ', ')") }
        if ($extra.Count -gt 0) { [void] $details.Add("unexpected actual: $($extra -join ', ')") }
        Fail-Contract ("Surefire red-set mismatch: " + ($details -join '; '))
    }
}

exit 0
