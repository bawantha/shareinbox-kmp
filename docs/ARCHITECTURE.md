# Architecture

```text
Android Intent / ContentResolver          iOS NSItemProvider
             |                                  |
      AndroidShareReceiver               Swift ShareLoader
             |                                  |
             |                           IosShareInbox bridge
             +------------------+---------------+
                                |
                     InboxStore (common Kotlin)
                                |
                   JVM FileLock / Darwin flock
                                |
                  Okio local filesystem + JSON
                                |
                  ShareInbox.pendingItems Flow
```

## Disk protocol, schema 1

`root/.lock` is the cross-process lock and must never be unlinked while the inbox is in use.

- `staging/{id}/`: unpublished copies and manifest. Cleared under lock before the next mutation after interruption.
- `pending/{id}/manifest.json`: committed delivery; attachments use generated `attachment-N.bin` names. Display names never participate in path construction.
- `consumed/{id}/`: acknowledgement tombstone. Payload is cleared under lock. An interrupted cleanup resumes on the next mutation.

The entire transaction holds the OS lock. No coroutine suspension occurs inside it. Java additionally uses a process-local lock because `FileChannel.lock()` rejects overlapping same-process locks. Darwin uses independent open file descriptions and `flock`.

Serialization is internal. Public models expose absolute paths resolved from validated relative names. A future unknown schema fails explicitly rather than being interpreted using old rules.

The Swift package knows nothing about the disk protocol. It temporarily owns provider files and calls the Kotlin bridge, avoiding two competing manifest writers. Temporary staging can briefly double file storage. The loader's own temporary files may survive extension termination until the OS purges its temporary directory.

## Boundaries

The core does not display UI, request permissions, launch other apps, parse documents, sniff MIME types, upload content or schedule background work. The caller chooses intent filters, extension activation rules, App Group ID and storage lifetime.

The polling Flow runs filesystem work on `Dispatchers.IO`, emits only changed snapshots and stops when collection is cancelled. It does not observe in the background without a collector. Corruption errors terminate the collector and should be displayed/reported by the app.

## Platform sources

- [Android: receive shared data](https://developer.android.com/develop/ui/compose/sharing/receive)
- [Apple: extension containers and App Groups](https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/ExtensionScenarios.html)
- [Apple: temporary file representation lifetime](https://developer.apple.com/documentation/foundation/nsitemprovider/loadfilerepresentation(fortypeidentifier:completionhandler:))
- [Kotlin toolchain compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
