package com.auralis.music.bot

const val COMMAND_PREFIX = "au"

/** A command read from chat: `auplay blinding lights` is "play" with "blinding lights". */
data class ChatCommand(val name: String, val args: String)

enum class CommandSection(val heading: String) { MUSIC("🎵 Music Commands"), MISC("✨ Misc") }

/** [args] is the usage line after a mistake; [shown] is how the command list writes it. */
data class CommandHelp(val name: String, val args: String, val section: CommandSection, val shown: List<String>)

private fun music(name: String, args: String = "", vararg shown: String) =
    CommandHelp(name, args, CommandSection.MUSIC, shown.toList().ifEmpty { listOf(name) })

private fun misc(name: String, args: String = "") = CommandHelp(name, args, CommandSection.MISC, listOf(name))

val COMMANDS = listOf(
    music("play", "<song or link>", "play song", "play shuffle link"),
    music("playnext", "<song or link>", "playnext song"),
    music("playlist", "<name>", "playlist name"),
    music("search", "<song>", "search song"),
    music("pause"),
    music("resume"),
    music("skip"),
    music("stop"),
    music("leave"),
    music("seek", "<1:30>", "seek 1:30"),
    music("volume", "<0-150>", "volume 50"),
    music("queue"),
    music("shuffle"),
    music("loop", "<off/song/queue>", "loop song"),
    music("remove", "<position>", "remove 3"),
    music("move", "<from> <to>", "move 3 1"),
    music("auto"),
    misc("np"),
    misc("lyrics"),
    misc("commands")
)

private val COMMAND_NAMES = COMMANDS.map { it.name }.toSet() + "help"

/** The command list, as each heading and its commands. */
fun commandSections(): List<Pair<String, String>> = CommandSection.entries.map { section ->
    section.heading to COMMANDS.filter { it.section == section }.flatMap { it.shown }
        .joinToString(", ") { "`$COMMAND_PREFIX$it`" }
}

fun usage(name: String): String {
    val args = COMMANDS.first { it.name == name }.args
    return "💡 Use: `$COMMAND_PREFIX$name $args`"
}

/**
 * The command in a chat message, or null for ordinary chat. The first word must be exactly a
 * command, so words that merely start with the prefix (audio, august) are left alone. Only the
 * first line counts, so a pasted block can't become one long search.
 */
fun parseCommand(content: String): ChatCommand? {
    val line = content.trim().lineSequence().firstOrNull().orEmpty()
    val word = line.takeWhile { !it.isWhitespace() }.lowercase()
    if (!word.startsWith(COMMAND_PREFIX)) return null
    val name = word.removePrefix(COMMAND_PREFIX).takeIf { it in COMMAND_NAMES } ?: return null
    return ChatCommand(name, line.drop(word.length).trim())
}
