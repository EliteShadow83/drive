# WinDrive (Android + Windows Folder Bridge)

This project is a starter Android app that behaves like a lightweight Google Drive browser for a folder hosted from a Windows PC on your local network.

## Gradle 10 compatibility

- Project build scripts use modern Kotlin DSL and avoid deprecated repository/project configuration patterns.
- The project targets **Android Gradle Plugin 8.8.2** and **Kotlin 2.0.21**, which are prepared for newer Gradle runtimes.
- To use Gradle 10 in Android Studio, set the Gradle version in **Gradle Settings** to `10.0` and re-sync.

## Features

- Browse a folder from Windows (`/list`)
- Upload a file to Windows (`/upload`)
- Download a file from Windows (`/download`)
- Delete a file on Windows (`/delete`)
- Remembers last server URL and path

## How it works

1. Run the Windows server script from `server/windows/folder-list-server.ps1`.
2. The Android app connects to the server and performs list/upload/download/delete operations.

## Run the server (Windows)

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\server\windows\folder-list-server.ps1 -Root "C:\Shared" -Port 8080
```

## Detailed Android Studio build instructions

### 1) Install prerequisites

- **Android Studio** latest stable
- **JDK 17** (embedded JDK is fine)
- SDK 34 + platform/build tools

### 2) Open and sync

1. Open this folder in Android Studio.
2. Wait for Gradle sync.
3. If needed, set Gradle JDK to 17.

### 3) Build

- UI: **Build > Make Project**
- Terminal: `./gradlew :app:assembleDebug`

Debug APK output:

`app/build/outputs/apk/debug/app-debug.apk`
