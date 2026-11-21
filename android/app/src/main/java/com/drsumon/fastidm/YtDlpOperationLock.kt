package com.drsumon.fastidm

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serializes yt-dlp binary updates against all execute() calls in this process.
 * The download service also uses a single-thread executor for its queued jobs.
 */
internal object YtDlpOperationLock {
    private val lock = ReentrantLock()

    fun <T> withLock(block: () -> T): T = lock.withLock(block)
}
