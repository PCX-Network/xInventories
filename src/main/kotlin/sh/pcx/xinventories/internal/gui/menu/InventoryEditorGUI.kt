package sh.pcx.xinventories.internal.gui.menu

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import sh.pcx.xinventories.PluginContext
import sh.pcx.xinventories.api.event.InventoryLoadEvent
import sh.pcx.xinventories.internal.gui.AbstractGUI
import sh.pcx.xinventories.internal.gui.GUIComponents
import sh.pcx.xinventories.internal.gui.GUIItem
import sh.pcx.xinventories.internal.gui.GUIItemBuilder
import sh.pcx.xinventories.internal.model.PlayerData
import sh.pcx.xinventories.internal.util.Logging
import java.util.*

/**
 * GUI for editing a player's stored inventory.
 *
 * Slots 0-35 hold the stored main-inventory items themselves, so an admin moves them exactly as
 * in a chest: pick up, place, split, shift-click into their own inventory, drag from their own
 * inventory. Armor and offhand are displayed read-only. Because items really leave the editor when
 * taken out, the editor cannot be closed without persisting: every close saves, and if the target
 * is online in this group the saved inventory is applied to them immediately. That is what keeps
 * "take an item out" from becoming "duplicate an item".
 */
class InventoryEditorGUI(
    plugin: PluginContext,
    private val targetUUID: UUID,
    private val targetName: String,
    private val groupName: String,
    private val gameMode: GameMode = GameMode.SURVIVAL
) : AbstractGUI(
    plugin,
    Component.text("Editing: $targetName", NamedTextColor.GOLD),
    54
) {

    // Stored data the editor was opened with; armor, offhand, ender chest and stats are kept from it.
    private var originalData: PlayerData? = null

    // Set by the GUI manager once a vanilla click/drag changed an editable slot.
    private var hasUnsavedChanges = false

    // Guards against the close hook and an explicit Save both persisting the same edit.
    private var saving = false

    // Editable slots (main inventory: 0-35)
    private val editableSlots = (0..35).toSet()

    init {
        loadData()
        setupItems()
    }

    private fun loadData() {
        originalData = runBlocking {
            plugin.serviceManager.storageService.load(targetUUID, groupName, gameMode)
        }
    }

    override fun editableSlots(): Set<Int> = if (originalData != null) editableSlots else emptySet()

    override fun onEditableSlotsChanged(player: Player) {
        hasUnsavedChanges = true
        refreshControls()
    }

    private fun setupItems() {
        val data = originalData

        if (data == null) {
            // No data - show error
            setItem(22, GUIItemBuilder()
                .material(Material.BARRIER)
                .name("No Data", NamedTextColor.RED)
                .lore("No inventory data found for this group")
                .lore("Cannot edit non-existent data")
                .build()
            )

            setItem(49, GUIItemBuilder()
                .material(Material.ARROW)
                .name("Back", NamedTextColor.GRAY)
                .lore("Return to player details")
                .onClick { event ->
                    val player = event.whoClicked as Player
                    PlayerDetailGUI(plugin, targetUUID, targetName).open(player)
                }
                .build()
            )
            return
        }

        // Main inventory contents: the real items, no wrapper, empty slots left empty so vanilla
        // can place into them.
        for (i in 0..35) {
            val item = data.mainInventory[i]
            if (item != null && item.type != Material.AIR) {
                setItem(i, GUIItem(item.clone()))
            }
        }

        // Armor slots (display only in this view - slots 36-39)
        val armorSlots = listOf(39, 38, 37, 36) // Helmet, chest, legs, boots
        for ((index, armorSlot) in armorSlots.withIndex()) {
            val item = data.armorInventory[3 - index]
            if (item != null && item.type != Material.AIR) {
                setItem(armorSlot, GUIItem(readOnlyCopy(item)))
            } else {
                val placeholder = when (index) {
                    0 -> createArmorPlaceholder("Helmet", Material.LEATHER_HELMET)
                    1 -> createArmorPlaceholder("Chestplate", Material.LEATHER_CHESTPLATE)
                    2 -> createArmorPlaceholder("Leggings", Material.LEATHER_LEGGINGS)
                    3 -> createArmorPlaceholder("Boots", Material.LEATHER_BOOTS)
                    else -> GUIComponents.filler()
                }
                setItem(armorSlot, placeholder)
            }
        }

        // Offhand (slot 40)
        val offhandItem = data.offhand
        if (offhandItem != null && offhandItem.type != Material.AIR) {
            setItem(40, GUIItem(readOnlyCopy(offhandItem)))
        } else {
            setItem(40, createArmorPlaceholder("Offhand", Material.SHIELD))
        }

        // Control panel (bottom row)
        setupControlPanel()
    }

    private fun setupControlPanel() {
        // Fill bottom row with different color to indicate controls
        for (i in 45..53) {
            setItem(i, GUIComponents.filler(Material.BLACK_STAINED_GLASS_PANE))
        }

        val target = Bukkit.getPlayer(targetUUID)
        val targetLive = target != null && isTargetInThisGroup(target)

        // Save button
        setItem(45, GUIItemBuilder()
            .material(Material.LIME_WOOL)
            .name("Save Changes", NamedTextColor.GREEN)
            .lore("Click to save now")
            .lore("Changes are also saved when you close")
            .lore("")
            .lore(if (hasUnsavedChanges) "You have unsaved changes!" else "No changes to save")
            .onClick { event ->
                val player = event.whoClicked as Player
                if (!hasUnsavedChanges) {
                    player.sendMessage(Component.text("No changes to save.", NamedTextColor.YELLOW))
                } else {
                    saveChanges(player, thenClose = false)
                }
            }
            .build()
        )

        // Back button
        setItem(46, GUIItemBuilder()
            .material(Material.ARROW)
            .name("Back", NamedTextColor.GRAY)
            .lore("Save and return to player details")
            .onClick { event ->
                val player = event.whoClicked as Player
                // Navigating away closes this inventory, and the close hook saves.
                PlayerDetailGUI(plugin, targetUUID, targetName).open(player)
            }
            .build()
        )

        // Clear inventory button
        setItem(47, GUIItemBuilder()
            .material(Material.TNT)
            .name("Clear Inventory", NamedTextColor.DARK_RED)
            .lore("Remove all items from inventory")
            .lore("")
            .lore("Shift-click to confirm")
            .onClick { event ->
                val player = event.whoClicked as Player
                if (event.click == ClickType.SHIFT_LEFT || event.click == ClickType.SHIFT_RIGHT) {
                    clearEditableSlots()
                    hasUnsavedChanges = true
                    refreshControls()
                    player.sendMessage(
                        Component.text("Inventory cleared. It is saved when you close the editor.", NamedTextColor.YELLOW)
                    )
                } else {
                    player.sendMessage(
                        Component.text("Shift-click to confirm clearing inventory.", NamedTextColor.RED)
                    )
                }
            }
            .build()
        )

        // Unsaved changes indicator
        val indicatorMaterial = if (hasUnsavedChanges) Material.YELLOW_WOOL else Material.GREEN_WOOL
        val indicatorName = if (hasUnsavedChanges) "Unsaved Changes" else "No Changes"
        val indicatorColor = if (hasUnsavedChanges) NamedTextColor.YELLOW else NamedTextColor.GREEN

        setItem(49, GUIItemBuilder()
            .material(indicatorMaterial)
            .name(indicatorName, indicatorColor)
            .lore(if (hasUnsavedChanges) "Saved when you click Save or close" else "All changes saved")
            .build()
        )

        // Instructions
        val builder = GUIItemBuilder()
            .material(Material.BOOK)
            .name("Instructions", NamedTextColor.AQUA)
            .lore("Move items like in a chest:")
            .lore("Click, drag, split or shift-click them")
            .lore("in and out of your own inventory.")
            .lore("Armor and offhand are view only.")
            .lore("")
            .lore("Changes are saved when you close.")
        if (targetLive) {
            builder.lore("$targetName is online in this group:")
                .lore("their inventory is replaced on save.")
        }
        setItem(51, builder.build())

        // Close button
        setItem(53, GUIItemBuilder()
            .material(Material.BARRIER)
            .name("Close", NamedTextColor.RED)
            .lore("Save and close")
            .onClick { event ->
                (event.whoClicked as Player).closeInventory()
            }
            .build()
        )
    }

    /** Re-renders the control row only; the editable slots are owned by the live inventory. */
    private fun refreshControls() {
        for (slot in 45..53) items.remove(slot)
        setupControlPanel()
        val inv = inventory ?: return
        for (slot in 45..53) {
            inv.setItem(slot, items[slot]?.itemStack)
        }
    }

    private fun readOnlyCopy(item: ItemStack): ItemStack {
        val copy = item.clone()
        val meta = copy.itemMeta ?: return copy
        val lore = meta.lore()?.toMutableList() ?: mutableListOf()
        lore.add(Component.empty())
        lore.add(
            Component.text("View only")
                .color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)
        )
        meta.lore(lore)
        copy.itemMeta = meta
        return copy
    }

    private fun createArmorPlaceholder(name: String, material: Material): GUIItem {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.displayName(
            Component.text("$name (View Only)")
                .color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)
        )
        meta.lore(listOf(
            Component.text("Armor editing not supported")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ))
        item.itemMeta = meta
        return GUIItem(item)
    }

    private fun clearEditableSlots() {
        val inv = inventory ?: return
        for (slot in editableSlots) {
            inv.setItem(slot, null)
        }
    }

    /** Main-thread snapshot of what is currently in the editable slots. */
    private fun snapshotEditableSlots(): Map<Int, ItemStack> {
        val inv = inventory ?: return emptyMap()
        val result = mutableMapOf<Int, ItemStack>()
        for (slot in editableSlots) {
            val item = inv.getItem(slot)
            if (item != null && item.type != Material.AIR) {
                result[slot] = item.clone()
            }
        }
        return result
    }

    private fun isTargetInThisGroup(target: Player): Boolean {
        val currentGroup = plugin.serviceManager.inventoryService.getCurrentGroup(target)
            ?: plugin.serviceManager.groupService.getGroupForWorld(target.world).name
        if (!currentGroup.equals(groupName, ignoreCase = true)) return false
        // When gamemodes are not separated the stored gamemode is the default one, so only
        // compare it when the editor was opened for a specific non-default gamemode.
        return !plugin.configManager.mainConfig.features.separateGamemodeInventories || target.gameMode == gameMode
    }

    private fun saveChanges(player: Player, thenClose: Boolean) {
        val original = originalData ?: return
        if (saving) return
        saving = true

        val edited = snapshotEditableSlots()
        hasUnsavedChanges = false

        plugin.scope.launch(plugin.storageDispatcher) {
            try {
                // Build updated player data
                val updatedData = PlayerData(
                    uuid = targetUUID,
                    playerName = targetName,
                    group = groupName,
                    gameMode = gameMode
                )

                edited.forEach { (slot, item) -> updatedData.mainInventory[slot] = item }

                // Copy armor (unchanged)
                original.armorInventory.forEach { (slot, item) ->
                    updatedData.armorInventory[slot] = item.clone()
                }

                // Copy offhand (unchanged)
                updatedData.offhand = original.offhand?.clone()

                // Copy ender chest (unchanged)
                original.enderChest.forEach { (slot, item) ->
                    updatedData.enderChest[slot] = item.clone()
                }

                // Copy other state
                updatedData.health = original.health
                updatedData.maxHealth = original.maxHealth
                updatedData.foodLevel = original.foodLevel
                updatedData.saturation = original.saturation
                updatedData.exhaustion = original.exhaustion
                updatedData.experience = original.experience
                updatedData.level = original.level
                updatedData.totalExperience = original.totalExperience
                updatedData.potionEffects.addAll(original.potionEffects)

                // Save to storage
                plugin.serviceManager.storageService.savePlayerData(updatedData)

                // Log to audit
                plugin.serviceManager.auditService?.logAdminEdit(
                    admin = player,
                    targetUuid = targetUUID,
                    targetName = targetName,
                    group = groupName,
                    details = "Edited inventory via GUI"
                )

                // An online target standing in this group would otherwise overwrite the edit with
                // their live inventory on the next save, so apply the edit to them now.
                val target = Bukkit.getPlayer(targetUUID)
                var applied = false
                if (target != null && target.isOnline && withContext(plugin.mainThreadDispatcher) { isTargetInThisGroup(target) }) {
                    val group = plugin.serviceManager.groupService.getGroup(groupName)
                        ?: plugin.serviceManager.groupService.getGroupForWorld(target.world)
                    plugin.serviceManager.inventoryService.loadInventory(
                        target,
                        group.toApiModel(),
                        InventoryLoadEvent.LoadReason.COMMAND
                    )
                    applied = true
                }

                withContext(plugin.mainThreadDispatcher) {
                    originalData = updatedData
                    player.sendMessage(
                        Component.text(
                            if (applied) "Changes saved and applied to $targetName." else "Changes saved successfully!",
                            NamedTextColor.GREEN
                        )
                    )
                    if (!thenClose) refreshControls()
                }
            } catch (e: Exception) {
                Logging.error("Failed to save inventory changes for $targetName", e)
                withContext(plugin.mainThreadDispatcher) {
                    hasUnsavedChanges = true
                    player.sendMessage(
                        Component.text("Failed to save changes: ${e.message}", NamedTextColor.RED)
                    )
                }
            } finally {
                saving = false
            }
        }
    }

    override fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        if (hasUnsavedChanges) {
            saveChanges(player, thenClose = true)
        }
    }

    override fun fillEmptySlots(inventory: Inventory) {
        // Don't fill editable slots - vanilla needs them empty to place into.
        // Only fill non-inventory areas
        val fillerSlots = (41..44).toList()
        val filler = GUIComponents.filler(Material.GRAY_STAINED_GLASS_PANE)

        for (slot in fillerSlots) {
            if (!items.containsKey(slot)) {
                items[slot] = filler
                inventory.setItem(slot, filler.itemStack)
            }
        }
    }
}

