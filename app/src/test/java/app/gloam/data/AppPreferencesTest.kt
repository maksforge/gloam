package app.gloam.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The store-level writes, against a real DataStore file: what one transaction keeps and drops. */
class AppPreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val preferences by lazy {
        AppPreferences(
            store = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("app.preferences_pb") },
            lentLocationStore =
                PreferenceDataStoreFactory.create(
                    scope = scope,
                ) { folder.newFile("lent.preferences_pb") },
            presetLabel = { it.name },
        )
    }

    @After fun tearDown() = scope.cancel()

    @Test
    fun `no write stores two presets with the same values or the same name`() =
        runBlocking {
            with(preferences) {
                val reading = DEFAULT_PRESETS.first()
                setDimLevelAndWarmth(reading.dimLevel, reading.warmth, reading.warmthColor)
                saveCurrentAsPreset("Copy of reading")
                assertEquals(DEFAULT_PRESETS, presets.first())

                setDimLevelAndWarmth(1, 2, 3)
                saveCurrentAsPreset("Mine")
                renamePreset(presets.first().last().id, "evening")
                assertEquals(
                    listOf("Reading", "Evening", "InBed", "Mine"),
                    presets.first().map {
                        it.displayName { p ->
                            p.name
                        }
                    },
                )
            }
        }
}
