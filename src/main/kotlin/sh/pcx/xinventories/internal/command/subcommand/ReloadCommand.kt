package sh.pcx.xinventories.internal.command.subcommand

import sh.pcx.xinventories.PluginContext
import sh.pcx.xinventories.internal.util.Logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bukkit.command.CommandSender

/**
 * Reloads the plugin configuration.
 */
class ReloadCommand : Subcommand {

    override val name = "reload"
    override val permission = "xinventories.command.reload"
    override val usage = "/xinv reload"
    override val description = "Reload configuration files"

    override suspend fun execute(plugin: PluginContext, sender: CommandSender, args: Array<String>): Boolean {
        val serviceManager = plugin.serviceManager
        val messages = serviceManager.messageService

        val success = plugin.reload()

        if (success) {
            messages.send(sender, "reloaded")
        } else {
            messages.send(sender, "reload-failed")
        }

        // storage.type / storage.sqlite / storage.mysql changes (e.g. after /xinv convert) take
        // effect here instead of silently waiting for a restart.
        try {
            val configured = plugin.configManager.mainConfig.storage.type
            val previous = withContext(Dispatchers.IO) { serviceManager.storageService.switchToConfiguredBackend() }
            if (previous != null) {
                sender.sendMessage("Storage backend switched: $previous -> $configured")
            }
        } catch (e: Exception) {
            Logging.error("Could not switch the storage backend", e)
            sender.sendMessage("Could not switch the storage backend: ${e.message}")
        }

        return true
    }
}
