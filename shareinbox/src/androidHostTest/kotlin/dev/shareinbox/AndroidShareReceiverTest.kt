package dev.shareinbox

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidShareReceiverTest {
    private lateinit var root: File
    private lateinit var inbox: ShareInbox
    private lateinit var receiver: AndroidShareReceiver
    private lateinit var provider: TestProvider

    @BeforeTest fun setup() {
        val context = RuntimeEnvironment.getApplication()
        root = File(context.cacheDir, newDeliveryId()).apply { mkdirs() }
        inbox = ShareInbox(File(root, "inbox").path)
        receiver = AndroidShareReceiver(context, inbox)
        provider = TestProvider(root)
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "shareinbox.test" })
        ShadowContentResolver.registerProviderInternal("shareinbox.test", provider)
    }
    @AfterTest fun cleanup() { root.deleteRecursively() }

    @Test fun ignoresLauncherIntent() = runBlocking {
        assertNull(receiver.receive(Intent(Intent.ACTION_MAIN)))
        assertTrue(inbox.pending().isEmpty())
    }
    @Test fun receivesTextAndPreservesDeliveryId() = runBlocking {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello")
        receiver.receive(intent, "one")
        assertEquals("hello", inbox.pending().single().text)
        assertTrue(receiver.receive(intent, "one")!!.duplicate)
    }
    @Test fun receivesUrlAsTextWithoutDiscardingIt() = runBlocking {
        receiver.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.com"))
        assertEquals("https://example.com", inbox.pending().single().text)
    }
    @Test fun importsContentUriWithProviderFilenameAndMimeType() = runBlocking {
        receiver.receive(Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, provider.file("report.pdf", "bytes")), "one")
        val file = inbox.pending().single().attachments.single()
        assertEquals("report.pdf", file.name)
        assertEquals("application/pdf", file.contentType)
        assertEquals("bytes", File(file.localPath).readText())
    }
    @Test fun importsMultipleAttachmentsAndTextAtomically() = runBlocking {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/pdf")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(provider.file("a.pdf", "a"), provider.file("b.pdf", "b")))
            .putExtra(Intent.EXTRA_TEXT, "documents")
        receiver.receive(intent)
        assertEquals(2, inbox.pending().single().attachments.size)
        assertEquals("documents", inbox.pending().single().text)
    }
    @Test fun usesClipDataWhenStreamExtrasAreAbsent() = runBlocking {
        val uri = provider.file("clip.pdf", "clip")
        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf")
        intent.clipData = ClipData.newRawUri("document", uri)
        receiver.receive(intent)
        assertEquals("clip", File(inbox.pending().single().attachments.single().localPath).readText())
    }
    @Test fun rejectsFileUrisInsteadOfReadingAppPrivatePaths() = runBlocking {
        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/private/secret"))
        assertFailsWith<IllegalArgumentException> { receiver.receive(intent) }
        assertTrue(inbox.pending().isEmpty())
    }
    @Test fun failingSecondAttachmentDoesNotDeliverFirst() = runBlocking {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/pdf")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(provider.file("good.pdf", "ok"), Uri.parse("content://shareinbox.test/missing")))
        assertFailsWith<java.io.FileNotFoundException> { receiver.receive(intent) }
        assertTrue(inbox.pending().isEmpty())
    }
    @Test fun enforcesLimitsOnActualStreamBytes() = runBlocking {
        val small = ShareInbox(File(root, "small").path, InboxLimits(maxAttachmentBytes = 2))
        val adapter = AndroidShareReceiver(RuntimeEnvironment.getApplication(), small)
        assertFailsWith<InboxLimitException> {
            adapter.receive(Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, provider.file("large.pdf", "123")))
        }
        assertTrue(small.pending().isEmpty())
    }

    @Test fun retryAfterUriPermissionExpiresReturnsExistingReceipt() = runBlocking {
        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, provider.file("retry.pdf", "bytes"))
        receiver.receive(intent, "retry")
        provider.revoked = true
        assertTrue(receiver.receive(intent, "retry")!!.duplicate)
    }

    private class TestProvider(private val directory: File) : ContentProvider() {
        var revoked = false
        fun file(name: String, data: String): Uri {
            File(directory, name).writeText(data)
            return Uri.parse("content://shareinbox.test/$name")
        }
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/pdf"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            if (revoked) throw SecurityException("URI permission expired")
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(uri.lastPathSegment)) }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(File(directory, uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
