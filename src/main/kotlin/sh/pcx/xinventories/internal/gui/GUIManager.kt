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
        val holder = event.view.topInventory.holder as? XInvGuiHolder ?: return
        val gui = holder.gui

        // Cancel ALL clicks unconditionally and FIRST, before any further branching. Any click while
        // one of our GUIs is the top inventory - including shift-click, number-key swaps,
        // collect-to-cursor, and clicks originating in the player's own inventory - must never move
        // items. Cancelling up front is what guarantees nothing slips through.
        event.isCancelled = true
        event.result = org.bukkit.event.Event.Result.DENY

        // Pure item-move actions are never treated as a button activation.
        if (event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY ||
            event.action == InventoryAction.COLLECT_TO_CURSOR ||
            event.action == InventoryAction.HOTBAR_SWAP ||
            event.action == InventoryAction.HOTBAR_MOVE_AND_READD) {
            return // Already cancelled above; don't dispatch to slot handlers.
        }

        // Only dispatch slot logic for clicks inside our GUI (the top inventory). Clicks in the
        // player's own inventory are already cancelled above; there's nothing else to do.
        val clickedInventory = event.clickedInventory ?: return
        if (clickedInventory != event.view.topInventory) {
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
        // Cancel any drag while one of our GUIs is the top inventory. This covers drags that span
        // both the GUI and the player's inventory, and drags entirely within either one.
        if (event.view.topInventory.holder !is XInvGuiHolder) return

        event.isCancelled = true
        event.result = org.bukkit.event.Event.Result.DENY
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
