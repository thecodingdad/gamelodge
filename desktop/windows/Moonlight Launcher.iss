; Installer of the Windows launcher (Inno Setup 6). Built by tools/build-installer.sh on the
; Windows build host: ISCC /DAppVersion=<version> /DSourceDir=<staged app> /DOutputDir=<dir>
; Installs for the current user (no admin rights) or for all users (admin), chosen in the setup.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "MoonlightLauncher"
#endif
#ifndef OutputDir
  #define OutputDir "."
#endif

#define AppName "Moonlight Launcher"
; ISPP turns """" into "", which the [Icons]/[Run] parameters read as one quote.
#define JavaArgs "-Xmx1g -XX:+UseZGC -cp """"{app}\lib\*"""" io.github.thecodingdad.moonlightlauncher.desktop.MainKt"

[Setup]
AppId={{6E1B8F5C-3A6E-4E51-9C3B-2D7B6B0C9A41}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=thecodingdad
AppPublisherURL=https://github.com/thecodingdad/moonlight-launcher
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
; Current user without admin rights, or all users with them: the setup asks.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputDir}
OutputBaseFilename=MoonlightLauncher-Setup
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\icon.ico
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
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

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "{#JavaArgs}"; WorkingDir: "{app}"; IconFilename: "{app}\icon.ico"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "{#JavaArgs}"; WorkingDir: "{app}"; IconFilename: "{app}\icon.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "{#JavaArgs}"; WorkingDir: "{app}"; Description: "{cm:StartNow}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Moonlight-Qt's settings and caches written next to it (current-user installs).
Type: filesandordirs; Name: "{app}\moonlight"

[Code]
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
  begin
    // The launcher's own "start with Windows" entry.
    RegDeleteValue(HKEY_CURRENT_USER, 'Software\Microsoft\Windows\CurrentVersion\Run', 'MoonlightLauncher');
  end;
  if CurUninstallStep = usPostUninstall then
  begin
    if not UninstallSilent and (MsgBox(CustomMessage('KeepData'), mbConfirmation, MB_YESNO) = IDNO) then
      DelTree(ExpandConstant('{localappdata}\MoonlightLauncher'), True, True, True);
  end;
end;
