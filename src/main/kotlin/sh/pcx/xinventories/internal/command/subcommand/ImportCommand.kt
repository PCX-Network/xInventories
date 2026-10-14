package sh.pcx.xinventories.internal.command.subcommand

import sh.pcx.xinventories.PluginContext
import sh.pcx.xinventories.internal.import.ImportService
import sh.pcx.xinventories.internal.model.ImportMapping
import sh.pcx.xinventories.internal.model.ImportOptions
import org.bukkit.command.CommandSender

/**
 * Command for importing data from other inventory plugins.
 *
 * Usage:
 * - /xinv import detect - Detect importable plugins
 * - /xinv import preview <plugin> - Preview import (dry run)
 * - /xinv import <plugin> [--confirm] - Execute import
 * - /xinv import <plugin> mapping - Show/edit group mapping
 */
class ImportCommand(private val importService: ImportService) : Subcommand {

    override val name = "import"
    override val aliases = emptyList<String>()
    override val permission = "xinventories.command.import"
    override val usage = "/xinv import detect | preview <plugin> | <plugin> [--confirm] | <plugin> mapping"
    override val description = "Import data from other inventory plugins"

    override suspend fun execute(plugin: PluginContext, sender: CommandSender, args: Array<String>): Boolean {
        val messages = plugin.serviceManager.messageService

        if (args.isEmpty()) {
            showHelp(sender)
            return true
        }

        val subcommand = args[0].lowercase()

        return when (subcommand) {
            "detect" -> handleDetect(sender)
            "preview" -> handlePreview(sender, args.drop(1).toTypedArray())
            "mapping" -> {
                if (args.size < 2) {
                    sender.sendMessage("Usage: /xinv import mapping <plugin>")
                    return true
                }
                handleMapping(sender, args[1])
            }
            "report" -> handleReport(sender)
            else -> {
                // Treat as plugin name
                handleImport(sender, args)
            }
        }
    }

    private fun showHelp(sender: CommandSender) {
        sender.sendMessage("Import data from other inventory plugins:")
        sender.sendMessage("")
        sender.sendMessage("  /xinv import detect")
        sender.sendMessage("    Detect available import sources")
        sender.sendMessage("")
        sender.sendMessage("  /xinv import preview <plugin>")
        sender.sendMessage("    Preview what will be imported (dry run)")
        sender.sendMessage("")
        sender.sendMessage("  /xinv import <plugin> [--confirm]")
        sender.sendMessage("    Import data from a plugin")
        sender.sendMessage("    Use --confirm to skip confirmation prompt")
        sender.sendMessage("")
        sender.sendMessage("  /xinv import <plugin> mapping")
        sender.sendMessage("    View or edit group mappings")
        sender.sendMessage("")
        sender.sendMessage("  /xinv import report")
        sender.sendMessage("    View last import report")
        sender.sendMessage("")
        sender.sendMessage("Supported plugins: pwi (PerWorldInventory), mvi (Multiverse-Inventories), myworlds (MyWorlds)")
    }

    private fun handleDetect(sender: CommandSender): Boolean {
        val availableSources = importService.detectSources()

        if (availableSources.isEmpty()) {
            sender.sendMessage("No importable data sources detected.")
            sender.sendMessage("Checked for: PerWorldInventory, Multiverse-Inventories, MyWorlds")
            return true
        }

        sender.sendMessage("Detected import sources:")
        sender.sendMessage("")

        availableSources.forEach { source ->
            val validation = source.validate()
            val apiStatus = if (source.hasApiAccess) "API available" else "File-based only"

            sender.sendMessage("  ${source.name} (${source.id})")
            sender.sendMessage("    Status: $apiStatus")
            sender.sendMessage("    Players: ${validation.playerCount}")
            sender.sendMessage("    Groups: ${validation.groupCount}")

            if (validation.warnings.isNotEmpty()) {
                sender.sendMessage("    Warnings:")
                validation.warnings.take(3).forEach { warning ->
                    sender.sendMessage("      - $warning")
                }
            }
            sender.sendMessage("")
        }

        sender.sendMessage("Use '/xinv import preview <plugin>' to preview an import")
        return true
    }

