package core.game.world // Ensure your package matches!

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import core.ServerConstants
import core.tools.SystemLogger
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.concurrent.ConcurrentHashMap

object MarketSaturation {
    // 1. The three active maps
    var saturationMap = ConcurrentHashMap<Int, Int>()
    var currentHourIntake = ConcurrentHashMap<Int, Int>()
    var lastHourIntake = ConcurrentHashMap<Int, Int>()

    private val saveFile = File(ServerConstants.GRAND_EXCHANGE_DATA_PATH+ "/ge_saturation.json")

    // 2. NEW: A wrapper class so Gson can save/load all three maps at exactly the same time
    data class SaturationSaveData(
        val total: ConcurrentHashMap<Int, Int>,
        val currentHour: ConcurrentHashMap<Int, Int>,
        val lastHour: ConcurrentHashMap<Int, Int>
    )

    fun addSaturation(itemId: Int, amount: Int) {
        val currentTotal = saturationMap.getOrDefault(itemId, 0)
        saturationMap.put(itemId, currentTotal + amount)

        val currentHourTotal = currentHourIntake.getOrDefault(itemId, 0)
        currentHourIntake.put(itemId, currentHourTotal + amount)

        save() // Saves instantly
    }

    fun getSaturation(itemId: Int): Int {
        return saturationMap.getOrDefault(itemId, 0)
    }

    fun cycleHourlyIntake() {
        lastHourIntake.clear()
        lastHourIntake.putAll(currentHourIntake)
        currentHourIntake.clear()
    }

    /**
     * Applies tiered hourly decay to the saturation map.
     * High-saturation items decay faster to prevent runaway accumulation.
     */
    fun decaySaturation() {
        for (key in saturationMap.keys) {
            val current = saturationMap[key] ?: 0
            val retainRate = when {
                current >= 1500 -> 0.70  // 30% decay for heavily saturated items
                current >= 500  -> 0.80  // 20% decay for moderately saturated items
                else            -> 0.90  // 10% decay for lightly saturated items
            }
            val decayed = (current * retainRate).toInt()
            if (decayed <= 0) {
                saturationMap.remove(key)
            } else {
                saturationMap[key] = decayed
            }
        }
    }

    fun save() {
        try {
            if (saveFile.parentFile != null && !saveFile.parentFile.exists()) {
                saveFile.parentFile.mkdirs()
            }
            if (!saveFile.exists()) {
                saveFile.createNewFile()
            }

            // Wrap all three maps into our new data class before saving
            val dataToSave = SaturationSaveData(saturationMap, currentHourIntake, lastHourIntake)

            val writer = FileWriter(saveFile)
            Gson().toJson(dataToSave, writer)
            writer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun load() {
        if (!saveFile.exists()) return
        try {
            // Tell Gson we are looking for the SaturationSaveData wrapper now
            val type = object : TypeToken<SaturationSaveData>() {}.type
            val reader = FileReader(saveFile)
            val loadedData: SaturationSaveData? = Gson().fromJson(reader, type)
            reader.close()

            // Unpack the wrapper and assign the maps back into the server's memory
            if (loadedData != null) {
                saturationMap = loadedData.total
                currentHourIntake = loadedData.currentHour
                lastHourIntake = loadedData.lastHour
            }
            SystemLogger.logGE("Loaded Market Saturation Data")
        } catch (e: Exception) {
            SystemLogger.logGE("Failed to load GE Saturation data. If you have an old JSON format, delete the file and restart.")
            e.printStackTrace()
        }
    }
}