package org.openredstone

import kotlin.system.exitProcess

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.source.yaml
import mu.KotlinLogging
import org.javacord.api.DiscordApiBuilder

import org.openredstone.commands.*
import org.openredstone.entity.ChadSpec
import org.openredstone.listeners.startDiscordListeners
import org.openredstone.listeners.startIrcListeners
import org.openredstone.managers.NotificationManager

val logger = KotlinLogging.logger("Chad")

data class CommandResponse(val reply: String, val privateReply: Boolean)

class CommandExecutor(private val commandChar: Char, private val commands: Commands) {
    fun tryExecute(sender: Sender, message: String): CommandResponse? {
        if (message.isEmpty() || message[0] != commandChar) {
            return null
        }

        logger.info("${sender.username} [${sender.service}]: $message")

        val parts = message.split(" ")
        val args = parts.drop(1)
        val name = parts[0].substring(1)
        val command = commands[name] ?: ErrorCommand

        val reply = if (args.size < command.requireParameters) {
            "Not enough arguments passed to command `$name`, expected at least ${command.requireParameters}."
        } else {
            command.runCommand(sender, args)
        }
        return CommandResponse(reply, command.privateReply)
    }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Please specify a config file")
        exitProcess(1)
    }

    val config = Config { addSpec(ChadSpec) }
        .from.yaml.file(args[0])

    val chadConfig = config[ChadSpec.chad]

    logger.info("Loading Chad...")
    logger.info("Notification channel ID: ${chadConfig.notificationChannelId}")
    logger.info("Command character: '${chadConfig.commandChar}'")

    val discordApi = DiscordApiBuilder()
        .setToken(chadConfig.botToken)
        .login()
        .join()
        .apply {
            updateActivity(chadConfig.playingMessage)
        }

    val commonCommands = chadConfig.commonCommands.mapValues { StaticCommand(it.value) }
    val discordCommands = mapOf(
        "apply" to ApplyCommand,
        "authorized" to AuthorizedCommand(chadConfig.authorizedDiscordRoles),
        "help" to HelpCommand,
        "insult" to InsultCommand(chadConfig.insults),
        "roll" to RollCommand
    ) + commonCommands + chadConfig.discordCommands.mapValues { StaticCommand(it.value) } + dslCommands
    val ircCommands = mapOf(
        "apply" to ApplyCommand,
        "authorized" to AuthorizedCommand(chadConfig.authorizedIrcRoles),
        "help" to HelpCommand,
        "insult" to InsultCommand(chadConfig.insults),
        "list" to ListCommand(chadConfig.statusChannelId, discordApi)
    ) + commonCommands + chadConfig.ircCommands.mapValues { StaticCommand(it.value) } + dslCommands

    logger.info("loaded the following Discord commands: ${discordCommands.keys.joinToString()}")
    logger.info("loaded the following IRC commands: ${ircCommands.keys.joinToString()}")
    logger.info("starting listeners...")

    startDiscordListeners(discordApi, CommandExecutor(chadConfig.commandChar, discordCommands), chadConfig.disableSpoilers)
    startIrcListeners(chadConfig.irc, CommandExecutor(chadConfig.commandChar, ircCommands), chadConfig.enableLinkPreview)

    if (chadConfig.enableNotificationRoles) NotificationManager(discordApi, chadConfig.notificationChannelId, chadConfig.notifications)

    logger.info("started listeners")
}
