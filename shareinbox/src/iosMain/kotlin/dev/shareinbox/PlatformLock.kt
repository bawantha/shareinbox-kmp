@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.shareinbox

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.*

internal actual fun platformFileSystem(): FileSystem = FileSystem.SYSTEM

internal actual fun platformLock(rootDirectory: String): StoreLock = object : StoreLock {
    override fun <T> locked(block: () -> T): T {
        FileSystem.SYSTEM.createDirectories(rootDirectory.toPath())
        val descriptor = open("$rootDirectory/.lock", O_CREAT or O_RDWR, 384) // 0600
        if (descriptor < 0) throw InboxStorageException("Cannot open inbox lock (errno=$errno)")
        try {
            while (flock(descriptor, LOCK_EX) != 0) {
                if (errno != EINTR) throw InboxStorageException("Cannot lock inbox (errno=$errno)")
            }
            return block()
        } finally {
            flock(descriptor, LOCK_UN)
            close(descriptor)
        }
    }
}
