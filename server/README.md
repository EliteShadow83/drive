# EliteDrive Server Files

This folder contains server-side scripts for sharing a Windows folder to the Android app and browser.

## Browser access

After starting the server, open:

- `http://localhost:8080/` (from server machine)
- `http://<windows-ip>:8080/` (from other devices on LAN)

The root page shows available endpoints.

## Endpoints

- `GET /list?path=<folder>`: list file/folder names (plain text)
- `GET /download?path=<folder>&name=<file>`: download file bytes
- `POST /upload?path=<folder>&name=<file>`: upload file bytes
- `POST /mkdir?path=<folder>&name=<folder>`: create a folder
- `POST /delete?path=<folder>&name=<file>`: delete file

## Terminal feedback

The server prints a log line for every app/browser request:

`[timestamp] <client-ip:port> -> <method> <path> => <status>`

## Run it

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\windows\folder-list-server.ps1 -Root "C:\Shared" -Port 8080
```
