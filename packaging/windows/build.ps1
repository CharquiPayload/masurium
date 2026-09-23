# Masurium Launcher's Windows setup: dist\MasuriumLauncherSetup-<version>.exe,
# made with Inno Setup from packaging\windows\masurium.iss. It carries the
# program, the Masurium jars it is given, and a Python of its own with Qt (the
# embeddable Python from python.org, checked against its checksum), so that
# installing needs nothing else on the machine.
#
#   packaging\windows\build.ps1                 the jars the mod's build left
#   packaging\windows\build.ps1 -Jars <folder>  the jars of a release
#
# Run it on Windows, with a Python 3 (for pip) and Inno Setup 6 installed.
param([string]$Jars = "")

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Version = [regex]::Match((Get-Content -Raw "$Root\launcher\__init__.py"), '(?m)^__version__ = "(.+)"').Groups[1].Value
if (-not $Version) { throw "build.ps1: no __version__ in launcher\__init__.py" }
# The Python the setup brings, and Qt for it: the versions tested.
$PythonVersion = "3.13.15"
$PythonSha256 = "d1f04d990aee1253d8569e8e5104e30fa9f5fa830899f14843448872d936a2cf"
$PySide = "6.11.2"

$Work = Join-Path $Root "build\windows"
$Stage = Join-Path $Work "stage"
$Out = Join-Path $Root "dist"
if (Test-Path $Stage) { Remove-Item -Recurse -Force $Stage }
New-Item -ItemType Directory -Force -Path $Stage, $Out, (Join-Path $Work "cache") | Out-Null

Write-Host "==> a Python of its own: the embeddable $PythonVersion"
$zip = Join-Path $Work "cache\python-$PythonVersion-embed-amd64.zip"
if (-not (Test-Path $zip) -or (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLower() -ne $PythonSha256) {
    (New-Object System.Net.WebClient).DownloadFile(
        "https://www.python.org/ftp/python/$PythonVersion/python-$PythonVersion-embed-amd64.zip", $zip)
}
$got = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLower()
if ($got -ne $PythonSha256) { throw "build.ps1: the embeddable Python's checksum is $got, not $PythonSha256" }
$runtime = Join-Path $Stage "runtime"
Expand-Archive -Path $zip -DestinationPath $runtime
# Its path file says where it looks for modules: the standard library, its
# own folder, and site-packages, where Qt goes.
$pth = Get-ChildItem $runtime -Filter "python*._pth" | Select-Object -First 1
$zipName = (Get-ChildItem $runtime -Filter "python*.zip" | Select-Object -First 1).Name
Set-Content -Path $pth.FullName -Encoding Ascii -Value @($zipName, ".", "Lib\site-packages", "import site")

Write-Host "==> Qt for Python $PySide, into that Python"
$site = Join-Path $runtime "Lib\site-packages"
$short = ($PythonVersion -split "\.")[0..1] -join ""
& python -m pip install --quiet --disable-pip-version-check --no-deps --only-binary=:all: `
    --platform win_amd64 --implementation cp --python-version $short `
    --target $site "PySide6-Essentials==$PySide" "shiboken6==$PySide"
if ($LASTEXITCODE -ne 0) { throw "build.ps1: PySide6 could not be downloaded" }

Write-Host "==> the program"
$app = Join-Path $Stage "app"
New-Item -ItemType Directory -Force -Path (Join-Path $app "jars") | Out-Null
foreach ($d in @("launcher", "mcp", "docs")) { Copy-Item -Recurse (Join-Path $Root $d) $app }
foreach ($f in @("README.md", "LICENSE", "CHANGELOG.md")) { Copy-Item (Join-Path $Root $f) $app }
Get-ChildItem $app -Recurse -Directory -Filter "__pycache__" | Remove-Item -Recurse -Force
$jarFiles = if ($Jars) { Get-ChildItem $Jars -Filter "masurium*.jar" }
            else { Get-ChildItem (Join-Path $Root "mod\build\libs") -Filter "masurium-*.jar" -ErrorAction SilentlyContinue |
                   Where-Object { $_.Name -notlike "*-sources.jar" } }
foreach ($j in @($jarFiles)) { Copy-Item $j.FullName (Join-Path $app "jars") }
Write-Host "    jars: $((@($jarFiles) | ForEach-Object Name) -join ', ')"
# Which installer made this copy: the setup, which the next setup updates.
Set-Content -Path (Join-Path $app "INSTALLER") -Encoding Ascii -Value "setup.exe"

Write-Host "==> the command"
$bin = Join-Path $Stage "bin"
New-Item -ItemType Directory -Force -Path $bin | Out-Null
Set-Content -Path (Join-Path $bin "masurium.cmd") -Encoding Ascii -Value @(
    "@rem installed by Masurium Launcher's setup",
    "@`"%~dp0..\runtime\python.exe`" `"%~dp0..\app\launcher\masurium.py`" %*")

Write-Host "==> the setup, with Inno Setup"
$iscc = @("${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe", "$env:ProgramFiles\Inno Setup 6\ISCC.exe",
          "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) { throw "build.ps1: Inno Setup 6 is needed (ISCC.exe): https://jrsoftware.org/isinfo.php" }
& $iscc /Q "/DVersion=$Version" "/DStage=$Stage" "/DOut=$Out" (Join-Path $PSScriptRoot "masurium.iss")
if ($LASTEXITCODE -ne 0) { throw "build.ps1: Inno Setup failed" }
Get-Item (Join-Path $Out "MasuriumLauncherSetup-$Version.exe") | ForEach-Object { Write-Host "    $($_.FullName)  $([math]::Round($_.Length / 1MB, 1)) MB" }
