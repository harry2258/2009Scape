package core.game.ge

import core.api.itemDefinition
import java.sql.ResultSet

object PriceIndex {
    @JvmStatic fun canTrade(id: Int): Boolean {
        var canTrade = false

        GEDB.run { conn ->
            val stmt = conn.prepareStatement(EXISTS_QUERY)
            stmt.setInt(1, id)
            val res = stmt.executeQuery()
            if (res.next()) {
                canTrade = res.getInt(1) == 1
            }
        }

        return canTrade
    }

    fun banItem(id: Int) {
        GEDB.run { conn ->
            val stmt = conn.prepareStatement(REMOVE_QUERY)
            stmt.setInt(1, id)
            stmt.execute()
        }
    }

    fun allowItem(id: Int) {
        if(canTrade(id)) return

        GEDB.run { conn ->
            val stmt = conn.prepareStatement(INSERT_QUERY)
            stmt.setInt(1, id)
            val def = itemDefinition(id)
            val basePrice = def.grandExchangePrice.takeIf { it > 0 } ?: def.getAlchemyValue(true)
            stmt.setInt(2, basePrice)
            stmt.setInt(3, basePrice)
            stmt.setInt(4, 0)
            stmt.setInt(5, 0)
            stmt.execute()
        }
    }

    fun getValue(id: Int): Int {
        val def = itemDefinition(id)
        var value = def.grandExchangePrice.takeIf { it > 0 } ?: def.getAlchemyValue(true)
        GEDB.run { conn ->
            val stmt = conn.prepareStatement(GET_VALUE_QUERY)
            stmt.setInt(1, id)
            val res = stmt.executeQuery()
            if (res.next()) {
                value = res.getInt(1)
            }
        }
        return value
    }

    fun addTrade(id: Int, amount: Int, pricePerUnit: Int) {
        val oldInfo = getInfo(id) ?: return
        val newInfo = oldInfo.copy()

        val volumeResetThreshold =
            if (pricePerUnit >= 1000000) 500
            else if (pricePerUnit >= 100000) 1000
            else if (pricePerUnit >= 25000) 2500
            else 10000

        newInfo.totalValue += (amount * pricePerUnit)
        newInfo.uniqueTrades += amount
        newInfo.lastUpdate = System.currentTimeMillis()
        newInfo.currentValue = (newInfo.totalValue / newInfo.uniqueTrades).toInt()

        if (newInfo.uniqueTrades > volumeResetThreshold) {
            val newAmt = (0.1 * volumeResetThreshold).toInt()
            newInfo.uniqueTrades = newAmt
            newInfo.totalValue = newAmt.toLong() * newInfo.currentValue
        }

        updateInfo(newInfo)
    }

    private fun updateInfo(newInfo: PriceInfo) {
        GEDB.run { conn ->
            val stmt = conn.prepareStatement(UPDATE_QUERY)
            stmt.setInt(1, newInfo.currentValue)
            stmt.setLong(2, newInfo.totalValue)
            stmt.setInt(3, newInfo.uniqueTrades)
            stmt.setLong(4, newInfo.lastUpdate)
            stmt.setInt(5, newInfo.itemId)
            stmt.execute()
        }
    }

    private fun getInfo(id: Int): PriceInfo? {
        var priceInfo: PriceInfo? = null

        GEDB.run { conn ->
            val stmt = conn.prepareStatement(SELECT_QUERY)
            stmt.setInt(1, id)
            val res = stmt.executeQuery()
            if (res.next()) {
                priceInfo = PriceInfo.fromQuery(res)
            }
        }

        return priceInfo
    }

    fun syncFromRemote(entries: List<Pair<Int, Int>>) {
        // Run the batch write asynchronously so it never blocks the game tick thread.
        GEDB.db.runAsync { conn ->
            val stmt = conn.prepareStatement(SYNC_UPDATE_QUERY)
            for ((itemId, value) in entries) {
                stmt.setInt(1, value)
                stmt.setLong(2, value.toLong())
                stmt.setLong(3, System.currentTimeMillis())
                stmt.setInt(4, itemId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    const val SELECT_QUERY = "SELECT * FROM price_index WHERE item_id = ?;"
    const val UPDATE_QUERY = "UPDATE price_index SET value = ?, total_value = ?, unique_trades = ?, last_update = ? WHERE item_id = ?;"
    const val SYNC_UPDATE_QUERY = "UPDATE price_index SET value = ?, total_value = ?, unique_trades = 0, last_update = ? WHERE item_id = ?;"
    const val EXISTS_QUERY = "SELECT EXISTS(SELECT 1 FROM price_index WHERE item_id = ?);"
    const val REMOVE_QUERY = "DELETE FROM price_index WHERE item_id = ?;"
    const val INSERT_QUERY = "INSERT INTO price_index (item_id, value, total_value, unique_trades, last_update) VALUES (?,?,?,?,?);"
    const val GET_VALUE_QUERY = "SELECT value FROM price_index WHERE item_id = ?;"
}

data class PriceInfo(
    var itemId: Int,
    var currentValue: Int,
    var totalValue: Long,
    var uniqueTrades: Int,
    var lastUpdate: Long
) {
    fun copy() : PriceInfo {
        return PriceInfo(itemId, currentValue, totalValue, uniqueTrades, lastUpdate)
    }
    companion object {
        fun fromQuery(result: ResultSet) : PriceInfo {
            return PriceInfo(
                result.getInt(1),
                result.getInt(2),
                result.getLong(3),
                result.getInt(4),
                result.getLong(5)
            )
        }
    }
}