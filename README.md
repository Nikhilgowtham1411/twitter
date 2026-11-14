# X Saver

Android app (Kotlin + Jetpack Compose) that downloads photos, videos and GIFs from X (Twitter) posts.

- **Two share targets** in the X app's Share sheet:
  - **X Saver · Quick save**: no screen, you stay in X. The app looks the post up and downloads everything in the background.
  - **X Saver**: opens the app so you can pick the video quality first.
- **Or paste** a post link inside the app.
- **Downloads history** inside the app: every file with live progress, Open, Cancel and Retry. Several links can be queued at once.
- **Quality picker** for videos (when X offers several), original resolution for photos.
- **Notifications**: live progress with a Cancel button, and a "tap to open" notification when each file finishes.
- Files are saved with MediaStore to `Movies/XSaver` and `Pictures/XSaver`, so no storage permission is needed.

Requires Android 10 or newer.

## Install

1. Open the [latest release](../../releases/latest) on your phone and download `XSaver.apk`.
2. Open the file and allow "install unknown apps" for your browser if Android asks.
3. Updates install over the old version (every build is signed with the same key and has a higher version code).

## How it works

X's official API is paid, so the app reads media links from public endpoints, trying them in order:
`api.fxtwitter.com`, then `api.vxtwitter.com`, then X's own embed (syndication) endpoint.
If one stops working the next takes over. Private or deleted posts can't be fetched.

## Build

Every push to `main` runs `.github/workflows/build.yml`, which builds a signed release APK and
publishes it as a GitHub release. Locally: open the folder in Android Studio, or run `./gradlew assembleRelease`.

The signing key (`xsaver.keystore`) is committed on purpose so builds are updatable. It is meant for
sideloading a personal app. Use a private key of your own before publishing anywhere else.

For personal use only. Respect creators' rights and X's terms.
