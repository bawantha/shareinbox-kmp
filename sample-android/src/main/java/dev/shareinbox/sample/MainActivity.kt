package dev.shareinbox.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import dev.shareinbox.*
import java.io.File
import kotlinx.coroutines.*

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var inbox: ShareInbox
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var deliveryId = newDeliveryId()
    private var observation: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deliveryId = savedInstanceState?.getString("deliveryId") ?: newDeliveryId()
        inbox = ShareInbox(File(filesDir, "shareinbox").absolutePath)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 64, 40, 40) }
        status = TextView(this).apply { setText(R.string.share_hint); textSize = 18f }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(ScrollView(this@MainActivity).apply { addView(list) })
        }
        setContentView(outer)
        importIntent(intent, deliveryId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliveryId = newDeliveryId()
        importIntent(intent, deliveryId)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("deliveryId", deliveryId)
        super.onSaveInstanceState(outState)
    }

    private fun importIntent(intent: Intent, id: String) {
        scope.launch {
            try {
                AndroidShareReceiver(this@MainActivity, inbox).receive(intent, id)?.let { status.setText(R.string.saved) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = getString(R.string.import_failed, e.message) }
        }
    }

    override fun onStart() {
        super.onStart()
        observation = scope.launch {
            try { inbox.pendingItems.collect { render(it) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = getString(R.string.inbox_error, e.message) }
        }
    }
    override fun onStop() { observation?.cancel(); super.onStop() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun render(items: List<SharedItem>) {
        list.removeAllViews()
        if (items.isEmpty()) list.addView(TextView(this).apply { setText(R.string.empty_inbox) })
        items.forEach { item ->
            list.addView(TextView(this).apply {
                text = listOfNotNull(item.text, item.url).plus(item.attachments.map {
                    getString(R.string.attachment_description, it.name, android.text.format.Formatter.formatFileSize(this@MainActivity, it.sizeBytes))
                }).joinToString("\n")
                textSize = 18f
                setPadding(0, 24, 0, 8)
            })
            list.addView(Button(this).apply {
                setText(R.string.acknowledge)
                setOnClickListener { scope.launch {
                    try { inbox.acknowledge(item.id) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { status.text = getString(R.string.acknowledge_failed, e.message) }
                } }
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}
