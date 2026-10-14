package sh.pcx.xinventories.internal.service

import sh.pcx.xinventories.PluginContext
import sh.pcx.xinventories.internal.config.*
import sh.pcx.xinventories.internal.util.Logging
import kotlinx.coroutines.CoroutineScope

/**
 * Dependency injection container for all services.
 */
class ServiceManager(
    private val plugin: PluginContext,
    private val scope: CoroutineScope
) {
    // Services
    lateinit var messageService: MessageService
        private set

    lateinit var groupService: GroupService
        private set

    lateinit var storageService: StorageService
        private set

    lateinit var inventoryService: InventoryService
        private set

    lateinit var backupService: BackupService
        private set

    lateinit var migrationService: MigrationService
        private set

    lateinit var conditionEvaluator: ConditionEvaluator
        private set

    lateinit var lockingService: LockingService
        private set

    // Data Foundation services
    lateinit var versioningService: VersioningService
        private set

    lateinit var deathRecoveryService: DeathRecoveryService
        private set

    // Content Control services
    lateinit var templateService: TemplateService
        private set

    lateinit var restrictionService: RestrictionService
        private set

    lateinit var sharedSlotService: SharedSlotService
        private set

    // External Integration services
    lateinit var economyService: EconomyService
        private set

    lateinit var importService: sh.pcx.xinventories.internal.import.ImportService
        private set

    // Quality of Life services
    lateinit var mergeService: MergeService
        private set

    lateinit var exportService: ExportService
        private set

    // Admin Tools services
    var auditService: AuditService? = null
        private set

    lateinit var bulkOperationService: BulkOperationService
        private set

    lateinit var metricsService: MetricsService
        private set

    lateinit var antiDupeService: AntiDupeService
        private set

    // Advanced Features services
    lateinit var nbtFilterService: NBTFilterService
        private set

    lateinit var expirationService: ExpirationService
        private set

    lateinit var temporaryGroupService: TemporaryGroupService
        private set

    // Sync service (nullable - only initialized when sync is enabled)
    var syncService: SyncService? = null
        private set

    // Sync config (for command access)
    var syncConfig: SyncConfig? = null
        private set

    private var initialized = false

    /**
     * Initializes all services.
     */
    suspend fun initialize() {
        if (initialized) {
            Logging.warning("ServiceManager already initialized")
            return
        }

        Logging.debug { "Initializing services..." }

        // Initialize services in dependency order

        // Message service (no dependencies)
        messageService = MessageService(plugin)
        Logging.debug { "MessageService initialized" }

        // Group service (no dependencies)
        groupService = GroupService(plugin)
        groupService.initialize()
        Logging.debug { "GroupService initialized" }

        // Storage service (no service dependencies)
        storageService = StorageService(plugin, scope)
        storageService.initialize()
        Logging.debug { "StorageService initialized" }

        // Inventory service (depends on storage, group, message)
        inventoryService = InventoryService(
            plugin, scope, storageService, groupService, messageService
        )
        Logging.debug { "InventoryService initialized" }

        // Backup service (depends on storage)
        backupService = BackupService(plugin, scope, storageService)
        backupService.initialize()
        Logging.debug { "BackupService initialized" }

        // Migration service (depends on storage)
        migrationService = MigrationService(plugin, storageService)
        Logging.debug { "MigrationService initialized" }

        // Condition evaluator (no dependencies)
        conditionEvaluator = ConditionEvaluator(plugin)
        Logging.debug { "ConditionEvaluator initialized" }

        // Locking service (depends on scope)
        lockingService = LockingService(plugin, scope)
        lockingService.initialize()
        Logging.debug { "LockingService initialized" }

        // Data Foundation services

        // Versioning service (depends on storage)
        versioningService = VersioningService(plugin, scope, storageService)
        versioningService.initialize()
        Logging.debug { "VersioningService initialized" }

        // Death recovery service (depends on storage)
        deathRecoveryService = DeathRecoveryService(plugin, scope, storageService)
        deathRecoveryService.initialize()
        Logging.debug { "DeathRecoveryService initialized" }

        // Content Control services

        // Template service (depends on scope)
        templateService = TemplateService(plugin, scope)
        templateService.initialize()
        Logging.debug { "TemplateService initialized" }

        // Restriction service (depends on message service)
        restrictionService = RestrictionService(plugin, scope, messageService)
        restrictionService.initialize()
        Logging.debug { "RestrictionService initialized" }

        // Shared slot service (depends on scope)
        sharedSlotService = SharedSlotService(plugin, scope)
        val sharedSlotsConfig = plugin.configManager.mainConfig.sharedSlots.toSharedSlotsConfig()
        sharedSlotService.initialize(sharedSlotsConfig)
        Logging.debug { "SharedSlotService initialized" }

        // External Integration services

        // Economy service (depends on storage, group service, Vault soft dependency)
        economyService = EconomyService(plugin, storageService, groupService)
        economyService.initialize()
        Logging.debug { "EconomyService initialized" }

        // Import service (depends on storage, group service, economy service)
        importService = sh.pcx.xinventories.internal.import.ImportService(plugin, scope, storageService, groupService, economyService)
        importService.initialize()
        Logging.debug { "ImportService initialized" }

        // Quality of Life services

        // Merge service (depends on storage)
        mergeService = MergeService(plugin, scope, storageService)
        mergeService.initialize()
        Logging.debug { "MergeService initialized" }

        // Export service (depends on storage)
        exportService = ExportService(plugin, scope, storageService)
        exportService.initialize()
        Logging.debug { "ExportService initialized" }

        // Admin Tools services

        // Metrics service (no dependencies)
        metricsService = MetricsService(plugin, scope)
        metricsService.initialize()
        Logging.debug { "MetricsService initialized" }

        // Audit service (depends on scope)
        auditService = AuditService(plugin, scope)
        auditService?.initialize()
        Logging.debug { "AuditService initialized" }

        // Bulk operation service (depends on storage, template service, audit service)
        bulkOperationService = BulkOperationService(plugin, scope)
        Logging.debug { "BulkOperationService initialized" }

        // Anti-dupe service (depends on scope)
        antiDupeService = AntiDupeService(plugin, scope)
        antiDupeService.initialize()
        Logging.debug { "AntiDupeService initialized" }

        // Advanced Features services

        // NBT filter service (depends on message service)
        nbtFilterService = NBTFilterService(plugin, scope, messageService)
        nbtFilterService.initialize()
        Logging.debug { "NBTFilterService initialized" }

        // Expiration service (depends on storage, backup service)
        expirationService = ExpirationService(plugin, scope, storageService, backupService)
        expirationService.initialize()
        Logging.debug { "ExpirationService initialized" }

        // Temporary group service (depends on storage, group, inventory, message)
        temporaryGroupService = TemporaryGroupService(
            plugin, scope, storageService, groupService, inventoryService, messageService
        )
        temporaryGroupService.initialize()
        Logging.debug { "TemporaryGroupService initialized" }

        // Sync service (optional - depends on config)
        initializeSyncService()

        initialized = true
        Logging.info("All services initialized")
    }

    /**
     * Initializes the sync service if enabled in configuration.
     */
    private suspend fun initializeSyncService() {
        val mainConfig = plugin.configManager.mainConfig.sync

        if (!mainConfig.enabled) {
            Logging.info("Network sync is disabled")
            return
        }

        // Convert config to internal sync config
        // Use fully qualified names due to SyncMode enum existing in both config and service packages
        val internalSyncConfig = sh.pcx.xinventories.internal.service.SyncConfig(
            enabled = mainConfig.enabled,
            mode = when (mainConfig.mode) {
                sh.pcx.xinventories.internal.config.SyncMode.REDIS -> sh.pcx.xinventories.internal.service.SyncMode.REDIS
                sh.pcx.xinventories.internal.config.SyncMode.PLUGIN_MESSAGING -> sh.pcx.xinventories.internal.service.SyncMode.PLUGIN_MESSAGING
                sh.pcx.xinventories.internal.config.SyncMode.MYSQL_NOTIFY -> sh.pcx.xinventories.internal.service.SyncMode.MYSQL_NOTIFY
            },
            serverId = mainConfig.serverId,
            redis = RedisConfig(
                host = mainConfig.redis.host,
                port = mainConfig.redis.port,
                password = mainConfig.redis.password,
                channel = mainConfig.redis.channel,
                timeout = mainConfig.redis.timeout
            ),
            conflicts = ConflictConfig(
                strategy = mainConfig.conflicts.strategy,
                mergeRules = mainConfig.conflicts.mergeRules.toMergeRules()
            ),
            transferLock = TransferLockConfig(
                enabled = mainConfig.transferLock.enabled,
                timeoutSeconds = mainConfig.transferLock.timeoutSeconds
            ),
            heartbeat = HeartbeatConfig(
                intervalSeconds = mainConfig.heartbeat.intervalSeconds,
                timeoutSeconds = mainConfig.heartbeat.timeoutSeconds
            )
        )

        syncConfig = internalSyncConfig

        when (mainConfig.mode) {
            sh.pcx.xinventories.internal.config.SyncMode.REDIS -> {
                val redisSyncService = RedisSyncService(plugin, scope, internalSyncConfig)
                if (redisSyncService.initialize()) {
                    syncService = redisSyncService

                    // Register cache invalidation handler
                    redisSyncService.onCacheInvalidate { uuid, group ->
                        if (group != null) {
                            storageService.invalidateCache(uuid, group, null)
                        } else {
                            storageService.invalidateCache(uuid)
                        }
                        Logging.debug { "Cache invalidated for $uuid (group: $group) via sync" }
                    }

                    Logging.info("Redis sync service initialized")
                } else {
                    Logging.error("Failed to initialize Redis sync service")
                }
            }
            sh.pcx.xinventories.internal.config.SyncMode.PLUGIN_MESSAGING -> {
                Logging.warning("Plugin messaging sync mode is not yet implemented")
            }
            sh.pcx.xinventories.internal.config.SyncMode.MYSQL_NOTIFY -> {
                Logging.warning("MySQL notify sync mode is not yet implemented")
            }
        }
    }

    /**
     * Shuts down all services.
     */
    suspend fun shutdown() {
        if (!initialized) return

        Logging.debug { "Shutting down services..." }

        // Shutdown in reverse order

        // Sync service (if enabled)
        try {
            syncService?.shutdown()
            syncService = null
            Logging.debug { "SyncService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down SyncService", e)
        }

        // Temporary group service
        try {
            temporaryGroupService.shutdown()
            Logging.debug { "TemporaryGroupService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down TemporaryGroupService", e)
        }

        // Expiration service
        try {
            expirationService.shutdown()
            Logging.debug { "ExpirationService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down ExpirationService", e)
        }

        // NBT filter service
        try {
            nbtFilterService.shutdown()
            Logging.debug { "NBTFilterService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down NBTFilterService", e)
        }

        // Anti-dupe service
        try {
            antiDupeService.shutdown()
            Logging.debug { "AntiDupeService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down AntiDupeService", e)
        }

        // Restriction service
        try {
            restrictionService.shutdown()
            Logging.debug { "RestrictionService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down RestrictionService", e)
        }

        // Audit service
        try {
            auditService?.shutdown()
            Logging.debug { "AuditService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down AuditService", e)
        }

        // Metrics service
        try {
            metricsService.shutdown()
            Logging.debug { "MetricsService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down MetricsService", e)
        }

        // Export service
        try {
            exportService.shutdown()
            Logging.debug { "ExportService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down ExportService", e)
        }

        // Merge service
        try {
            mergeService.shutdown()
            Logging.debug { "MergeService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down MergeService", e)
        }

        // Template service
        try {
            templateService.shutdown()
            Logging.debug { "TemplateService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down TemplateService", e)
        }

        // Locking service
        try {
            lockingService.shutdown()
            Logging.debug { "LockingService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down LockingService", e)
        }

        // Death recovery service
        try {
            deathRecoveryService.shutdown()
            Logging.debug { "DeathRecoveryService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down DeathRecoveryService", e)
        }

        // Versioning service
        try {
            versioningService.shutdown()
            Logging.debug { "VersioningService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down VersioningService", e)
        }

        // Backup service
        try {
            backupService.shutdown()
            Logging.debug { "BackupService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down BackupService", e)
        }

        // Storage service (flushes cache, closes connections)
        try {
            storageService.shutdown()
            Logging.debug { "StorageService shut down" }
        } catch (e: Exception) {
            Logging.error("Error shutting down StorageService", e)
        }

        initialized = false
        Logging.info("All services shut down")
    }

    /**
     * Reloads services that support hot-reloading.
     */
    fun reload() {
        Logging.debug { "Reloading services..." }

        // Reload group service
        groupService.reload()

        // Reload condition evaluator
        conditionEvaluator.reload()

        // Reload locking service
        lockingService.reload()

        // Reload template service
        // templateService.reload() is called by TemplateCommand when needed

        // Reload audit service
        auditService?.reload()

        // Reload anti-dupe service
        antiDupeService.reload()

        // Reload NBT filter service
        nbtFilterService.reload()

        // Reload expiration service
        expirationService.reload()

        // Storage backend changes are applied by ReloadCommand through
        // StorageService.switchToConfiguredBackend() (suspending, off the main thread).

        Logging.info("Services reloaded")
    }

    /**
     * Checks if all services are initialized.
     */
    fun isInitialized(): Boolean = initialized

    /**
     * Gets service health status.
     */
    suspend fun getHealthStatus(): Map<String, Boolean> {
        val healthMap = mutableMapOf(
            "storage" to storageService.isHealthy(),
            "cache" to storageService.cache.isEnabled(),
            "groups" to (groupService.getAllGroups().isNotEmpty())
        )

        // Add sync health if enabled
        syncService?.let {
            healthMap["sync"] = it.isEnabled
        }

        return healthMap
    }
}
