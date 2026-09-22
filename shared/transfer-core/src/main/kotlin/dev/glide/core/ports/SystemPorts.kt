package dev.glide.core.ports

import dev.glide.core.domain.BatchId
import dev.glide.core.domain.ItemId
import dev.glide.core.domain.SessionId
import java.util.UUID

interface MonotonicClock {
    fun nowMillis(): Long
    fun nowNanos(): Long
}

class SystemMonotonicClock : MonotonicClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun nowNanos(): Long = System.nanoTime()
}

interface IdGenerator {
    fun generateSessionId(): SessionId
    fun generateBatchId(): BatchId
    fun generateItemId(): ItemId
}

class DefaultIdGenerator : IdGenerator {
    override fun generateSessionId(): SessionId = SessionId(UUID.randomUUID().toString().lowercase())
    override fun generateBatchId(): BatchId = BatchId(UUID.randomUUID().toString().lowercase())
    override fun generateItemId(): ItemId = ItemId(UUID.randomUUID().toString().lowercase())
}
