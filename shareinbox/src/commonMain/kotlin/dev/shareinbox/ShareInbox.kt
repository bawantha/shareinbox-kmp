package dev.shareinbox

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import okio.FileSystem
import okio.Path.Companion.toPath

/** A file to copy into the inbox. The source remains owned by the caller. */
data class ImportFile(val sourcePath: String, val name: String, val contentType: String? = null)

/** Local durable inbox. Use a dedicated, private directory (an App Group directory on iOS). */
class ShareInbox(val rootDirectory: String, val limits: InboxLimits = InboxLimits()) {
    init { require(rootDirectory.toPath().isAbsolute) { "Use an absolute private directory" } }
    internal val store = InboxStore(rootDirectory.toPath(), platformFileSystem(), platformLock(rootDirectory), limits)
    val pendingItems: Flow<List<SharedItem>> get() = observePending()
    suspend fun receive(text: String? = null, url: String? = null, files: List<ImportFile> = emptyList(), deliveryId: String = newDeliveryId()): Submission = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        receiveBlocking(text, url, files, deliveryId)
    }
    suspend fun pending(): List<SharedItem> = withContext(Dispatchers.IO) { store.pending() }
    suspend fun acknowledge(id: String): Boolean = withContext(Dispatchers.IO) { store.acknowledge(id) }

    /** Cold polling flow. Collect only while the UI is active; cancellation stops polling. */
    fun observePending(pollIntervalMillis: Long = 1000): Flow<List<SharedItem>> {
        require(pollIntervalMillis > 0) { "Polling interval must be positive" }
        return flow {
            while (true) {
                emit(pending())
                delay(pollIntervalMillis)
            }
        }.distinctUntilChanged()
    }

    internal fun receiveBlocking(text: String?, url: String?, files: List<ImportFile>, deliveryId: String): Submission =
        store.receive(deliveryId, text, url, files.map { file ->
            AttachmentInput(file.name, file.contentType) { platformFileSystem().source(file.sourcePath.toPath()) }
        })
}

fun newDeliveryId(): String = kotlin.uuid.Uuid.random().toString()
internal expect fun platformLock(rootDirectory: String): StoreLock
internal expect fun platformFileSystem(): FileSystem
