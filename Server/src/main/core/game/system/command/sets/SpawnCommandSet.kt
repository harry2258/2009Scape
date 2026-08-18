package core.game.system.command.sets

import core.api.log
import core.api.sendMessage
import core.cache.Cache
import core.cache.def.impl.ItemDefinition
import core.game.node.scenery.Scenery
import core.game.node.scenery.SceneryBuilder
import core.game.node.entity.npc.NPC
import core.game.node.item.Item
import core.game.system.command.CommandPlugin
import core.plugin.Initializable
import core.game.system.command.Privilege
import core.tools.Log
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Initializable
class SpawnCommandSet : CommandSet(Privilege.ADMIN){
    override fun defineCommands() {
        /**
         * Spawns an npc with the given ID
         */
        define("npc", usage = "::npc <lt>npc-id<gt> [amount] [iswalks]", description = "Spawns one or more NPCs at your location, optionally letting them walk."){player,args ->
            if (args.size < 2) {
                reject(player, "syntax: id (required) amount (optional) isWalks (optional)")
                return@define
            }
            val amount = if (args.size > 2) CommandPlugin.toInteger(args[2]) else 1
            if (amount < 1) {
                reject(player, "Invalid amount")
                return@define
            }
            if (amount > 900) {
                reject(player, "Based on experience, spawning that many NPCs at once is a bad idea")
                return@define
            }
            var isWalks = false
            if (args.size > 3) {
                if (args[3] == "true") {
                    isWalks = true
                } else if (args[3] != "" && args[3] != "false") {
                    reject(player, "The \"isWalks\" argument only accepts \"true\" and \"false\"")
                    return@define
                }
            }
            var npcString = ""
            for (i in 1..amount) {
                val npc = NPC.create(CommandPlugin.toInteger(args[1]), player.location)
                npc.setAttribute("spawned:npc", true)
                npc.isRespawn = false
                npc.direction = player.direction
                npc.init()
                npc.isWalks = isWalks
                npcString = "{" + npc.location.x + "," + npc.location.y + "," + npc.location.z + "," + (if (npc.isWalks) "1" else "0") + "," + npc.direction.ordinal + "}"
                println(npcString)
            }
            val clpbrd = Toolkit.getDefaultToolkit().systemClipboard
            clpbrd.setContents(StringSelection(npcString), null)
        }

        /**
         * Spawns an item with the given ID or searches by name
         */
        define("item", usage = "::item <lt>item-id|name<gt> [amount]", description = "Spawns the specified item by ID or name search into your inventory."){player,args ->
            if (args.size < 2) {
                reject(player,"You must specify an item ID or name")
                return@define
            }

            // If the first arg is a numeric ID, use legacy behavior
            val numericId = args[1].toIntOrNull()
            if (numericId != null) {
                val id = numericId
                var amount = (args.getOrNull(2) ?: "1").toIntOrNull() ?: 1
                if (id > Cache.getItemDefinitionsSize()) {
                    reject(player, "Item ID '$id' out of range.")
                    return@define
                }
                val item = Item(id, amount)
                val max = player.inventory.getMaximumAdd(item)
                if (amount > max) {
                    amount = max
                }
                item.setAmount(amount)
                player.inventory.add(item)
                return@define
            }

            // Name-based search: check if the last arg is a numeric amount
            val lastArg = args.last()
            val trailingAmount = lastArg.toIntOrNull()
            val nameParts = if (trailingAmount != null && args.size > 2) {
                args.drop(1).dropLast(1)
            } else {
                args.drop(1)
            }
            val query = nameParts.joinToString(" ").lowercase()
            var amount = trailingAmount ?: 1

            // Search all item definitions
            val maxId = Cache.getItemDefinitionsSize()
            val matches = mutableListOf<Pair<Int, String>>()
            var exactMatch: Pair<Int, String>? = null

            for (i in 0 until maxId) {
                val def = ItemDefinition.forId(i)
                val name = def.name ?: continue
                if (name.isBlank() || name == "null") continue
                val nameLower = name.lowercase()
                if (nameLower == query) {
                    exactMatch = Pair(i, name)
                    break
                }
                if (nameLower.contains(query)) {
                    matches.add(Pair(i, name))
                }
            }

            // Exact match: spawn it directly
            if (exactMatch != null) {
                val item = Item(exactMatch.first, amount)
                val max = player.inventory.getMaximumAdd(item)
                if (amount > max) amount = max
                item.setAmount(amount)
                player.inventory.add(item)
                notify(player, "Spawned ${exactMatch.second} (ID: ${exactMatch.first}) x$amount")
                return@define
            }

            // Single partial match: spawn it directly
            if (matches.size == 1) {
                val match = matches[0]
                val item = Item(match.first, amount)
                val max = player.inventory.getMaximumAdd(item)
                if (amount > max) amount = max
                item.setAmount(amount)
                player.inventory.add(item)
                notify(player, "Spawned ${match.second} (ID: ${match.first}) x$amount")
                return@define
            }

            // No matches
            if (matches.isEmpty()) {
                reject(player, "No items found matching '$query'.")
                return@define
            }

            // Multiple partial matches: list up to 10
            notify(player, "Found ${matches.size} items matching '$query':")
            for (match in matches.take(10)) {
                notify(player, "  ID: ${match.first} - ${match.second}")
            }
            if (matches.size > 10) {
                notify(player, "  ...and ${matches.size - 10} more. Refine your search.")
            }
        }

        /**
         * Spawn object with given ID at the player's location
         */
        define("object", usage = "::object <lt>object-id<gt> [type] [rotation]", description = "Spawns an object at your tile with optional type and rotation."){player,args ->
            if (args!!.size < 2) {
                reject(player,"syntax error: id (optional) type rotation or rotation")
                return@define
            }
            val `object` = if (args.size > 3) Scenery(CommandPlugin.toInteger(args[1]!!), player!!.location, CommandPlugin.toInteger(args[2]!!), CommandPlugin.toInteger(args[3]!!)) else if (args.size == 3) Scenery(CommandPlugin.toInteger(args[1]!!), player!!.location, CommandPlugin.toInteger(args[2]!!)) else Scenery(CommandPlugin.toInteger(args[1]!!), player!!.location)
            SceneryBuilder.add(`object`)
            log(this::class.java, Log.FINE,  "object = $`object`")
        }

        define("objectgrid", usage = "::objectgrid <lt>start-id<gt> <lt>end-id<gt> <lt>type<gt> <lt>rotation<gt>", description = "Spawns a 10-wide grid cycling through the given object id range.") { player, args ->
            if(args!!.size != 5) {
                reject(player, "Usage: objectgrid beginId endId type rotation")
                return@define
            }
            val beginId = args[1].toIntOrNull() ?: return@define
            val endId = args[2].toIntOrNull() ?: return@define
            val type = args[3].toIntOrNull() ?: return@define
            val rotation = args[4].toIntOrNull() ?: return@define
            for(i in 0..10) {
                SceneryBuilder.add(Scenery(29447 + i, player.location.transform(i, -1, 0)))
            }
            for(i in beginId..endId) {
                val j = i - beginId
                val scenery = Scenery(i, player.location.transform(j % 10, j / 10, 0), type, rotation)
                SceneryBuilder.add(scenery)
                if(j % 10 == 0) {
                    SceneryBuilder.add(Scenery(29447 + (j / 10) % 10, player.location.transform(-1, j/10, 0)))
                }
            }
        }
    }
}
