; Masurium Launcher's Windows setup, made by packaging\windows\build.ps1 with
; Inno Setup: a wizard, Next, Next, Install, for this user alone and without
; administrator rights. It brings everything the launcher runs on (a Python of
; its own with Qt), so the machine needs nothing else; the bots' Java 21 and
; Claude Code the launcher itself asks for the first time it opens.
;
; build.ps1 hands it: Version, Stage (the folder to install) and Out.

#ifndef Version
  #error build.ps1 gives the version: /DVersion=...
#endif

[Setup]
AppId={{E000393A-D159-4995-B450-0AA9DD580A15}
AppName=Masurium Launcher
AppVersion={#Version}
AppVerName=Masurium Launcher {#Version}
AppPublisher=Masurium
AppPublisherURL=https://github.com/CharquiPayload/masurium
AppSupportURL=https://github.com/CharquiPayload/masurium/issues
AppUpdatesURL=https://github.com/CharquiPayload/masurium/releases/latest
DefaultDirName={localappdata}\Programs\Masurium Launcher
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
LicenseFile={#Stage}\app\LICENSE
SetupIconFile={#Stage}\app\launcher\gui\appicon\masurium-launcher.ico
UninstallDisplayIcon={app}\app\launcher\gui\appicon\masurium-launcher.ico
UninstallDisplayName=Masurium Launcher
OutputDir={#Out}
OutputBaseFilename=MasuriumLauncherSetup-{#Version}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ChangesEnvironment=yes
; A running launcher holds its Python: closed for the update, and opened again.
CloseApplications=yes
RestartApplications=no

[Tasks]
Name: desktopicon; Description: "Create a desktop shortcut"; Flags: unchecked

; The program and its Python are replaced whole: an update leaves nothing of
; the old ones behind. Instances, servers and settings live elsewhere
; (%LOCALAPPDATA%\Masurium and ~\.masurium) and are never touched.
[InstallDelete]
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#Stage}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
; The window through pythonw, so that no console opens with it.
Name: "{autoprograms}\Masurium Launcher"; Filename: "{app}\runtime\pythonw.exe"; \
  Parameters: """{app}\app\launcher\masurium.py"" gui"; WorkingDir: "{%USERPROFILE}"; \
  IconFilename: "{app}\app\launcher\gui\appicon\masurium-launcher.ico"; \
  Comment: "Create, start and watch Minecraft bots with a brain"
Name: "{autodesktop}\Masurium Launcher"; Filename: "{app}\runtime\pythonw.exe"; \
  Parameters: """{app}\app\launcher\masurium.py"" gui"; WorkingDir: "{%USERPROFILE}"; \
  IconFilename: "{app}\app\launcher\gui\appicon\masurium-launcher.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\runtime\pythonw.exe"; Parameters: """{app}\app\launcher\masurium.py"" gui"; \
  WorkingDir: "{%USERPROFILE}"; Description: "Open Masurium Launcher"; Flags: postinstall nowait skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Code]
{ The `masurium` command: {app}\bin on this user's PATH, and off it again when
  the program goes. The PATH of a user lives in the registry. }
const
  EnvKey = 'Environment';

function BinDir(): String;
begin
  Result := ExpandConstant('{app}\bin');
end;

function PathWithout(Path, Dir: String): String;
var
  Rest, Part: String;
  P: Integer;
begin
  Result := '';
  Rest := Path;
  while Rest <> '' do
  begin
    P := Pos(';', Rest);
    if P = 0 then begin Part := Rest; Rest := ''; end
    else begin Part := Copy(Rest, 1, P - 1); Delete(Rest, 1, P); end;
    if (Part <> '') and (CompareText(RemoveBackslashUnlessRoot(Part), Dir) <> 0) then
    begin
      if Result <> '' then Result := Result + ';';
      Result := Result + Part;
    end;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Path: String;
begin
  if CurStep = ssPostInstall then
  begin
    if not RegQueryStringValue(HKCU, EnvKey, 'Path', Path) then Path := '';
    Path := PathWithout(Path, BinDir());
    if Path <> '' then Path := Path + ';';
    RegWriteExpandStringValue(HKCU, EnvKey, 'Path', Path + BinDir());
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Path: String;
begin
  if CurUninstallStep = usPostUninstall then
    if RegQueryStringValue(HKCU, EnvKey, 'Path', Path) then
      RegWriteExpandStringValue(HKCU, EnvKey, 'Path', PathWithout(Path, BinDir()));
end;
