# iOS integration

## Run the sample

On an Apple Silicon Mac with JDK 21, Android SDK 36 and Xcode:

```sh
./gradlew :shareinbox:assembleShareInboxKitDebugXCFramework
open sample-ios/ShareInboxDemo.xcodeproj
```

Select the `ShareInboxDemo` scheme and an arm64 simulator. The checked-in Xcode project has app and Share Extension targets. `python3 sample-ios/generate-project.py` regenerates it without installing another tool.

For a physical device, select your Apple Development team for **both** targets, use your own bundle IDs, and register an App Group. Replace `group.dev.shareinbox.sample` in both entitlement files and both Swift sources. The sample identifier is a placeholder and is not registered to an Apple developer account.

Unsigned build check:

```sh
xcodebuild -project sample-ios/ShareInboxDemo.xcodeproj \
  -scheme ShareInboxDemo -sdk iphonesimulator -configuration Debug \
  -derivedDataPath build/ios-sample CODE_SIGNING_ALLOWED=NO build
```

## Add to an existing app

1. Add the generated `ShareInboxKit.xcframework` to the app and a new Share Extension target. It is static; use **Do Not Embed**.
2. Add `ios-support` as a local Swift package and link `ShareInboxExtension` to the extension. Unlike the sample (which directly compiles `ShareLoader.swift`), import `ShareInboxExtension` in your extension controller.
3. Enable the same App Group in both targets. Keep the framework and extension configured for application-extension-safe APIs.
4. Add activation rules for supported text, web URLs, images and files. Copy the sample's rules and narrow them if your app handles fewer types. Do not ship `TRUEPREDICATE`.
5. Collect the `NSItemProvider`s from **all** extension input items. Pass them to `ShareLoader.load`, then pass `ImportFile` values to `IosShareInbox.receive`. Preserve one UUID per extension request. Call `cleanup()` after the Kotlin commit, including failure paths.
6. Complete the extension request only after the inbox commit succeeds. Show an error if loading or storage fails. Let the user return to the containing app normally.
7. In the containing app, reload on activation. Either use shared Kotlin's `pendingItems` Flow or the synchronous Swift bridge from a background actor/queue.

`sample-ios/ShareExtension/ShareViewController.swift` is the complete integration example. It uses actors to run Kotlin filesystem operations off the main actor.

## Important behavior

- `NSItemProvider.loadFileRepresentation` URLs are borrowed; the loader copies the data within the callback, before the URL can disappear.
- File-URL representations use security-scoped access when available. A denied/missing source fails the whole delivery.
- Multiple providers are loaded sequentially, keeping memory bounded for attachments. Text representations are loaded by Foundation before the text-length check.
- Multiple text providers, and multiple URL providers, are joined with newlines in their respective fields. The URL field should be treated as received text, not automatically opened or assumed to contain one validated URL.
- Cancelling the extension before commit leaves no pending item. A cancellation racing an already-started commit can still leave a complete pending item; there is no attempt to undo a committed delivery.
- App Group setup cannot be replaced by a Gradle dependency. A missing entitlement is surfaced as an error.
- Kotlin App Group storage and Swift temporary staging are different directories. Only Kotlin owns the persistent inbox protocol.
- The supplied frameworks target arm64 devices and arm64 simulators. Intel simulator artifacts are not included.
