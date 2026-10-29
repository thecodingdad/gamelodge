; Installer of the Windows launcher (Inno Setup 6). Built by tools/build-installer.sh on the
; Windows build host: ISCC /DAppVersion=<version> /DSourceDir=<staged app> /DOutputDir=<dir>
; Installs for the current user (no admin rights) or for all users (admin), chosen in the setup.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "GameLodge"
#endif
#ifndef OutputDir
  #define OutputDir "."
#endif

#define AppName "GameLodge"

[Setup]
AppId={{59B5CDFE-9278-4BE8-8CCC-2E6FEECC86F9}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=thecodingdad
AppPublisherURL=https://github.com/thecodingdad/gamelodge
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
; Current user without admin rights, or all users with them: the setup asks.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputDir}
OutputBaseFilename=GameLodge-Setup
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\GameLodge.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
; GameLodge icon in the header and the banner on the finish page, per display scaling.
WizardSmallImageFile=wizard\small-100.bmp,wizard\small-125.bmp,wizard\small-150.bmp,wizard\small-175.bmp,wizard\small-200.bmp,wizard\small-250.bmp
WizardImageFile=wizard\side-100.bmp,wizard\side-125.bmp,wizard\side-150.bmp,wizard\side-175.bmp,wizard\side-200.bmp,wizard\side-250.bmp
; A running launcher holds its files: offered to be closed (and started again afterwards).
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "de"; MessagesFile: "compiler:Languages\German.isl"

[CustomMessages]
en.DesktopIcon=Create a desktop shortcut
de.DesktopIcon=Verknüpfung auf dem Desktop erstellen
en.StartNow=Start {#AppName}
de.StartNow={#AppName} starten
en.KeepData=Keep your library, settings and pairings?%n%nYes: they are used again after a new installation.%nNo: everything the launcher stored is deleted.
en.InUse=GameLodge or a game started through it is still running (its files are in use).%n%nClose GameLodge, quit the game and run setup again. Nothing was changed.
de.InUse=GameLodge oder ein darüber gestartetes Spiel läuft noch (seine Dateien sind in Benutzung).%n%nBeende GameLodge und das Spiel und starte das Setup erneut. Es wurde nichts verändert.
de.KeepData=Bibliothek, Einstellungen und Kopplungen behalten?%n%nJa: Sie werden nach einer Neuinstallation wieder verwendet.%nNein: Alles, was der Launcher gespeichert hat, wird gelöscht.

[Tasks]
Name: "desktopicon"; Description: "{cm:DesktopIcon}"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "icon.ico"; DestDir: "{app}"; Flags: ignoreversion

[InstallDelete]
; Jars of an older version (names carry versions): replaced as a whole.
Type: filesandordirs; Name: "{app}\lib"
Type: filesandordirs; Name: "{app}\runtime"
; The streaming client too (files dropped between versions would stay otherwise).
Type: filesandordirs; Name: "{app}\moonlight"

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\GameLodge.exe"; WorkingDir: "{app}"; IconFilename: "{app}\icon.ico"; AppUserModelID: "thecodingdad.GameLodge"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\GameLodge.exe"; WorkingDir: "{app}"; IconFilename: "{app}\icon.ico"; AppUserModelID: "thecodingdad.GameLodge"; Tasks: desktopicon

[Run]
Filename: "{app}\GameLodge.exe"; WorkingDir: "{app}"; Description: "{cm:StartNow}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Moonlight-Qt's settings and caches written next to it (current-user installs).
Type: filesandordirs; Name: "{app}\moonlight"

[Code]
// A folder whose files are open can't be renamed: tried (and undone) before anything is deleted,
// so a running launcher or game runner never leaves a half removed installation behind.
function FolderInUse(const Dir: String): Boolean;
begin
  Result := False;
  if not DirExists(Dir) then Exit;
  if RenameFile(Dir, Dir + '.setup-check') then
    RenameFile(Dir + '.setup-check', Dir)
  else
    Result := True;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  App: String;
begin
  Result := '';
  App := ExpandConstant('{app}');
  if FolderInUse(App + '\runtime') or FolderInUse(App + '\lib') or FolderInUse(App + '\moonlight') then
    Result := CustomMessage('InUse');
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
  begin
    // The launcher's own "start with Windows" entry.
    RegDeleteValue(HKEY_CURRENT_USER, 'Software\Microsoft\Windows\CurrentVersion\Run', 'GameLodge');
  end;
  if CurUninstallStep = usPostUninstall then
  begin
    if not UninstallSilent and (MsgBox(CustomMessage('KeepData'), mbConfirmation, MB_YESNO) = IDNO) then
      DelTree(ExpandConstant('{localappdata}\GameLodge'), True, True, True);
  end;
end;
