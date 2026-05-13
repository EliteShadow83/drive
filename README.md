# WinDrive (Android + Windows Folder Bridge)

This project is a starter Android app that behaves like a lightweight Google Drive browser for a folder hosted from a Windows PC on your local network.

## How it works

1. Run the Windows server script from `server/windows/folder-list-server.ps1`.
2. The script exposes:
   - `GET /list?path=shared`
   - Response format: one file/folder name per line.
3. The Android app connects to the server and displays the returned files.
4. The app remembers the last server URL and folder path it connected to.

## Run the server (Windows)

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\server\windows\folder-list-server.ps1 -Root "C:\Shared" -Port 8080
```

## Android setup

- Open in Android Studio.
- Build and run on a device on the same Wi-Fi as the Windows PC.
- Enter:
  - Server URL: `http://<windows-ip>:8080`
  - Folder path: `/` or `shared`
- Tap **Connect**.

## Notes

- This is intentionally simple and does not include auth, TLS, or upload/download UI yet.
- For production, add authentication, HTTPS, and JSON APIs.
