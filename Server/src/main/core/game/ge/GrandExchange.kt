package core.game.ge

import core.ServerConstants
import core.api.*
import core.cache.def.impl.ItemDefinition
import core.game.node.entity.player.Player
import core.game.node.entity.player.info.PlayerDetails
import core.game.system.command.Privilege
import core.game.system.task.Pulse
import core.game.world.GameWorld
import core.game.world.repository.Repository
import core.tools.Log
import core.tools.SystemLogger
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadLocalRandom

/**
 * Handles the exchanging of offers, offer update thread, etc.
 * @author Ceikry
 */
class GrandExchange : StartupListener, Commands {

    /**
     * Fallback safety check to make sure we don't start the GE twice under any circumstance
     */
    var isRunning = false
    /**
     * Initializes the offer manager and spawns an update thread.
     */


    fun boot(){
        if(isRunning) return

        SystemLogger.logGE("Initializing GE Update Worker")
        getValidOffers()
            .asSequence()
            .filter { !it.isBot && it.offerState == OfferState.REGISTERED && it.amountLeft > 0 }
            .forEach { pendingOffers.addLast(it) }

        Thread {
            Thread.currentThread().name = "GE Update Worker"
            while(true) {
                var offer = pendingOffers.takeFirst()
                if (offer.uid == 0L) {
                    // Only persist brand-new offers once; existing offers must keep their original UID/state.
                    offer.writeNew()
                }
                if (offer.uid <= 0L) {
                    // Bot stock offers use uid=0 and are not tracked in player_offers.
                    continue
                }
                offer = getOfferByUid(offer.uid) ?: continue
                selectPotentialMatches(offer).asSequence()
                    .sortedBy { if (offer.sell) -it.offeredValue else it.offeredValue }
                    .filter { if (offer.sell) it.offeredValue >= offer.offeredValue else it.offeredValue <= offer.offeredValue }
                    .forEach { match -> exchange(offer, match) }
                if (!offer.isBot && offer.amountLeft > 0) {
                    if (!offer.sell) {
                        tryExchangeWithBots(offer)
                    }
                    tryDynamicAutoFill(offer, null)
                    if (offer.amountLeft > 0 && offer.offerState == OfferState.REGISTERED) {
                        scheduleOfferRetry(offer.uid)
                    }
                }
            }
        }.start()

        isRunning = true
    }


    private fun tryExchangeWithBots(offer: GrandExchangeOffer) {
        GEDB.run { conn ->
            val query = conn.prepareStatement(GET_MATCH_FROM_BOT_OFFERS)
            query.setInt(1, offer.itemID)
            val res = query.executeQuery()

            if (res.next()) {
                exchange(offer, GrandExchangeOffer.fromBotQuery(res).also { it.timeStamp = offer.timeStamp - 1L })
            }
        }
    }

    private fun selectPotentialMatches(offer: GrandExchangeOffer): List<GrandExchangeOffer> {
        val matches = ArrayList<GrandExchangeOffer>()
        GEDB.run { conn ->
            val query = conn.prepareStatement(GET_MATCHES_FROM_PLAYER_OFFERS)
            query.setInt(1, offer.itemID)
            query.setBoolean(2, !offer.sell)
            val res = query.executeQuery()
            while (res.next()) {
                matches.add(GrandExchangeOffer.fromQuery(res))
            }
        }
        return matches
    }

    override fun defineCommands() {
        define("addbotoffer", Privilege.ADMIN, "::addbotoffer <lt>item-id<gt> <lt>amount<gt>", "Adds a GE bot sell offer for the given item ID and amount.") {player, strings ->
            val id = strings[1].toInt()
            val amount = strings[2].toInt()
            addBotOffer(id, amount)
            notify(player, "Added ${amount}x ${getItemName(id)} to the bot offers.")
        }

        define("bange", Privilege.ADMIN, "::bange <lt>item-id<gt>", "Bans/blacklists the specified item from GE trades.") {player, strings ->
            val id = strings[1].toInt()
            PriceIndex.banItem(id)
            notify(player, "Banned ${getItemName(id)} from GE trade.")
        }

        define("allowge", Privilege.ADMIN, "::allowge <lt>item-id<gt>", "Allows/whitelists the specified item to be traded on the GE.") {player, strings ->
            val id = strings[1].toInt()
            PriceIndex.allowItem(id)
            notify(player, "Allowed ${getItemName(id)} for GE trade.")
        }
    }

