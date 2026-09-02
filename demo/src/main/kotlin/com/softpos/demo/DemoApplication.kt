package com.softpos.demo

import android.app.Application
import android.content.Context
import android.util.Log
import com.softpos.sdk.SoftPos
import com.softpos.sdk.SoftPosConfig
import com.softpos.sdk.data.ProductEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container.
 *
 * A DI framework would earn its keep in a larger app; here it would only add build configuration
 * around three objects.
 */
class DemoContainer(context: Context) {

    val softPos: SoftPos = SoftPos.create(
        context = context,
        config = SoftPosConfig(
            merchantName = "SoftPOS Demo Store",
            // Left at the default. Turning it on writes a recoverable PAN to disk; see the
            // documentation on the field before changing it.
            persistEncryptedPan = false,
        ),
    )

    val cart = CartStore()

    suspend fun seedCatalogIfEmpty() {
        if (!softPos.catalog.isEmpty()) return
        softPos.catalog.upsertAll(SAMPLE_CATALOG)
    }

    private companion object {
        val SAMPLE_CATALOG = listOf(
            ProductEntity("ESP-001", "Espresso", 250, "USD", 40, "Drinks"),
            ProductEntity("FLW-002", "Flat white", 395, "USD", 40, "Drinks"),
            ProductEntity("TEA-003", "Pot of tea", 320, "USD", 25, "Drinks"),
            ProductEntity("CRS-004", "Butter croissant", 340, "USD", 12, "Bakery"),
            ProductEntity("SCN-005", "Fruit scone", 295, "USD", 8, "Bakery"),
            ProductEntity("SND-006", "Cheese sandwich", 650, "USD", 6, "Food"),
            ProductEntity("SLD-007", "Garden salad", 725, "USD", 4, "Food"),
            ProductEntity("WTR-008", "Sparkling water", 180, "USD", 2, "Drinks"),
        )
    }
}

class DemoApplication : Application() {

    lateinit var container: DemoContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = DemoContainer(this)
        applicationScope.launch {
            container.seedCatalogIfEmpty()
            // Signals only - see DeviceIntegrity for why this is not attestation. Logged rather
            // than enforced because a prototype that refuses to run on an emulator is untestable.
            Log.i("SoftPosDemo", "Device integrity: " + container.softPos.deviceIntegrity().describe())
        }
    }
}

val Context.demoContainer: DemoContainer
    get() = (applicationContext as DemoApplication).container
