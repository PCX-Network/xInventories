package sh.pcx.xinventories.internal.service

import sh.pcx.xinventories.PluginContext
import sh.pcx.xinventories.api.model.MigrationError
import sh.pcx.xinventories.api.model.MigrationReport
import sh.pcx.xinventories.api.model.StorageType
import sh.pcx.xinventories.internal.storage.MySqlStorage
import sh.pcx.xinventories.internal.storage.SqliteStorage
import sh.pcx.xinventories.internal.storage.Storage
import sh.pcx.xinventories.internal.storage.YamlStorage
import sh.pcx.xinventories.internal.util.Logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service for migrating data between storage backends.
 */
class MigrationService(
    private val plugin: PluginContext,
    private val storageService: StorageService
) {
    private val migrationInProgress = AtomicBoolean(false)

    /**
     * Checks if a migration is currently in progress.
     */
    fun isMigrationInProgress(): Boolean = migrationInProgress.get()

    /**
     * Migrates data from one storage backend to another.
     *
     * @param from Source storage type
     * @param to Target storage type
     * @return MigrationReport with results
     */
    suspend fun migrate(from: StorageType, to: StorageType): Result<MigrationReport> {
        if (from == to) {
            return Result.failure(IllegalArgumentException("Source and target storage types are the same"))
        }

        if (!migrationInProgress.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("Migration already in progress"))
        }

        val startTime = Instant.now()
        val errors = mutableListOf<MigrationError>()
        var playersProcessed = 0
        var entriesMigrated = 0

        try {
            Logging.info("Starting migration: $from -> $to")

            // Saves still waiting in the write-behind cache exist only in memory; persist them to
            // the running backend first or the migration silently leaves them behind.
            val flushed = storageService.flushDirtyEntries()
            if (flushed > 0) {
                Logging.info("Flushed $flushed pending saves before migrating")
            }

            // Create source storage
            val sourceStorage = createStorage(from)
            sourceStorage.initialize()

            // Create target storage
            val targetStorage = createStorage(to)
            targetStorage.initialize()

            try {
                // Get all player UUIDs from source
                val playerUUIDs = sourceStorage.getAllPlayerUUIDs()
                Logging.info("Found ${playerUUIDs.size} players to migrate")

                for (uuid in playerUUIDs) {
                    try {
                        // Load all data for player
                        val playerData = sourceStorage.loadAllPlayerData(uuid)

                        if (playerData.isNotEmpty()) {
                            // Save to target
                            val savedCount = targetStorage.savePlayerDataBatch(playerData.values.toList())
                            entriesMigrated += savedCount
                            playersProcessed++

                            if (playersProcessed % 100 == 0) {
                                Logging.info("Migration progress: $playersProcessed players processed")
                            }
                        }
                    } catch (e: Exception) {
                        errors.add(MigrationError(
                            uuid = uuid,
                            group = null,
                            message = "Failed to migrate player data: ${e.message}",
                            exception = e
                        ))
                        Logging.error("Failed to migrate data for $uuid", e)
                    }
                }

                Logging.info("Migration complete: $playersProcessed players, $entriesMigrated entries")
            } finally {
                // Shutdown storages
                if (sourceStorage !== storageService.storage) {
                    sourceStorage.shutdown()
                }
                if (targetStorage !== storageService.storage) {
                    targetStorage.shutdown()
                }
            }

            val endTime = Instant.now()
            val report = MigrationReport(
                from = from,
                to = to,
                playersProcessed = playersProcessed,
                entriesMigrated = entriesMigrated,
                errors = errors,
                startTime = startTime,
                endTime = endTime
            )

            return Result.success(report)
        } catch (e: Exception) {
            Logging.error("Migration failed", e)
            return Result.failure(e)
        } finally {
            migrationInProgress.set(false)
        }
    }

    /**
     * Creates a storage instance for the given type.
     */
    private fun createStorage(type: StorageType): Storage {
        val config = plugin.configManager.mainConfig

        return when (type) {
            StorageType.YAML -> YamlStorage(plugin)
            StorageType.SQLITE -> SqliteStorage(plugin, config.storage.sqlite.file)
            StorageType.MYSQL -> MySqlStorage(plugin, config.storage.mysql)
        }
    }

    /**
     * Validates storage configuration for migration.
     */
    fun validateStorageConfig(type: StorageType): Result<Unit> {
        val config = plugin.configManager.mainConfig

        return when (type) {
            StorageType.YAML -> Result.success(Unit)

            StorageType.SQLITE -> {
                val file = config.storage.sqlite.file
                if (file.isBlank()) {
                    Result.failure(IllegalArgumentException("SQLite file path not configured"))
                } else {
                    Result.success(Unit)
                }
            }

            StorageType.MYSQL -> {
                val mysql = config.storage.mysql
                when {
                    mysql.host.isBlank() -> Result.failure(IllegalArgumentException("MySQL host not configured"))
                    mysql.database.isBlank() -> Result.failure(IllegalArgumentException("MySQL database not configured"))
                    else -> Result.success(Unit)
                }
            }
        }
    }

    /**
     * Tests connection to a storage backend.
     */
    suspend fun testConnection(type: StorageType): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val storage = createStorage(type)
                storage.initialize()

                val healthy = storage.isHealthy()
                storage.shutdown()

                if (healthy) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Storage not healthy"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