    private suspend fun handlePreview(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("Usage: /xinv import preview <plugin>")
            sender.sendMessage("Available: pwi, mvi, myworlds")
            return true
        }

        val sourceId = args[0].lowercase()
        val source = importService.getSource(sourceId)

        if (source == null) {
            sender.sendMessage("Unknown import source: ${args[0]}")
            sender.sendMessage("Available: pwi, mvi, myworlds")
            return true
        }

        if (!source.isAvailable) {
            sender.sendMessage("Import source '${source.name}' is not available (no data found)")
            return true
        }

        sender.sendMessage("Generating import preview for ${source.name}...")

        // Load existing mapping or create default
        val mappingFile = importService.getMappingFile()
        var mapping = importService.loadMapping(mappingFile)

        if (mapping == null || mapping.source != sourceId) {
            // Create identity mapping
            val groups = source.getGroups()
            mapping = ImportMapping.identity(sourceId, groups)
        }

        val preview = importService.previewImport(sourceId, mapping)

        if (preview == null) {
            sender.sendMessage("Failed to generate preview")
            return true
        }

        sender.sendMessage("")
        sender.sendMessage(preview.getSummary())
        sender.sendMessage("")
        sender.sendMessage("To import, run: /xinv import $sourceId --confirm")
        sender.sendMessage("To edit mappings: /xinv import $sourceId mapping")

