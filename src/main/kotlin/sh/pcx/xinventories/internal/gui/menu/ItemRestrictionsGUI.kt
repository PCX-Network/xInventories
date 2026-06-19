package sh.pcx.xinventories.internal.gui.menu

import sh.pcx.xinventories.PluginContext
import sh.pcx.xinventories.internal.gui.AbstractGUI
import sh.pcx.xinventories.internal.gui.GUIComponents
import sh.pcx.xinventories.internal.gui.GUIItem
import sh.pcx.xinventories.internal.gui.GUIItemBuilder
import sh.pcx.xinventories.internal.model.Group
import sh.pcx.xinventories.internal.model.RestrictionConfig
import sh.pcx.xinventories.internal.model.RestrictionMode
import sh.pcx.xinventories.internal.model.RestrictionViolationAction
import sh.pcx.xinventories.internal.util.Logging
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * GUI for editing item restrictions for a group.
 *
 * Features:
 * - Select group to configure
 * - Toggle between whitelist/blacklist mode
 * - Visual list of restricted items
 * - Drag items into GUI to add restrictions
 * - Click to remove restrictions
 * - Set violation action (REMOVE, DROP, PREVENT)
 * - Save/cancel buttons
 * - Show current restriction count
 */
class ItemRestrictionsGUI(
    plugin: PluginContext,
    private val groupName: String
) : AbstractGUI(
    plugin,
    Component.text("Item Restrictions: $groupName", NamedTextColor.DARK_RED),
    54
) {
    private val group: Group? = plugin.serviceManager.groupService.getGroup(groupName)

    // Working copy of restriction config for editing
    private var workingConfig: RestrictionConfig = group?.restrictions?.copy() ?: RestrictionConfig.disabled()

    // Track if changes have been made
    private var hasChanges: Boolean = false

    // Current page for pattern list
    private var currentPage: Int = 0

    // Number of patterns we can display per page (slots 10-16, 19-25, 28-34 = 21 slots)
    private val patternsPerPage: Int = 21

    // Slots for displaying patterns
    private val patternSlots: List<Int> = listOf(
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34
    )

    init {
        setupItems()
    }

    private fun setupItems() {
        items.clear()

        if (group == null) {
            // Group not found
            setItem(22, GUIItemBuilder()
                .material(Material.BARRIER)
                .name("Group Not Found", NamedTextColor.RED)
                .lore("The group '$groupName' does not exist")
                .build()
            )
            setItem(49, GUIComponents.closeButton())
            return
        }

        // Fill border
        fillBorder(GUIComponents.filler())

        // === Row 0: Header ===

        // Mode toggle button (slot 4)
        setItem(4, createModeToggleButton())

        // === Left side: Controls ===

        // Violation action selector (slot 1)
        setItem(1, createViolationActionButton())

        // Notification toggles (slot 7)
        setItem(7, createNotificationButton())

        // === Center: Pattern list ===
        setupPatternList()

        // === Row 4: Info ===

        // Info display (slot 40)
        setItem(40, createInfoButton())

        // === Row 5: Navigation and actions ===

        // Previous page (slot 45)
        if (currentPage > 0) {
            setItem(45, GUIComponents.previousPageButton(currentPage + 1) {
                currentPage--
                setupItems()
                refreshInventory()
            })
        } else {
            setItem(45, GUIComponents.filler())
        }

        // Back button (slot 46)
        setItem(46, GUIItemBuilder()
            .material(Material.ARROW)
            .name("Back", NamedTextColor.RED)
            .lore("Return to group list")
            .lore("")
            .lore(if (hasChanges) "Warning: Unsaved changes!" else "")
            .onClick { event ->
                val player = event.whoClicked as Player
                GroupListGUI(plugin).open(player)
            }
            .build()
        )

        // Cancel button (slot 47)
        setItem(47, GUIItemBuilder()
            .material(Material.RED_WOOL)
            .name("Cancel", NamedTextColor.RED)
            .lore("Discard all changes")
            .onClick { event ->
                hasChanges = false
                workingConfig = group.restrictions?.copy() ?: RestrictionConfig.disabled()
                setupItems()
                refreshInventory()
                val player = event.whoClicked as Player
                player.sendMessage(Component.text("Changes discarded.", NamedTextColor.YELLOW))
            }
            .build()
        )

        // Save button (slot 51)
        setItem(51, GUIItemBuilder()
            .material(if (hasChanges) Material.LIME_WOOL else Material.GRAY_WOOL)
            .name(if (hasChanges) "Save Changes" else "No Changes",
                  if (hasChanges) NamedTextColor.GREEN else NamedTextColor.GRAY)
            .lore(if (hasChanges) "Click to save restrictions" else "Make changes to enable saving")
            .onClick { event ->
                if (hasChanges) {
                    saveChanges()
                    val player = event.whoClicked as Player
                    player.sendMessage(Component.text("Restrictions saved!", NamedTextColor.GREEN))
                    setupItems()
                    refreshInventory()
                }
            }
            .build()
        )

        // Next page (slot 52)
        val totalPatterns = workingConfig.getActivePatterns().size + workingConfig.stripOnExit.size
        val totalPages = (totalPatterns + patternsPerPage - 1) / patternsPerPage
        if (currentPage < totalPages - 1) {
            setItem(52, GUIComponents.nextPageButton(currentPage + 1) {
                currentPage++
                setupItems()
                refreshInventory()
            })
        } else {
            setItem(52, GUIComponents.filler())
        }

        // Close button (slot 53)
        setItem(53, GUIComponents.closeButton())

        // Add item drop zone indicator (slot 49)
        setItem(49, GUIItemBuilder()
            .material(Material.HOPPER)
            .name("Add Restriction", NamedTextColor.GOLD)
            .lore("Drag an item here to add")
            .lore("it to the restriction list")
            .lore("")
            .lore("Or click to enter pattern manually")
            .onClick { event ->
                val player = event.whoClicked as Player
                // Open an anvil GUI for manual pattern input
                player.closeInventory()
                player.sendMessage(Component.text("Enter the material pattern in chat (e.g., DIAMOND_SWORD, *_SWORD, etc.):", NamedTextColor.YELLOW))
                plugin.inputManager.awaitChatInput(player) { input ->
                    addPattern(input.trim().uppercase())
                    ItemRestrictionsGUI(plugin, groupName).apply {
                        this.workingConfig = this@ItemRestrictionsGUI.workingConfig
                        this.hasChanges = this@ItemRestrictionsGUI.hasChanges
                    }.open(player)
                }
            }
            .build()
        )
    }

    private fun createModeToggleButton(): GUIItem {
        val (material, color, modeName) = when (workingConfig.mode) {
            RestrictionMode.BLACKLIST -> Triple(Material.BLACK_WOOL, NamedTextColor.RED, "BLACKLIST")
            RestrictionMode.WHITELIST -> Triple(Material.WHITE_WOOL, NamedTextColor.GREEN, "WHITELIST")
            RestrictionMode.NONE -> Triple(Material.GRAY_WOOL, NamedTextColor.GRAY, "DISABLED")
        }

        return GUIItemBuilder()
            .material(material)
            .name("Mode: $modeName", color)
            .lore(when (workingConfig.mode) {
                RestrictionMode.BLACKLIST -> "Items on the list are BLOCKED"
                RestrictionMode.WHITELIST -> "Only items on the list are ALLOWED"
                RestrictionMode.NONE -> "Restrictions are disabled"
            })
            .lore("")
            .lore("Left-click: Toggle mode")
            .lore("Right-click: Disable")
            .onClick { event ->
                workingConfig = when {
                    event.click == ClickType.RIGHT -> workingConfig.copy(mode = RestrictionMode.NONE)
                    workingConfig.mode == RestrictionMode.NONE -> workingConfig.copy(mode = RestrictionMode.BLACKLIST)
                    workingConfig.mode == RestrictionMode.BLACKLIST -> workingConfig.copy(mode = RestrictionMode.WHITELIST)
                    else -> workingConfig.copy(mode = RestrictionMode.BLACKLIST)
                }
                hasChanges = true
                setupItems()
                refreshInventory()
            }
            .build()
    }

    private fun createViolationActionButton(): GUIItem {
        val (material, actionName, description) = when (workingConfig.onViolation) {
            RestrictionViolationAction.REMOVE -> Triple(Material.LAVA_BUCKET, "REMOVE", "Delete restricted items")
            RestrictionViolationAction.DROP -> Triple(Material.DROPPER, "DROP", "Drop items on ground")
            RestrictionViolationAction.PREVENT -> Triple(Material.BARRIER, "PREVENT", "Block group switch")
            RestrictionViolationAction.MOVE_TO_VAULT -> Triple(Material.ENDER_CHEST, "MOVE_TO_VAULT", "Store in player vault")
        }

        return GUIItemBuilder()
            .material(material)
            .name("Action: $actionName", NamedTextColor.GOLD)
            .lore(description)
            .lore("")
            .lore("Click to cycle through actions")
            .onClick { event ->
                val actions = RestrictionViolationAction.entries.toTypedArray()
                val currentIndex = actions.indexOf(workingConfig.onViolation)
                val nextIndex = (currentIndex + 1) % actions.size
                workingConfig = workingConfig.copy(onViolation = actions[nextIndex])
                hasChanges = true
                setupItems()
                refreshInventory()
            }
            .build()
    }

    private fun createNotificationButton(): GUIItem {
        val playerStatus = if (workingConfig.notifyPlayer) "ON" else "OFF"
        val adminStatus = if (workingConfig.notifyAdmins) "ON" else "OFF"

        return GUIItemBuilder()
            .material(Material.BELL)
            .name("Notifications", NamedTextColor.YELLOW)
            .lore("Notify Player: $playerStatus")
            .lore("Notify Admins: $adminStatus")
            .lore("")
            .lore("Left-click: Toggle player notifications")
            .lore("Right-click: Toggle admin notifications")
            .onClick { event ->
                workingConfig = when (event.click) {
                    ClickType.RIGHT -> workingConfig.copy(notifyAdmins = !workingConfig.notifyAdmins)
                    else -> workingConfig.copy(notifyPlayer = !workingConfig.notifyPlayer)
                }
                hasChanges = true
                setupItems()
                refreshInventory()
            }
            .build()
    }

    private fun setupPatternList() {
        // Combine active patterns and strip-on-exit patterns
        val activePatterns = workingConfig.getActivePatterns().map { it to false }
        val stripPatterns = workingConfig.stripOnExit.map { it to true }
        val allPatterns = activePatterns + stripPatterns

        // Calculate pagination
        val startIndex = currentPage * patternsPerPage
        val endIndex = minOf(startIndex + patternsPerPage, allPatterns.size)
        val patternsToShow = if (startIndex < allPatterns.size) {
            allPatterns.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        // Display patterns
        patternsToShow.forEachIndexed { index, (pattern, isStripOnExit) ->
            if (index < patternSlots.size) {
                val slot = patternSlots[index]
                setItem(slot, createPatternItem(pattern, isStripOnExit))
            }
        }

        // Fill remaining slots with empty indicators
        for (i in patternsToShow.size until patternSlots.size) {
            val slot = patternSlots[i]
            setItem(slot, GUIItemBuilder()
                .material(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                .name("Empty Slot", NamedTextColor.GRAY)
                .lore("Drag an item to the hopper")
                .lore("to add a restriction")
                .build()
            )
        }
    }

    private fun createPatternItem(pattern: String, isStripOnExit: Boolean): GUIItem {
        // Try to parse the pattern as a material for display
        val displayMaterial = try {
            Material.valueOf(pattern.replace("*", "STONE").replace(":", "_"))
        } catch (e: Exception) {
            Material.PAPER
        }

        val typeColor = if (isStripOnExit) NamedTextColor.LIGHT_PURPLE else NamedTextColor.RED
        val typeName = if (isStripOnExit) "[Strip on Exit]" else "[Restricted]"

        return GUIItemBuilder()
            .material(displayMaterial)
            .name(pattern, typeColor)
            .lore(typeName)
            .lore("")
            .lore("Left-click: Remove restriction")
            .lore("Right-click: Toggle strip-on-exit")
            .onClick { event ->
                when (event.click) {
                    ClickType.RIGHT -> {
                        // Toggle between normal restriction and strip-on-exit
                        if (isStripOnExit) {
                            workingConfig = workingConfig
                                .withoutStripOnExitPattern(pattern)
                                .let {
                                    when (workingConfig.mode) {
                                        RestrictionMode.BLACKLIST -> it.withBlacklistPattern(pattern)
                                        RestrictionMode.WHITELIST -> it.withWhitelistPattern(pattern)
                                        RestrictionMode.NONE -> it.withBlacklistPattern(pattern)
                                    }
                                }
                        } else {
                            workingConfig = when (workingConfig.mode) {
                                RestrictionMode.BLACKLIST -> workingConfig.withoutBlacklistPattern(pattern)
                                RestrictionMode.WHITELIST -> workingConfig.withoutWhitelistPattern(pattern)
                                RestrictionMode.NONE -> workingConfig
                            }.withStripOnExitPattern(pattern)
                        }
                    }
                    else -> {
                        // Remove the restriction
                        if (isStripOnExit) {
                            workingConfig = workingConfig.withoutStripOnExitPattern(pattern)
                        } else {
                            workingConfig = when (workingConfig.mode) {
                                RestrictionMode.BLACKLIST -> workingConfig.withoutBlacklistPattern(pattern)
                                RestrictionMode.WHITELIST -> workingConfig.withoutWhitelistPattern(pattern)
                                RestrictionMode.NONE -> workingConfig
                            }
                        }
                    }
                }
                hasChanges = true
                setupItems()
                refreshInventory()
            }
            .build()
    }

    private fun createInfoButton(): GUIItem {
        val totalPatterns = workingConfig.getActivePatterns().size
        val stripPatterns = workingConfig.stripOnExit.size

        return GUIItemBuilder()
            .material(Material.BOOK)
            .name("Restriction Info", NamedTextColor.AQUA)
            .lore("Mode: ${workingConfig.mode}")
            .lore("Active Patterns: $totalPatterns")
            .lore("Strip-on-Exit: $stripPatterns")
            .lore("Action: ${workingConfig.onViolation}")
            .lore("")
            .lore("Pattern Syntax:")
            .lore("- MATERIAL - exact match")
            .lore("- *_SWORD - wildcard match")
            .lore("- DIAMOND:SHARPNESS:5+ - enchant check")
            .build()
    }

    /**
     * Handles item being dragged into the GUI for restriction addition.
     */
    override fun onClick(event: InventoryClickEvent): Boolean {
        val slot = event.rawSlot

        // Check if clicking the hopper slot with a cursor item
        if (slot == 49 && event.cursor != null && !event.cursor!!.type.isAir) {
            val cursorItem = event.cursor!!
            val pattern = cursorItem.type.name
            addPattern(pattern)
            event.isCancelled = true
            setupItems()
            refreshInventory()
            val player = event.whoClicked as Player
            player.sendMessage(Component.text("Added restriction: $pattern", NamedTextColor.GREEN))
            return true
        }

        // Default behavior
        return super.onClick(event)
    }

    private fun addPattern(pattern: String) {
        // Validate the pattern
        val validation = plugin.serviceManager.restrictionService.validatePattern(pattern)
        if (validation.isFailure) {
            Logging.debug { "Invalid pattern: $pattern - ${validation.exceptionOrNull()?.message}" }
            return
        }

        // Add to the appropriate list based on mode
        workingConfig = when (workingConfig.mode) {
            RestrictionMode.BLACKLIST -> workingConfig.withBlacklistPattern(pattern)
            RestrictionMode.WHITELIST -> workingConfig.withWhitelistPattern(pattern)
            RestrictionMode.NONE -> {
                // Enable blacklist mode when adding first pattern
                workingConfig.copy(mode = RestrictionMode.BLACKLIST).withBlacklistPattern(pattern)
            }
        }
        hasChanges = true
    }

    private fun saveChanges() {
        if (group == null) return

        // Update the group's restrictions
        group.restrictions = workingConfig

        // Save to config
        plugin.serviceManager.groupService.saveToConfig()

        // Clear the restriction service cache
        plugin.serviceManager.restrictionService.clearCache()

        hasChanges = false
        Logging.info("Saved restriction config for group '$groupName'")
    }

    private fun refreshInventory() {
        val inv = inventory ?: return

        // Clear and repopulate
        for (i in 0 until size) {
            val guiItem = items[i]
            inv.setItem(i, guiItem?.itemStack)
        }
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

    override fun onClose(event: InventoryCloseEvent) {
        if (hasChanges) {
            val player = event.player as? Player ?: return
            player.sendMessage(
                Component.text("Warning: You have unsaved changes to restrictions!", NamedTextColor.YELLOW)
            )
        }
    }
}

/**
 * Input manager extension for chat input handling.
 * This should be part of PluginContext.
 */
val PluginContext.inputManager: InputManager
    get() = InputManager.getInstance(this)

/**
 * Simple input manager for awaiting chat input from players.
 */
class InputManager private constructor(private val plugin: PluginContext) {
    private val pendingInputs = mutableMapOf<java.util.UUID, (String) -> Unit>()

    init {
        plugin.plugin.server.pluginManager.registerEvents(object : org.bukkit.event.Listener {
            // Deprecated but kept intentionally for Spigot compatibility (no AsyncChatEvent there);
            // still present in paper-api 26.1.2. See GUIManager.onPlayerChat for the rationale.
            @Suppress("DEPRECATION")
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
            fun onChat(event: org.bukkit.event.player.AsyncPlayerChatEvent) {
                val callback = pendingInputs.remove(event.player.uniqueId) ?: return
                event.isCancelled = true
                plugin.plugin.server.scheduler.runTask(plugin.plugin, Runnable {
                    callback(event.message)
                })
            }

            @org.bukkit.event.EventHandler
            fun onQuit(event: org.bukkit.event.player.PlayerQuitEvent) {
                pendingInputs.remove(event.player.uniqueId)
            }
        }, plugin.plugin)
    }

    fun awaitChatInput(player: Player, callback: (String) -> Unit) {
        pendingInputs[player.uniqueId] = callback
    }

    companion object {
        private var instance: InputManager? = null

        fun getInstance(plugin: PluginContext): InputManager {
            return instance ?: InputManager(plugin).also { instance = it }
        }
    }
}
