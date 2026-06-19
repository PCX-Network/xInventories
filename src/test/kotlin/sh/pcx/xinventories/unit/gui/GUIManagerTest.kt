package sh.pcx.xinventories.unit.gui

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkAll
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import sh.pcx.xinventories.PluginContext
import sh.pcx.xinventories.internal.gui.AbstractGUI
import sh.pcx.xinventories.internal.gui.GUIItem
import sh.pcx.xinventories.internal.gui.GUIManager
import sh.pcx.xinventories.internal.gui.XInvGuiHolder
import sh.pcx.xinventories.internal.util.Logging

/**
 * Tests for the GUI item-leak fix: GUI inventories carry an [XInvGuiHolder], and [GUIManager]
 * cancels every click/drag while one of our GUIs is the top inventory - even when the manager's
 * tracking maps are empty (the post-reload / navigation desync that previously let items leak out).
 */
@DisplayName("GUIManager click cancellation")
class GUIManagerTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: PluginContext
    private lateinit var manager: GUIManager

    /** Minimal concrete GUI with one button at slot 13 that records the slot it was clicked at. */
    private class TestGUI(plugin: PluginContext) : AbstractGUI(plugin, Component.text("Test GUI"), 27) {
        var handlerFiredSlot: Int? = null
        init {
            setItem(13, GUIItem(ItemStack(Material.DIAMOND)) { e -> handlerFiredSlot = e.rawSlot })
        }
    }

    @BeforeEach
    fun setUp() {
        mockkObject(Logging)
        every { Logging.debug(any<() -> String>()) } just Runs
        every { Logging.debug(any<String>()) } just Runs
        every { Logging.info(any()) } just Runs
        every { Logging.warning(any()) } just Runs
        every { Logging.error(any<String>()) } just Runs
        every { Logging.error(any<String>(), any()) } just Runs

        server = MockBukkit.mock()
        plugin = mockk(relaxed = true)
        manager = GUIManager(plugin)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
        unmockkAll()
    }

    private fun clickEvent(
        player: PlayerMock,
        gui: TestGUI,
        rawSlot: Int,
        action: InventoryAction = InventoryAction.PICKUP_ALL,
        click: ClickType = ClickType.LEFT,
    ): InventoryClickEvent {
        // Build the inventory directly via the GUI (NOT through manager.open), so the manager's
        // tracking maps are deliberately empty - this reproduces the desync condition.
        val inv = gui.createInventory(player)
        val view = player.openInventory(inv)!!
        return InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, click, action)
    }

    @Test
    @DisplayName("GUI inventory carries an XInvGuiHolder pointing back to the GUI")
    fun inventoryHasHolder() {
        val player = server.addPlayer()
        val gui = TestGUI(plugin)
        val inv = gui.createInventory(player)

        val holder = assertInstanceOf(XInvGuiHolder::class.java, inv.holder)
        assertEquals(gui, holder.gui)
    }

    @Test
    @DisplayName("click inside the GUI is cancelled even with empty tracking maps (reload desync)")
    fun clickCancelledWithoutTracking() {
        val player = server.addPlayer()
        val gui = TestGUI(plugin)
        val event = clickEvent(player, gui, rawSlot = 13)

        manager.onInventoryClick(event)

        assertTrue(event.isCancelled, "click must be cancelled via holder identity, not tracking maps")
        assertEquals(13, gui.handlerFiredSlot, "the slot's click handler should still fire")
    }

    @Test
    @DisplayName("shift-click from the player's inventory is cancelled and does not fire a handler")
    fun shiftClickFromBottomCancelled() {
        val player = server.addPlayer()
        val gui = TestGUI(plugin)
        // rawSlot 31 is in the bottom (player) inventory for a 27-slot top inventory.
        val event = clickEvent(
            player, gui, rawSlot = 31,
            action = InventoryAction.MOVE_TO_OTHER_INVENTORY, click = ClickType.SHIFT_LEFT,
        )

        manager.onInventoryClick(event)

        assertTrue(event.isCancelled, "shift-click moving items must be cancelled")
        assertNull(gui.handlerFiredSlot, "bottom-inventory clicks must not trigger button handlers")
    }

    @Test
    @DisplayName("number-key / collect-to-cursor / double-click move actions are cancelled")
    fun moveActionsCancelled() {
        val player = server.addPlayer()
        val gui = TestGUI(plugin)

        for (action in listOf(
            InventoryAction.HOTBAR_SWAP,
            InventoryAction.HOTBAR_MOVE_AND_READD,
            InventoryAction.COLLECT_TO_CURSOR,
        )) {
            val event = clickEvent(player, gui, rawSlot = 13, action = action, click = ClickType.NUMBER_KEY)
            manager.onInventoryClick(event)
            assertTrue(event.isCancelled, "$action must be cancelled")
        }
    }

    @Test
    @DisplayName("click in an unrelated (non-xInventories) inventory is left untouched")
    fun foreignInventoryIgnored() {
        val player = server.addPlayer()
        val chest = server.createInventory(null, InventoryType.CHEST)
        val view = player.openInventory(chest)!!
        val event = InventoryClickEvent(
            view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL,
        )

        manager.onInventoryClick(event)

        assertFalse(event.isCancelled, "non-xInventories inventories must not be cancelled by us")
    }
}
