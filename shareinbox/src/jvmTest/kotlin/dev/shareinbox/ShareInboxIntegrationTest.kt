package dev.shareinbox

import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class ShareInboxIntegrationTest {
    private val directory = Files.createTempDirectory("shareinbox-test").toFile()
    private fun inbox() = ShareInbox(directory.resolve("inbox").path)

    @AfterTest fun cleanup() { directory.deleteRecursively() }

    @Test fun receivesCopiesThatSurviveSourceDeletionAndRestart() = runBlocking {
        val source = directory.resolve("source.pdf").apply { writeText("pdf bytes") }
        inbox().receive(files = listOf(ImportFile(source.path, "report.pdf", "application/pdf")), deliveryId = "delivery")
        source.delete()
        val item = inbox().pending().single()
        assertEquals("pdf bytes", java.io.File(item.attachments.single().localPath).readText())
        assertTrue(inbox().acknowledge(item.id))
        assertTrue(inbox().pending().isEmpty())
    }

    @Test fun independentInstancesObserveIncomingItemsAndAcknowledgements() = runBlocking {
        val reader = inbox()
        assertTrue(reader.pendingItems.first().isEmpty())
        val received = async { withTimeout(5000) { reader.observePending(20).first { it.isNotEmpty() } } }
        inbox().receive(text = "from extension", deliveryId = "external")
        assertEquals("external", received.await().single().id)
        inbox().acknowledge("external")
        assertTrue(withTimeout(5000) { reader.observePending(20).first { it.isEmpty() } }.isEmpty())
    }

    @Test fun rejectsNonPositivePollInterval() {
        assertFailsWith<IllegalArgumentException> { inbox().observePending(0) }
    }

    @Test fun concurrentWritersDoNotLoseItemsAndDeduplicateSameDelivery() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 40).map { n -> Callable {
                runBlocking { inbox().receive(text = "item $n", deliveryId = "id-${n % 20}") }
            } }
            val results = pool.invokeAll(tasks, 20, TimeUnit.SECONDS).map { it.get() }
            assertEquals(20, results.count { !it.duplicate })
            assertEquals(20, runBlocking { inbox().pending().size })
        } finally { pool.shutdownNow() }
    }

    @Test fun cancellationDoesNotStartImport() = runBlocking {
        val job = launch(start = CoroutineStart.LAZY) { inbox().receive(text = "cancelled") }
        job.cancelAndJoin()
        assertTrue(inbox().pending().isEmpty())
    }

    @Test fun independentProcessesSerializeImportsWithTheOsLock() {
        val java = java.io.File(System.getProperty("java.home"), "bin/java").path
        val classpath = System.getProperty("shareinbox.test.classpath")
        val children = (0 until 3).map { worker ->
            ProcessBuilder(java, "-cp", classpath, "dev.shareinbox.ProcessWriter", inbox().rootDirectory)
                .redirectErrorStream(true).redirectOutput(directory.resolve("worker-$worker.log")).start()
        }
        try {
            children.forEachIndexed { index, child ->
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Writer timed out")
                assertEquals(0, child.exitValue(), directory.resolve("worker-$index.log").readText())
            }
            assertEquals(10, runBlocking { inbox().pending().size })
        } finally { children.forEach { if (it.isAlive) it.destroyForcibly() } }
    }
}

object ProcessWriter {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val inbox = ShareInbox(args.single())
        repeat(10) { inbox.receive(text = "from child process", deliveryId = "process-$it") }
    }
}
