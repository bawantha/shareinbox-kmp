package dev.shareinbox

import okio.Buffer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class InboxStoreTest {
    private val fs = FakeFileSystem()
    private val root = "/inbox".toPath()
    private val lock = object : StoreLock { override fun <T> locked(block: () -> T): T = block() }
    private fun store(limits: InboxLimits = InboxLimits()) = InboxStore(root, fs, lock, limits)
    private fun attachment(name: String = "photo.jpg", bytes: String = "image") =
        AttachmentInput(name, "image/jpeg") { Buffer().writeUtf8(bytes) }

    @Test fun emptyInboxHasNoItems() { assertEquals(emptyList(), store().pending()) }

    @Test fun persistsTextAndUrlAcrossNewInstances() {
        store().receive("delivery-1", "A note", "https://example.com", emptyList())
        val item = store().pending().single()
        assertEquals("delivery-1", item.id)
        assertEquals("A note", item.text)
        assertEquals("https://example.com", item.url)
    }

    @Test fun keepsDifferentUserSharesEvenWhenContentMatches() {
        store().receive("one", "same", null, emptyList())
        store().receive("two", "same", null, emptyList())
        assertEquals(setOf("one", "two"), store().pending().map { it.id }.toSet())
    }

    @Test fun duplicateDeliveryDoesNotReadAttachmentsOrCreateAnotherItem() {
        store().receive("one", "original", null, emptyList())
        val result = store().receive("one", "retry", null, listOf(
            AttachmentInput("x", null) { error("Duplicate should not open source") }
        ))
        assertTrue(result.duplicate)
        assertEquals("original", store().pending().single().text)
    }

    @Test fun acknowledgeRemovesPendingAndPreventsReplayAfterRestart() {
        store().receive("one", null, null, listOf(attachment()))
        val path = store().pending().single().attachments.single().localPath.toPath()
        assertTrue(fs.exists(path))
        assertTrue(store().acknowledge("one"))
        assertFalse(fs.exists(path))
        assertTrue(store().pending().isEmpty())
        assertTrue(store().receive("one", "retry", null, emptyList()).duplicate)
        assertFalse(store().acknowledge("missing"))
        assertFalse(store().acknowledge("one"))
    }

    @Test fun ownsCopiesAndKeepsDuplicateFilenamesSeparate() {
        store().receive("one", null, null, listOf(attachment(bytes = "a"), attachment(bytes = "b")))
        val files = store().pending().single().attachments
        assertEquals(listOf("a", "b"), files.map { fs.read(it.localPath.toPath()) { readUtf8() } })
        assertEquals(listOf(1L, 1L), files.map { it.sizeBytes })
        assertNotEquals(files[0].localPath, files[1].localPath)
    }

    @Test fun untrustedFilenameCannotEscapeInbox() {
        store().receive("one", null, null, listOf(attachment("../../evil\u0000.jpg")))
        val file = store().pending().single().attachments.single()
        assertTrue(file.localPath.startsWith("/inbox/pending/one/"))
        assertEquals("evil.jpg", file.name)
    }

    @Test fun oversizedAttachmentIsRejectedWithoutPartialDelivery() {
        assertFailsWith<InboxLimitException> {
            store(InboxLimits(maxAttachmentBytes = 3)).receive("one", null, null, listOf(attachment(bytes = "1234")))
        }
        assertTrue(store().pending().isEmpty())
        assertTrue(fs.list(root / "staging").isEmpty())
    }

    @Test fun totalByteLimitAppliesAcrossAttachments() {
        assertFailsWith<InboxLimitException> {
            store(InboxLimits(maxTotalBytes = 3)).receive("one", null, null,
                listOf(attachment(bytes = "12"), attachment(bytes = "34")))
        }
        assertTrue(store().pending().isEmpty())
    }

    @Test fun exactByteLimitIsAccepted() {
        store(InboxLimits(maxAttachmentBytes = 3, maxTotalBytes = 3))
            .receive("one", null, null, listOf(attachment(bytes = "123")))
        assertEquals(3L, store().pending().single().attachments.single().sizeBytes)
    }

    @Test fun inputFailureClosesSourceAndRollsBackWholeShare() {
        assertFailsWith<IllegalStateException> {
            store().receive("one", "text", null, listOf(attachment(),
                AttachmentInput("bad", null) { error("source disappeared") }))
        }
        assertTrue(store().pending().isEmpty())
        assertTrue(fs.list(root / "staging").isEmpty())
    }

    @Test fun rejectsEmptyShareInvalidIdAndExcessiveCountBeforeOpeningSource() {
        assertFailsWith<IllegalArgumentException> { store().receive("one", null, null, emptyList()) }
        assertFailsWith<IllegalArgumentException> { store().receive("../escape", "x", null, emptyList()) }
        assertFailsWith<InboxLimitException> {
            store(InboxLimits(maxAttachments = 1)).receive("one", null, null, listOf(attachment(), attachment()))
        }
        assertFailsWith<InboxLimitException> {
            store(InboxLimits(maxTextCharacters = 2)).receive("one", "abc", null, emptyList())
        }
    }

    @Test fun interruptedStagingIsNeverDeliveredAndIsRecoveredOnNextWrite() {
        store().pending()
        fs.createDirectories(root / "staging" / "interrupted")
        fs.write(root / "staging" / "interrupted" / "partial.bin") { writeUtf8("partial") }
        assertTrue(store().pending().isEmpty())
        store().receive("one", "complete", null, emptyList())
        assertTrue(fs.list(root / "staging").isEmpty())
    }

    @Test fun corruptManifestFailsExplicitlyWithoutDeletingData() {
        store().receive("one", "original", null, emptyList())
        val manifest = root / "pending" / "one" / "manifest.json"
        fs.write(manifest) { writeUtf8("broken") }
        assertFailsWith<InboxStorageException> { store().pending() }
        assertTrue(fs.exists(manifest))
    }

    @Test fun maliciousPersistedPathIsRejected() {
        store().receive("one", null, null, listOf(attachment()))
        val manifest = root / "pending" / "one" / "manifest.json"
        val original = fs.read(manifest) { readUtf8() }
        fs.write(manifest) { writeUtf8(original.replace("attachment-0.bin", "../../../outside")) }
        assertFailsWith<InboxStorageException> { store().pending() }
    }

    @Test fun closesAttachmentSourceWhenLimitIsExceeded() {
        var closed = false
        val source = object : okio.ForwardingSource(Buffer().writeUtf8("1234")) {
            override fun close() { closed = true; super.close() }
        }
        assertFailsWith<InboxLimitException> {
            store(InboxLimits(maxAttachmentBytes = 2)).receive("one", null, null,
                listOf(AttachmentInput("data", null) { source }))
        }
        assertTrue(closed)
    }

    @Test fun futureSchemaIsRejectedWithoutDataLoss() {
        store().receive("one", "future", null, emptyList())
        val path = root / "pending" / "one" / "manifest.json"
        val original = fs.read(path) { readUtf8() }
        fs.write(path) { writeUtf8(original.replaceFirst("{", "{\"schemaVersion\":99,")) }
        assertFailsWith<InboxStorageException> { store().pending() }
        assertTrue(fs.exists(path))
    }

    @Test fun interruptedAcknowledgementIsNotReplayedAndNextMutationFinishesCleanup() {
        store().receive("one", null, null, listOf(attachment()))
        fs.atomicMove(root / "pending" / "one", root / "consumed" / "one")
        assertTrue(store().pending().isEmpty())
        assertTrue(store().receive("one", "retry", null, emptyList()).duplicate)
        assertTrue(fs.list(root / "consumed" / "one").isEmpty())
    }
}
