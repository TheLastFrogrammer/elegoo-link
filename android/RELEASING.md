# Releasing Link Workshop

Signed releases are built by GitHub Actions (`.github/workflows/android.yml`) when a tag `android-vX.Y.Z` is pushed: it
runs the tests and lint, builds `assembleRelease`, signs it with the release key and checks the slicer engine, then
publishes `Link-Workshop-vX.Y.Z.apk` and `SHA256SUMS` as a GitHub release. The app's Settings › About › Check for updates
reads that list (once a day by itself unless turned off) and offers the APK; installing stays with the person.

## The release key

The key never goes into the repository. The workflow reads it from four repository secrets
(Settings › Secrets and variables › Actions):

| Secret | Value |
| --- | --- |
| `LINK_WORKSHOP_KEYSTORE_BASE64` | the keystore file, base64-encoded (`base64 -w0 link-workshop-release.jks`) |
| `LINK_WORKSHOP_KEYSTORE_PASSWORD` | the keystore's password |
| `LINK_WORKSHOP_KEY_ALIAS` | `link-workshop` |
| `LINK_WORKSHOP_KEY_PASSWORD` | the key's password (the same as the keystore's) |

Keep a copy of the keystore and its password somewhere safe: a lost key means no further updates install over the app.

## Key rotation (`signing/lineage.bin`)

Development builds have been signed with a development key. `signing/lineage.bin` is that key's signature over the
release key (public: certificates and signatures only), and the build signs release APKs again with it
(`rotateReleaseSigning`), using APK Signature Scheme v3 only. So:

- a release installs over a development build on Android 9 or later, keeping the app's data;
- the development key keeps its rollback capability, so development builds still install over a release;
- release APKs need Android 9 or later (v1/v2 signatures would need the development key on every release).

## Making a release

1. Bump `versionCode` and `versionName` in `app/build.gradle` and commit.
2. Tag the commit `android-v<versionName>` and push the tag.
3. Watch the "Android release" workflow; the release appears under Releases with the APK.

Locally: set the four `LINK_WORKSHOP_*` values (with `LINK_WORKSHOP_KEYSTORE` the keystore's path) in the environment or
`~/.gradle/gradle.properties` and run `./gradlew assembleRelease`.
