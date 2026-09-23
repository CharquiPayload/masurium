# The setup build.ps1 made, as a person would use it, but silent: installed for
# this user, its command and its window's Python tried, then uninstalled, and
# nothing left behind but what was not its to take. GitHub's Windows machine
# runs it on every commit (.github/workflows/tests.yml).
#
#   packaging\windows\check-setup.ps1
# Continue, not Stop: a program's stderr is part of what is checked here, and
# Windows PowerShell would make it an error that ends the script.
$ErrorActionPreference = "Continue"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$setup = Get-ChildItem (Join-Path $Root "dist") -Filter "MasuriumLauncherSetup-*.exe" | Select-Object -First 1
if (-not $setup) { throw "check-setup.ps1: no setup in dist\: run build.ps1 first" }
$dir = Join-Path ([IO.Path]::GetTempPath()) "masurium-setup-check"
if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
$failures = @()
function Check([string]$what, [bool]$ok, [string]$detail = "") {
    if ($ok) { Write-Host "  ok   $what" }
    else { Write-Host "  FAIL $what"; if ($detail) { Write-Host "         $detail" }; $script:failures += $what }
}

Write-Host "Setup: $($setup.Name), $([math]::Round($setup.Length / 1MB, 1)) MB"
$p = Start-Process -FilePath $setup.FullName -Wait -PassThru `
    -ArgumentList "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CURRENTUSER", "/DIR=`"$dir`"", "/LOG=`"$dir-install.log`""
Check "it installs, silently, for this user" ($p.ExitCode -eq 0 -and (Test-Path "$dir\app\launcher\masurium.py")) "exit $($p.ExitCode)"
Check "...with a Python of its own" (Test-Path "$dir\runtime\python.exe")
Check "...and says the setup made it" ((Get-Content "$dir\app\INSTALLER" -ErrorAction SilentlyContinue) -eq "setup.exe")

$help = & "$dir\bin\masurium.cmd" --help 2>&1 | Out-String
Check "the masurium command runs the launcher" ($LASTEXITCODE -eq 0 -and $help -match "setup") $help.Trim()
$env:QT_QPA_PLATFORM = "offscreen"
$qt = & "$dir\runtime\python.exe" -c "from PySide6.QtWidgets import QApplication, QLabel; app = QApplication([]); QLabel('Ma'); print('qt ok')" 2>&1 | Out-String
Check "...and its Python has Qt for the window" ($qt -match "qt ok") $qt.Trim()
$version = & "$dir\runtime\python.exe" -c "import sys; print(sys.version.split()[0])" 2>&1 | Out-String
Check "...its own, not the machine's: $($version.Trim())" ($version.Trim() -like "3.*")
$path = (Get-ItemProperty -Path "HKCU:\Environment" -Name Path -ErrorAction SilentlyContinue).Path
Check "its command's folder is on this user's PATH" ($path -and ($path.Split(";") -contains "$dir\bin")) $path
$menu = Join-Path ([Environment]::GetFolderPath("Programs")) "Masurium Launcher.lnk"
Check "a Start menu entry" (Test-Path $menu) $menu

$data = Join-Path $env:LOCALAPPDATA "Masurium\instances"
New-Item -ItemType Directory -Force -Path $data | Out-Null
$p = Start-Process -FilePath "$dir\unins000.exe" -Wait -PassThru -ArgumentList "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART"
# The uninstaller hands its work to a copy of itself and returns: wait for the folder.
for ($i = 0; $i -lt 60 -and (Test-Path "$dir\app"); $i++) { Start-Sleep -Milliseconds 500 }
Check "the uninstaller takes the program and its Python away" (-not (Test-Path "$dir\app") -and -not (Test-Path "$dir\runtime"))
$path = (Get-ItemProperty -Path "HKCU:\Environment" -Name Path -ErrorAction SilentlyContinue).Path
Check "...its folder leaves the PATH" (-not $path -or -not ($path.Split(";") -contains "$dir\bin")) $path
Check "...and the Start menu" (-not (Test-Path $menu))
Check "...and the instances stay" (Test-Path $data)

if ($failures) {
    Write-Host "`n$($failures.Count) check(s) failed"
    if (Test-Path "$dir-install.log") { Get-Content "$dir-install.log" | Select-Object -Last 25 }
    exit 1
}
Write-Host "`nthe setup works"
