package sh.pcx.xinventories.internal.service

import sh.pcx.xinventories.PluginContext
import sh.pcx.xinventories.api.model.StorageType
import sh.pcx.xinventories.internal.cache.PlayerDataCache
import sh.pcx.xinventories.internal.model.PlayerData
import sh.pcx.xinventories.internal.storage.MySqlStorage
import sh.pcx.xinventories.internal.storage.SqliteStorage
import sh.pcx.xinventories.internal.storage.Storage
import sh.pcx.xinventories.internal.storage.YamlStorage
import sh.pcx.xinventories.internal.util.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.GameMode
import java.util.UUID

/**
 * Service orchestrating storage operations with caching.
 */
class StorageService(
    private val plugin: PluginContext,
    private val scope: CoroutineScope
) {
    lateinit var storage: Storage
        private set

    lateinit var cache: PlayerDataCache
        private set

    private var writeBehindJob: Job? = null
    private var initialized = false

    val storageType: StorageType
        get() = plugin.configManager.mainConfig.storage.type

    /**
     * Initializes the storage service.
     */
    suspend fun initialize() {
        if (initialized) return

        val config = plugin.configManager.mainConfig

        // Initialize cache
        cache = PlayerDataCache(config.cache)

        // Initialize storage backend
        storage = when (config.storage.type) {
            StorageType.YAML -> YamlStorage(plugin)
            StorageType.SQLITE -> SqliteStorage(plugin, config.storage.sqlite.file)
            StorageType.MYSQL -> MySqlStorage(plugin, config.storage.mysql)
        }

        storage.initialize()

        // Start write-behind job if caching is enabled
        if (config.cache.enabled && config.cache.writeBehindSeconds > 0) {
            startWriteBehind()
        }

        initialized = true
        Logging.info("Storage service initialized with ${config.storage.type} backend")
    }

    /**
     * Shuts down the storage service.
     */
    suspend fun shutdown() {
        if (!initialized) return

        // Stop write-behind
        writeBehindJob?.cancel()
        writeBehindJob = null

        // Flush dirty cache entries
        flushDirtyEntries()

        // Shutdown storage
        storage.shutdown()

        initialized = false
        Logging.info("Storage service shut down")
    }

    /**
     * Saves player data.
     */
    suspend fun savePlayerData(data: PlayerData, useCache: Boolean = true): Boolean {
        // Increment version for sync conflict detection
        data.version++

        if (useCache && cache.isEnabled()) {
            cache.put(data, markDirty = true)

            // If async saving is disabled, save immediately
            if (!plugin.configManager.mainConfig.features.asyncSaving) {
                return storage.savePlayerData(data).also {
                    if (it) {
                        cache.markClean(data.uuid, data.group, data.gameMode)
                        // Broadcast update to other servers if sync is enabled
                        broadcastDataUpdate(data)
                    }
                }
            }

            // Async saving: the data is cached and marked dirty, normally flushed by the write-behind
            // job. But if write-behind is NOT running (e.g. cache.writeBehindSeconds <= 0), nothing
            // would ever persist it until shutdown - so kick off a background write ourselves to
            // avoid silently losing the save on a crash/restart.
            if (!isWriteBehindActive()) {
                scope.launch {
                    try {
                        if (storage.savePlayerData(data)) {
                            cache.markClean(data.uuid, data.group, data.gameMode)
                            broadcastDataUpdate(data)
                        }
                    } catch (e: Exception) {
                        Logging.error("Async persist failed for ${data.uuid} in group ${data.group}", e)
                    }
                }
            }

            return true
        }

        val success = storage.savePlayerData(data)
        if (success) {
            // Broadcast update to other servers if sync is enabled
            broadcastDataUpdate(data)
        }
        return success
    }

    /**
     * Broadcasts a data update notification to other servers via sync service.
     */
    private fun broadcastDataUpdate(data: PlayerData) {
        val syncService = plugin.serviceManager.syncService
        if (syncService != null && syncService.isEnabled) {
            syncService.broadcastUpdate(data.uuid, data.group, data.version)
            Logging.debug { "Broadcast sync update for ${data.uuid} in group ${data.group}, version ${data.version}" }
        }
    }

    /**
     * Saves player data immediately (bypasses write-behind).
     */
    suspend fun savePlayerDataImmediate(data: PlayerData): Boolean {
        // Increment version for sync conflict detection
        data.version++

        val success = storage.savePlayerData(data)
        if (success) {
            if (cache.isEnabled()) {
                cache.put(data, markDirty = false)
            }
            // Broadcast update to other servers if sync is enabled
            broadcastDataUpdate(data)
        }
        return success
    }

    /**
     * Loads player data.
     */
    suspend fun loadPlayerData(uuid: UUID, group: String, gameMode: GameMode?): PlayerData? {
        return cache.getOrLoad(uuid, group, gameMode) {
            storage.loadPlayerData(uuid, group, gameMode)
        }
    }

    /**
     * Loads all player data for a player.
     */
    suspend fun loadAllPlayerData(uuid: UUID): Map<String, PlayerData> {
        // Check cache first
        if (cache.isEnabled()) {
            val cached = cache.getAllForPlayer(uuid)
            if (cached.isNotEmpty()) {
                // Load any missing from storage and merge
                val fromStorage = storage.loadAllPlayerData(uuid)
                val merged = cached.toMutableMap()

                fromStorage.forEach { (key, data) ->
                    if (!merged.containsKey(key)) {
                        merged[key] = data
                        cache.put(data)
                    }
                }

                return merged
            }
        }

        // Load from storage
        val data = storage.loadAllPlayerData(uuid)

        // Populate cache
        if (cache.isEnabled()) {
            data.values.forEach { cache.put(it) }
        }

        return data
    }

    /**
     * Deletes player data.
     */
    suspend fun deletePlayerData(uuid: UUID, group: String, gameMode: GameMode?): Boolean {
        cache.invalidate(uuid, group, gameMode)
        return storage.deletePlayerData(uuid, group, gameMode)
    }

    /**
     * Deletes all player data.
     */
    suspend fun deleteAllPlayerData(uuid: UUID): Int {
        cache.invalidatePlayer(uuid)
        return storage.deleteAllPlayerData(uuid)
    }

    /**
     * Checks if player data exists.
     */
    suspend fun hasPlayerData(uuid: UUID, group: String, gameMode: GameMode?): Boolean {
        if (cache.contains(uuid, group, gameMode)) {
            return true
        }
        return storage.hasPlayerData(uuid, group, gameMode)
    }

    /**
     * Convenience method to check if player has any data for a group.
     */
    suspend fun hasData(uuid: UUID, group: String): Boolean {
        return hasPlayerData(uuid, group, null) ||
               hasPlayerData(uuid, group, GameMode.SURVIVAL) ||
               hasPlayerData(uuid, group, GameMode.CREATIVE)
    }

    /**
     * Alias for hasPlayerData without requiring GameMode for import compatibility.
     * Checks if any data exists for the player in the group.
     */
    suspend fun hasPlayerData(uuid: UUID, group: String): Boolean {
        return hasData(uuid, group)
    }

    /**
     * Convenience alias for loadPlayerData.
     */
    suspend fun load(uuid: UUID, group: String, gameMode: GameMode?): PlayerData? {
        return loadPlayerData(uuid, group, gameMode)
    }

    /**
     * Gets all player UUIDs.
     */
    suspend fun getAllPlayerUUIDs(): Set<UUID> = storage.getAllPlayerUUIDs()

    /**
     * Gets groups for a player.
     */
    suspend fun getPlayerGroups(uuid: UUID): Set<String> = storage.getPlayerGroups(uuid)

    /**
     * Saves multiple player data in a batch.
     */
    suspend fun savePlayerDataBatch(dataList: List<PlayerData>): Int {
        val count = storage.savePlayerDataBatch(dataList)

        // Update cache
        if (cache.isEnabled()) {
            dataList.forEach { data ->
                cache.put(data, markDirty = false)
            }
        }

        return count
    }

    /**
     * Invalidates cache for a player.
     */
    fun invalidateCache(uuid: UUID) {
        cache.invalidatePlayer(uuid)
    }

    /**
     * Invalidates specific cache entry.
     */
    fun invalidateCache(uuid: UUID, group: String, gameMode: GameMode?) {
        cache.invalidate(uuid, group, gameMode)
    }

    /**
     * Clears all cache entries.
     */
    fun clearCache(): Int = cache.clear()

    /**
     * Gets cache statistics.
     */
    fun getCacheStats() = cache.getStats()

    /**
     * Gets storage entry count.
     */
    suspend fun getEntryCount(): Int = storage.getEntryCount()

    /**
     * Gets storage size.
     */
    suspend fun getStorageSize(): Long = storage.getStorageSize()

    /**
     * Checks storage health.
     */
    suspend fun isHealthy(): Boolean = storage.isHealthy()

    /**
     * Flushes all dirty cache entries to storage.
     */
    suspend fun flushDirtyEntries(): Int {
        val dirty = cache.getDirtyEntries()
        if (dirty.isEmpty()) return 0

        Logging.debug { "Flushing ${dirty.size} dirty cache entries" }

        // NOTE: each entry's version was already incremented by savePlayerData when it was marked
        // dirty. Do NOT increment again here, or persisted versions drift ahead of reality and trip
        // sync conflict detection.

        val savedCount = storage.savePlayerDataBatch(dirty)

        if (savedCount == dirty.size) {
            // Full success: mark clean and broadcast updates.
            dirty.forEach { data ->
                cache.markClean(data.uuid, data.group, data.gameMode)
                // Broadcast update to other servers if sync is enabled
                broadcastDataUpdate(data)
            }
        } else {
            // Partial/total failure. savePlayerDataBatch reports only a count, not which entries
            // failed, so keep them ALL dirty for the next flush rather than marking them clean and
            // silently dropping whatever did not persist. Re-saving the ones that succeeded is
            // harmless (idempotent upsert).
            Logging.warning(
                "Write-behind flush persisted $savedCount/${dirty.size} entries; " +
                    "keeping the remainder dirty for retry"
            )
        }

        return savedCount
    }

    /**
     * Whether the background write-behind job is currently running. When false, async saves must
     * persist themselves rather than relying on a flush that will never happen.
     */
    private fun isWriteBehindActive(): Boolean = writeBehindJob?.isActive == true

    /**
     * Starts the write-behind background job.
     */
    private fun startWriteBehind() {
        writeBehindJob = scope.launch {
            while (isActive) {
                delay(cache.writeBehindDelayMs)

                try {
                    val flushed = flushDirtyEntries()
                    if (flushed > 0) {
                        Logging.debug { "Write-behind: saved $flushed entries" }
                    }
                } catch (e: Exception) {
                    Logging.error("Write-behind error", e)
                }
            }
        }

        Logging.debug { "Write-behind job started (delay: ${cache.writeBehindDelayMs}ms)" }
    }

}
