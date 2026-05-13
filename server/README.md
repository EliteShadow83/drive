# WinDrive Server Files

This folder contains server-side scripts for sharing a Windows folder to the Android app.

## Endpoints

- `GET /list?path=<folder>`: list file/folder names
- `GET /download?path=<folder>&name=<file>`: download file bytes
- `POST /upload?path=<folder>&name=<file>`: upload file bytes
- `POST /delete?path=<folder>&name=<file>`: delete file

## Run it

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\windows\folder-list-server.ps1 -Root "C:\Shared" -Port 8080
```
