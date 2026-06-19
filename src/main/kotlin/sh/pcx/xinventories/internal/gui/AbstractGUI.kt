package sh.pcx.xinventories.internal.gui

import sh.pcx.xinventories.PluginContext
import sh.pcx.xinventories.internal.util.Logging
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * Abstract base class for GUI implementations.
 */
abstract class AbstractGUI(
    protected val plugin: PluginContext,
    override val title: Component,
    override val size: Int
) : GUI {

    protected val items = mutableMapOf<Int, GUIItem>()
    protected var inventory: Inventory? = null

    override fun createInventory(player: Player): Inventory {
        // Use a custom holder so the inventory carries its own GUI identity. Event handlers can then
        // recognize our GUIs via the holder instead of a side tracking map, which prevents clicks
        // from slipping through uncancelled when that map is out of sync (reload/navigation).
        val holder = XInvGuiHolder(this)
        val inv = Bukkit.createInventory(holder, size, title)
        holder.attach(inv)

        // Populate with items from the items map
        items.forEach { (slot, guiItem) ->
            if (slot in 0 until size) {
                inv.setItem(slot, guiItem.itemStack)
            }
        }

        // Fill empty slots if needed
        fillEmptySlots(inv)

        inventory = inv
        return inv
    }

    override fun open(player: Player) {
        plugin.guiManager.open(player, this)
    }

    override fun onClick(event: InventoryClickEvent): Boolean {
        val slot = event.rawSlot

        // Validate slot is within our GUI bounds
        if (slot < 0 || slot >= size) {
            // Click in player inventory or outside - ignore
            return true
        }

        // Look up the GUIItem for this slot
        val guiItem = items[slot]
        Logging.debug { "Click at slot $slot in ${this.javaClass.simpleName}, hasItem=${guiItem != null}, hasHandler=${guiItem?.onClick != null}" }

        if (guiItem != null && guiItem.onClick != null) {
            try {
                Logging.debug { "Invoking click handler for slot $slot in ${this.javaClass.simpleName}" }
                guiItem.onClick.invoke(event)
                Logging.debug { "Click handler completed for slot $slot" }
            } catch (e: Exception) {
                Logging.error("Error in GUI click handler at slot $slot", e)
            }
        } else {
            Logging.debug { "No click handler for slot $slot (item=${guiItem?.itemStack?.type})" }
        }

        return true // Always cancel
    }

    override fun onClose(event: InventoryCloseEvent) {
        // Override in subclasses if needed
    }

    override fun refresh(player: Player) {
        val inv = inventory ?: return

        items.forEach { (slot, guiItem) ->
            if (slot in 0 until size) {
                inv.setItem(slot, guiItem.itemStack)
            }
        }
    }

    /**
     * Sets an item in a slot.
     */
    protected fun setItem(slot: Int, item: GUIItem) {
        if (slot in 0 until size) {
            items[slot] = item
        } else {
            Logging.warning("Attempted to set item at invalid slot $slot (size=$size) in ${this.javaClass.simpleName}")
        }
    }

    /**
     * Sets an item using row and column.
     */
    protected fun setItem(row: Int, col: Int, item: GUIItem) {
        val slot = row * 9 + col
        setItem(slot, item)
    }

    /**
     * Fills a row with an item.
     */
    protected fun fillRow(row: Int, item: GUIItem) {
        for (col in 0..8) {
            setItem(row, col, item)
        }
    }

    /**
     * Fills a column with an item.
     */
    protected fun fillColumn(col: Int, item: GUIItem) {
        for (row in 0 until (size / 9)) {
            setItem(row, col, item)
        }
    }

    /**
     * Fills the border with an item.
     */
    protected fun fillBorder(item: GUIItem) {
        val rows = size / 9

        // Top and bottom rows
        fillRow(0, item)
        if (rows > 1) {
            fillRow(rows - 1, item)
        }

        // Left and right columns (excluding corners already filled)
        for (row in 1 until (rows - 1)) {
            setItem(row, 0, item)
            setItem(row, 8, item)
        }
    }

    /**
     * Fills empty slots with an item.
     * Note: This only fills the Bukkit inventory visually.
     * For click handling, items must be added to the items map via setItem().
     */
    protected open fun fillEmptySlots(inventory: Inventory) {
        // Override in subclasses to fill empty slots with filler items
    }

    /**
     * Gets the number of items registered for click handling.
     */
    protected fun getRegisteredItemCount(): Int = items.size
}

/**
 * Represents an item in a GUI with optional click handler.
 */
data class GUIItem(
    val itemStack: ItemStack,
    val onClick: ((InventoryClickEvent) -> Unit)? = null
)
