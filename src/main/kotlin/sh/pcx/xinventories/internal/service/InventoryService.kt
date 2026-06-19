package sh.pcx.xinventories.internal.service

import sh.pcx.xinventories.PluginContext
import sh.pcx.xinventories.api.event.GroupChangeEvent
import sh.pcx.xinventories.api.event.InventoryLoadEvent
import sh.pcx.xinventories.api.event.InventorySaveEvent
import sh.pcx.xinventories.api.event.InventorySwitchEvent
import sh.pcx.xinventories.api.model.GroupSettings
import sh.pcx.xinventories.api.model.InventoryGroup
import sh.pcx.xinventories.api.model.PlayerInventorySnapshot
import sh.pcx.xinventories.internal.model.Group
import sh.pcx.xinventories.internal.model.PlayerData
import sh.pcx.xinventories.internal.model.RestrictionAction
import sh.pcx.xinventories.internal.model.RestrictionConfig
import sh.pcx.xinventories.internal.model.VersionTrigger
import sh.pcx.xinventories.internal.util.Logging
import sh.pcx.xinventories.internal.util.SchedulerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Core service for inventory operations.
 */
class InventoryService(
    private val plugin: PluginContext,
    private val scope: CoroutineScope,
    private val storageService: StorageService,
    private val groupService: GroupService,
    private val messageService: MessageService
) {
    // Track current group for online players
    private val playerGroups = ConcurrentHashMap<UUID, String>()

    // Track active snapshots for online players
    private val activeSnapshots = ConcurrentHashMap<UUID, PlayerInventorySnapshot>()

    // Runtime bypasses (in addition to permission-based)
    private val bypasses = ConcurrentHashMap<UUID, MutableSet<String?>>()

    private val config get() = plugin.configManager.mainConfig

    /**
     * Handles player join - loads their inventory for the current world.
     */
    suspend fun handlePlayerJoin(player: Player) {
        if (hasBypass(player)) {
            Logging.debug { "Player ${player.name} has bypass, skipping inventory load" }
            return
        }

        // Acquire distributed lock if sync is enabled
        val syncService = plugin.serviceManager.syncService
        if (syncService != null && syncService.isEnabled) {
            val lockTimeout = plugin.configManager.mainConfig.sync.transferLock.timeoutSeconds * 1000L
            val acquired = syncService.acquireLock(player.uniqueId, lockTimeout)
            if (!acquired) {
                Logging.warning("Failed to acquire lock for ${player.name} on join, proceeding anyway")
                // Continue anyway - better to have potential conflict than to block the player
            }
        }

        val group = groupService.getGroupForWorld(player.world)
        playerGroups[player.uniqueId] = group.name

        // Load inventory
        loadInventory(player, group.toApiModel(), InventoryLoadEvent.LoadReason.JOIN)

        // Handle template application on join
        handleTemplateOnGroupEntry(player, group)

        // Apply shared slot enforced items
        plugin.serviceManager.sharedSlotService.applyEnforcedItems(player)
    }

    /**
     * Handles player quit - saves their inventory.
     *
     * @param capturedData an inventory snapshot captured synchronously at quit time via
     *   [capturePlayerData]. Strongly preferred: it guarantees the saved inventory reflects the
     *   player's state at the moment they quit, rather than a possibly-empty read on a later tick.
     */
    suspend fun handlePlayerQuit(player: Player, capturedData: PlayerData? = null) {
        try {
            if (hasBypass(player)) {
                Logging.debug { "Player ${player.name} has bypass, skipping inventory save" }
                return
            }

            val groupName = capturedData?.group ?: playerGroups[player.uniqueId]
                ?: groupService.getGroupForWorld(player.world).name

            // Create inventory version on disconnect if enabled
            if (config.versioning.enabled && config.versioning.triggerOn.disconnect) {
                try {
                    plugin.serviceManager.versioningService.createVersion(
                        player,
                        VersionTrigger.DISCONNECT,
                        mapOf("reason" to "player_disconnect")
                    )
                } catch (e: Exception) {
                    Logging.error("Failed to create version on disconnect for ${player.name}", e)
                }
            }

            // Clear version tracking for this player
            plugin.serviceManager.versioningService.clearPlayerTracking(player.uniqueId)

            // Save current inventory. Prefer the snapshot captured synchronously at quit time so we
            // never persist an empty/partial inventory read after the player has been removed.
            saveInventory(player, groupName, preCaptured = capturedData)
        } finally {
            // Always release the distributed lock and clean up - even if saving threw - otherwise a
            // failed save would leave the player's data locked across the network indefinitely.
            val syncService = plugin.serviceManager.syncService
            if (syncService != null && syncService.isEnabled) {
                syncService.releaseLock(player.uniqueId)
            }
            plugin.serviceManager.sharedSlotService.cleanup(player.uniqueId)
            playerGroups.remove(player.uniqueId)
            activeSnapshots.remove(player.uniqueId)
        }
    }

    /**
     * Handles world change.
     */
    suspend fun handleWorldChange(player: Player, fromWorldName: String, toWorldName: String) {
        if (hasBypass(player)) return

        val fromGroup = groupService.getGroupForWorld(fromWorldName)
        val toGroup = groupService.getGroupForWorld(toWorldName)

        // Same group - no switch needed
        if (fromGroup.name == toGroup.name) {
            if (config.features.saveOnWorldChange) {
                saveInventory(player, fromGroup.name)
            }
            return
        }

        // Switch inventories
        switchInventory(
            player,
            fromGroup.toApiModel(),
            toGroup.toApiModel(),
            InventorySwitchEvent.SwitchReason.WORLD_CHANGE
        )
    }

    /**
     * Handles gamemode change.
     */
    suspend fun handleGameModeChange(player: Player, oldGameMode: GameMode, newGameMode: GameMode) {
        if (hasBypass(player)) return

        val groupName = playerGroups[player.uniqueId] ?: return
        val group = groupService.getGroup(groupName) ?: return

        // Check if this group separates gamemode inventories
        if (!group.settings.separateGameModeInventories) return

        if (config.features.saveOnGamemodeChange) {
            // Save current inventory with old gamemode
            val data = PlayerData.fromPlayer(player, groupName)
            data.gameMode = oldGameMode
            // Load extended data (statistics, advancements, recipes) if enabled
            data.loadFromPlayerExtended(player, group.settings)
            storageService.savePlayerData(data)
        }

        // Load inventory for new gamemode
        val newData = storageService.loadPlayerData(player.uniqueId, groupName, newGameMode)

        if (newData != null) {
            applyInventory(player, newData, group.settings)
            activeSnapshots[player.uniqueId] = newData.toSnapshot()
        } else {
            // Clear inventory for new gamemode
            clearPlayerInventory(player, group.settings)
        }
    }

    /**
     * Synchronously captures a player's current inventory for saving.
     *
     * MUST be called on the main thread (e.g. directly inside `PlayerQuitEvent`) while the player is
     * still fully valid. Persisting the returned data can then happen asynchronously without risking
     * an empty/partial save from reading the player after they have been removed. Returns null if
     * the player is bypassed or their group can't be resolved.
     */
    fun capturePlayerData(player: Player, groupName: String? = null): PlayerData? {
        if (hasBypass(player)) return null
        val group = groupName ?: playerGroups[player.uniqueId]
            ?: groupService.getGroupForWorld(player.world).name
        val groupObj = groupService.getGroup(group) ?: return null
        return PlayerData.fromPlayer(player, group).also {
            it.loadFromPlayerExtended(player, groupObj.settings)
        }
    }

    /**
     * Saves a player's current inventory.
     *
     * @param preCaptured an inventory snapshot already captured synchronously (see [capturePlayerData]).
     *   When provided it is used verbatim instead of reading the (possibly offline) player again -
     *   this is how quit saves avoid losing items.
     */
    suspend fun saveInventory(player: Player, groupName: String? = null, preCaptured: PlayerData? = null): Boolean {
        val group = preCaptured?.group ?: groupName ?: playerGroups[player.uniqueId]
            ?: groupService.getGroupForWorld(player.world).name

        val groupObj = groupService.getGroup(group) ?: return false
        val data = preCaptured ?: PlayerData.fromPlayer(player, group).also {
            // Load extended data (statistics, advancements, recipes) if enabled
            it.loadFromPlayerExtended(player, groupObj.settings)
        }

        // Fire event - always sync since we may be on main thread
        // Paper requires async events to be called from async threads
        val isAsync = !plugin.plugin.server.isPrimaryThread
        val event = InventorySaveEvent(
            player,
            groupObj.toApiModel(),
            data.toSnapshot(),
            isAsync
        )
        plugin.plugin.server.pluginManager.callEvent(event)

        val success = storageService.savePlayerData(data)

        if (success) {
            activeSnapshots[player.uniqueId] = data.toSnapshot()
            Logging.debug { "Saved inventory for ${player.name} in group $group" }
        }

        return success
    }

    /**
     * Loads inventory for a player.
     */
    suspend fun loadInventory(
        player: Player,
        group: InventoryGroup,
        reason: InventoryLoadEvent.LoadReason
    ): Boolean {
        val gameMode = if (group.settings.separateGameModeInventories) {
            player.gameMode
        } else {
            null
        }

        val data = storageService.loadPlayerData(player.uniqueId, group.name, gameMode)

        // Create empty data if none exists
        val snapshot = data?.toSnapshot()
            ?: PlayerInventorySnapshot.empty(player.uniqueId, player.name, group.name, player.gameMode)

        // Fire load event on main thread (Paper requires sync events on main thread)
        val eventResult = withContext(plugin.mainThreadDispatcher) {
            val event = InventoryLoadEvent(player, group, snapshot, reason)
            plugin.plugin.server.pluginManager.callEvent(event)
            if (event.isCancelled) null else event.snapshot
        }

        if (eventResult == null) {
            Logging.debug { "Inventory load cancelled for ${player.name}" }
            return false
        }

        // Apply to player (applyInventory already schedules on main thread)
        if (data != null) {
            applyInventory(player, data, group.settings)
        } else if (group.settings.clearOnJoin) {
            clearPlayerInventory(player, group.settings)
        }

        activeSnapshots[player.uniqueId] = eventResult
        playerGroups[player.uniqueId] = group.name

        Logging.debug { "Loaded inventory for ${player.name} in group ${group.name}" }
        return true
    }

    /**
     * Switches inventory between groups.
     */
    suspend fun switchInventory(
        player: Player,
        fromGroup: InventoryGroup,
        toGroup: InventoryGroup,
        reason: InventorySwitchEvent.SwitchReason
    ): Boolean {
        val currentSnapshot = activeSnapshots[player.uniqueId]
            ?: PlayerInventorySnapshot.fromPlayer(player, fromGroup.name)

        // Get internal group objects for restrictions
        val fromGroupInternal = groupService.getGroup(fromGroup.name)
        val toGroupInternal = groupService.getGroup(toGroup.name)

        // Check restrictions when leaving group (strip-on-exit)
        fromGroupInternal?.restrictions?.let { restrictions ->
            if (restrictions.isEnabled() || restrictions.stripOnExit.isNotEmpty()) {
                val allowed = checkAndHandleRestrictions(player, fromGroupInternal, restrictions, RestrictionAction.EXITING)
                if (!allowed) {
                    Logging.debug { "Inventory switch blocked by restrictions for ${player.name} (exiting ${fromGroup.name})" }
                    return false
                }
            }
        }

        // Check restrictions when entering group
        toGroupInternal?.restrictions?.let { restrictions ->
            if (restrictions.isEnabled()) {
                val allowed = checkAndHandleRestrictions(player, toGroupInternal, restrictions, RestrictionAction.ENTERING)
                if (!allowed) {
                    Logging.debug { "Inventory switch blocked by restrictions for ${player.name} (entering ${toGroup.name})" }
                    return false
                }
            }
        }

        // Fire switch event on main thread (Paper requires sync events on main thread)
        val switchEventResult = withContext(plugin.mainThreadDispatcher) {
            val event = InventorySwitchEvent(
                player,
                fromGroup,
                toGroup,
                player.world,
                player.world,
                currentSnapshot,
                reason
            )
            plugin.plugin.server.pluginManager.callEvent(event)
            if (event.isCancelled) null else event
        }

        if (switchEventResult == null) {
            Logging.debug { "Inventory switch cancelled for ${player.name}" }
            return false
        }

        // Create inventory version on world change if enabled
        if (config.versioning.enabled && config.versioning.triggerOn.worldChange) {
            try {
                plugin.serviceManager.versioningService.createVersion(
                    player,
                    VersionTrigger.WORLD_CHANGE,
                    mapOf(
                        "from_group" to fromGroup.name,
                        "to_group" to toGroup.name,
                        "reason" to "world_change"
                    )
                )
            } catch (e: Exception) {
                Logging.error("Failed to create version on world change for ${player.name}", e)
            }
        }

        // Preserve shared slots before saving
        plugin.serviceManager.sharedSlotService.preserveSharedSlots(player)

        // Save current inventory
        if (config.features.saveOnWorldChange) {
            val data = PlayerData.fromPlayer(player, fromGroup.name)
            // Load extended data (statistics, advancements, recipes) if enabled
            fromGroupInternal?.let { data.loadFromPlayerExtended(player, it.settings) }
            // Exclude shared slots from saved data
            plugin.serviceManager.sharedSlotService.excludeSharedSlotsFromData(data)
            storageService.savePlayerData(data)
        }

        // Fire group change event on main thread
        withContext(plugin.mainThreadDispatcher) {
            val groupChangeEvent = GroupChangeEvent(
                player,
                fromGroup,
                toGroup,
                GroupChangeEvent.ChangeReason.WORLD_CHANGE
            )
            plugin.plugin.server.pluginManager.callEvent(groupChangeEvent)

            // Signal LuckPerms context update (if available)
            plugin.hookManager.signalLuckPermsContextUpdate(player)
        }

        // Load new inventory (use override if provided)
        val loadedData = if (switchEventResult.overrideSnapshot != null) {
            PlayerData.fromSnapshot(switchEventResult.overrideSnapshot!!)
        } else {
            val gameMode = if (toGroup.settings.separateGameModeInventories) player.gameMode else null
            storageService.loadPlayerData(player.uniqueId, toGroup.name, gameMode)
        }

        // Apply new inventory
        if (loadedData != null) {
            applyInventory(player, loadedData, toGroup.settings)
            activeSnapshots[player.uniqueId] = loadedData.toSnapshot()
        } else if (toGroup.settings.clearOnJoin) {
            clearPlayerInventory(player, toGroup.settings)
            activeSnapshots[player.uniqueId] = PlayerInventorySnapshot.empty(
                player.uniqueId, player.name, toGroup.name, player.gameMode
            )
        }

        // Restore shared slots after loading new inventory
        withContext(plugin.mainThreadDispatcher) {
            plugin.serviceManager.sharedSlotService.restoreSharedSlots(player)
        }

        playerGroups[player.uniqueId] = toGroup.name

        // Notify player
        notifySwitch(player, fromGroup.name, toGroup.name)

        // Handle template application on group entry
        if (toGroupInternal != null) {
            handleTemplateOnGroupEntry(player, toGroupInternal)
        }

        Logging.debug { "Switched inventory for ${player.name}: ${fromGroup.name} -> ${toGroup.name}" }
        return true
    }

    /**
     * Gets the active snapshot for a player.
     */
    fun getActiveSnapshot(player: Player): PlayerInventorySnapshot? {
        return activeSnapshots[player.uniqueId]
    }

    /**
     * Gets the current group for a player.
     */
    fun getCurrentGroup(player: Player): String? {
        return playerGroups[player.uniqueId]
    }

    /**
     * Clears player data for a specific group.
     */
    suspend fun clearPlayerData(uuid: UUID, group: String, gameMode: GameMode?): Boolean {
        return storageService.deletePlayerData(uuid, group, gameMode)
    }

    // Bypass management

    fun addBypass(uuid: UUID, group: String? = null) {
        bypasses.getOrPut(uuid) { Collections.synchronizedSet(HashSet()) }.add(group)
    }

    fun removeBypass(uuid: UUID, group: String? = null) {
        bypasses[uuid]?.remove(group)
        if (bypasses[uuid]?.isEmpty() == true) {
            bypasses.remove(uuid)
        }
    }

    fun hasBypass(player: Player, group: String? = null): Boolean {
        // Check permission
        if (player.hasPermission("xinventories.bypass")) return true
        if (group != null && player.hasPermission("xinventories.bypass.$group")) return true

        // Check runtime bypass
        val playerBypasses = bypasses[player.uniqueId] ?: return false
        return playerBypasses.contains(null) || (group != null && playerBypasses.contains(group))
    }

    fun hasBypass(uuid: UUID, group: String? = null): Boolean {
        val playerBypasses = bypasses[uuid] ?: return false
        return playerBypasses.contains(null) || (group != null && playerBypasses.contains(group))
    }

    fun getBypasses(): Map<UUID, Set<String?>> {
        return bypasses.mapValues { it.value.toSet() }
    }

    // Private helpers

    private fun applyInventory(player: Player, data: PlayerData, settings: GroupSettings) {
        // Run on player's region thread (Folia) or main thread (Paper/Spigot)
        SchedulerCompat.runTask(plugin.plugin, player) { p ->
            data.applyToPlayer(p, settings)
        }
    }

    private fun clearPlayerInventory(player: Player, settings: GroupSettings) {
        // Run on player's region thread (Folia) or main thread (Paper/Spigot)
        SchedulerCompat.runTask(plugin.plugin, player) { p ->
            // Only clear inventory if save-inventory is enabled
            if (settings.saveInventory) {
                p.inventory.clear()
                p.inventory.armorContents = arrayOfNulls(4)
                p.inventory.setItemInOffHand(null)
            }

            if (settings.saveEnderChest) {
                p.enderChest.clear()
            }

            if (settings.saveHealth) {
                p.health = 20.0
            }

            if (settings.saveHunger) {
                p.foodLevel = 20
            }

            if (settings.saveSaturation) {
                p.saturation = 5.0f
            }

            if (settings.saveExperience) {
                p.exp = 0f
                p.level = 0
                p.totalExperience = 0
            }

            if (settings.savePotionEffects) {
                p.activePotionEffects.forEach { effect ->
                    p.removePotionEffect(effect.type)
                }
            }

            // PWI-style player state clearing
            if (settings.saveFlying) {
                p.isFlying = false
            }

            if (settings.saveAllowFlight) {
                p.allowFlight = false
            }

            if (settings.saveFallDistance) {
                p.fallDistance = 0.0f
            }

            if (settings.saveFireTicks) {
                p.fireTicks = 0
            }

            if (settings.saveMaximumAir) {
                p.maximumAir = 300
            }

            if (settings.saveRemainingAir) {
                p.remainingAir = 300
            }

            // Clear statistics if enabled (reset to 0)
            if (settings.saveStatistics) {
                org.bukkit.Statistic.entries.forEach { stat ->
                    try {
                        when (stat.type) {
                            org.bukkit.Statistic.Type.UNTYPED -> p.setStatistic(stat, 0)
                            org.bukkit.Statistic.Type.BLOCK -> {
                                org.bukkit.Material.entries.filter { it.isBlock }.forEach { material ->
                                    try { p.setStatistic(stat, material, 0) } catch (_: Exception) {}
                                }
                            }
                            org.bukkit.Statistic.Type.ITEM -> {
                                org.bukkit.Material.entries.filter { it.isItem }.forEach { material ->
                                    try { p.setStatistic(stat, material, 0) } catch (_: Exception) {}
                                }
                            }
                            org.bukkit.Statistic.Type.ENTITY -> {
                                org.bukkit.entity.EntityType.entries.filter { it.isAlive }.forEach { entityType ->
                                    try { p.setStatistic(stat, entityType, 0) } catch (_: Exception) {}
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            // Clear advancements if enabled (revoke all)
            if (settings.saveAdvancements) {
                org.bukkit.Bukkit.advancementIterator().forEach { advancement ->
                    val progress = p.getAdvancementProgress(advancement)
                    progress.awardedCriteria.forEach { criteria ->
                        progress.revokeCriteria(criteria)
                    }
                }
            }

            // Clear recipes if enabled (undiscover all)
            if (settings.saveRecipes) {
                org.bukkit.Bukkit.recipeIterator().forEach { recipe ->
                    if (recipe is org.bukkit.Keyed) {
                        try { p.undiscoverRecipe(recipe.key) } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun notifySwitch(player: Player, fromGroup: String, toGroup: String) {
        val globalSettings = plugin.configManager.groupsConfig.globalSettings

        if (globalSettings.notifyOnSwitch) {
            messageService.send(player, "inventory-switched",
                "from_group" to fromGroup,
                "to_group" to toGroup
            )
        }

        // Play sound
        globalSettings.switchSound?.let { soundName ->
            try {
                val sound = Sound.valueOf(soundName)
                player.playSound(
                    player.location,
                    sound,
                    globalSettings.switchSoundVolume,
                    globalSettings.switchSoundPitch
                )
            } catch (e: Exception) {
                Logging.debug { "Invalid switch sound: $soundName" }
            }
        }
    }

    // =========================================================================
    // Content Control Integration
    // =========================================================================

    /**
     * Handles template application when a player enters a group.
     */
    private suspend fun handleTemplateOnGroupEntry(player: Player, group: Group) {
        val templateSettings = group.templateSettings ?: return
        if (!templateSettings.enabled) return

        plugin.serviceManager.templateService.handleGroupEntry(
            player,
            group.toApiModel(),
            templateSettings
        )
    }

    /**
     * Checks item restrictions and handles violations.
     * Returns true if the player is allowed to proceed, false if blocked.
     */
    private fun checkAndHandleRestrictions(
        player: Player,
        group: Group,
        config: RestrictionConfig,
        action: RestrictionAction
    ): Boolean {
        val restrictionService = plugin.serviceManager.restrictionService

        // Check player's inventory for violations
        val violations = restrictionService.checkPlayerInventory(player, config, action)

        if (violations.isEmpty()) {
            return true
        }

        // Handle the violations
        return restrictionService.handleViolations(
            player,
            group.toApiModel(),
            config,
            violations,
            action
        )
    }
}
