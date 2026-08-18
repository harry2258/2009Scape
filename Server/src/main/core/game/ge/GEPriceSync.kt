package core.game.ge

import core.ServerConstants
import core.api.StartupListener
import core.tools.SystemLogger
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import java.util.TimerTask

/**
 * Syncs GE item prices from the official 2009Scape CDN into the local
 * price_index table on startup and every 24 hours thereafter.
 */
class GEPriceSync : StartupListener {

    companion object {
        private const val CDN_URL = "https://cdn.2009scape.org/gedata/latest.json"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val SYNC_INTERVAL_MS = 24L * 60 * 60 * 1000 // 24 hours
    }

    override fun startup() {
        // Run the first sync on a background thread so it doesn't block server boot.
        val timer = Timer("GE-Price-Sync", true)
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                performSync()
            }
        }, 0L, SYNC_INTERVAL_MS)
    }

    private fun performSync() {
        try {
            SystemLogger.logGE("[GE] Starting GE price sync from CDN...")
            val json = fetchJson()
            if (json.isNullOrEmpty()) {
                SystemLogger.logGE("[GE] Price sync: received empty response from CDN, skipping.")
                return
            }

            val entries = parseEntries(json)
            if (entries.isEmpty()) {
                SystemLogger.logGE("[GE] Price sync: parsed 0 entries from CDN, skipping.")
                return
            }

            persistSnapshot(json)
            PriceIndex.syncFromRemote(entries)
            SystemLogger.logGE("[GE] Synced ${entries.size} item prices from CDN.")
        } catch (e: Exception) {
            SystemLogger.logGE("[GE] Price sync failed: ${e.message}")
        }
    }

    /**
     * Persists the raw CDN snapshot so other systems (e.g. bot dialogue price
     * talk) can read live GE prices without touching the price_index DB.
     */
    private fun persistSnapshot(json: String) {
        try {
            val dir = File(ServerConstants.GRAND_EXCHANGE_DATA_PATH)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            File(dir, "latest.json").writeText(json)
        } catch (e: Exception) {
            SystemLogger.logGE("[GE] Failed to persist CDN price snapshot: ${e.message}")
        }
    }

    private fun fetchJson(): String? {
        val connection = URL(CDN_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "2009Scape-Server")

        return try {
            if (connection.responseCode != 200) {
                SystemLogger.logGE("[GE] Price sync: CDN returned HTTP ${connection.responseCode}")
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseEntries(json: String): List<Pair<Int, Int>> {
        val parser = JSONParser()
        val array = parser.parse(json) as JSONArray
        val entries = ArrayList<Pair<Int, Int>>(array.size)

        for (element in array) {
            val obj = element as JSONObject
            val itemId = (obj["item_id"] as Number).toInt()
            val value = (obj["value"] as Number).toInt()
            if (value > 0) {
                entries.add(itemId to value)
            }
        }

        return entries
    }
}
