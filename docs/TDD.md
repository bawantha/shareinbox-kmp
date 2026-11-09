# TDD and verification record

Implemented and verified locally on **9 November 2026 (Australia/Sydney)** with JDK 21, Xcode 26.6, Android SDK 36 and an Apple Silicon Mac. CI is supplied but has not been run on GitHub.

## Red → green history

Tests were written against unimplemented APIs and executed before their implementations were added. The checked-in `test-evidence/` logs retain those failures; paths are normalized for portability.

| Cycle | Observed red | Implementation and green |
|---|---|---|
| Shared store | 15 tests failed with unimplemented operations | Atomic staging/commit, copied attachments, byte/count limits, validation, recovery and acknowledgement |
| Public API | 5 integration tests failed | Suspend API, cross-instance observation and real filesystem locking |
| Android | 9 adapter tests failed | Intent parsing, content-provider imports, ClipData fallback and all-or-nothing attachment handling |
| Swift loader | 8 tests failed | Temporary-file lifetime handling, streaming copies, limits and cleanup; URL representation decoding was corrected based on a failing test |
| Native bridge | 2 iOS tests failed | Swift-callable Kotlin API and App Group validation |
| Android retry regression | Revoked URI permission caused a duplicate delivery to fail | Check committed receipt before reopening the provider |
| Swift file-URL regression | A file-URL-only provider was rejected | Scoped file-URL handling, with copying inside the callback |

Additional regression checks cover stream closure, unknown manifest versions, interrupted acknowledgement cleanup and three independent JVM writer processes. These were added after the underlying behavior existed and passed without a production change; they are not represented as additional red/green cycles.

Native compilation caught a JVM-only `use` resolution and was fixed to use `okio.use`. Local Maven publication then caught a shared-metadata filesystem reference that target builds did not catch. It was moved behind an expect/actual platform function. An independent Gradle consumer verifies the resulting Maven artifacts rather than depending on project sources.

## Final automated results

| Suite | Test executions | Result |
|---|---:|---|
| JVM: 18 shared + 6 integration tests | 24 | Pass |
| Android/Robolectric API 35: 18 shared + 10 adapter tests | 28 | Pass |
| Kotlin iOS simulator: 18 shared + 2 native bridge tests | 20 | Pass |
| Swift loader using real Foundation item providers on macOS | 10 | Pass |
| Independent published-package consumer on JVM | 1 | Pass |
| **Total** | **83** | **0 failures** |

There are **47 distinct test cases**; shared tests run on three targets. The Swift tests run on macOS, not inside a deployed iOS Share Extension. No test-coverage percentage is claimed.

Other checks completed:

- Android demo APK assembled.
- Android lint passed with no errors. One `OldTargetApi` warning remains because the tested sample deliberately targets SDK 36; review the target SDK when preparing a store release.
- Device and simulator arm64 XCFrameworks built.
- iOS app and embedded Share Extension built, including simulator ad-hoc signing.
- iOS demo installed and launched on an iPhone 17 Pro simulator; App Group container resolved and the empty inbox rendered without an error.
- All KMP Maven publications generated into the local build repository.
- Separate consumer compiled common metadata, JVM and iOS simulator code against those publications, and passed its JVM test.

## Reproduce

```sh
./gradlew :shareinbox:jvmTest :shareinbox:testAndroidHostTest \
  :shareinbox:iosSimulatorArm64Test \
  :shareinbox:assembleShareInboxKitDebugXCFramework \
  :shareinbox:publishAllPublicationsToLocalBuildRepository \
  :sample-android:assembleDebug :sample-android:lintDebug

./gradlew -p verification/consumer jvmTest \
  compileCommonMainKotlinMetadata compileKotlinIosSimulatorArm64

(cd ios-support && swift test)

xcodebuild -project sample-ios/ShareInboxDemo.xcodeproj \
  -scheme ShareInboxDemo -sdk iphonesimulator -configuration Debug \
  -derivedDataPath build/ios-sample CODE_SIGNING_ALLOWED=NO build
```

Gradle XML/HTML reports are written under `shareinbox/build/test-results` and `shareinbox/build/reports/tests`. Snapshot XML results are included in `test-evidence/results/`.

## Manual release checks still required

These are **not** claimed as completed:

- Actual system share-sheet → extension → containing app round trip on an iPhone with your signing team and App Group.
- Android device share-sheet round trips on the minimum supported API 26 and a current OS, using Photos, Files, a browser and third-party/cloud document providers. Automated adapter coverage here uses API 35.
- iCloud-backed files, large photos, permission revocation, low storage and locked-device behavior on physical devices.
- Kill the extension during a multi-file copy and retry; confirm no partial delivery appears and the next mutation cleans staging.
- Verify app-specific permanent file processing before acknowledgement. The library's delivery ID is an idempotency key, not an exactly-once guarantee for the app's external side effects.
- Review App Store/Play metadata and publish a signed release only after those checks.

## Development rule

For a bug or feature, add a behavior test, run it to confirm the intended failure, implement the smallest coherent change, rerun the affected suite, then run the platform/package checks touched by the change. Never weaken a test to hide an implementation failure. A test fixture defect should be fixed as a fixture defect (the Android provider fixture needed `attachInfo` before its URI methods were usable).
