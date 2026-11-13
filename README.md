# SonLive

SonLive is an Android production studio with a browser remote. Its local web UI and remote are served directly by the host Android phone.

## Run

1. Install SonLive on Android 10 or later. Keep the host phone awake with the studio visible during production. Tap Mini player to keep the Program canvas in Android Picture-in-Picture while switching to a game. For a clean picture, choose the game app in the system capture picker when Android offers app-only capture.
2. Add a scene and layer text, color, images, local videos, camera or Android screen capture. Grant Android permissions on the host phone. Select a source to edit its size, position and opacity. Drag a selected layer on the host Preview canvas.
3. Choose Cut or Fade and press Take to program. Enable the microphone before starting a recording to include microphone audio. Recordings are saved in Downloads/SonLive. Long recordings may hit a memory limit.
4. Connect both devices to the same Wi-Fi. Tap Remote link in SonLive and open the full link in the other device's browser. The remote shows Preview and Program frames plus scene, source, mixer and recording controls. Reopen the current link after restarting the app. Local media is imported on the host phone.

The remote carries picture frames and controls over the local network. Local video is rendered without its audio. This build has no RTMP/RTMPS streaming, system/game audio, capture-card support, or OBS plugin compatibility. It uses a development signing key for test installation.

## Build

With JDK 17 or newer, Android SDK Platform 35 and Build-Tools 35 installed, run:

```sh
export ANDROID_PLATFORM_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
export ANDROID_BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
bash build.sh
```

Set SONLIVE_KEYSTORE to your signing keystore for builds that must update an installed copy consistently. The default APK is build/SonLive.apk.
