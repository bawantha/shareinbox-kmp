package dev.shareinbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.source

/** Call while the receiving Activity still holds the sender's temporary URI grants. */
class AndroidShareReceiver(context: Context, private val inbox: ShareInbox) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun receive(intent: Intent, deliveryId: String = newDeliveryId()): Submission? = withContext(Dispatchers.IO) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return@withContext null
        // A lifecycle retry can arrive after the provider's temporary permission expired.
        inbox.store.existingSubmission(deliveryId)?.let { return@withContext it }
        val uris = streamUris(intent).ifEmpty {
            intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } } ?: emptyList()
        }
        if (uris.size > inbox.limits.maxAttachments) throw InboxLimitException("Too many attachments")
        require(uris.all { it.scheme == "content" }) { "Only content:// attachments are accepted" }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val attachments = uris.map { uri ->
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: "attachment"
            val type = resolver.getType(uri) ?: intent.type?.takeUnless { '*' in it }
            AttachmentInput(name, type) {
                resolver.openInputStream(uri)?.source() ?: throw java.io.FileNotFoundException("Cannot open shared attachment")
            }
        }
        inbox.store.receive(deliveryId, text, null, attachments)
    }

    @Suppress("DEPRECATION")
    private fun streamUris(intent: Intent): List<Uri> = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    } else {
        listOfNotNull(if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }
}
