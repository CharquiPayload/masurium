# Masurium Launcher for Windows, for this user and without administrator
# rights: the program, its own Python environment with Qt for its window, a
# `masurium` command and an entry in the Start menu. The Windows twin of
# install.sh.
#
#   irm https://github.com/CharquiPayload/masurium/releases/latest/download/install.ps1 | iex
#       the latest release, downloaded and checked; run it again to update
#   .\install.ps1               from a release's folder, or the repository: that one
#   .\install.ps1 -NoGui        the command line alone
#   .\install.ps1 -Uninstall    take it away; your instances and settings stay
#
# Piped, it takes no arguments: set $env:MASURIUM_INSTALL to "no-gui" or
# "uninstall" first. Everything runs from Main, on the last line: a download
# cut short runs nothing.
param([switch]$NoGui, [switch]$Uninstall)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Name = "Masurium Launcher"
$Prefix = Join-Path $env:LOCALAPPDATA "Programs\masurium-launcher"
$Bin = Join-Path $Prefix "bin"
$Shortcut = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\$Name.lnk"
$PySide = "PySide6-Essentials>=6.7,<7"
$Release = if ($env:MASURIUM_RELEASE_URL) { $env:MASURIUM_RELEASE_URL.TrimEnd("/") } `
           else { "https://github.com/CharquiPayload/masurium/releases/latest/download" }

function Say([string]$text) { Write-Host $text }
function Die([string]$text) { throw "install.ps1: $text" }

# The `masurium` command's folder on this user's PATH, or off it. The PATH of
# a user lives in the registry; MASURIUM_USER_PATH=skip leaves it alone (the
# tests, which must not touch the machine they run on).
function Set-UserPath([bool]$present) {
    if ($env:MASURIUM_USER_PATH -eq "skip") { return }
    $now = [Environment]::GetEnvironmentVariable("Path", "User")
    $parts = @(if ($now) { $now.Split(";") | Where-Object { $_ -and ($_.TrimEnd("\") -ne $Bin) } })
    if ($present) { $parts += $Bin }
    [Environment]::SetEnvironmentVariable("Path", ($parts -join ";"), "User")
}

function Remove-Program {
    if (Test-Path $Prefix) { Remove-Item -Recurse -Force $Prefix }
    if (Test-Path $Shortcut) { Remove-Item -Force $Shortcut }
    Set-UserPath $false
    Say "$Name is uninstalled."
    Say "Your instances, servers and settings stay ($env:LOCALAPPDATA\Masurium and ~\.masurium):"
    Say "delete those folders yourself if you want them gone too."
}

# A Python 3.9 or newer: the py launcher first, then python on PATH. Windows'
# own python.exe that only opens the Store answers nothing, and is passed over.
function Find-Python {
    foreach ($line in @("py -3", "python")) {
        $words = $line.Split(" ")
        $exe = $words[0]
        $rest = @($words | Select-Object -Skip 1)
        if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) { continue }
        try {
            $ok = & $exe @rest -c "import sys; print(sys.version_info >= (3, 9))" 2>$null
        } catch { continue }
        if ($ok -eq "True") {
            return (& $exe @rest -c "import sys; print(sys.executable)").Trim()
        }
    }
    return $null
}

function Get-Url([string]$url, [string]$to) {
    $client = New-Object System.Net.WebClient
    $client.Headers.Add("User-Agent", "masurium-install")
    try { $client.DownloadFile($url, $to) }
    catch { Die "$url could not be downloaded: $($_.Exception.InnerException.Message)" }
}

# Piped, or run anywhere but Masurium's folder: the latest release. Its
# SHA256SUMS names the launcher's tarball and its checksum, the tarball is
# checked before anything in it runs, and its own install.ps1 installs it.
function Install-FromRelease([string[]]$passOn) {
    Say "==> getting the latest $Name from $Release"
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("masurium-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        Get-Url "$Release/SHA256SUMS" (Join-Path $tmp "SHA256SUMS")
        $sums = Get-Content -Raw (Join-Path $tmp "SHA256SUMS")
        $m = [regex]::Match($sums, "(?m)^([0-9a-f]{64}) [ *](masurium-launcher-[0-9][A-Za-z0-9.+~-]*\.tar\.gz)\r?$")
        if (-not $m.Success) { Die "the release's SHA256SUMS names no masurium-launcher-<version>.tar.gz" }
        $name = $m.Groups[2].Value
        $tarball = Join-Path $tmp $name
        Get-Url "$Release/$name" $tarball
        $got = (Get-FileHash -Algorithm SHA256 $tarball).Hash.ToLower()
        if ($got -ne $m.Groups[1].Value) { Die "$name did not arrive as the release has it (its checksum differs)" }
        & tar -xzf $tarball -C $tmp
        if ($LASTEXITCODE -ne 0) { Die "$name could not be unpacked" }
        $dir = Join-Path $tmp ($name -replace "\.tar\.gz$", "")
        $script = Join-Path $dir "install.ps1"
        if (-not (Test-Path $script)) { Die "$name has no install.ps1: that release has no Windows installer" }
        Say "==> ${name}: checked, and unpacked"
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script @passOn
        if ($LASTEXITCODE -ne 0) { Die "the release's install.ps1 failed" }
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}

function Install-Here([string]$here, [string]$python, [bool]$gui) {
    Say "==> installing $Name in $Prefix"
    New-Item -ItemType Directory -Force -Path $Prefix | Out-Null
    # The program, replaced whole: an update leaves nothing of the old one behind.
    $new = Join-Path $Prefix "app.new"
    if (Test-Path $new) { Remove-Item -Recurse -Force $new }
    New-Item -ItemType Directory -Force -Path (Join-Path $new "jars") | Out-Null
    foreach ($d in @("launcher", "mcp", "docs")) {
        if (Test-Path (Join-Path $here $d)) { Copy-Item -Recurse (Join-Path $here $d) $new }
    }
    foreach ($f in @("README.md", "LICENSE", "CHANGELOG.md")) {
        if (Test-Path (Join-Path $here $f)) { Copy-Item (Join-Path $here $f) $new }
    }
    # The Masurium jars it came with: a release's jars\ (the mod and its
    # add-ons), or the mod a clone of the repository built. `masurium setup`
    # puts them in shared\mods.
    $jars = if (Test-Path (Join-Path $here "jars")) { Get-ChildItem (Join-Path $here "jars") -Filter "*.jar" }
            else { Get-ChildItem (Join-Path $here "mod\build\libs") -Filter "masurium-*.jar" -ErrorAction SilentlyContinue |
                   Where-Object { $_.Name -notlike "*-sources.jar" } }
    foreach ($j in @($jars)) { Copy-Item $j.FullName (Join-Path $new "jars") }
    # Which installer made this copy: one of this script's can update itself
    # from the window (launcher/updates.py), for its user and without rights.
    Set-Content -Path (Join-Path $new "INSTALLER") -Value "install.ps1"
    Get-ChildItem $new -Recurse -Directory -Filter "__pycache__" | Remove-Item -Recurse -Force
    $app = Join-Path $Prefix "app"
    if (Test-Path $app) {
        try { Remove-Item -Recurse -Force $app }
        catch { Die "the old program could not be taken away ($($_.Exception.Message)): close $Name and run this again" }
    }
    Rename-Item $new "app"

    # Its own Python environment: Qt for the window lives there, not in the
    # system's Python. Made again when the Python under it is gone.
    $venv = Join-Path $Prefix "venv"
    $vpython = Join-Path $venv "Scripts\python.exe"
    $works = $false
    if (Test-Path $vpython) {
        # Windows PowerShell turns a program's stderr, redirected, into an
        # error that stops the script: asked inside a try, it is only an answer.
        try {
            & $vpython -c "import sys" 2>$null
            $works = ($LASTEXITCODE -eq 0)
        } catch { $works = $false }
    }
    if (-not $works) {
        if (Test-Path $venv) { Remove-Item -Recurse -Force $venv }
        & $python -m venv $venv
        if ($LASTEXITCODE -ne 0) { Die "Python could not make an environment in $venv" }
    }
    if ($gui) {
        Say "==> getting Qt for the window (PySide6): about 100 MB, only the first time"
        & $vpython -m pip install --quiet --disable-pip-version-check $PySide
        if ($LASTEXITCODE -ne 0) { Die "PySide6 could not be installed (no internet?). -NoGui installs the command line alone" }
    }

    # The command: bin\masurium.cmd, found through this user's PATH.
    New-Item -ItemType Directory -Force -Path $Bin | Out-Null
    Set-Content -Path (Join-Path $Bin "masurium.cmd") -Encoding Ascii -Value @(
        "@rem installed by Masurium Launcher's install.ps1",
        "@`"%~dp0..\venv\Scripts\python.exe`" `"%~dp0..\app\launcher\masurium.py`" %*")
    Set-UserPath $true

    # The Start menu entry: the window, through pythonw so no console opens.
    if ($gui) {
        New-Item -ItemType Directory -Force -Path (Split-Path $Shortcut) | Out-Null
        $link = (New-Object -ComObject WScript.Shell).CreateShortcut($Shortcut)
        $link.TargetPath = Join-Path $venv "Scripts\pythonw.exe"
        $link.Arguments = "`"$(Join-Path $app 'launcher\masurium.py')`" gui"
        $link.WorkingDirectory = $env:USERPROFILE
        $link.IconLocation = Join-Path $app "launcher\gui\appicon\masurium-launcher.ico"
        $link.Description = "Create, start and watch Minecraft bots with a brain"
        $link.Save()
    }

    Say ""
    Say "$Name is installed."
    if ($gui) {
        Say "  Open it from the Start menu, or run:  masurium gui"
        Say "  The first time, it walks you through the setup."
    } else {
        Say "  Run:  masurium setup   and then  masurium --help"
    }
    Say '  A terminal opened before this one does not know the masurium command yet: open a new one.'
}

function Main([string[]]$passOn) {
    $gui = -not $NoGui
    $remove = [bool]$Uninstall
    switch ($env:MASURIUM_INSTALL) {
        "no-gui" { $gui = $false }
        "uninstall" { $remove = $true }
    }
    if ($env:OS -ne "Windows_NT") { Die "this installer is for Windows: on Linux, install.sh" }
    if ($remove) { Remove-Program; return }
    $python = Find-Python
    if (-not $python) {
        Die ("Python 3.9 or newer is needed. Install it, for this user, with:  " +
             "winget install -e --id Python.Python.3.12   and run this again in a new terminal")
    }
    # Piped, there is no script file, and so no folder of its own.
    $here = $PSScriptRoot
    if ($here -and (Test-Path (Join-Path $here "launcher\masurium.py")) -and (Test-Path (Join-Path $here "mcp\bridge.py"))) {
        Install-Here $here $python $gui
    } else {
        $opts = @(if (-not $gui) { "-NoGui" })
        Install-FromRelease $opts
    }
}

Main
