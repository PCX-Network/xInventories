package sh.pcx.xinventories.internal.gui

import sh.pcx.xinventories.PluginContext
import sh.pcx.xinventories.internal.util.Logging
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.inventory.Inventory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages GUI instances and handles inventory events.
 */
class GUIManager(private val plugin: PluginContext) : Listener {

    private val openGUIs = ConcurrentHashMap<UUID, GUI>()
    private val openInventories = ConcurrentHashMap<UUID, Inventory>()
    private val chatInputHandlers = ConcurrentHashMap<UUID, (String) -> Unit>()

    fun initialize() {
        plugin.plugin.server.pluginManager.registerEvents(this, plugin.plugin)
        Logging.debug("GUIManager initialized and events registered")
    }

    /**
     * Opens a GUI for a player.
     */
    fun open(player: Player, gui: GUI) {
        Logging.debug("Opening GUI ${gui.javaClass.simpleName} for ${player.name}")

        // Create the inventory first
        val inventory = gui.createInventory(player)

        // Track the GUI BEFORE opening the inventory
        openGUIs[player.uniqueId] = gui
        openInventories[player.uniqueId] = inventory

        // Open for player - this may trigger InventoryCloseEvent for any previous inventory
        player.openInventory(inventory)

        Logging.debug("GUI opened, tracking ${openGUIs.size} GUIs")
    }

    /**
     * Closes any GUI the player has open.
     */
    fun close(player: Player) {
        openGUIs.remove(player.uniqueId)
        openInventories.remove(player.uniqueId)
    }

    /**
     * Gets the GUI a player has open, if any.
     */
    fun getOpenGUI(player: Player): GUI? = openGUIs[player.uniqueId]

    /**
     * Checks if a player has a GUI open.
     */
    fun hasGUIOpen(player: Player): Boolean = openGUIs.containsKey(player.uniqueId)

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return

        // Identify our GUI from the TOP inventory's holder. This identity is intrinsic to the
        // inventory, so it stays correct even when the tracking maps are out of sync (after a
        // reload, during navigation, or on rapid re-open) - which is what previously let items
        // leak out with no handler firing.
        val top = event.view.topInventory
        val holder = top.holder as? XInvGuiHolder ?: return
        val gui = holder.gui
        val editable = gui.editableSlots()
        val action = event.action

        // Two actions can pull items out of the GUI no matter which slot was clicked: a
        // double-click gathers matching stacks from both inventories, and a creative middle-click
        // clones whatever is under the cursor. Neither has a legitimate use in any of our GUIs.
        if (action == InventoryAction.COLLECT_TO_CURSOR || action == InventoryAction.CLONE_STACK) {
            deny(event)
            return
        }

        val clicked = event.clickedInventory
        if (clicked == null) {
            // Click outside the window: drops whatever is on the cursor. The cursor can only hold
            // the player's own items, or items from an editable slot the admin is deliberately
            // discarding, so this is fine for an editor and pointless for a read-only menu.
            if (editable.isEmpty()) deny(event)
            return
        }

