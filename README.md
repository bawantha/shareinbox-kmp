# ShareInbox KMP

Receive text, links, images and files shared **into** an Android or iOS app. Save each delivery to a local inbox, read it from shared Kotlin, and explicitly acknowledge it when processing finishes.

**Status: 0.1.0-alpha01, Maven Central publication pending.** Android 8+ (API 26), iOS 15+ for the supplied Swift integration. JVM is included for storage testing and local consumers; it has no desktop share-target adapter. No backend, analytics, or network permissions.

## What is included

- A Kotlin Multiplatform library, with an Android intent receiver and a Swift-callable iOS bridge.
- Atomic multi-attachment imports, bounded streaming copies, stable delivery IDs and duplicate suppression.
- Persistent pending items, explicit acknowledgement, and cleanup after interrupted imports.
- OS file locking for independent processes, including an iOS app and its Share Extension.
- A cancellable polling `Flow` that observes changes made by another inbox instance/process.
- A Swift package that safely loads `NSItemProvider` attachments inside their temporary-file lifetime.
- Android and iOS samples, automated tests, CI configuration and publishing instructions.

## Build and test

Requires JDK 21 and Android SDK 36. iOS builds additionally require an Apple Silicon Mac and Xcode. Set `ANDROID_HOME` or an ignored `local.properties` containing `sdk.dir=/your/android/sdk`.

```sh
./gradlew :shareinbox:jvmTest :shareinbox:testAndroidHostTest :sample-android:assembleDebug
./gradlew :shareinbox:iosSimulatorArm64Test
cd ios-support && swift test
```

The first native build downloads Kotlin/Native and LLVM. The pinned toolchain is Kotlin 2.4.0, Gradle 9.3.1 and AGP 9.1.0. The [test record](docs/TDD.md) documents actual red/green runs and remaining manual checks.

## Use from shared Kotlin

Until published, include the module in your build or publish it locally:

```sh
./gradlew :shareinbox:publishToMavenLocal
```

Then add `mavenLocal()` and `implementation("io.github.bawantha:shareinbox:0.1.0-alpha01")` to `commonMain.dependencies`. These are local development coordinates, not a claim of Maven Central availability.

```kotlin
val inbox = ShareInbox(privateDirectory)

// Collect while the screen is active. Each collector polls once per second.
inbox.pendingItems.collect { items ->
    // Update your UI. SharedAttachment.localPath points to an owned file.
}

// Copy attachments into your own permanent storage BEFORE acknowledgement.
inbox.acknowledge(item.id)
```

For programmatic ingestion:

```kotlin
val receipt = inbox.receive(
    text = "Invoice",
    files = listOf(ImportFile(absolutePath, "invoice.pdf", "application/pdf")),
    deliveryId = stableIdForThisDelivery,
)
```

Use a new ID for each intentional user share, and reuse that ID for retries. Matching content is **not** deduplicated: sharing the same photo twice is a legitimate action. IDs must be 1–100 ASCII letters, digits, hyphens or underscores. The default is a random UUID.

## Android

Create the inbox under `context.filesDir`. Add `ACTION_SEND` / `ACTION_SEND_MULTIPLE` filters for the MIME types your app accepts. In both `onCreate` and `onNewIntent`, call:

```kotlin
AndroidShareReceiver(context, inbox).receive(intent, deliveryId)
```

Run in a coroutine while the Activity holds the temporary URI permission. Persist `deliveryId` through saved instance state; create a new one for a new intent. Only `content://` attachments are accepted. Content is copied immediately without requesting broad storage access. The [Android sample](sample-android/src/main/java/dev/shareinbox/sample/MainActivity.kt) includes lifecycle handling and manifest filters.

## iOS

Build the framework:

```sh
./gradlew :shareinbox:assembleShareInboxKitDebugXCFramework
```

The output is `shareinbox/build/XCFrameworks/debug/ShareInboxKit.xcframework`. Add it to your app and Share Extension; add the local Swift package at `ios-support` to the extension. Both targets must have the **same App Group entitlement**.

`IosShareInbox.forAppGroup` selects the group's `ShareInbox-v1` directory. The extension loads attachments using `ShareLoader`, commits them through the bridge, cleans temporary files, then completes the extension request. The app reads the inbox on activation. File operations in the Swift bridge are synchronous; run them off the main thread.

See [the iOS integration guide](docs/IOS.md) and the sample project. The extension saves to the inbox; it does not force-launch the containing app.

## Delivery and storage contract

- An import is visible only after every attachment and its manifest are written and the staging directory is atomically renamed.
- Acknowledgement first moves a delivery to `consumed/`, then removes its payload. Empty tombstone directories prevent replay of an acknowledged ID. They are retained indefinitely; choose a fresh inbox directory only when deliberately resetting all deduplication history.
- Pending files remain until acknowledgement. There is no expiry, total inbox quota, encryption layer or automatic deletion of unprocessed shares.
- The OS lock serializes readers, writers and acknowledgements. A large import can delay other operations. Keep one `ShareInbox` per directory in each process; instances share the lock.
- A corrupt manifest or missing attachment raises `InboxStorageException`; data is preserved for inspection. The library does not silently drop damaged items.
- These guarantees cover process interruption on a local filesystem with atomic rename. No explicit disk `fsync` is issued, so sudden power-loss durability is not guaranteed.
- Cancellation before dispatch prevents ingestion. Once a synchronous filesystem transaction starts, it finishes or rolls back; cancellation may be observed after it committed. Retry using the same delivery ID.
- All consumers must use this library's protocol. Acknowledging while another consumer is reading a local path can invalidate that path; coordinate processing in the host app. There is no work-claim/lease API.

Default limits: 20 attachments, 50 MiB per attachment, 100 MiB per delivery, and 100,000 UTF-16 code units of text plus URL. Kotlin limits are configurable through `InboxLimits`. The bundled Swift loader uses the same file defaults and a fixed text limit. MIME types and filenames are metadata, not content validation.

## Contributing and release

Use tests first for behavior changes. See [TDD](docs/TDD.md), [architecture](docs/ARCHITECTURE.md), and [publishing](docs/PUBLISHING.md). Public API and storage schema are versioned separately. Do not change the on-disk format without migration tests.

MIT licensed. Source: [bawantha/shareinbox-kmp](https://github.com/bawantha/shareinbox-kmp). Maven Central publication and klibs.io indexing are pending.
