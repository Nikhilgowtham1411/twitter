# X Saver

Android app (Kotlin + Jetpack Compose) that downloads photos, videos and GIFs from X (Twitter) posts.

- **Two share targets** in the X app's Share sheet:
  - **X Saver · Quick save**: no screen, you stay in X. The app looks the post up and downloads everything in the background.
  - **X Saver**: opens the app so you can pick the video quality first.
- **Or paste** a post link inside the app.
- **Saved links**: every link you paste/share stays in the app with all its qualities and the data each one uses
  (for example `1280p · 5.0 MB`). Pick a quality, download, and come back later to download another quality.
- **Notification panel** (optional, switch it on inside the app): copy a link, tap *Paste & save* (or type it into the
  panel) and it shows up in Saved links without opening the app.
- **Fast and reliable downloads**: 3 files at once, each split over 4 to 8 parallel connections. If the network drops or
  switches, a download waits and resumes from the exact byte instead of failing. A wake lock keeps it going with the screen off.
- **Downloads history** inside the app: every file with live progress, Open, Cancel and Retry.
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

## Privacy

No accounts, no analytics, no ads, no server of its own. Saved links and the downloads history live only in the app's
private storage on your phone. The only network traffic is: the public lookup services listed above (they see the post
link and your IP address), X's CDN (thumbnails and the media files), and nothing else.

For personal use only. Respect creators' rights and X's terms.