        if (clicked != top) {
            // The player's own inventory. Rearranging it is allowed (it is what lets an admin
            // pick an item up to drop into an editor or onto the restriction hopper), but a
            // shift-click would push the item into the GUI, which only an editor may accept.
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                if (editable.isEmpty()) {
                    deny(event)
                } else {
                    scheduleChanged(gui, player)
                }
            }
            return
        }

        val slot = event.rawSlot
        if (slot in editable) {
            // A real item slot of an editor: let vanilla move things, then tell the GUI.
            scheduleChanged(gui, player)
            return
        }

        // A button or display item. Cancel ALL clicks unconditionally and FIRST, before any further
        // branching. Shift-click, number-key swaps, drops and plain pickups must never move it.
        deny(event)

        // Pure item-move actions are never treated as a button activation.
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY ||
            action == InventoryAction.HOTBAR_SWAP ||
            action == InventoryAction.HOTBAR_MOVE_AND_READD) {
            return
        }

        // Let the GUI handle the click for its logic
        try {
            gui.onClick(event)
        } catch (e: Exception) {
            Logging.error("Error handling GUI click for ${player.name}", e)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val top = event.view.topInventory
        val holder = top.holder as? XInvGuiHolder ?: return
        val editable = holder.gui.editableSlots()

        // A drag may touch the player's own slots and an editor's item slots; anything that would
        // land on a button or display item cancels the whole drag.
        val touchesGui = event.rawSlots.any { it < top.size }
        val allowed = event.rawSlots.all { it >= top.size || it in editable }
        if (!allowed) {
            deny(event)
            return
        }
        if (touchesGui) scheduleChanged(holder.gui, player)
    }

    private fun deny(event: org.bukkit.event.Cancellable) {
        event.isCancelled = true
        if (event is InventoryClickEvent) event.result = org.bukkit.event.Event.Result.DENY
        if (event is InventoryDragEvent) event.result = org.bukkit.event.Event.Result.DENY
    }

    /** The inventory only reflects a vanilla click after the event, so notify on the next tick. */
    private fun scheduleChanged(gui: GUI, player: Player) {
        plugin.plugin.server.scheduler.runTask(plugin.plugin, Runnable {
            try {
                gui.onEditableSlotsChanged(player)
            } catch (e: Exception) {
                Logging.error("Error handling GUI edit for ${player.name}", e)
            }
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return

        // Resolve the GUI from the closing inventory's own holder (robust to tracking desync).
        val holder = event.inventory.holder as? XInvGuiHolder ?: return

        // Only clear tracking if it still points at the inventory being closed. During navigation
        // (opening GUI B over GUI A), A's close fires *after* tracking already points at B - we must
        // not clear B's entry here.
        if (openInventories[player.uniqueId] == event.inventory) {
            openGUIs.remove(player.uniqueId)
            openInventories.remove(player.uniqueId)
        }

        // Always notify the GUI that actually closed.
        try {
            holder.gui.onClose(event)
        } catch (e: Exception) {
            Logging.error("Error handling GUI close for ${player.name}", e)
        }

        Logging.debug { "GUI closed for ${player.name}, tracking ${openGUIs.size} GUIs" }
    }

    /**
     * Registers a chat input handler for a player.
     * The handler will be called with the player's next chat message.
     */
    fun registerChatInput(player: Player, handler: (String) -> Unit) {
        chatInputHandlers[player.uniqueId] = handler
        Logging.debug("Registered chat input handler for ${player.name}")
    }

    /**
     * Cancels any pending chat input handler for a player.
     */
    fun cancelChatInput(player: Player) {
        chatInputHandlers.remove(player.uniqueId)
    }

    /**
     * Checks if a player has a chat input handler registered.
     */
    fun hasChatInput(player: Player): Boolean = chatInputHandlers.containsKey(player.uniqueId)

    // NOTE: We intentionally use the deprecated AsyncPlayerChatEvent rather than Paper's
    // AsyncChatEvent. xInventories supports Spigot as well, where AsyncChatEvent does not exist;
    // AsyncPlayerChatEvent is the only chat event available on both platforms and is still present
    // (deprecated) in paper-api 26.1.2.
    @Suppress("DEPRECATION")
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerChat(event: AsyncPlayerChatEvent) {
        val handler = chatInputHandlers.remove(event.player.uniqueId) ?: return

        // Cancel the chat event so the message isn't broadcast
        event.isCancelled = true

        // Run the handler on the main thread
        plugin.plugin.server.scheduler.runTask(plugin.plugin, Runnable {
            try {
                handler(event.message)
            } catch (e: Exception) {
                Logging.error("Error in chat input handler for ${event.player.name}", e)
            }
        })
    }

    fun shutdown() {
        // Close all open GUIs
        openGUIs.keys.toList().forEach { uuid ->
            plugin.plugin.server.getPlayer(uuid)?.closeInventory()
        }
        openGUIs.clear()
        openInventories.clear()
        chatInputHandlers.clear()
    }
}
