package org.openredstone.entity

import com.uchuhimo.konf.ConfigSpec

data class ChadConfig (
    val botToken: String,
    val enableNotificationRoles: Boolean,
    val notificationChannelId: Long,
    val statusChannelId: Long,
    val playingMessage: String,
    val commandChar: Char,
    val disableSpoilers: Boolean,
    val enableLinkPreview: Boolean,
    val irc: IrcBotConfig,
    val notifications: List<NotificationRoleConfig>,
    val authorizedIrcRoles: List<String>,
    val authorizedDiscordRoles: List<String>,
    val logging: LoggingConfig,
    val insults: List<String>,
    val commonCommands: Map<String, String>,
    val discordCommands: Map<String, String>,
    val ircCommands: Map<String, String>
)

object ChadSpec : ConfigSpec("") {
    val chad by required<ChadConfig>()
}

data class IrcBotConfig(val name: String, val server: String, val channel: String, val password: String)

data class NotificationRoleConfig(val emoji: String, val name: String, val role: String, val description: String)

data class LoggingConfig(val defaultLevel: String, val chadLevel: String, val dateTimeFormat: String)