    companion object {
        private val scheduledRetryOffers = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

        val pendingOffers = LinkedBlockingDeque<GrandExchangeOffer>()
        private const val ACTIVE_OFFER_STATE = 1 // OfferState.REGISTERED
        private const val GET_SPECIFIC_OFFER_BY_UID = "SELECT * FROM player_offers WHERE uid = ?;"
        private const val GET_MATCHES_FROM_PLAYER_OFFERS = "SELECT * FROM player_offers WHERE item_id = ? AND is_sale = ? AND offer_state = $ACTIVE_OFFER_STATE;"
        private const val GET_MATCH_FROM_BOT_OFFERS = "SELECT * FROM bot_offers WHERE item_id = ?;"
        // Retry cadence for unfinished offers. Higher values = slower market updates.
        private const val RETRY_MIN_TICKS = 20
        private const val RETRY_MAX_TICKS_EXCLUSIVE = 61
        // Order size penalty. Lower divisor / higher max = large stacks fill slower.
        private const val ORDER_SIZE_PENALTY_DIVISOR = 350.0
        private const val ORDER_SIZE_PENALTY_MAX = 0.45
        // Global fill chance clamps after all factors are applied.
        private const val FILL_CHANCE_MIN = 0.05
        private const val FILL_CHANCE_MAX = 0.70
        // Hard price bands for bot autofill eligibility.
        private const val SELL_AUTOFILL_MAX_RATIO = 1.20
        private const val BUY_AUTOFILL_MIN_RATIO = 0.85
        private const val LOW_FILL_WARNING_THRESHOLD = 0.06
        // Sell quantity shaping: small sell stacks get a bonus; very large stacks get a penalty.
        private const val SELL_SMALL_STACK_BONUS_THRESHOLD = 100.0
        private const val SELL_SMALL_STACK_BONUS_MAX = 0.25
        private const val SELL_LARGE_STACK_PENALTY_START = 250.0
        private const val SELL_LARGE_STACK_PENALTY_DIVISOR = 1200.0
        private const val SELL_LARGE_STACK_PENALTY_MAX = 0.25
        // Sell value shaping: cheap items move faster, expensive items move slower.
        private const val SELL_LOW_VALUE_THRESHOLD = 200.0
        private const val SELL_LOW_VALUE_BONUS_MAX = 0.15
        private const val SELL_HIGH_VALUE_START = 5000.0
        private const val SELL_HIGH_VALUE_PENALTY_SCALE = 50000.0
        private const val SELL_HIGH_VALUE_PENALTY_MAX = 0.20
        // Buy overpay shaping: paying above guide gives a direct fill-speed boost.
        private const val BUY_OVERPAY_BOOST_FULL_PERCENT = 0.20
        private const val BUY_OVERPAY_BOOST_MAX = 0.20
        // Sell underprice shaping: selling below guide gives a direct fill-speed boost.
        private const val SELL_UNDERPRICE_BOOST_FULL_PERCENT = 0.20
        private const val SELL_UNDERPRICE_BOOST_MAX = 0.20
        // Protection for "good" prices: sell <= guide, buy >= guide.
        private const val FAVORABLE_FILL_CHANCE_FLOOR = 0.40
        // Random market noise range per attempt.
        private const val VOLATILITY_MIN = -0.08
        private const val VOLATILITY_MAX = 0.06
        // Partial-fill depth. Lower values = more trickle fills.
        private const val PARTIAL_FILL_MIN = 0.03
        private const val PARTIAL_FILL_BASE_MAX = 0.12
        private const val PARTIAL_FILL_FROM_CHANCE_MULTIPLIER = 0.40
        private const val PARTIAL_FILL_MAX_CAP = 0.55
        // Per-fill lot sizing. Keeps large stacks from clearing unrealistically fast.
        private const val MIN_FILL_UNITS = 2
        private const val BASE_FILL_UNITS = 8
        private const val FILL_UNITS_PER_SQRT = 2.0
        private const val MAX_FILL_UNITS_CAP = 120
        // Delay before a successful auto-fill settles. Higher = slower perceived execution.
        private const val SETTLEMENT_MIN_TICKS = 8
        private const val SETTLEMENT_MAX_TICKS_EXCLUSIVE = 46

        private fun scheduleOfferRetry(uid: Long) {
            if (uid <= 0L || !scheduledRetryOffers.add(uid)) {
                return
            }
            val delayTicks = ThreadLocalRandom.current().nextInt(RETRY_MIN_TICKS, RETRY_MAX_TICKS_EXCLUSIVE)
            GameWorld.Pulser.submit(object : Pulse(delayTicks) {
                override fun pulse(): Boolean {
                    try {
                        val offer = getOfferByUid(uid) ?: return true
                        if (!offer.isBot && offer.offerState == OfferState.REGISTERED && offer.amountLeft > 0) {
                            pendingOffers.addLast(offer)
                        }
                    } finally {
                        scheduledRetryOffers.remove(uid)
                    }
                    return true
                }
            })
        }

        private fun tryDynamicAutoFill(offer: GrandExchangeOffer, player: Player?): Boolean {
            if (!ServerConstants.I_AM_A_CHEATER || offer.isBot || offer.offerState != OfferState.REGISTERED || offer.amountLeft < 1) {
                return false
            }

            val recPrice = getRecommendedPrice(offer.itemID, false).toDouble()
            val safeRecPrice = if (recPrice <= 0.0) 1.0 else recPrice

            val unitPrice = offer.offeredValue.toDouble()
            val ratio = unitPrice / safeRecPrice
            val minSellPrice = (safeRecPrice * 0.85).toInt()
            val maxSellPrice = (safeRecPrice * 1.15).toInt()
            val minBuyPrice = (safeRecPrice * 0.90).toInt()
            val maxBuyPrice = (safeRecPrice * 1.10).toInt()

            // Reject extreme prices from bot autofill entirely; these should wait for real market matches.
            if (offer.sell && ratio > SELL_AUTOFILL_MAX_RATIO) {
                if (player != null && player.isActive) {
                    player.packetDispatch.sendMessage("Demand is weak at this price right now. Suggested range: $minSellPrice-$maxSellPrice gp.")
                }
                return false
            }
            if (!offer.sell && ratio < BUY_AUTOFILL_MIN_RATIO) {
                if (player != null && player.isActive) {
                    player.packetDispatch.sendMessage("Supply is thin at this price right now. Suggested range: $minBuyPrice-$maxBuyPrice gp.")
                }
                return false
            }

            val priceFactor = if (offer.sell) {
                when {
                    ratio <= 0.95 -> 1.0
                    ratio >= 1.15 -> 0.02
                    else -> (1.15 - ratio) / 0.20
                }
            } else {
                when {
                    ratio >= 1.05 -> 1.0
                    ratio <= 0.90 -> 0.02
                    else -> (ratio - 0.90) / 0.15
                }
            }.coerceIn(0.02, 1.0)

            // Fixed liquidity factor: prices are now CDN-synced, no saturation tracking.
            val liquidityFactor = 1.0

            val sizePenalty = (offer.amountLeft / ORDER_SIZE_PENALTY_DIVISOR).coerceIn(0.0, ORDER_SIZE_PENALTY_MAX)
            val volatility = ThreadLocalRandom.current().nextDouble(VOLATILITY_MIN, VOLATILITY_MAX)
            val rawFillChance = ((priceFactor * liquidityFactor) - sizePenalty + volatility).coerceIn(FILL_CHANCE_MIN, FILL_CHANCE_MAX)
            val sellQuantityAdjustment = if (offer.sell) {
                val amountLeft = offer.amountLeft.toDouble()
                val smallStackBonus = ((SELL_SMALL_STACK_BONUS_THRESHOLD - amountLeft) / SELL_SMALL_STACK_BONUS_THRESHOLD)
                    .coerceIn(0.0, 1.0) * SELL_SMALL_STACK_BONUS_MAX
                val largeStackPenalty = ((amountLeft - SELL_LARGE_STACK_PENALTY_START) / SELL_LARGE_STACK_PENALTY_DIVISOR)
                    .coerceIn(0.0, SELL_LARGE_STACK_PENALTY_MAX)
                smallStackBonus - largeStackPenalty
            } else {
                0.0
            }
            val sellValueAdjustment = if (offer.sell) {
                when {
                    safeRecPrice <= SELL_LOW_VALUE_THRESHOLD ->
                        ((SELL_LOW_VALUE_THRESHOLD - safeRecPrice) / SELL_LOW_VALUE_THRESHOLD)
                            .coerceIn(0.0, 1.0) * SELL_LOW_VALUE_BONUS_MAX
                    safeRecPrice >= SELL_HIGH_VALUE_START ->
                        -((safeRecPrice - SELL_HIGH_VALUE_START) / SELL_HIGH_VALUE_PENALTY_SCALE)
                            .coerceIn(0.0, SELL_HIGH_VALUE_PENALTY_MAX)
                    else -> 0.0
                }
            } else {
                0.0
            }
            val buyOverpayRatio = ratio - 1.0
            val buyOverpayAdjustment = if (!offer.sell && buyOverpayRatio > 0.0) {
                (buyOverpayRatio / BUY_OVERPAY_BOOST_FULL_PERCENT)
                    .coerceIn(0.0, 1.0) * BUY_OVERPAY_BOOST_MAX
            } else {
                0.0
            }
            val sellUnderpriceRatio = 1.0 - ratio
            val sellUnderpriceAdjustment = if (offer.sell && sellUnderpriceRatio > 0.0) {
                (sellUnderpriceRatio / SELL_UNDERPRICE_BOOST_FULL_PERCENT)
                    .coerceIn(0.0, 1.0) * SELL_UNDERPRICE_BOOST_MAX
            } else {
                0.0
            }
            val adjustedFillChance = (rawFillChance + sellQuantityAdjustment + sellValueAdjustment + buyOverpayAdjustment + sellUnderpriceAdjustment)
                .coerceIn(FILL_CHANCE_MIN, FILL_CHANCE_MAX)
            val isFavorablePrice = (offer.sell && ratio <= 1.0) || (!offer.sell && ratio >= 1.0)
            val fillChance = if (isFavorablePrice) maxOf(adjustedFillChance, FAVORABLE_FILL_CHANCE_FLOOR) else adjustedFillChance

            // Scale fill chance by item-specific demand.
            val demandMultiplier = ItemDemand.getMultiplier(offer.itemID)
            val demandAdjustedFillChance = (fillChance * demandMultiplier).coerceIn(FILL_CHANCE_MIN, FILL_CHANCE_MAX)

            val passesDynamicCheck = ThreadLocalRandom.current().nextDouble() < demandAdjustedFillChance
            if (!passesDynamicCheck) {
                if (!isFavorablePrice && demandAdjustedFillChance <= LOW_FILL_WARNING_THRESHOLD && player != null && player.isActive) {
                    if (offer.sell) {
                        player.packetDispatch.sendMessage("Demand is weak at this price right now. Suggested range: $minSellPrice-$maxSellPrice gp.")
                    } else {
                        player.packetDispatch.sendMessage("Supply is thin at this price right now. Suggested range: $minBuyPrice-$maxBuyPrice gp.")
                    }
                }
                return false
            }

            val minFraction = PARTIAL_FILL_MIN
            val maxFraction = (PARTIAL_FILL_BASE_MAX + (demandAdjustedFillChance * PARTIAL_FILL_FROM_CHANCE_MULTIPLIER))
                .coerceIn(PARTIAL_FILL_BASE_MAX, PARTIAL_FILL_MAX_CAP)
            val fraction = ThreadLocalRandom.current().nextDouble(minFraction, maxFraction)

            val percentBasedAmount = maxOf(1, (offer.amountLeft * fraction).toInt())
            val dynamicLotCap = (BASE_FILL_UNITS + (Math.sqrt(offer.amountLeft.toDouble()) * FILL_UNITS_PER_SQRT).toInt())
                .coerceIn(MIN_FILL_UNITS, MAX_FILL_UNITS_CAP)
            val sellMinLot = when {
                !offer.sell -> MIN_FILL_UNITS
                offer.amountLeft <= 25 -> 3
                offer.amountLeft <= 100 -> 2
                else -> MIN_FILL_UNITS
            }
            val minLot = sellMinLot.coerceAtMost(offer.amountLeft)
            val fulfillAmount = percentBasedAmount
                .coerceAtLeast(minLot)
                .coerceAtMost(dynamicLotCap)
                .coerceAtMost(offer.amountLeft)

            if (offer.uid == 0L) {
                offer.writeNew()
            }
            if (offer.uid <= 0L) {
                return false
            }
            val offerUid = offer.uid

            val otherO = GrandExchangeOffer()
            otherO.itemID = offer.itemID
            otherO.amount = fulfillAmount
            otherO.sell = !offer.sell
            otherO.offeredValue = offer.offeredValue
            otherO.isBot = true

            val settlementDelayTicks = ThreadLocalRandom.current().nextInt(SETTLEMENT_MIN_TICKS, SETTLEMENT_MAX_TICKS_EXCLUSIVE)
            GameWorld.Pulser.submit(object : Pulse(settlementDelayTicks) {
                override fun pulse(): Boolean {
                    val offer2 = getOfferByUid(offerUid) ?: return true
                    if (offer2.offerState != OfferState.REGISTERED || offer2.amountLeft < 1) {
                        return true
                    }
                    exchange(offer2, otherO)
                    if (offer2.amountLeft > 0 && offer2.offerState == OfferState.REGISTERED) {
                        scheduleOfferRetry(offerUid)
                    }
                    return true
                }
            })
            return true
        }

        private fun getOfferByUid(uid: Long): GrandExchangeOffer? {
            var offer: GrandExchangeOffer? = null
            GEDB.run { conn ->
                val query = conn.prepareStatement(GET_SPECIFIC_OFFER_BY_UID)
                query.setLong(1, uid)
                val res = query.executeQuery()

                if (res.next())
                    offer = GrandExchangeOffer.fromQuery(res)
            }
            return offer
        }

        @JvmStatic
        fun getRecommendedPrice(itemID: Int, fromBot: Boolean = false): Int {
            var base = PriceIndex.getValue(itemID)
            if (fromBot) base = (maxOf(BotPrices.getPrice(itemID), base) * 1.10).toInt()
            return base
        }

        @JvmStatic
        fun getOfferStats(itemID: Int, sale: Boolean) : String
        {
            val sb = StringBuilder()

            GEDB.run { conn ->
                var foundOffers = 0
                var totalAmount = 0
                var bestPrice = 0
                val stmt = conn.createStatement()

                if (!sale) {
                    var botAmt = 0
                    var botPrice = 0
                    val playerOffers = stmt.executeQuery("SELECT * from player_offers where item_id = $itemID AND is_sale = 1 AND offer_state = $ACTIVE_OFFER_STATE")

                    while (playerOffers.next()) {
                        val o = GrandExchangeOffer.fromQuery(playerOffers)
                        ++foundOffers
                        totalAmount += o.amountLeft
                        if (o.offeredValue < bestPrice || bestPrice == 0)
                            bestPrice = o.offeredValue
                    }

                    stmt.close()
                    val botOffers = conn.createStatement().executeQuery("SELECT * from bot_offers where item_id = $itemID")
                    if (botOffers.next()) {
                        val o = GrandExchangeOffer.fromBotQuery(botOffers)
                        botAmt = o.amount
                        botPrice = getRecommendedPrice(itemID, true)
                    }

                    sb.append("Player Stock: <col=FFFFFF>$totalAmount  ")
                    sb.append("</col>  Lowest Price: <col=FFFFFF>$bestPrice<br>")
                    sb.append("-".repeat(50))
                    sb.append("</col><br>Bot Stock: <col=FFFFFF>$botAmt  ")
                    sb.append("</col>  Bot Price: <col=FFFFFF>$botPrice")
                } else {
                    val buyOffers = stmt.executeQuery("SELECT * from player_offers where item_id = $itemID AND is_sale = 0 AND offer_state = $ACTIVE_OFFER_STATE")

                    while (buyOffers.next()) {
                        val o = GrandExchangeOffer.fromQuery(buyOffers)
                        ++foundOffers
                        totalAmount += o.amountLeft
                        if (o.offeredValue > bestPrice)
                            bestPrice = o.offeredValue
                    }

                    sb.append("Buy Offers: <col=FFFFFF>$totalAmount    ")
                    sb.append("</col>Highest Offer: <col=FFFFFF>$bestPrice</col>")
                }

                stmt.close()
            }

            return sb.toString()
        }

        fun addBotOffer(itemID: Int, amount: Int): Boolean
        {
            if (!PriceIndex.canTrade(itemID))
                return false

            // noted offers can not be bought.
            val itemDef = ItemDefinition.forId(itemID)
            val offer = GrandExchangeOffer.createBotOffer(if (itemDef.isUnnoted) itemID else itemDef.noteId, amount)
            pendingOffers.addLast(offer)

            return true
        }

        fun dispatch(player: Player, offer: GrandExchangeOffer) : Boolean {
            if (offer.amount < 1)
                sendMessage(player, "You must choose the quantity you wish to buy!").also { return false }

            if (offer.offeredValue < 1)
                sendMessage(player, "You must choose the price you wish to buy for!").also { return false }

            if (offer.offerState != OfferState.PENDING || offer.uid != 0L) {
                log(this::class.java, Log.WARN, "[GE] DISPATCH FAILURE: ${offer.offerState.name}, UID: ${offer.uid}")
                return false
            }

            if (player.isArtificial)
                offer.playerUID = PlayerDetails.getDetails("2009scape").uid.also { offer.isBot = true }
            else
                offer.playerUID = player.details.uid

            offer.offerState = OfferState.REGISTERED
            //GrandExchangeRecords.getInstance(player).update(offer)

            if (offer.sell && !player.isArtificial) {
                sendNews(player.username + " just offered " + offer.amount + " " + getItemName(offer.itemID) + " on the GE.")
            }

            if (tryDynamicAutoFill(offer, player)) {
                scheduleOfferRetry(offer.uid)
                return true
            }

            pendingOffers.add(offer)
            return true
        }

        fun exchange(offer: GrandExchangeOffer, other: GrandExchangeOffer)
        {
            if(offer.sell == other.sell) return //Don't exchange if they are both buy/sell offers
            val amount = Integer.min(offer.amount - offer.completedAmount, other.amount - other.completedAmount)

            if (amount == 0) return

            val seller = if(offer.sell) offer else other
            val buyer = if(offer == seller) other else offer

            val sellerBias = seller.timeStamp > buyer.timeStamp

            //If the buyer is buying for less than the seller is selling for, don't exchange
            if(seller.offeredValue > buyer.offeredValue) return

            seller.completedAmount += amount
            buyer.completedAmount += amount

            seller.addWithdrawItem(995, amount * if(sellerBias) buyer.offeredValue else seller.offeredValue)
            buyer.addWithdrawItem(seller.itemID, amount)

            if(!sellerBias)
                buyer.addWithdrawItem(995, amount * (buyer.offeredValue - seller.offeredValue))

            if(seller.amountLeft < 1)
                seller.offerState = OfferState.COMPLETED
            if(buyer.amountLeft < 1)
                buyer.offerState = OfferState.COMPLETED

            val totalCoinXC = (if(sellerBias) buyer.offeredValue else seller.offeredValue) * amount

            seller.totalCoinExchange += totalCoinXC
            buyer.totalCoinExchange += totalCoinXC

            // Prices are now CDN-synced; local trades no longer influence the price index.

/*
            if (seller.amountLeft > 0) {
                Discord.postOfferUpdate(true, seller.itemID, seller.offeredValue, seller.amountLeft)
            }

            if (buyer.amountLeft > 0) {
                Discord.postOfferUpdate(false, buyer.itemID, buyer.offeredValue, buyer.amountLeft)
            }
*/

            for (entity in arrayOf(buyer, seller)) {
                entity.update()
                val player = Repository.uid_map[entity.playerUID] ?: continue
                val records = GrandExchangeRecords.getInstance(player)
                records.visualizeRecords()
                records.updateNotification = true
            }
        }

        fun getValidOffers(): List<GrandExchangeOffer>
        {
            val offers = ArrayList<GrandExchangeOffer>()

            GEDB.run { conn ->
                val stmt = conn.createStatement()

                val results =
                        stmt.executeQuery("SELECT * FROM player_offers WHERE offer_state = $ACTIVE_OFFER_STATE")
                while (results.next()) {
                    val o = GrandExchangeOffer.fromQuery(results)
                    offers.add(o)
                }
                stmt.close()
            }
            return offers
        }

        fun getBotOffers(): List<GrandExchangeOffer>
        {
            val offers = ArrayList<GrandExchangeOffer>()

            GEDB.run { conn ->
                val stmt = conn.createStatement()

                val results = stmt.executeQuery("SELECT item_id,amount FROM bot_offers WHERE amount > 0")
                while (results.next()) {
                    val o = GrandExchangeOffer.fromBotQuery(results)
                    offers.add(o)
                }
                stmt.close()
            }
            return offers
        }

        fun getBotstockForId(itemId: Int): Int {
            var total = 0
            GEDB.run { conn ->
                val stmt = conn.prepareStatement("SELECT sum(amount) FROM bot_offers WHERE amount > 0 AND item_id = ?")
                stmt.setInt(1, itemId)

                val results = stmt.executeQuery()
                while (results.next()) {
                    total += results.getInt(1)
                }
            }
            return total
        }

    }

    override fun startup(){
        GEDB.init()
        boot()
    }
}
