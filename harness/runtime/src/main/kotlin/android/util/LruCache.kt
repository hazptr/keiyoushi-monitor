package android.util

/**
 * android.util.LruCache with AOSP's semantics (frameworks/base/core/java/android/util/LruCache.java):
 * access-ordered, size in sizeOf() units (1 per entry by default), create() on a miss, entryRemoved()
 * on eviction/removal/replacement, null keys and values rejected, every method synchronized.
 */
open class LruCache<K : Any, V : Any>(maxSize: Int) {
    private val map = LinkedHashMap<K, V>(0, 0.75f, true)
    private var size = 0
    private var maxSize = maxSize
    private var putCount = 0
    private var createCount = 0
    private var evictionCount = 0
    private var hitCount = 0
    private var missCount = 0

    init {
        require(maxSize > 0) { "maxSize <= 0" }
    }

    fun resize(maxSize: Int) {
        require(maxSize > 0) { "maxSize <= 0" }
        synchronized(this) { this.maxSize = maxSize }
        trimToSize(maxSize)
    }

    fun get(key: K): V? {
        synchronized(this) {
            map[key]?.let {
                hitCount++
                return it
            }
            missCount++
        }
        val createdValue = create(key) ?: return null
        val mapValue: V?
        synchronized(this) {
            createCount++
            mapValue = map.put(key, createdValue)
            if (mapValue != null) map[key] = mapValue else size += safeSizeOf(key, createdValue)
        }
        return if (mapValue != null) {
            entryRemoved(false, key, createdValue, mapValue)
            mapValue
        } else {
            trimToSize(maxSize)
            createdValue
        }
    }

    fun put(key: K, value: V): V? {
        val previous: V?
        synchronized(this) {
            putCount++
            size += safeSizeOf(key, value)
            previous = map.put(key, value)
            if (previous != null) size -= safeSizeOf(key, previous)
        }
        if (previous != null) entryRemoved(false, key, previous, value)
        trimToSize(maxSize)
        return previous
    }

    open fun trimToSize(maxSize: Int) {
        while (true) {
            val key: K
            val value: V
            synchronized(this) {
                check(size >= 0 && !(map.isEmpty() && size != 0)) { "${javaClass.name}.sizeOf() is reporting inconsistent results!" }
                if (size <= maxSize || map.isEmpty()) return
                val toEvict = map.entries.iterator().next()
                key = toEvict.key
                value = toEvict.value
                map.remove(key)
                size -= safeSizeOf(key, value)
                evictionCount++
            }
            entryRemoved(true, key, value, null)
        }
    }

    fun remove(key: K): V? {
        val previous: V?
        synchronized(this) {
            previous = map.remove(key)
            if (previous != null) size -= safeSizeOf(key, previous)
        }
        if (previous != null) entryRemoved(false, key, previous, null)
        return previous
    }

    protected open fun entryRemoved(evicted: Boolean, key: K, oldValue: V, newValue: V?) {}

    protected open fun create(key: K): V? = null

    private fun safeSizeOf(key: K, value: V): Int {
        val result = sizeOf(key, value)
        check(result >= 0) { "Negative size: $key=$value" }
        return result
    }

    protected open fun sizeOf(key: K, value: V): Int = 1

    fun evictAll() = trimToSize(-1)

    @Synchronized fun size(): Int = size
    @Synchronized fun maxSize(): Int = maxSize
    @Synchronized fun hitCount(): Int = hitCount
    @Synchronized fun missCount(): Int = missCount
    @Synchronized fun createCount(): Int = createCount
    @Synchronized fun putCount(): Int = putCount
    @Synchronized fun evictionCount(): Int = evictionCount
    @Synchronized fun snapshot(): Map<K, V> = LinkedHashMap(map)

    @Synchronized
    override fun toString(): String {
        val accesses = hitCount + missCount
        val hitPercent = if (accesses != 0) 100 * hitCount / accesses else 0
        return "LruCache[maxSize=$maxSize,hits=$hitCount,misses=$missCount,hitRate=$hitPercent%]"
    }
}
