package com.example.simplemediadownloader

/**
 * Small, thread-safe admission controller. Room remains the durable queue; this class only tracks
 * which persisted task IDs are waiting or currently admitted in this service process.
 */
class DownloadQueueScheduler(maxConcurrent: Int = DEFAULT_CONCURRENCY) {
    private val waiting = LinkedHashSet<String>()
    private val active = LinkedHashSet<String>()
    private var concurrencyLimit = requireValidLimit(maxConcurrent)

    @Synchronized
    fun setConcurrencyLimit(limit: Int) {
        concurrencyLimit = requireValidLimit(limit)
    }

    @Synchronized
    fun enqueue(taskId: String): Boolean {
        if (taskId in active) return false
        return waiting.add(taskId)
    }

    @Synchronized
    fun restore(taskIds: Iterable<String>) {
        taskIds.forEach(::enqueue)
    }

    @Synchronized
    fun takeReady(): List<String> {
        val admitted = ArrayList<String>()
        val iterator = waiting.iterator()
        while (active.size < concurrencyLimit && iterator.hasNext()) {
            val taskId = iterator.next()
            iterator.remove()
            active += taskId
            admitted += taskId
        }
        return admitted
    }

    @Synchronized
    fun complete(taskId: String): Boolean = active.remove(taskId)

    @Synchronized
    fun cancelWaiting(taskId: String): Boolean = waiting.remove(taskId)

    @Synchronized
    fun activeTaskIds(): List<String> = active.toList()

    @Synchronized
    fun waitingTaskIds(): List<String> = waiting.toList()

    @Synchronized
    fun isIdle(): Boolean = active.isEmpty() && waiting.isEmpty()

    private fun requireValidLimit(limit: Int): Int {
        require(limit in 1..MAX_CONCURRENCY) {
            "Concurrency limit must be between 1 and $MAX_CONCURRENCY."
        }
        return limit
    }

    companion object {
        const val DEFAULT_CONCURRENCY = 2
        const val MAX_CONCURRENCY = 8
    }
}
