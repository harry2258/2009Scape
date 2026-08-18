package core.game.system.command.sets

import core.plugin.Initializable
import core.game.system.command.Privilege
import core.game.world.repository.Repository

@Initializable
class BotCommandSet : CommandSet(Privilege.ADMIN) {

    override fun defineCommands() {

        /**
         * Counts the total number of players + bots currently spawned
         */
        define("botcount", Privilege.ADMIN, "::botcount", "Counts all entities in the player repository.") { player, _ ->
            val totalCount = Repository.players.size

            player.packetDispatch.sendMessage("There are currently $totalCount bots roaming the world.")
        }

        /**
         * Finds and removes any broken bots (named "null" or level 0)
         */
        define("clearnulls", Privilege.ADMIN, "::clearnulls", "Removes bugged husk bots from the world.") { player, _ ->
            // Broad filter: Catches ANY entity in the player list with a missing or "null" name
            val brokenBots = Repository.players.filter {
                it != null && (it.name == null || it.name.trim().isEmpty() || it.name.equals("null", ignoreCase = true))
            }

            val removedCount = brokenBots.size

            for (bot in brokenBots) {
                Repository.removePlayer(bot)
            }

            player.packetDispatch.sendMessage("Successfully swept away $removedCount broken null bots.")
        }
        /**
         * Clears ALL bots from the server instantly
         */
        define("clearbots", Privilege.ADMIN, "::clearbots", "Removes all bots from the world.") { player, _ ->
            val allBots = Repository.players.filter {
                it != null && (it.javaClass.simpleName == "AIPlayer" || it.javaClass.simpleName == "CombatBot")
            }

            val removedCount = allBots.size
            for (bot in allBots) {
                Repository.removePlayer(bot)
            }
            player.packetDispatch.sendMessage("Successfully swept away all $removedCount bots.")
        }

        define("spawnbots", Privilege.ADMIN, "::spawnbots", "Spawns the bots defined in ImmerseWorld.") { player, _ ->
            // Calls the exact same function the server uses when it boots up
            core.game.world.ImmerseWorld.spawnBots()

            player.packetDispatch.sendMessage("Bot spawn sequence initiated! They will begin trickling in.")
        }
    }
}