/**
 * A simple confirmation dialog GUI.
 */
class ConfirmationGUI(
    plugin: PluginContext,
    title: Component,
    private val line1: String,
    private val line2: String,
    private val onConfirm: () -> Unit,
    private val onCancel: () -> Unit
) : AbstractGUI(plugin, title, 27) {

    init {
        setupItems()
    }

    private fun setupItems() {
        // Fill with gray glass
        fillBorder(GUIComponents.filler())

        // Info
        setItem(13, GUIItemBuilder()
            .material(Material.PAPER)
            .name("Confirmation Required", NamedTextColor.YELLOW)
            .lore(line1)
            .lore(line2)
            .build()
        )

        // Confirm button
        setItem(11, GUIItemBuilder()
            .material(Material.LIME_WOOL)
            .name("Confirm", NamedTextColor.GREEN)
            .lore("Click to confirm")
            .onClick { event ->
                event.whoClicked.closeInventory()
                onConfirm()
            }
            .build()
        )

        // Cancel button
        setItem(15, GUIItemBuilder()
            .material(Material.RED_WOOL)
            .name("Cancel", NamedTextColor.RED)
            .lore("Click to cancel")
            .onClick { event ->
                event.whoClicked.closeInventory()
                onCancel()
            }
            .build()
        )
    }

    override fun fillEmptySlots(inventory: Inventory) {
        val filler = GUIComponents.filler()
        for (i in 0 until size) {
            if (!items.containsKey(i)) {
                items[i] = filler
                inventory.setItem(i, filler.itemStack)
            }
        }
    }
}
