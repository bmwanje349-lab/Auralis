# Releasing Auralis

Debug builds come from CI on every push. A store release must be signed with a key only you hold.

1. Create a keystore once and back it up somewhere safe. If you lose it you can never update the app.

   ```bash
   keytool -genkeypair -v -keystore auralis-release.jks -alias auralis \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Build a signed release (the keystore stays on your machine, never in git):

   ```bash
   export AURALIS_KEYSTORE=/path/to/auralis-release.jks
   export AURALIS_KEYSTORE_PASSWORD=...
   export AURALIS_KEY_ALIAS=auralis
   export AURALIS_KEY_PASSWORD=...
   gradle assembleRelease      # APK for direct distribution
   gradle bundleRelease        # AAB for Google Play
   ```

3. Raise `versionCode` in `app/build.gradle` for every release you upload.

Before selling: replace or license anything derived from third-party apps (see the fidelity document), and add a privacy policy URL to the store listing.
