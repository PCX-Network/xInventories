package sh.pcx.xinventories.internal.service

import sh.pcx.xinventories.PluginContext
import sh.pcx.xinventories.internal.model.PlayerData
import sh.pcx.xinventories.internal.model.SharedSlotEntry
import sh.pcx.xinventories.internal.model.SharedSlotsConfig
import sh.pcx.xinventories.internal.model.SlotMode
import sh.pcx.xinventories.internal.util.Logging
import kotlinx.coroutines.CoroutineScope
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for managing shared inventory slots.
 *
 * Shared slots persist across group switches and can optionally be locked
 * from player modification or synced across all groups.
 */
class SharedSlotService(
    private val plugin: PluginContext,
    private val scope: CoroutineScope
) : Listener {

    // Cache of shared slot items per player
    // Key: player UUID, Value: Map of slot -> ItemStack
    private val sharedSlotCache = ConcurrentHashMap<UUID, MutableMap<Int, ItemStack?>>()

    // Cache of synced slot data (shared across all groups)
    // Key: player UUID, Value: Map of slot -> ItemStack
    private val syncedSlotData = ConcurrentHashMap<UUID, MutableMap<Int, ItemStack?>>()

    private var config: SharedSlotsConfig = SharedSlotsConfig.disabled()

    /**
     * Initializes the shared slot service.
     */
    fun initialize(config: SharedSlotsConfig) {
        this.config = config

        if (config.enabled) {
            plugin.plugin.server.pluginManager.registerEvents(this, plugin.plugin)
            Logging.info("SharedSlotService initialized with ${config.slots.size} slot entries")
        } else {
            Logging.debug { "SharedSlotService disabled" }
        }
    }

    /**
     * Updates the configuration.
     */
    fun updateConfig(newConfig: SharedSlotsConfig) {
        this.config = newConfig
        Logging.debug { "SharedSlotService config updated" }
    }

    /**
     * Checks if a slot is a shared slot.
     */
    fun isSharedSlot(slot: Int): Boolean = config.isSharedSlot(slot)

    /**
     * Checks if a slot is locked.
     */
    fun isLockedSlot(slot: Int): Boolean = config.isLockedSlot(slot)

    /**
     * Gets the shared slot entry for a slot.
     */
    fun getEntryForSlot(slot: Int): SharedSlotEntry? = config.getEntryForSlot(slot)

    /**
     * Preserves shared slot contents before a group switch.
     *
     * This should be called BEFORE saving the current inventory.
     */
    fun preserveSharedSlots(player: Player) {
        if (!config.enabled) return

        val playerSlots = sharedSlotCache.computeIfAbsent(player.uniqueId) { mutableMapOf() }
        val sharedIndices = config.getAllSharedSlots()

        for (slot in sharedIndices) {
            val item = getItemFromSlot(player, slot)
            playerSlots[slot] = item?.clone()

            // For synced slots, also update the synced data
            val entry = config.getEntryForSlot(slot)
            if (entry?.mode == SlotMode.SYNC) {
                val syncedSlots = syncedSlotData.computeIfAbsent(player.uniqueId) { mutableMapOf() }
                syncedSlots[slot] = item?.clone()
            }
        }

        Logging.debug { "Preserved ${playerSlots.size} shared slots for ${player.name}" }
    }

    /**
     * Restores shared slot contents after a group switch.
     *
     * This should be called AFTER loading the new inventory.
     */
    fun restoreSharedSlots(player: Player) {
        if (!config.enabled) return

        val playerSlots = sharedSlotCache[player.uniqueId] ?: return

        for ((slot, item) in playerSlots) {
            val entry = config.getEntryForSlot(slot)
            if (entry == null) continue

            // Check if there's an enforced item for this slot
            val itemToSet = if (entry.item != null) {
                entry.item.toItemStack()
            } else if (entry.mode == SlotMode.SYNC) {
                // For synced slots, use the synced data
                syncedSlotData[player.uniqueId]?.get(slot) ?: item
            } else {
                item
            }

            setItemToSlot(player, slot, itemToSet?.clone())
        }

        Logging.debug { "Restored ${playerSlots.size} shared slots for ${player.name}" }
    }

    /**
     * Modifies player data to exclude shared slots before saving.
     *
     * This ensures shared slot contents don't get saved with group-specific data.
     */
    fun excludeSharedSlotsFromData(playerData: PlayerData) {
        if (!config.enabled) return

        val sharedIndices = config.getAllSharedSlots()

        for (slot in sharedIndices) {
            when {
                slot in 0..35 -> playerData.mainInventory.remove(slot)
                slot in 36..39 -> playerData.armorInventory.remove(slot - 36)
                slot == 40 -> playerData.offhand = null
            }
        }
    }

    /**
     * Applies enforced items to shared slots.
     *
     * Called when a player joins or switches groups.
     */
    fun applyEnforcedItems(player: Player) {
        if (!config.enabled) return

        for (entry in config.slots) {
            if (entry.item != null) {
                for (slot in entry.getSlotIndices()) {
                    setItemToSlot(player, slot, entry.item.toItemStack())
                }
            }
        }
    }

    /**
     * Gets an item from a player's inventory slot.
     */
    private fun getItemFromSlot(player: Player, slot: Int): ItemStack? {
        return when {
            slot in 0..35 -> player.inventory.getItem(slot)
            slot == 36 -> player.inventory.boots
            slot == 37 -> player.inventory.leggings
            slot == 38 -> player.inventory.chestplate
            slot == 39 -> player.inventory.helmet
            slot == 40 -> player.inventory.itemInOffHand
            else -> null
        }
    }

    /**
     * Sets an item to a player's inventory slot.
     */
    private fun setItemToSlot(player: Player, slot: Int, item: ItemStack?) {
        when {
            slot in 0..35 -> player.inventory.setItem(slot, item)
            slot == 36 -> player.inventory.setBoots(item)
            slot == 37 -> player.inventory.setLeggings(item)
            slot == 38 -> player.inventory.setChestplate(item)
            slot == 39 -> player.inventory.setHelmet(item)
            slot == 40 -> player.inventory.setItemInOffHand(item)
        }
    }

    /**
     * Converts a raw slot from inventory click to our slot numbering.
     */
    private fun convertRawSlot(rawSlot: Int, inventoryType: org.bukkit.event.inventory.InventoryType): Int? {
        // For player inventory views, the raw slot mapping is:
        // 0-8: Crafting slots (not relevant)
        // 9-35: Main inventory (maps to our 9-35)
        // 36-44: Hotbar (maps to our 0-8)

        // This depends heavily on the inventory type being viewed
        if (inventoryType == org.bukkit.event.inventory.InventoryType.PLAYER ||
            inventoryType == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            return when {
                rawSlot in 9..35 -> rawSlot
                rawSlot in 36..44 -> rawSlot - 36
                else -> null
            }
        }

        // For chest-like inventories, bottom inventory is the player inventory
        // This is more complex and depends on the top inventory size
        return null
    }

    // =========================================================================
    // Event Handlers for LOCK mode
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        if (!config.enabled) return

        val player = event.whoClicked as? Player ?: return

        // Check if clicked slot is locked
        val clickedSlot = event.slot
        if (event.clickedInventory == player.inventory && isLockedSlot(clickedSlot)) {
            event.isCancelled = true
            return
        }

        // Also check hotbar swaps
        if (event.hotbarButton >= 0 && isLockedSlot(event.hotbarButton)) {
            event.isCancelled = true
            return
        }

        // Handle number key slots (armor slots when using number keys)
        when (event.click) {
            org.bukkit.event.inventory.ClickType.NUMBER_KEY -> {
                val hotbarSlot = event.hotbarButton
                if (isLockedSlot(hotbarSlot)) {
                    event.isCancelled = true
                }
            }
            else -> {}
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (!config.enabled) return

        val player = event.whoClicked as? Player ?: return

        // Check if any dragged slots are locked
        for (rawSlot in event.rawSlots) {
            // Need to convert raw slot to player inventory slot
            if (event.view.getInventory(rawSlot) == player.inventory) {
                val slot = event.view.convertSlot(rawSlot)
                if (isLockedSlot(slot)) {
                    event.isCancelled = true
                    return
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        if (!config.enabled) return

        // Find which slot the dropped item came from
        // Unfortunately, Bukkit doesn't provide this directly
        // We can check if the player is holding a locked slot's item

        val player = event.player
        val droppedItem = event.itemDrop.itemStack

        // Check hotbar slots (most common drop scenario via Q key)
        val heldSlot = player.inventory.heldItemSlot
        if (isLockedSlot(heldSlot)) {
            // Check if the dropped item matches what should be in the slot
            val slotItem = player.inventory.getItem(heldSlot)
            if (slotItem == null || slotItem.type.isAir) {
                // Item was from this slot, cancel
                event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerSwapHandItems(event: PlayerSwapHandItemsEvent) {
        if (!config.enabled) return

        // Check if either hand slot is locked
        val player = event.player
        val mainHandSlot = player.inventory.heldItemSlot
        val offhandSlot = SharedSlotEntry.OFFHAND

        if (isLockedSlot(mainHandSlot) || isLockedSlot(offhandSlot)) {
            event.isCancelled = true
        }
    }

    /**
     * Cleans up player data when they leave.
     */
    fun cleanup(playerUuid: UUID) {
        sharedSlotCache.remove(playerUuid)
        // Don't remove synced data - it should persist
    }

    /**
     * Clears all cached data.
     */
    fun clearCache() {
        sharedSlotCache.clear()
        syncedSlotData.clear()
        Logging.debug { "Shared slot cache cleared" }
    }

    /**
     * Updates synced slot data when a player modifies a synced slot.
     */
    fun updateSyncedSlot(player: Player, slot: Int, item: ItemStack?) {
        if (!config.enabled) return

        val entry = config.getEntryForSlot(slot)
        if (entry?.mode != SlotMode.SYNC) return

        val syncedSlots = syncedSlotData.computeIfAbsent(player.uniqueId) { mutableMapOf() }
        syncedSlots[slot] = item?.clone()

        Logging.debug { "Updated synced slot $slot for ${player.name}" }
    }
}
