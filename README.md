# WinDrive (Android + Windows Folder Bridge)

This project is a starter Android app that behaves like a lightweight Google Drive browser for a folder hosted from a Windows PC on your local network.


## Gradle compatibility note

This project is pinned to **Gradle 8.10.2** (see `gradle/wrapper/gradle-wrapper.properties`) with AGP `8.5.2`.
Using Gradle 10 currently triggers Android plugin deprecation warnings and is not supported in this starter.

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

- **Android Studio**: Install the latest stable Android Studio from the official website.
- **JDK**: Use Android Studio's bundled JDK (recommended) or JDK 17.
- **Android SDK components**:
  - Android SDK Platform 34
  - Android SDK Build-Tools (latest available)
  - Android SDK Platform-Tools
  - Android Emulator (optional, for emulator testing)

### 2) Open the project

1. Launch Android Studio.
2. Click **Open**.
3. Select this repository folder (the folder containing `settings.gradle.kts`).
4. Wait for **Gradle Sync** to complete.

### 3) Configure SDK/JDK if prompted

- If Android Studio asks for an SDK path, choose the default SDK location.
- If it asks for Gradle JDK, choose **Embedded JDK (17)** or another JDK 17 installation.
- Re-run **Sync Project with Gradle Files** if needed.

### 4) Let Gradle download dependencies

- On first open, Android Studio will download Gradle wrapper files and dependencies.
- Wait until the bottom status bar shows sync/build completion with no errors.

### 5) Build the app

**From the UI**

1. In the top menu, select **Build > Make Project**.
2. Wait for the build to finish.
3. Confirm `BUILD SUCCESSFUL` in the Build output window.

**From Terminal inside Android Studio (optional)**

```bash
./gradlew :app:assembleDebug
```

The debug APK will be generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 6) Run on a physical Android device (recommended)

1. On your phone, enable **Developer options** and **USB debugging**.
2. Connect the phone with USB.
3. Approve the computer authorization prompt on the phone.
4. In Android Studio, select your device from the run target dropdown.
5. Click **Run** (▶) for the `app` configuration.

### 7) Run on an emulator (optional)

1. Open **Tools > Device Manager**.
2. Create a virtual device (for example Pixel + Android 13/14 image).
3. Start the emulator.
4. Click **Run** (▶) in Android Studio.

### 8) Connect app to Windows server

1. Make sure phone/emulator and Windows PC are on the same network (for physical devices).
2. Find Windows machine IP (example `192.168.1.20`).
3. In the app enter:
   - Server URL: `http://<windows-ip>:8080`
   - Folder path: `/` or `shared`
4. Tap **Connect** and use Upload/Download/Delete as needed.

### 9) Common build issues

- **Gradle sync fails**: click **Sync Project with Gradle Files** and verify internet access.
- **SDK not found**: install API 34 in **SDK Manager**.
- **Device not showing**: re-enable USB debugging and use a data-capable USB cable.
- **Network request fails**: verify Windows firewall allows inbound traffic on port `8080`.

## Quick verification checklist

- App opens without crash.
- Connect shows file listing.
- Upload creates file on Windows folder.
- Download saves file into app storage.
- Delete removes selected file on Windows side.
