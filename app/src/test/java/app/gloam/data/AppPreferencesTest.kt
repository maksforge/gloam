package app.gloam.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.gloam.shade.AutoOff
import app.gloam.shade.Coordinates
import app.gloam.shade.PauseLength
import app.gloam.shade.ScheduleKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalTime

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
    fun `every key is either a setting that Restore removes or kept, never both or neither`() {
        // Kotlin note: an `object`'s `val`s compile to static fields on its class, so plain Java
        // reflection finds every key declared there without kotlin-reflect.
        val declared =
            AppPreferences.Keys::class.java.declaredFields
                .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
                .map { field ->
                    field.isAccessible = true
                    field.get(null) as Preferences.Key<*>
                }
        assertTrue(declared.isNotEmpty())
        for (key in declared) {
            val lists = listOf(AppPreferences.Keys.SETTINGS, AppPreferences.Keys.KEPT).count { key in it }
            assertEquals(key.name, 1, lists)
        }
        assertEquals(declared.toSet(), (AppPreferences.Keys.SETTINGS + AppPreferences.Keys.KEPT).toSet())
    }

    @Test
    fun `restoring resets every setting and keeps live state, counters, asks and the lent location`() =
        runBlocking {
            with(preferences) {
                setThemeMode(ThemeMode.LIGHT)
                setDimLevelAndWarmth(12, 34, 56)
                setLowerBacklight(false)
                setAutoOff(AutoOff.ofMinutes(30))
                setPauseLength(PauseLength.ofMinutes(97))
                setScheduleEnabled(true)
                setScheduleWindow(LocalTime.of(20, 0), LocalTime.of(6, 0))
                setScheduleKind(ScheduleKind.SunsetToSunrise)
                setLauncherCompact(false)
                setControlsBubble(false)
                saveCurrentAsPreset("Mine")
                deletePreset("reading")
                beginShade(offAtMillis = 5_000L, beganAtMillis = 1_000L)
                setScheduleHonouredAt(777L)
                setBubbleAsked()
                setTipAsked()
                lendLocation(Coordinates(50.1, 19.9), "Europe/Warsaw")

                restoreDefaults()

                assertEquals(ThemeMode.DARK, themeMode.first())
                assertEquals(DEFAULT_DIM_LEVEL, dimLevel.first())
                assertEquals(0, warmth.first())
                assertEquals(DEFAULT_WARMTH_COLOR, warmthColor.first())
                assertTrue(lowerBacklight.first())
                assertEquals(AutoOff.Default, autoOff.first())
                assertEquals(PauseLength.Default, pauseLength.first())
                val schedule = schedule.first()
                assertFalse(schedule.enabled)
                assertEquals(LocalTime.of(22, 0), schedule.onAt)
                assertEquals(LocalTime.of(7, 0), schedule.offAt)
                assertEquals(ScheduleKind.FixedTimes, schedule.kind)
                assertNotNull(schedule.lent)
                assertTrue(launcherCompact.first())
                assertTrue(controlsBubble.first())
                assertEquals(DEFAULT_PRESETS, presets.first())

                assertEquals(
                    ShadeIntent(running = true, offAtMillis = 5_000L, beganAtMillis = 1_000L),
                    shadeIntent.first(),
                )
                assertEquals(777L, scheduleHonouredAt.first())
                assertEquals(1, shadeStarts.first())
                assertTrue(bubbleAsked.first())
                assertTrue(tipAsked.first())
            }
        }

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
