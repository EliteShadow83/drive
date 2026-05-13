# WinDrive (Android + Windows Folder Bridge)

This project is a starter Android app that behaves like a lightweight Google Drive browser for a folder hosted from a Windows PC on your local network.

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

## Android setup

- Open in Android Studio.
- Build and run on a device on the same Wi-Fi as the Windows PC.
- Enter server URL + folder path.
- Use file name + content with action buttons.
