package dev.shareinbox

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// FileChannel rejects overlapping locks inside one JVM, so serialize there first.
private val localLocks = ConcurrentHashMap<String, ReentrantLock>()
internal actual fun platformFileSystem(): okio.FileSystem = okio.FileSystem.SYSTEM

internal actual fun platformLock(rootDirectory: String): StoreLock {
    val root = File(rootDirectory).canonicalFile
    val mutex = localLocks.computeIfAbsent(root.path) { ReentrantLock() }
    return object : StoreLock {
        override fun <T> locked(block: () -> T): T = mutex.withLock {
            check(root.isDirectory || root.mkdirs()) { "Cannot create inbox directory" }
            RandomAccessFile(File(root, ".lock"), "rw").use { file ->
                file.channel.lock().use { block() }
            }
        }
    }
}
