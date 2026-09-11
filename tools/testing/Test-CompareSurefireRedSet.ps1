[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$comparator = Join-Path $PSScriptRoot 'Compare-SurefireRedSet.ps1'
$powerShell = (Get-Process -Id $PID).Path
$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ("compare-surefire-red-set-" + [Guid]::NewGuid().ToString('N'))

function Write-SurefireReport {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [AllowEmptyCollection()] [object[]] $TestCases = @(),
        [switch] $Malformed
    )

    if ($Malformed) {
        Set-Content -LiteralPath $Path -Value '<testsuite><testcase>' -NoNewline
        return
    }

    $document = New-Object System.Xml.XmlDocument
    $suite = $document.CreateElement('testsuite')
    [void] $document.AppendChild($suite)
    foreach ($testCase in $TestCases) {
        $node = $document.CreateElement('testcase')
        [void] $node.SetAttribute('classname', $testCase.ClassName)
        [void] $node.SetAttribute('name', $testCase.Name)
        if ($testCase.Kind) {
            [void] $node.AppendChild($document.CreateElement($testCase.Kind))
        }
        [void] $suite.AppendChild($node)
    }
    $document.Save($Path)
}

function Invoke-Case {
    param(
        [Parameter(Mandatory)] [string] $Name,
        [Parameter(Mandatory)] [scriptblock] $Action,
        [Parameter(Mandatory)] [bool] $ExpectSuccess,
        [string] $ExpectedOutput
    )

    $actionResult = & $Action
    $succeeded = $actionResult.ExitCode -eq 0
    if ($succeeded -ne $ExpectSuccess) {
        throw "Case '$Name' expected success=$ExpectSuccess but exit code was $($actionResult.ExitCode): $($actionResult.Output)"
    }
    if ($ExpectedOutput -and $actionResult.Output -notmatch $ExpectedOutput) {
        throw "Case '$Name' did not print the expected output '$ExpectedOutput'."
    }
    Write-Output "PASS $Name"
}

function Invoke-Comparator {
    param([Parameter(Mandatory)] [string[]] $Arguments)

    $output = @(& $powerShell -NoProfile -File $comparator @Arguments 2>&1)
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = [string]::Join("`n", @($output | ForEach-Object ToString))
    }
}

function Assert-RepositoryInventory {
    $inventoryPath = Join-Path $repositoryRoot 'docs/testing/red-suite-inventory.tsv'
    $exclusionsPath = Join-Path $repositoryRoot 'docs/testing/unfinished-sk-zone-red-exclusions.txt'
    $inventory = @(Import-Csv -LiteralPath $inventoryPath -Delimiter "`t")
    $exclusions = @(Get-Content -LiteralPath $exclusionsPath | Where-Object { $_ -and -not $_.StartsWith('#') })
    $developRows = @($inventory | Where-Object { $_.branch -in @('develop', 'develop+next') })
    $nextScopeRows = @($inventory | Where-Object { $_.branch -eq 'next' -and $_.disposition -eq 'in-scope' })

    if (@($developRows | Where-Object wave -ne 'D').Count -gt 0) {
        throw 'Develop/shared inventory rows must use wave D.'
    }
    if ($inventory.Count -ne 179 -or $developRows.Count -ne 36 -or @($inventory | Where-Object branch -eq 'next').Count -ne 143 -or $nextScopeRows.Count -ne 47 -or $exclusions.Count -ne 96) {
        throw 'Repository inventory totals changed unexpectedly.'
    }
    if (@($nextScopeRows | Where-Object wave -eq 'N1').Count -ne 20 -or @($nextScopeRows | Where-Object wave -eq 'N2').Count -ne 10 -or @($nextScopeRows | Where-Object wave -eq 'N3').Count -ne 7 -or @($nextScopeRows | Where-Object wave -eq 'N4').Count -ne 10) {
        throw 'Next-only in-scope inventory wave totals are incorrect.'
    }

    $parentGuard = @($inventory | Where-Object test -eq 'com.openggf.game.rewind.TestParentDependentGraphCoverageGuard#parentDependentBucketMatchesBaselineAndCoveredEntriesNameGraphTests')
    $tailGuard = @($inventory | Where-Object test -eq 'com.openggf.game.rewind.TestRemainingRewindTailInventory#remainingRoundTripTailMatchesInventory')
    if ($parentGuard.Count -ne 1 -or $parentGuard[0].isolated_result -ne 'failure:3-cnz-parent-dependent') {
        throw 'Origin-integrated CNZ parent-dependent diagnostics are not catalogued on the existing guard identity.'
    }
    if ($tailGuard.Count -ne 1 -or $tailGuard[0].isolated_result -ne 'failure:5-cnz-no-probe+3-cnz-parent-dependent') {
        throw 'Origin-integrated CNZ tail diagnostics are not catalogued on the existing guard identity.'
    }
}

