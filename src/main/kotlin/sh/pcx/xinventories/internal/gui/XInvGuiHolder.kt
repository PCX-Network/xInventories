package sh.pcx.xinventories.internal.gui

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/**
 * Custom [InventoryHolder] that ties a Bukkit [Inventory] back to its owning [GUI].
 *
 * GUI identity is made *intrinsic to the inventory itself*: event handlers recognize one of our
 * GUIs via `inventory.holder is XInvGuiHolder` regardless of any side-channel tracking state. This
 * removes the class of bugs where a desynced tracking map let clicks through uncancelled — e.g.
 * after a `/xinv reload` (which clears the tracking maps) or during open/close navigation, a player
 * could previously move items out of a GUI and the click handler would never fire.
 *
 * The holder is created before the inventory (Bukkit requires the holder to build the inventory),
 * then [attach]ed to the inventory immediately afterwards so [getInventory] resolves correctly.
 */
class XInvGuiHolder(val gui: GUI) : InventoryHolder {

    private var backing: Inventory? = null

    /**
     * Associates the created inventory with this holder. Called once, right after
     * `Bukkit.createInventory(holder, ...)` returns.
     */
    fun attach(inventory: Inventory) {
        this.backing = inventory
    }

    override fun getInventory(): Inventory =
        backing ?: error("XInvGuiHolder inventory accessed before it was attached")
}
