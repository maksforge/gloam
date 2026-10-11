package app.gloam.tip

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

private const val TAG = "GloamTip"

/**
 * The product ids, which must match the one-time products in Play Console exactly. A product
 * missing from the Console is left out of [TipJar.offers] rather than failing the others.
 */
val TIP_PRODUCT_IDS = listOf("tip_small", "tip_medium", "tip_large")

/**
 * One tip as Play prices it for this user.
 *
 * [price] is Play's own formatted string, in the user's currency, and is the only price the UI may
 * show — a literal "5 zł" would be wrong everywhere outside Poland.
 */
class TipOffer internal constructor(
    val productId: String,
    val price: String,
    internal val priceMicros: Long,
    internal val details: ProductDetails,
)

/** What the last tip turned into, for the one line the Support screen says about it. */
enum class TipOutcome {
    Thanked,

    /** Paid by a method that settles later (cash at a shop, for example). Play finishes it. */
    Pending,
}

/**
 * The tip, through Play Billing (ADR-0009).
 *
 * **Every tip is consumed, never just acknowledged.** Consuming is what lets the same product be
 * bought again, and it also counts as the acknowledgement Play requires within three days — an
 * unacknowledged purchase is refunded automatically. Nothing is unlocked and nothing is stored, so
 * there is no entitlement to keep and no purchase this app ever needs to remember.
 *
 * **Lives in [app.gloam.AppContainer], not in a `ViewModel`.** A tip can finish after the screen
 * that started it is gone — the user leaves Play's sheet open and backs out — and its consume has to
 * run anyway, so the listener belongs to something with the process's lifetime.
 *
 * Unavailable is a normal state rather than an error: a phone without Play, a debug build whose
 * package Play has never heard of, or products not yet created in the Console all leave [offers]
 * empty, and the UI then shows no tip at all.
 */
class TipJar(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val _offers = MutableStateFlow<List<TipOffer>>(emptyList())

    /** Cheapest first, and empty until [refresh] has heard from Play — or for good, if it never does. */
    val offers: StateFlow<List<TipOffer>> = _offers.asStateFlow()

    // Kotlin note: a `SharedFlow` with no replay is an event stream — an `EventEmitter`, not a value.
    // A thank-you belongs to the moment it happened, so a screen opened later must not receive it.
    private val _outcomes = MutableSharedFlow<TipOutcome>(extraBufferCapacity = 1)
    val outcomes: SharedFlow<TipOutcome> = _outcomes.asSharedFlow()

    private val client: BillingClient =
        BillingClient
            .newBuilder(context.applicationContext)
            .setListener { result, purchases ->
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK ->
                        scope.launch { settle(purchases.orEmpty(), announce = true) }
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                        scope.launch { settlePending(announce = true) }
                }
            }.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()

    private val connecting = Mutex()

    /** Ask Play for prices. */
    suspend fun refresh() {
        if (!connect()) return
        val (result, details) =
            client.queryProductDetails(
                QueryProductDetailsParams
                    .newBuilder()
                    .setProductList(
                        TIP_PRODUCT_IDS.map {
                            QueryProductDetailsParams.Product
                                .newBuilder()
                                .setProductId(it)
                                .setProductType(BillingClient.ProductType.INAPP)
                                .build()
                        },
                    ).build(),
            )
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.i(TAG, "no prices: ${result.debugMessage}")
            return
        }
        _offers.value = details.orEmpty().mapNotNull(::offerOf).sortedBy { it.priceMicros }
    }

    /**
     * Finish any tip an earlier run left unconsumed — a pending payment that has since cleared, or a
     * process killed between paying and consuming. Run on every foreground, because nothing else
     * comes back for it and Play refunds what is not consumed within three days.
     */
    suspend fun settlePending(announce: Boolean = false) {
        if (!connect()) return
        val owned =
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            )
        if (owned.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.i(TAG, "no purchases: ${owned.billingResult.debugMessage}")
            return
        }
        settle(owned.purchasesList, announce)
    }

    /**
     * Open Play's purchase sheet. The answer arrives at the listener, not here, which is why this
     * returns nothing: the sheet belongs to Play, and the user can leave it however they like.
     */
    fun tip(
        activity: Activity,
        offer: TipOffer,
    ) {
        val params =
            BillingFlowParams
                .newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams
                            .newBuilder()
                            .setProductDetails(offer.details)
                            .build(),
                    ),
                ).build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.i(TAG, "sheet refused: ${result.debugMessage}")
        }
    }

    private suspend fun settle(
        purchases: List<Purchase>,
        announce: Boolean,
    ) {
        for (purchase in purchases) {
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> {
                    val consumed =
                        client.consumePurchase(
                            ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
                        )
                    val ok = consumed.billingResult.responseCode == BillingClient.BillingResponseCode.OK
                    if (!ok) Log.w(TAG, "consume failed: ${consumed.billingResult.debugMessage}")
                    if (ok && announce) _outcomes.tryEmit(TipOutcome.Thanked)
                }
                Purchase.PurchaseState.PENDING -> if (announce) _outcomes.tryEmit(TipOutcome.Pending)
                else -> Unit
            }
        }
    }

    private suspend fun connect(): Boolean =
        connecting.withLock {
            if (client.isReady) return@withLock true
            // Kotlin note: `suspendCancellableCoroutine` turns a one-shot callback into a suspend
            // call, as `new Promise(resolve => api(resolve))` does. The library has no ktx form of
            // `startConnection`, so this is the hand-written one.
            suspendCancellableCoroutine { continuation ->
                client.startConnection(
                    object : BillingClientStateListener {
                        override fun onBillingSetupFinished(result: BillingResult) {
                            val ready = result.responseCode == BillingClient.BillingResponseCode.OK
                            if (!ready) Log.i(TAG, "Play Billing unavailable: ${result.debugMessage}")
                            if (continuation.isActive) continuation.resume(ready)
                        }

                        override fun onBillingServiceDisconnected() {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                )
            }
        }
}

private fun offerOf(details: ProductDetails): TipOffer? {
    val offer = details.oneTimePurchaseOfferDetails ?: return null
    return TipOffer(details.productId, offer.formattedPrice, offer.priceAmountMicros, details)
}
