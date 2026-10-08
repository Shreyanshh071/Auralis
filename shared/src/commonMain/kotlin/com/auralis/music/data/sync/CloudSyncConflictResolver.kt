package com.auralis.music.data.sync

data class SyncableItem<T>(
    val id: String,
    val data: T,
    val updatedAt: Long,
    val isDeleted: Boolean = false
)

object CloudSyncConflictResolver {

    /**
     * Resolves conflict between local and remote items using Last-Write-Wins (LWW).
     */
    fun <T> resolveConflict(local: SyncableItem<T>?, remote: SyncableItem<T>?): SyncableItem<T>? {
        if (local == null && remote == null) return null
        if (local == null) return if (remote!!.isDeleted) null else remote
        if (remote == null) return if (local.isDeleted) null else local

        // Both exist: pick the newer one
        val winner = if (remote.updatedAt >= local.updatedAt) remote else local
        return if (winner.isDeleted) null else winner
    }

    /**
     * Merges collections of local and remote items with anti-wipe protection.
     * If remote collection is completely empty and local is non-empty on first sync,
     * it prevents wiping local items.
     */
    fun <T> mergeCollections(
        localItems: Map<String, SyncableItem<T>>,
        remoteItems: Map<String, SyncableItem<T>>,
        isInitialSync: Boolean = false
    ): Map<String, T> {
        // Anti-wipe protection: If remote is empty during initial sync, preserve local
        if (isInitialSync && remoteItems.isEmpty() && localItems.isNotEmpty()) {
            return localItems.filterValues { !it.isDeleted }.mapValues { it.value.data }
        }

        val allKeys = localItems.keys + remoteItems.keys
        val result = mutableMapOf<String, T>()

        for (key in allKeys) {
            val local = localItems[key]
            val remote = remoteItems[key]
            val resolved = resolveConflict(local, remote)
            if (resolved != null && !resolved.isDeleted) {
                result[key] = resolved.data
            }
        }

        return result
    }
}
