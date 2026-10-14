package sh.pcx.xinventories.internal.command

import sh.pcx.xinventories.PluginContext
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * Main command executor for /xinventories (/xinv).
 */
class XInventoriesCommand(
    private val plugin: PluginContext,
    private val commandManager: CommandManager
) : CommandExecutor, TabCompleter {

    companion object {
        private const val COMMANDS_PER_PAGE = 8
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>
    ): Boolean {
        val messages = plugin.serviceManager.messageService

        if (args.isEmpty()) {
            showHelp(sender, 1)
            return true
        }

        val subcommandName = args[0]

        // Handle "help" specially for pagination
        if (subcommandName.equals("help", ignoreCase = true)) {
            val page = args.getOrNull(1)?.toIntOrNull() ?: 1
            showHelp(sender, page)
            return true
        }

        // Check if first arg is a page number (for /xinv 2 style navigation)
        val pageNumber = subcommandName.toIntOrNull()
        if (pageNumber != null) {
            showHelp(sender, pageNumber)
            return true
        }

        val subcommand = commandManager.getSubcommand(subcommandName)

        if (subcommand == null) {
            messages.send(sender, "unknown-command", "command" to subcommandName)
            return true
        }

        // Check permission
        if (!sender.hasPermission(subcommand.permission)) {
            messages.send(sender, "no-permission")
            return true
        }

        // Player-only subcommands cast the sender to Player, so reject console/command blocks here
        if (subcommand.playerOnly && sender !is Player) {
            messages.send(sender, "player-only")
            return true
        }

        // Execute subcommand asynchronously
        val subArgs = args.drop(1).toTypedArray()

        plugin.scope.launch {
            try {
                subcommand.execute(plugin, sender, subArgs)
            } catch (e: Exception) {
                plugin.plugin.logger.severe("Error executing command ${subcommand.name}: ${e.message}")
                e.printStackTrace()
                messages.send(sender, "command-error")
            }
        }

        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<String>
    ): List<String> {
        if (args.isEmpty()) {
            return emptyList()
        }

        if (args.size == 1) {
            // Complete subcommand names + help
            val completions = mutableListOf("help")
            completions.addAll(
                commandManager.getAllSubcommands()
                    .filter { sender.hasPermission(it.permission) }
                    .map { it.name }
            )
            return completions
                .filter { it.lowercase().startsWith(args[0].lowercase()) }
                .sorted()
        }

        // Handle help page completion
        if (args[0].equals("help", ignoreCase = true) && args.size == 2) {
            val subcommands = commandManager.getAllSubcommands()
                .filter { sender.hasPermission(it.permission) }
            val maxPage = (subcommands.size + COMMANDS_PER_PAGE - 1) / COMMANDS_PER_PAGE
            return (1..maxPage).map { it.toString() }
                .filter { it.startsWith(args[1]) }
        }

        // Delegate to subcommand
        val subcommand = commandManager.getSubcommand(args[0])
        if (subcommand == null || !sender.hasPermission(subcommand.permission)) {
            return emptyList()
        }

        return subcommand.tabComplete(plugin, sender, args.drop(1).toTypedArray())
    }

    private fun showHelp(sender: CommandSender, page: Int) {
        val primaryColor = TextColor.color(0x5e4fa2)  // Purple
        val secondaryColor = TextColor.color(0x9e7bb5) // Light purple
        val accentColor = TextColor.color(0x5dade2)   // Cyan/blue

        fun send(component: Component) = sender.sendMessage(component)

        // Get available subcommands grouped by category
        val subcommands = commandManager.getAllSubcommands()
            .filter { sender.hasPermission(it.permission) }

        // Define categories with their commands
        val categories = listOf(
            "Core" to listOf("gui", "reload", "save", "load"),
            "Management" to listOf("group", "world", "pattern", "template", "restrict"),
            "Data" to listOf("history", "restore", "snapshot", "deaths", "vault"),
            "Groups" to listOf("conditions", "whoami", "lock", "unlock", "tempgroup", "expiration"),
            "Utilities" to listOf("cache", "backup", "convert", "debug", "sync", "reset"),
            "Import/Export" to listOf("import", "export", "importjson", "merge", "balance"),
            "Admin" to listOf("bulk", "audit", "stats")
        )

        // Build flat list of (category, command) pairs for pagination
        val allEntries = mutableListOf<Pair<String?, sh.pcx.xinventories.internal.command.subcommand.Subcommand>>()
        val categorizedCommands = mutableSetOf<String>()

        for ((category, commandNames) in categories) {
            val categoryCommands = subcommands.filter { it.name in commandNames }.sortedBy { it.name }
            if (categoryCommands.isNotEmpty()) {
                // Add category header marker (null command)
                allEntries.add(category to categoryCommands.first()) // First entry includes category
                categoryCommands.forEachIndexed { index, cmd ->
                    if (index == 0) {
                        // Already added with category
                    } else {
                        allEntries.add(null to cmd)
                    }
                    categorizedCommands.add(cmd.name)
                }
            }
        }

        // Add uncategorized commands
        val uncategorized = subcommands.filter { it.name !in categorizedCommands }.sortedBy { it.name }
        if (uncategorized.isNotEmpty()) {
            allEntries.add("Other" to uncategorized.first())
            uncategorized.forEachIndexed { index, cmd ->
                if (index > 0) {
                    allEntries.add(null to cmd)
                }
                categorizedCommands.add(cmd.name)
            }
        }

        // Calculate pagination
        val totalCommands = subcommands.size
        val maxPage = (totalCommands + COMMANDS_PER_PAGE - 1) / COMMANDS_PER_PAGE
        val currentPage = page.coerceIn(1, maxPage.coerceAtLeast(1))

        // Build paginated command list maintaining category groupings
        val paginatedCommands = mutableListOf<Pair<String?, sh.pcx.xinventories.internal.command.subcommand.Subcommand>>()
        var commandCount = 0
        var startIndex = (currentPage - 1) * COMMANDS_PER_PAGE
        var currentCategory: String? = null

        for (entry in allEntries) {
            val (category, cmd) = entry
            if (category != null) {
                currentCategory = category
            }

            if (commandCount >= startIndex && paginatedCommands.size < COMMANDS_PER_PAGE) {
                // If this is the first command on the page and we're mid-category, add the category header
                if (paginatedCommands.isEmpty() && category == null && currentCategory != null) {
                    paginatedCommands.add(currentCategory to cmd)
                } else {
                    paginatedCommands.add(entry)
                }
            }
            commandCount++
        }

        // Header
        send(Component.empty())
        send(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY))
        send(
            Component.text()
                .append(Component.text("  ◆ ", primaryColor))
                .append(Component.text("x", primaryColor).decorate(TextDecoration.BOLD))
                .append(Component.text("Inventories", secondaryColor).decorate(TextDecoration.BOLD))
                .append(Component.text(" Commands", NamedTextColor.GRAY))
                .build()
        )
        send(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY))

        // Display commands for current page
        var lastCategory: String? = null
        for ((category, cmd) in paginatedCommands) {
            if (category != null && category != lastCategory) {
                send(Component.empty())
                send(Component.text("  $category", accentColor).decorate(TextDecoration.BOLD))
                lastCategory = category
            }
            send(buildCommandEntry(cmd, primaryColor, accentColor))
        }

        // Navigation footer
        send(Component.empty())
        send(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY))

        // Build navigation row
        val navBuilder = Component.text().append(Component.text("  "))

        // Previous button
        if (currentPage > 1) {
            navBuilder.append(
                Component.text("[")
                    .color(NamedTextColor.DARK_GRAY)
            ).append(
                Component.text("< Prev")
                    .color(accentColor)
                    .decorate(TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand("/xinv help ${currentPage - 1}"))
                    .hoverEvent(HoverEvent.showText(Component.text("Go to page ${currentPage - 1}", NamedTextColor.GRAY)))
            ).append(
                Component.text("]")
                    .color(NamedTextColor.DARK_GRAY)
            ).append(Component.text("  "))
        } else {
            navBuilder.append(
                Component.text("[< Prev]  ", NamedTextColor.DARK_GRAY)
            )
        }

        // Page indicator
        navBuilder.append(
            Component.text("Page $currentPage/$maxPage", NamedTextColor.GRAY)
        ).append(Component.text("  "))

        // Next button
        if (currentPage < maxPage) {
            navBuilder.append(
                Component.text("[")
                    .color(NamedTextColor.DARK_GRAY)
            ).append(
                Component.text("Next >")
                    .color(accentColor)
                    .decorate(TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand("/xinv help ${currentPage + 1}"))
                    .hoverEvent(HoverEvent.showText(Component.text("Go to page ${currentPage + 1}", NamedTextColor.GRAY)))
            ).append(
                Component.text("]")
                    .color(NamedTextColor.DARK_GRAY)
            )
        } else {
            navBuilder.append(
                Component.text("[Next >]", NamedTextColor.DARK_GRAY)
            )
        }

        send(navBuilder.build())

        // Tip
        send(
            Component.text()
                .append(Component.text("  Tip: ", NamedTextColor.GRAY))
                .append(Component.text("Click", accentColor).decorate(TextDecoration.UNDERLINED))
                .append(Component.text(" commands to use them", NamedTextColor.GRAY))
                .build()
        )
        send(Component.empty())
    }

    private fun buildCommandEntry(
        subcommand: sh.pcx.xinventories.internal.command.subcommand.Subcommand,
        primaryColor: TextColor,
        accentColor: TextColor
    ): Component {
        // Extract the base command for clicking (e.g., "/xinv gui" from "/xinv gui")
        val baseCommand = "/xinv ${subcommand.name}"

        // Build hover text
        val hoverText = Component.text()
            .append(Component.text(subcommand.usage, accentColor).decorate(TextDecoration.BOLD))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text(subcommand.description, NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text("Permission: ", NamedTextColor.DARK_GRAY))
            .append(Component.text(subcommand.permission, NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text("▶ Click to run", NamedTextColor.GREEN))
            .build()

        return Component.text()
            .append(Component.text("    "))
            .append(
                Component.text("▸ ", primaryColor)
            )
            .append(
                Component.text(subcommand.name, accentColor)
                    .clickEvent(ClickEvent.suggestCommand(baseCommand + " "))
                    .hoverEvent(HoverEvent.showText(hoverText))
            )
            .append(
                Component.text(" - ", NamedTextColor.DARK_GRAY)
            )
            .append(
                Component.text(subcommand.description, NamedTextColor.GRAY)
                    .clickEvent(ClickEvent.suggestCommand(baseCommand + " "))
                    .hoverEvent(HoverEvent.showText(hoverText))
            )
            .build()
    }
}
