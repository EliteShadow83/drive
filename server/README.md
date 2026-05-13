# WinDrive Server Files

This folder contains server-side scripts for sharing a Windows folder to the Android app.

## Windows script

- File: `windows/folder-list-server.ps1`
- Purpose: exposes `GET /list?path=...` over HTTP and returns one item name per line.

### Run it

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\windows\folder-list-server.ps1 -Root "C:\Shared" -Port 8080
```
