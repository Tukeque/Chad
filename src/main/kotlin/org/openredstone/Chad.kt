package org.openredstone

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.source.yaml
import mu.KotlinLogging
import org.javacord.api.DiscordApiBuilder
import org.openredstone.commands.*
import org.openredstone.commands.dsl.command
import org.openredstone.entity.ChadSpec
import org.openredstone.listeners.startDiscordListeners
import org.openredstone.listeners.startIrcListeners
import org.openredstone.managers.NotificationManager
import org.openredstone.managers.Sql
import java.util.*
import kotlin.concurrent.schedule
import kotlin.system.exitProcess

/**
 * The global logger for Chad.
 */
val logger = KotlinLogging.logger("Chad")

/**
 * The main function.
 */
fun main(args: Array<String>) {
    // argument parsing
    if (args.size != 1) {
        eprintln("Expected one argument, got ${args.size}")
        eprintln("Usage: Chad config")
        exitProcess(1)
    }
    val configFile = args[0]

    // loading config
    val config = Config { addSpec(ChadSpec) }.from.yaml.watchFile(configFile)
    var chadConfig = config[ChadSpec.chad]
    val database = Sql(chadConfig.databaseFile)
    database.initTables()

    // logging properties
    val loggingConfig = chadConfig.logging
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", loggingConfig.defaultLevel)
    System.setProperty("org.slf4j.simpleLogger.log.Chad", loggingConfig.chadLevel)
    System.setProperty("org.slf4j.simpleLogger.log.IRC link listener", loggingConfig.chadLevel)
    System.setProperty("org.slf4j.simpleLogger.log.Spoiler listener", loggingConfig.chadLevel)
    System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")
    System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", chadConfig.logging.dateTimeFormat)
    System.setProperty("org.slf4j.simpleLogger.showThreadName", "false")

    logger.info("Loading Chad...")
    logger.info("Notification channel ID: ${chadConfig.notificationChannelId}")
    logger.info("Command character: '${chadConfig.commandChar}'")
    logger.info("Disable spoilers: ${chadConfig.disableSpoilers}")
    logger.info("Link preview: ${chadConfig.enableLinkPreview}")

    val discordApi = DiscordApiBuilder()
        .setToken(chadConfig.botToken)
        .login()
        .join()
        .apply { updateActivity(chadConfig.playingMessage) }

    val discordServer = discordApi.getServerById(chadConfig.serverId).get()

    val discordCommands = concurrentMapOf<String, Command>()
    val ircCommands = concurrentMapOf<String, Command>()

    fun reloadCommands() {
        chadConfig = config[ChadSpec.chad]
        val authorizedRoles = AuthorizedRoles(chadConfig.authorizedDiscordRoles, chadConfig.authorizedIrcRoles)

        logger.info("(Re)loading commands...")

        val commonCommands = mutableMapOf(
            "add" to command(authorizedRoles) {
                val name by required()
                val messages by vararg()
                reply {
                    val msg = messages.joinToString(separator = " ")
                    val cmd = command {
                        reply { msg }
                    }
                    // write command to database
                    database.insertCommand(name, msg)
                    // add command
                    discordCommands[name] = cmd
                    discordCommands["help"] = helpCommand(discordCommands)
                    ircCommands[name] = cmd
                    ircCommands["help"] = helpCommand(discordCommands)
                    "Done!"
                }
            },
            "pikl" to command(authorizedRoles) {
                val name by required()
                fun parseId() = Regex("""<@!?([0-9]{10,20})>""").find(name)?.groupValues?.last()
                fun gimmiePikl() = discordServer.getRolesByName("pikl")?.firstOrNull()
                reply {
                    val piklRole = gimmiePikl() ?: return@reply "No pikl rank :("
                    val discordId = parseId() ?: return@reply "Invalid user."
                    val user = discordApi.getUserById(discordId).get()
                    val roles = user.getRoles(discordServer)
                    if (roles.none { role -> role.name == "pikl" }) {
                        user.addRole(piklRole)
                    }
                    Timer().schedule(120000) {
                        user.removeRole(piklRole)
                    }
                    "<@${discordId}> got pikl'd."
                }
            },
            "lmgtfy" to lmgtfy,
            "apply" to applyCommand,
            "authorized" to command(authorizedRoles) {
                reply { "authorized !" }
            },
            "insult" to insultCommand(chadConfig.insults),
            "reload" to command(authorizedRoles) {
                reply {
                    reloadCommands()
                    "Done!"
                }
            }
        ).apply {
            putAll(database.getCommands().mapValues { staticCommand(it.value) })
        }

        discordCommands.apply {
            clear()
            put("poll", pollCommand)
            put("roll", rollCommand)
            put("help", helpCommand(this))
            putAll(commonCommands)
            putAll(chadConfig.discordCommands.mapValues { staticCommand(it.value) })
        }

        ircCommands.apply {
            put("list", listCommand(chadConfig.statusChannelId, discordApi))
            put("help", helpCommand(this))
            putAll(commonCommands)
            putAll(chadConfig.ircCommands.mapValues { staticCommand(it.value) })
        }
    }

    reloadCommands()

    logger.info("Loaded the following Discord commands: ${discordCommands.keys.joinToString()}")
    logger.info("Loaded the following IRC commands: ${ircCommands.keys.joinToString()}")
    logger.info("Starting listeners...")

    startDiscordListeners(
        discordApi,
        CommandExecutor(chadConfig.commandChar, discordCommands),
        chadConfig.disableSpoilers,
    )
    startIrcListeners(
        chadConfig.irc,
        CommandExecutor(chadConfig.commandChar, ircCommands),
        chadConfig.enableLinkPreview,
    )

    if (chadConfig.enableNotificationRoles) NotificationManager(
        discordApi,
        chadConfig.notificationChannelId,
        chadConfig.notifications,
    )

    logger.info("Done")
}
