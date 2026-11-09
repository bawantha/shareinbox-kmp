package dev.shareinbox

import okio.FileSystem
import okio.Path
import okio.Source
import okio.Buffer
import okio.use
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

data class InboxLimits(
    val maxAttachments: Int = 20,
    val maxAttachmentBytes: Long = 50L * 1024 * 1024,
    val maxTotalBytes: Long = 100L * 1024 * 1024,
    val maxTextCharacters: Int = 100_000,
) {
    init {
        require(maxAttachments > 0 && maxAttachmentBytes > 0 && maxTotalBytes > 0 && maxTextCharacters > 0)
    }
}
data class SharedAttachment(val name: String, val contentType: String?, val sizeBytes: Long, val localPath: String)
data class SharedItem(val id: String, val text: String?, val url: String?, val attachments: List<SharedAttachment>, val receivedAtEpochMillis: Long)
data class Submission(val id: String, val duplicate: Boolean)
class InboxLimitException(message: String) : IllegalArgumentException(message)
class InboxStorageException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
internal class AttachmentInput(val name: String, val contentType: String?, val open: () -> Source)
internal interface StoreLock { fun <T> locked(block: () -> T): T }

internal class InboxStore(
    private val root: Path,
    private val fs: FileSystem,
    private val lock: StoreLock,
    private val limits: InboxLimits = InboxLimits(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = root / "pending"
    private val consumed = root / "consumed"
    private val staging = root / "staging"

    fun existingSubmission(id: String): Submission? = lock.locked {
        require(validId(id)) { "Invalid delivery ID" }
        initialize()
        if (fs.exists(pending / id) || fs.exists(consumed / id)) Submission(id, true) else null
    }

    fun pending(): List<SharedItem> = lock.locked {
        initialize()
        fs.list(pending).map { directory ->
            try {
                val stored = fs.read(directory / "manifest.json") { json.decodeFromString<Manifest>(readUtf8()) }
                require(stored.schemaVersion == 1) { "Unsupported manifest version" }
                require(validId(stored.id) && stored.id == directory.name) { "Invalid delivery ID" }
                val files = stored.attachments.mapIndexed { index, attachment ->
                    require(attachment.file == "attachment-$index.bin") { "Unsafe attachment path" }
                    val path = directory / attachment.file
                    val metadata = fs.metadata(path)
                    require(metadata.isRegularFile && metadata.symlinkTarget == null && metadata.size == attachment.sizeBytes) {
                        "Missing or damaged attachment"
                    }
                    SharedAttachment(attachment.name, attachment.contentType, attachment.sizeBytes, path.toString())
                }
                SharedItem(stored.id, stored.text, stored.url, files, stored.receivedAtEpochMillis)
            } catch (e: Exception) {
                throw InboxStorageException("Cannot read inbox item ${directory.name}; data was preserved", e)
            }
        }.sortedWith(compareBy<SharedItem> { it.receivedAtEpochMillis }.thenBy { it.id })
    }

    fun receive(id: String, text: String?, url: String?, attachments: List<AttachmentInput>): Submission = lock.locked {
        require(validId(id)) { "Delivery ID must contain 1–100 ASCII letters, digits, underscores or hyphens" }
        initialize()
        recover()
        if (fs.exists(pending / id) || fs.exists(consumed / id)) return@locked Submission(id, true)
        require(!text.isNullOrBlank() || !url.isNullOrBlank() || attachments.isNotEmpty()) { "Share is empty" }
        if (attachments.size > limits.maxAttachments) throw InboxLimitException("Too many attachments")
        if ((text?.length ?: 0).toLong() + (url?.length ?: 0) > limits.maxTextCharacters) {
            throw InboxLimitException("Text and URL exceed character limit")
        }
        val directory = staging / id
        fs.createDirectories(directory)
        try {
            var totalBytes = 0L
            val files = attachments.mapIndexed { index, attachment ->
                val file = "attachment-$index.bin"
                var size = 0L
                attachment.open().use { source ->
                    fs.sink(directory / file).use { sink ->
                        val buffer = Buffer()
                        while (true) {
                            val read = source.read(buffer, 8192)
                            if (read == -1L) break
                            if (read == 0L) throw InboxStorageException("Attachment source made no progress")
                            if (read > limits.maxAttachmentBytes - size || read > limits.maxTotalBytes - totalBytes) {
                                throw InboxLimitException("Attachment bytes exceed configured limit")
                            }
                            size += read
                            totalBytes += read
                            sink.write(buffer, read)
                        }
                    }
                }
                StoredAttachment(file, safeName(attachment.name), attachment.contentType?.take(255), size)
            }
            val manifest = Manifest(id = id, text = text, url = url, attachments = files,
                receivedAtEpochMillis = Clock.System.now().toEpochMilliseconds())
            fs.write(directory / "manifest.json") { writeUtf8(json.encodeToString(manifest)) }
            // Same filesystem: readers see either no delivery or the complete delivery.
            fs.atomicMove(directory, pending / id)
            Submission(id, false)
        } finally {
            if (fs.exists(directory)) fs.deleteRecursively(directory)
        }
    }

    fun acknowledge(id: String): Boolean = lock.locked {
        require(validId(id)) { "Invalid delivery ID" }
        initialize()
        recover()
        val directory = pending / id
        if (!fs.exists(directory)) return@locked false
        // Commit the tombstone BEFORE deleting attachments; a crash cannot resurrect the item.
        fs.atomicMove(directory, consumed / id)
        cleanConsumed()
        true
    }

    private fun initialize() {
        fs.createDirectories(pending)
        fs.createDirectories(consumed)
        fs.createDirectories(staging)
    }

    private fun recover() {
        // All producers hold the same process-wide/OS lock while importing.
        fs.list(staging).forEach { fs.deleteRecursively(it) }
        cleanConsumed()
    }

    private fun cleanConsumed() {
        // Empty directories are permanent idempotency tombstones. They contain no payload.
        fs.list(consumed).forEach { directory -> fs.list(directory).forEach { fs.deleteRecursively(it) } }
    }

    private fun validId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,100}"))
    private fun safeName(value: String): String = value.replace('\\', '/').substringAfterLast('/')
        .filter { it >= ' ' && it != '\u007f' }.take(255).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"
}

@Serializable
private data class Manifest(
    val schemaVersion: Int = 1,
    val id: String,
    val text: String?,
    val url: String?,
    val attachments: List<StoredAttachment>,
    val receivedAtEpochMillis: Long,
)

@Serializable
private data class StoredAttachment(val file: String, val name: String, val contentType: String?, val sizeBytes: Long)
