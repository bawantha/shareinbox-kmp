package dev.shareinbox

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.*

class IosShareInboxTest {
    private val directory = (NSTemporaryDirectory() + newDeliveryId()).toPath()
    private fun inbox() = IosShareInbox((directory / "inbox").toString())

    @AfterTest fun cleanup() { FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false) }

    @Test fun nativeWriterAndHostSharePersistentInbox() {
        FileSystem.SYSTEM.createDirectories(directory)
        val source = directory / "source.pdf"
        FileSystem.SYSTEM.write(source) { writeUtf8("pdf") }
        inbox().receive("caption", null, listOf(ImportFile(source.toString(), "doc.pdf", "application/pdf")), "one")
        FileSystem.SYSTEM.delete(source)
        val item = inbox().pending().single()
        assertEquals("caption", item.text)
        assertEquals("pdf", FileSystem.SYSTEM.read(item.attachments.single().localPath.toPath()) { readUtf8() })
        assertTrue(inbox().acknowledge("one"))
        assertTrue(inbox().pending().isEmpty())
        assertTrue(inbox().receive("again", null, emptyList(), "one").duplicate)
    }

    @Test fun appGroupConfigurationErrorsAreExplicit() {
        assertFailsWith<IllegalArgumentException> { IosShareInbox.forAppGroup("") }
    }
}
