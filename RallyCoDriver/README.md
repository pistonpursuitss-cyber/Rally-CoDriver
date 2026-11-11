# Rally Co-Driver

A native Android driving companion built with Kotlin, Jetpack Compose, Google Maps, and Android GPS/text-to-speech.

## What works

- Embedded Google map with live GPS position, follow mode, and locally saved report markers
- Live GPS speed, moving heading, drive timer, distance, and maximum speed
- Start/stop trip recording while the app is open
- Voice test with two selectable call styles
- Local road reports for police sightings, cameras, hazards, crashes, weather, and closures
- Report history survives app restarts and can be opened in Google Maps
- MPH/KM/H, map-follow, voice, and report-announcement preferences are saved on device

## Google Maps API key

Google Maps requires a Google Cloud project, Maps SDK for Android enabled, billing enabled, and an API key. See Google's [Maps SDK setup guide](https://developers.google.com/maps/documentation/android-sdk/get-api-key).

For GitHub Actions builds, add a repository Actions secret named `MAPS_API_KEY` containing the key. In GitHub, use **Settings → Secrets and variables → Actions → New repository secret**. The workflow passes the secret to Gradle at build time. Without it, the APK still builds, but the map area shows a setup message instead of loading Google Maps.

Restrict the key to **Maps SDK for Android**. Google recommends application restrictions as well; for a published/release app, restrict it to package `com.rallycodriver` and the SHA-1 certificate fingerprint for the signing certificate used by that build. Never put an unrestricted key in the public source tree. See Google's [API key security guidance](https://developers.google.com/maps/api-security-best-practices).

For a local build, set `MAPS_API_KEY` as an environment variable before running Gradle, or pass `-PMAPS_API_KEY=...`. Do not commit the key.

## Build an APK

Open this folder in Android Studio and choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**. Or run with Java 17, Android SDK Platform 35, and Gradle 8.11.1:

```powershell
$env:MAPS_API_KEY = "your-key"
gradle --no-daemon :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`.

The repository-level GitHub Actions workflow builds the same APK on pushes to `main` and on manual runs. Download it from the completed workflow run's **Artifacts** section.

## Current boundaries

- GPS updates stop when the app goes into the background; this version does not run a foreground tracking service.
- Trips and reports are stored on this phone only. Reports are not broadcast to other drivers.
- Co-driver speech is a voice test and report announcement. The app does not yet calculate routes or generate verified automatic corner calls; those need routing/road geometry data and real-world validation.
- Weather, camera, police, radar detector, and live traffic alerts are not connected to verified live data feeds. Reports are user-entered observations, not authoritative notices.
- Google Maps mobile SDK usage requires billing to be enabled; review Google Maps Platform pricing and usage limits in Cloud before distributing the app.

Configure the app before driving. Do not interact with the screen while the vehicle is moving.
