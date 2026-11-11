# Rally Co-Driver Android starter

A native Kotlin/Jetpack Compose Android starter for the rally-style GPS co-driver concept.

## Build an APK without Android Studio

This project includes a GitHub Actions workflow that builds the debug APK in the cloud.

1. Create a free repository on GitHub.
2. Extract this ZIP on your computer.
3. Open the new repository on GitHub, choose **Add file → Upload files**, and upload the contents of this folder (including the hidden `.github` folder). Commit the upload to the `main` branch.
4. Open the repository's **Actions** tab and select **Build Rally Co-Driver APK**.
5. If it did not start automatically, press **Run workflow**, then **Run workflow** again.
6. When the run finishes with a green check, open it and download **Rally-CoDriver-debug-APK** under **Artifacts**.
7. Extract the downloaded artifact ZIP. Move `app-debug.apk` to your phone and tap it to install. Android may ask you to allow your browser or file manager to install unknown apps.

No Android Studio, Android SDK, or local Gradle installation is needed. The first build can take several minutes.

## Included in this starter
- Native Android app shell with Drive, Co-driver, and Alerts tabs
- MapLibre map view using the public demo style
- GPS location permission and live GPS speed / heading readout
- Android text-to-speech voice test
- Rally-vs-normal voice mode setting
- On-device report entry UI for police, cameras, road hazards, crashes, and weather
- Button to hand off to Google Maps navigation

## Important limitations
This is a starter scaffold, not a finished navigation product:
- MapLibre currently uses its demo style URL. Replace it with a production tile/style provider and comply with that provider's terms before release.
- Google Maps handoff opens Google Maps; it does not draw an overlay over Google's app yet.
- The current app does not yet calculate turn-by-turn routes or generate verified pace notes. That needs a routing/road-geometry data source and on-road validation.
- Community reports are currently local to the running app and are not shared with other drivers. A backend is needed for live crowdsourced reports.
- Speed-camera and police notices need lawful, accurate data sources. No unverified police location is generated.
- A phone GPS receiver cannot detect radar or laser signals. Radar detection requires a compatible external detector and its supported Bluetooth protocol/API.
- Weather alerts need a weather data provider and location-based forecast integration.
- This app is not a certified safety system. Do not interact with it while driving; configure it before setting off and obey traffic laws.
