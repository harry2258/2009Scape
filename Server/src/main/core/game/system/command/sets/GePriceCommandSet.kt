package core.game.system.command.sets

import core.api.*
import core.game.ge.GEDB
import core.game.ge.PriceIndex
import core.game.system.command.Privilege
import core.plugin.Initializable

import core.game.system.task.Pulse
import core.game.world.GameWorld

/**
 * Server-side command handler for the LiveGEPrices client plugin.
 *
 * Responds to ::gepricebulk and ::geprice commands by sending
 * GE prices to the client as varp pairs (4500 = itemId, 4501 = price).
 *
 * Uses player.packetDispatch.sendVarp() directly instead of setVarp()
 * to avoid polluting player.varpMap (which would cause resend on
 * region changes via reinitVarps).
 */
@Initializable
class GePriceCommandSet : CommandSet(Privilege.STANDARD) {

    companion object {
        // Must match client plugin (LiveGEPrices/plugin.kt)
        const val VARP_GE_ITEM_ID = 4500
        const val VARP_GE_PRICE   = 4501

        const val SELECT_ALL_PRICES = "SELECT item_id, value FROM price_index;"
    }

    override fun defineCommands() {

        /**
         * ::gepricebulk — Sends all known GE prices to the client.
         * Called automatically by the client plugin on login.
         */
        define("gepricebulk", Privilege.STANDARD, "", "Sends all GE prices to the client plugin.") { player, _ ->
            val allPrices = mutableListOf<Pair<Int, Int>>()
            GEDB.run { conn ->
                val stmt = conn.prepareStatement(SELECT_ALL_PRICES)
                val res = stmt.executeQuery()
                while (res.next()) {
                    allPrices.add(Pair(res.getInt(1), res.getInt(2)))
                }
            }

            if (allPrices.isEmpty()) {
                player.packetDispatch.sendVarp(VARP_GE_ITEM_ID, -1)
                return@define
            }

            // Chunk the sending of packets to prevent client side lag
            // The client (Protocol.method1756) only reads up to 100 packets per tick.
            // 20 items * 2 varps = 40 packets per tick.
            GameWorld.submit(object : Pulse(1) {
                var index = 0
                val chunkSize = 20

                override fun pulse(): Boolean {
                    if (!player.isActive) return true

                    var count = 0
                    while (index < allPrices.size && count < chunkSize) {
                        val pair = allPrices[index]
                        player.packetDispatch.sendVarp(VARP_GE_ITEM_ID, pair.first)
                        player.packetDispatch.sendVarp(VARP_GE_PRICE, pair.second)
                        index++
                        count++
                    }

                    if (index >= allPrices.size) {
                        // Send sentinel: itemId = -1 signals bulk transfer complete
                        player.packetDispatch.sendVarp(VARP_GE_ITEM_ID, -1)
                        // Optional notify: notify(player, "Sent ${allPrices.size} GE prices to client.")
                        return true
                    }
                    return false
                }
            })
        }

        /**
         * ::geprice <itemId> — Sends the GE price for a single item.
         */
        define("geprice", Privilege.STANDARD, "::geprice <itemId>", "Sends the GE price of the specified item to the client plugin.") { player, args ->
            if (args.size < 2) {
                reject(player, "Usage: ::geprice <itemId>")
            }
            val itemId = args[1].toIntOrNull()
            if (itemId == null) {
                reject(player, "Invalid item ID: ${args[1]}")
            }
            val price = PriceIndex.getValue(itemId!!)
            player.packetDispatch.sendVarp(VARP_GE_ITEM_ID, itemId)
            player.packetDispatch.sendVarp(VARP_GE_PRICE, price)
            notify(player, "Item $itemId GE price: $price gp")
        }
    }
}