try {
    New-Item -ItemType Directory -Path $scratch | Out-Null

    $expected = Join-Path $scratch 'expected.txt'
    Set-Content -LiteralPath $expected -Value 'example.RedTest#fails'

    $reports = Join-Path $scratch 'reports'
    New-Item -ItemType Directory -Path $reports | Out-Null
    $report = Join-Path $reports 'TEST-example.xml'

    Write-SurefireReport -Path $report -TestCases @([pscustomobject]@{ ClassName = 'example.RedTest'; Name = 'fails'; Kind = 'failure' })
    Invoke-Case -Name 'exact-match' -ExpectSuccess $true -ExpectedOutput 'failures=1 errors=0 skipped_disabled=0' -Action { Invoke-Comparator @('-ReportsPath', $reports, '-ExpectedPath', $expected) }

    Write-SurefireReport -Path $report -TestCases @(
        [pscustomobject]@{ ClassName = 'example.RedTest'; Name = 'fails'; Kind = 'failure' },
        [pscustomobject]@{ ClassName = 'example.RedTest'; Name = 'fails'; Kind = 'error' }
    )
    Invoke-Case -Name 'duplicate-rejected' -ExpectSuccess $false -Action { Invoke-Comparator @('-ReportsPath', $reports) }

    Write-SurefireReport -Path $report -TestCases @()
    Invoke-Case -Name 'missing-rejected' -ExpectSuccess $false -ExpectedOutput 'missing expected' -Action { Invoke-Comparator @('-ReportsPath', $reports, '-ExpectedPath', $expected) }

    Write-SurefireReport -Path $report -TestCases @(
        [pscustomobject]@{ ClassName = 'example.RedTest'; Name = 'fails'; Kind = 'failure' },
        [pscustomobject]@{ ClassName = 'example.RedTest'; Name = 'extra'; Kind = 'error' }
    )
    Invoke-Case -Name 'extra-rejected' -ExpectSuccess $false -Action { Invoke-Comparator @('-ReportsPath', $reports, '-ExpectedPath', $expected) }

    Write-SurefireReport -Path $report -Malformed
    Invoke-Case -Name 'malformed-rejected' -ExpectSuccess $false -Action { Invoke-Comparator @('-ReportsPath', $reports) }

    Write-SurefireReport -Path $report -TestCases @([pscustomobject]@{ ClassName = 'example.SkippedTest'; Name = 'disabled'; Kind = 'skipped' })
    Invoke-Case -Name 'skipped-rejected' -ExpectSuccess $false -ExpectedOutput 'failures=0 errors=0 skipped_disabled=1' -Action { Invoke-Comparator @('-ReportsPath', $reports) }

    Set-Content -LiteralPath $expected -Value 'example.CaseTest#lower'
    Write-SurefireReport -Path $report -TestCases @([pscustomobject]@{ ClassName = 'example.CaseTest'; Name = 'LOWER'; Kind = 'failure' })
    Invoke-Case -Name 'case-distinct-rejected' -ExpectSuccess $false -Action { Invoke-Comparator @('-ReportsPath', $reports, '-ExpectedPath', $expected) }

    $actualOutput = Join-Path $scratch 'actual.txt'
    Write-SurefireReport -Path $report -TestCases @(
        [pscustomobject]@{ ClassName = 'example.OrderTest'; Name = 'a'; Kind = 'failure' },
        [pscustomobject]@{ ClassName = 'example.OrderTest'; Name = 'A'; Kind = 'error' }
    )
    Invoke-Case -Name 'case-distinct-ordinal-order' -ExpectSuccess $true -Action {
        $result = Invoke-Comparator @('-ReportsPath', $reports, '-WriteActualPath', $actualOutput)
        if ($result.ExitCode -ne 0) { return $result }
        if (([string]::Join("`n", @(Get-Content -LiteralPath $actualOutput))) -cne "example.OrderTest#A`nexample.OrderTest#a") { $result.ExitCode = 1 }
        return $result
    }

    Set-Content -LiteralPath $expected -Value @('# exported inventory comment', '', 'example.CommentedTest#red')
    Write-SurefireReport -Path $report -TestCases @([pscustomobject]@{ ClassName = 'example.CommentedTest'; Name = 'red'; Kind = 'failure' })
    Invoke-Case -Name 'commented-allowlist' -ExpectSuccess $true -Action { Invoke-Comparator @('-ReportsPath', $reports, '-ExpectedPath', $expected) }

    Set-Content -LiteralPath $expected -Value @('# empty red-set', '')
    Write-SurefireReport -Path $report -TestCases @()
    Invoke-Case -Name 'empty-actual-empty-expected' -ExpectSuccess $true -ExpectedOutput 'failures=0 errors=0 skipped_disabled=0' -Action { Invoke-Comparator @('-ReportsPath', $reports, '-ExpectedPath', $expected) }

    Set-Content -LiteralPath $expected -Value 'example.RedTest#fails'
    Invoke-Case -Name 'empty-actual-missing-expected' -ExpectSuccess $false -ExpectedOutput 'missing expected' -Action { Invoke-Comparator @('-ReportsPath', $reports, '-ExpectedPath', $expected) }

    Assert-RepositoryInventory
    Write-Output 'PASS repository-inventory'
}
finally {
    if (Test-Path -LiteralPath $scratch) {
        Remove-Item -LiteralPath $scratch -Recurse -Force
    }
}

exit 0
