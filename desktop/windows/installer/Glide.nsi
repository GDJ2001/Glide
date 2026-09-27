; Glide per-user installer. Build with desktop/windows/package-windows.ps1.
; The installer intentionally owns only the files and registry entries it creates.

Unicode true
RequestExecutionLevel user
SetCompressor /SOLID lzma

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "FileFunc.nsh"

!ifndef APP_VERSION
  !define APP_VERSION "0.1.0"
!endif
!ifndef STAGE_DIR
  !error "STAGE_DIR must point to the published Glide payload."
!endif
!ifndef OUTPUT_FILE
  !define OUTPUT_FILE "Glide-Setup-${APP_VERSION}-win-x64.exe"
!endif

!define PRODUCT_NAME "Glide"
!define PRODUCT_PUBLISHER "Glide"
!define PRODUCT_REGKEY "Software\Glide"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\Glide"

Name "${PRODUCT_NAME} ${APP_VERSION}"
OutFile "${OUTPUT_FILE}"
InstallDir "$LOCALAPPDATA\Programs\Glide"
InstallDirRegKey HKCU "${PRODUCT_REGKEY}" "InstallPath"
BrandingText "Glide local sharing"
ShowInstDetails show
ShowUninstDetails show

!define MUI_ABORTWARNING
!define MUI_ICON "${STAGE_DIR}\glide.ico"
!define MUI_UNICON "${STAGE_DIR}\glide.ico"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "${__FILEDIR__}\LICENSE.txt"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN "$INSTDIR\Glide.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Launch Glide"
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Section "Glide application" SecApplication
  SetOutPath "$INSTDIR"
  File /r "${STAGE_DIR}\*.*"
  FileOpen $0 "$INSTDIR\glide-installer-manifest.txt" w
  FileWrite $0 "Glide installer-owned files only.$\r$\n"
  FileClose $0

  WriteRegStr HKCU "${PRODUCT_REGKEY}" "InstallPath" "$INSTDIR"
  WriteRegStr HKCU "${PRODUCT_REGKEY}" "Version" "${APP_VERSION}"

  CreateDirectory "$SMPROGRAMS\Glide"
  CreateShortcut "$SMPROGRAMS\Glide\Glide.lnk" "$INSTDIR\Glide.exe"
  CreateShortcut "$DESKTOP\Glide.lnk" "$INSTDIR\Glide.exe"
  CreateShortcut "$SENDTO\Send with Glide.lnk" "$INSTDIR\Glide.exe"

  WriteRegStr HKCU "Software\Classes\glide" "" "URL:Glide Protocol"
  WriteRegStr HKCU "Software\Classes\glide" "URL Protocol" ""
  WriteRegStr HKCU "Software\Classes\glide\shell\open\command" "" '"$INSTDIR\Glide.exe" "%1"'

  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayName" "Glide"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "Publisher" "${PRODUCT_PUBLISHER}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayIcon" "$INSTDIR\Glide.exe"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoModify" 1
  ; Re-running this versioned installer is the supported repair path. Do not expose an
  ; Installed Apps repair affordance that Windows cannot invoke for an NSIS package.
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoRepair" 1
  WriteUninstaller "$INSTDIR\Uninstall.exe"
SectionEnd

Section "un.Uninstall"
  Delete "$SMPROGRAMS\Glide\Glide.lnk"
  RMDir "$SMPROGRAMS\Glide"
  Delete "$DESKTOP\Glide.lnk"
  Delete "$SENDTO\Send with Glide.lnk"

  DeleteRegKey HKCU "Software\Classes\glide"
  DeleteRegKey HKCU "${UNINSTALL_KEY}"
  DeleteRegKey HKCU "${PRODUCT_REGKEY}"

  ; Delete only known installer-owned files. Do not recursively delete $INSTDIR: users may
  ; have placed unrelated files there after installation.
  Delete "$INSTDIR\Glide.exe"
  Delete "$INSTDIR\glide.cmd"
  Delete "$INSTDIR\glide.ico"
  Delete "$INSTDIR\QRCoder-LICENSE.txt"
  Delete "$INSTDIR\glide-installer-manifest.txt"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"

  ${GetParameters} $0
  ${GetOptions} $0 "/REMOVEAPPDATA" $1
  ${IfNot} ${Errors}
    RMDir /r "$LOCALAPPDATA\Glide"
  ${EndIf}
SectionEnd
