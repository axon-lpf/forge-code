package com.codeforge.plugin.idea.completion

import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * B7：行内补全缓存
 *
 * LRU 缓存（最大 50 条），300ms TTL。
 * key = 前缀哈希，value = 补全结果 + 时间戳。
 */
object CompletionCache {
    private val log = logger<CompletionCache>()

    private data class CacheEntry(
        val completion: String,
        val timestamp: Long
    )

    private val cache = object : LinkedHashMap<String, CacheEntry>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > 50
        }
    }

    private val cacheMutex = ConcurrentHashMap<String, CacheEntry>()

    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)

    const val TTL_MS = 300L

    /**
     * 根据前缀获取缓存的补全结果（未过期）
     */
    fun get(prefix: String): String? {
        val key = prefix.take(100).hashCode().toString()
        val entry = cacheMutex.get(key) ?: return null
        if (System.currentTimeMillis() - entry.timestamp > TTL_MS) {
            cacheMutex.remove(key)
            missCount.incrementAndGet()
            return null
        }
        hitCount.incrementAndGet()
        log.debug("B7: 缓存命中率 ${hitCount.get()}/${hitCount.get() + missCount.get()}")
        return entry.completion
    }

    /**
     * 写入缓存
     */
    fun put(prefix: String, completion: String) {
        val key = prefix.take(100).hashCode().toString()
        cacheMutex[key] = CacheEntry(completion, System.currentTimeMillis())
    }

    /**
     * 清除全部缓存
     */
    fun clear() {
        cacheMutex.clear()
        hitCount.set(0)
        missCount.set(0)
        log.debug("B7: 补全缓存已清空")
    }
}