        return true
    }

    private suspend fun handleImport(sender: CommandSender, args: Array<String>): Boolean {
        val sourceId = args[0].lowercase()
        val confirmed = args.any { it == "--confirm" || it == "-c" }

        val source = importService.getSource(sourceId)

        if (source == null) {
            sender.sendMessage("Unknown import source: ${args[0]}")
            sender.sendMessage("Available: pwi, mvi, myworlds")
            return true
        }

        if (!source.isAvailable) {
            sender.sendMessage("Import source '${source.name}' is not available (no data found)")
            return true
        }

        // Load mapping
        val mappingFile = importService.getMappingFile()
        var mapping = importService.loadMapping(mappingFile)

        if (mapping == null || mapping.source != sourceId) {
            val groups = source.getGroups()
            mapping = ImportMapping.identity(sourceId, groups)
        }

        if (!confirmed) {
            // Show preview and ask for confirmation
            val preview = importService.previewImport(sourceId, mapping)

            if (preview == null) {
                sender.sendMessage("Failed to generate preview")
                return true
            }

            sender.sendMessage("")
            sender.sendMessage("Import Preview:")
            sender.sendMessage("  Source: ${source.name}")
            sender.sendMessage("  Players to import: ${preview.totalPlayers - preview.playersToSkip}")
            sender.sendMessage("  Players to skip: ${preview.playersToSkip}")
            sender.sendMessage("  Groups: ${preview.groups.size}")
            sender.sendMessage("")

            if (preview.warnings.isNotEmpty()) {
                sender.sendMessage("Warnings:")
                preview.warnings.forEach { warning ->
                    sender.sendMessage("  - $warning")
                }
                sender.sendMessage("")
            }

            sender.sendMessage("Add --confirm to execute the import:")
            sender.sendMessage("  /xinv import $sourceId --confirm")
            return true
        }

        // Execute import
        sender.sendMessage("Starting import from ${source.name}...")
        sender.sendMessage("This may take a while for large datasets.")

        val result = importService.executeImport(sourceId, mapping)

        sender.sendMessage("")
        sender.sendMessage("Import Complete!")
        sender.sendMessage("  Players imported: ${result.playersImported}")
        sender.sendMessage("  Players skipped: ${result.playersSkipped}")
        sender.sendMessage("  Players failed: ${result.playersFailed}")
        sender.sendMessage("  Duration: ${result.durationMs}ms")

        if (result.errors.isNotEmpty()) {
            sender.sendMessage("")
            sender.sendMessage("Errors (${result.errors.size}):")
            result.errors.take(5).forEach { error ->
                sender.sendMessage("  - ${error.message}")
            }
            if (result.errors.size > 5) {
                sender.sendMessage("  ... and ${result.errors.size - 5} more errors")
            }
        }

        if (result.warnings.isNotEmpty()) {
            sender.sendMessage("")
            sender.sendMessage("Warnings (${result.warnings.size}):")
            result.warnings.take(3).forEach { warning ->
                sender.sendMessage("  - $warning")
            }
        }

        sender.sendMessage("")
        sender.sendMessage("Use '/xinv import report' for full details")

        return true
    }

    private fun handleMapping(sender: CommandSender, sourceId: String): Boolean {
        val source = importService.getSource(sourceId.lowercase())

        if (source == null) {
            sender.sendMessage("Unknown import source: $sourceId")
            sender.sendMessage("Available: pwi, mvi, myworlds")
            return true
        }

        if (!source.isAvailable) {
            sender.sendMessage("Import source '${source.name}' is not available")
            return true
        }

        val groups = source.getGroups()

        // Load existing mapping
        val mappingFile = importService.getMappingFile()
        val existingMapping = importService.loadMapping(mappingFile)

        sender.sendMessage("")
        sender.sendMessage("Group Mappings for ${source.name}:")
        sender.sendMessage("")
        sender.sendMessage("Source Group -> Target Group")
        sender.sendMessage("-".repeat(40))

        groups.forEach { group ->
            val targetGroup = existingMapping?.groupMappings?.get(group.name) ?: group.name
            val worlds = if (group.worlds.isNotEmpty()) {
                " [${group.worlds.joinToString(", ")}]"
            } else ""
            sender.sendMessage("  ${group.name}$worlds -> $targetGroup")
        }

        sender.sendMessage("")
        sender.sendMessage("Edit mappings in: plugins/xInventories/import-mapping.yml")
        sender.sendMessage("")
        sender.sendMessage("Example mapping file:")
        sender.sendMessage("  source: ${source.id}")
        sender.sendMessage("  mappings:")
        groups.take(3).forEach { group ->
            sender.sendMessage("    ${group.name}: ${group.name}")
        }
        sender.sendMessage("  options:")
        sender.sendMessage("    overwriteExisting: false")
        sender.sendMessage("    importBalances: true")

        // Save default mapping if none exists
        if (existingMapping == null || existingMapping.source != source.id) {
            val defaultMapping = ImportMapping.identity(source.id, groups)
            importService.saveMapping(defaultMapping, mappingFile)
            sender.sendMessage("")
            sender.sendMessage("Default mapping file created.")
        }

        return true
    }

    private fun handleReport(sender: CommandSender): Boolean {
        val result = importService.getLastImportResult()

        if (result == null) {
            sender.sendMessage("No import has been run yet.")
            return true
        }

        sender.sendMessage(importService.generateReport(result))
        return true
    }

    override fun tabComplete(plugin: PluginContext, sender: CommandSender, args: Array<String>): List<String> {
        return when (args.size) {
            1 -> {
                val options = mutableListOf("detect", "preview", "mapping", "report")
                options.addAll(importService.getSources().map { it.id })
                options.filter { it.lowercase().startsWith(args[0].lowercase()) }
            }
            2 -> {
                when (args[0].lowercase()) {
                    "preview", "mapping" -> {
                        importService.detectSources().map { it.id }
                            .filter { it.lowercase().startsWith(args[1].lowercase()) }
                    }
                    in importService.getSources().map { it.id } -> {
                        listOf("--confirm", "mapping")
                            .filter { it.lowercase().startsWith(args[1].lowercase()) }
                    }
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }
}
