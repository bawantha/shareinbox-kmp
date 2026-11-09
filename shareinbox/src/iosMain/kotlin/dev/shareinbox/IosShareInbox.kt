package dev.shareinbox

import platform.Foundation.NSFileManager

/** Synchronous Swift bridge. Run file operations off the main thread. */
class IosShareInbox @Throws(Exception::class) constructor(rootDirectory: String) {
    private val inbox = ShareInbox(rootDirectory)
    @Throws(Exception::class)
    fun receive(text: String?, url: String?, files: List<ImportFile>, deliveryId: String): Submission =
        inbox.receiveBlocking(text, url, files, deliveryId)
    @Throws(Exception::class)
    fun pending(): List<SharedItem> = inbox.store.pending()
    @Throws(Exception::class)
    fun acknowledge(id: String): Boolean = inbox.store.acknowledge(id)
    companion object {
        @Throws(Exception::class)
        fun forAppGroup(identifier: String): IosShareInbox {
            require(identifier.isNotBlank()) { "App Group identifier is required" }
            val container = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(identifier)
            val path = container?.path ?: throw IllegalArgumentException("App Group is unavailable. Enable the same entitlement in both targets: $identifier")
            return IosShareInbox("$path/ShareInbox-v1")
        }
    }
}
