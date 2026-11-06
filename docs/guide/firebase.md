# Firebase Test Lab (optional)

Robolectric tests run on the JVM and miss real layout, fonts, insets and system dialogs.
Firebase Test Lab runs the real app on real Android images, so you can build, look at the
result, fix and run again. This is **optional**: the unit-test gate works without it, and
setting it up needs your own Google Cloud project and billing.

Start with **Set up your own** below, then **Quick start**. The rest is reference.

## Set up your own

1. Create a Google Cloud project and enable Firebase Test Lab (it bills past the free
   quota; check current prices on the Firebase pricing page).
2. Create a service account with the Test Lab role, download its JSON key, and keep it
   outside the repository (mode 600, never committed).
3. Give the script three environment variables, for example in a root-readable
   `EnvironmentFile=`:

   ```bash
   export PHONE_STEWARD_GCP_PROJECT=your-project-id
   export PHONE_STEWARD_RESULTS_BUCKET=your-results-bucket
   export PHONE_STEWARD_GCP_CONFIG=/etc/phone-steward/gcloud   # a directory for this script's gcloud config
   ```

4. Check that auth works:

   ```bash
   export CLOUDSDK_CONFIG="$PHONE_STEWARD_GCP_CONFIG"
   gcloud auth activate-service-account --key-file=/path/to/sa.json
   gcloud firebase test android models list --filter="form=VIRTUAL" --limit 5
   ```

## Quick start

1. **Build both APKs** (the app and its instrumentation tests) from `app/`:

   ```bash
   cd app && ./gradlew assembleDebug assembleDebugAndroidTest
   # app/build/outputs/apk/debug/phone-steward-debug.apk
   # app/build/outputs/apk/androidTest/debug/phone-steward-debug-androidTest.apk
   ```

2. **Run on the default device** with the repo's wrapper, which takes the run lock (one
   run at a time), uses your config and bucket, and copies the results back:

   ```bash
   bin/testlab-run "$PWD" <label> MediumPhone.arm 33          # from the repo root
   # prints RESULTS=gs://<bucket>/<run>, the test table, GCLOUD_EXIT,
   # LOCAL=/tmp/testlab/<run> and the paths of every screenshot
   ```

   Extra `gcloud firebase test android run` flags go after the API level (for example
   `--use-orchestrator`, or `--test-targets class com.example.phonesteward.ChatScreensTest`).

3. **Look at the result**: open the PNGs listed under `LOCAL=`; logs, the video and JUnit
   XML are beside them. Results stay in the bucket for 30 days. Never copy results into
   the repo unless they are deliberate baselines.

4. **Iterate**: fix, rebuild, rerun one device at a time.

## How the screenshot tests work

- **Where they live:** `app/src/androidTest/java/.../ScreensTest.kt` (every screen from
  fixture states, light and dark), `ChatInputInsetTest.kt` (the real `MainActivity`, paired
  against an address nothing listens on), helpers in `testlab/` (`ScreenShots`, `Layout`).
  The invented fixture data is `app/src/sharedTest/java/.../testlab/ScreenFixtures.kt`,
  compiled into both the unit-test and instrumentation source sets, so nothing reaches a
  network or a real server.
- **Screenshots:** `ScreenShots.save(name)` takes a full-device shot with
  `UiAutomation.takeScreenshot()` (system bars and keyboard included) and saves
  `/sdcard/Pictures/steward-screens/<name>.png` through MediaStore with
  `RELATIVE_PATH=Pictures/steward-screens`, which needs no permission on API 29+ for a
  file the app creates. If MediaStore refuses it falls back to the app's external files
  dir; pull that directory instead.
- **Host preview (not a gate):** `ScreenPreviewTest` (unit tests, Robolectric native
  graphics) writes PNGs of the same fixtures to `app/build/previews/`. It shows layout,
  colour and type in seconds on a host, but not system bars, real insets or the keyboard.
  Test Lab stays the gate.

## Rules

- **Cost.** Usage beyond the free quota is billed to your project. Prefer virtual
  devices, run **one run at a time**, keep `--timeout` short (the wrapper caps a run at
  20 minutes), and think before any batch (several devices or many reruns in a row) or
  any physical-device run.
- **Credentials.** Use only the service account through `CLOUDSDK_CONFIG`. Never print,
  copy or commit its key. Do not run `gcloud auth login` or change the active account in
  that config.
- **No production.** A Test Lab device is on the public internet and your server should
  not be. Tests use a fake server inside the test APK (`FakeSteward`, `MockWebServer`),
  never a live steward.
- **Privacy.** Test runs use fixture data, never a real pairing token or real phone
  content.
- **Device choice.** The default `MediumPhone.arm` with version 33 (the app's `minSdk`)
  is a good everyday check; a version-35 run covers `targetSdk` behaviour. Match your own
  phone's screen size and Android version where you can.
- **End to end.** `app/scripts/build-e2e.sh` builds everything the full `e2e/` Flow*
  suite needs (including the tiny helper apps the uninstall flow installs and removes,
  and a "newer" APK for the self-update flow). Run it before a full-suite Test Lab run.
