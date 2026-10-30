package app.gloam.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.gloam.shade.AutoOff
import app.gloam.shade.Coordinates
import app.gloam.shade.Location
import app.gloam.shade.NO_DEADLINE
import app.gloam.shade.PauseLength
import app.gloam.shade.Schedule
import app.gloam.shade.ScheduleKind
import app.gloam.shade.deadlineOrNull
import app.gloam.shade.minutesOf
import app.gloam.shade.timeOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * The two values that say what the shade should be doing, read together because they are written
 * together (see [AppPreferences.beginShade]).
 *
 * **They are one fact, not two.** A caller that can read *running* without the deadline beside it is
 * a caller that can believe a shade is up nine hours after it should have come down — which is the
 * one way this app's stored values can disagree with each other. Phase 2's boot receiver wants
 * exactly this pair in one read, and so does the screen's resume reconcile.
 *
 * @param running what the user asked for, **not** whether the service is alive. The ROM kills the
 *   service without the user changing their mind; this is the value that survives that.
 * @param offAtMillis the instant the shade next comes down, or `null` for no deadline. Storage's `0`
 *   is collapsed to `null` here, at the one boundary that knows the sentinel.
 * @param beganAtMillis when the shade was last started, or `null` if no write of ours has said.
 *   It has two readers. `BootReceiver` compares it against the install's own `firstInstallTime`,
 *   which is never backed up, to tell this install's intent from another phone's that Auto Backup
 *   restored (Phase 5, R4). The bubble's ring drains the deadline from it, which holds because
 *   [AppPreferences.beginShade] is the one writer of a deadline and writes both keys together, so
 *   every deadline write re-stamps it.
 */
data class ShadeIntent(
    val running: Boolean,
    val offAtMillis: Long?,
    val beganAtMillis: Long?,
)

/** Which colour scheme applies, regardless of what the phone is set to. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Everything the app remembers that is not a row in the database.
 *
 * **DataStore, not SharedPreferences.** SharedPreferences' `commit()` blocks and its `apply()` can
 * still write on the main thread at the wrong moment; DataStore is `suspend`-only and transactional,
 * so there is no API here that can stall a frame.
 *
 * Kotlin note: every read is a `Flow`, not a value. There is no "current value" to ask for
 * synchronously — the first emission arrives after a disk read. That matters at startup: a screen
 * collecting `themeMode` gets `SYSTEM` for one frame and the stored value on the next, which is a
 * visible flash. [themeModeNow] exists for the one caller that must have the answer *before* the
 * first frame, and it is `suspend` because reading a file is.
 *
 * ## Adding a preference
 *
 * A key, a `Flow` that reads it with a default, and a `suspend` setter. Keep the default in the
 * `Flow` rather than writing it on first launch — a default that has been written to disk cannot be
 * changed in a later version without a migration, and a default that has not can.
 */
class AppPreferences(
    private val store: DataStore<Preferences>,
    /** The lent location's own file, kept out of backup. See `lentLocationStore` for why it is separate. */
    private val lentLocationStore: DataStore<Preferences>,
) {
    private object LentKeys {
        // Tenths of a degree as whole numbers. The 0.1° rounding ADR-0013 §8 asks for is then what the
        // file holds, not something applied after reading a more precise value from disk.
        val LATITUDE_TENTHS = intPreferencesKey("latitude_tenths")
        val LONGITUDE_TENTHS = intPreferencesKey("longitude_tenths")
        val ZONE_ID = stringPreferencesKey("zone_id")
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")

        // Retired, never reuse: `material_you` was a boolean until the toggle was removed (ADR-0006's
        // amendment), and installs from before still carry it. Nothing reads it, so it costs nothing
        // — but a new key under that name with another type would throw on those installs' first read.
        val DIM_LEVEL = intPreferencesKey("dim_level")
        val SHADE_RUNNING = booleanPreferencesKey("shade_running")
        val LOWER_BACKLIGHT = booleanPreferencesKey("lower_backlight")
        val WARMTH = intPreferencesKey("warmth")
        val WARMTH_COLOR = intPreferencesKey("warmth_color")
        val AUTO_OFF_MINUTES = intPreferencesKey("auto_off_minutes")
        val OFF_AT_MILLIS = longPreferencesKey("off_at_millis")
        val PAUSE_MINUTES = intPreferencesKey("pause_minutes")

        /**
         * When the shade was started. Absent rather than `0` when unknown, which is what an install
         * predating this key and a restore from another phone both look like — and both are
         * refused.
         */
        val SHADE_BEGAN_AT = longPreferencesKey("shade_began_at")
        val LAUNCHER_COMPACT = booleanPreferencesKey("launcher_compact")
        val CONTROLS_BUBBLE = booleanPreferencesKey("controls_bubble")
        val BUBBLE_ASKED = booleanPreferencesKey("controls_bubble_asked")
        val SCHEDULE_ENABLED = booleanPreferencesKey("schedule_enabled")
        val SCHEDULE_ON_MINUTES = intPreferencesKey("schedule_on_minutes")
        val SCHEDULE_OFF_MINUTES = intPreferencesKey("schedule_off_minutes")
        val SCHEDULE_HONOURED_AT = longPreferencesKey("schedule_honoured_at")
        val SCHEDULE_KIND = stringPreferencesKey("schedule_kind")
        val SHADE_STARTS = intPreferencesKey("shade_starts")
        val TIP_ASKED = booleanPreferencesKey("tip_asked")
        val SAVED_PRESETS = stringPreferencesKey("saved_presets")
    }

    /**
     * Light, dark or follow-the-system — and **the default is [ThemeMode.DARK], not
     * [ThemeMode.SYSTEM]**.
     *
     * That is a product decision rather than a style one. Gloam is opened by somebody whose screen
     * is already too bright for the room they are in; a phone left on the system default hands that
     * person a full-brightness white screen as the *first* thing the dimming app does. Following
     * the system is the right default for an app that is used at any hour, and this one is not.
     *
     * The setting stays — all three values are still offered in Settings, [ThemeMode.SYSTEM]
     * included — so this changes what an untouched install does and nothing else.
     *
     * Unknown stored values fall back to the same default rather than throwing: a preference file
     * survives a downgrade, and a crash loop at startup is a far worse outcome than the wrong theme.
     * This is the opposite of the rule for enums stored in the *database*, where an unknown value
     * means the schema gate missed something and should be loud.
     *
     * **The default lives in the read, never on disk** (the house rule): nothing writes `DARK` at
     * first launch, so a later version can still change its mind without a migration, and a user who
     * has actually chosen dark is indistinguishable from one who never opened Settings — which is
     * fine, because both want the same screen.
     */
    val themeMode: Flow<ThemeMode> =
        store.data.map { prefs ->
            prefs[Keys.THEME_MODE]?.let { name ->
                runCatching { enumValueOf<ThemeMode>(name) }.getOrNull()
            } ?: ThemeMode.DARK
        }

    /**
     * How dark the shade is, 0–100. **Not a brightness** — it runs the other way, and the backlight
     * is Android's value rather than ours (CONTEXT.md).
     *
     * Clamped on read as well as on write. A value outside the range can only arrive from a build
     * that used a different range, and the screen it feeds draws a slider that would throw on one.
     * Coercing is the behaviour that degrades rather than crashes.
     */
    val dimLevel: Flow<Int> = store.data.map { (it[Keys.DIM_LEVEL] ?: DEFAULT_DIM_LEVEL).coerceIn(0, 100) }

    /**
     * Whether the shade should be on screen, and when it next comes down.
     *
     * The user's intent, not the live state of the service — those are different questions and only
     * this one survives the process being killed. Xiaomi will kill the service; what it cannot do is
     * change this, which is what lets the shade come back rather than silently staying off.
     *
     * One `Flow` for both keys rather than one each, because [beginShade] and [endShade] write them
     * in a single transaction and a reader that could take them apart is a reader that can see a
     * half-written intent.
     */
    val shadeIntent: Flow<ShadeIntent> =
        store.data.map { prefs ->
            ShadeIntent(
                running = prefs[Keys.SHADE_RUNNING] ?: false,
                offAtMillis = deadlineOrNull(prefs[Keys.OFF_AT_MILLIS] ?: NO_DEADLINE),
                beganAtMillis = prefs[Keys.SHADE_BEGAN_AT],
            )
        }

    /**
     * How long a hand-started shade stays up before Gloam takes it down (CONTEXT.md: **auto-off**).
     *
     * Stored as **minutes**, `0` for *Never*, which is what let a typed length arrive without a
     * migration; `AutoOff.ofMinutes` carries the clamp. Defaults to `AutoOff.Default`
     * — on rather than to `Never`, because until Phase 2b's tile ships this is the only way out of
     * the shade that needs no gesture at all.
     */
    val autoOff: Flow<AutoOff> =
        store.data.map { AutoOff.ofMinutes(it[Keys.AUTO_OFF_MINUTES] ?: AutoOff.Default.minutes) }

    /**
     * How long a pause clears the shade for (CONTEXT.md: **pause**). The length is a setting and
     * travels with a backup; the pause itself is live state and is never stored.
     */
    val pauseLength: Flow<PauseLength> =
        store.data.map { PauseLength.ofMinutes(it[Keys.PAUSE_MINUTES] ?: PauseLength.Default.minutes) }

    /**
     * Whether Gloam may take the **backlight** down before it draws the shade (ADR-0010).
     *
     * **Defaults on**, because it is the larger of the two mechanisms — on the development phone the
     * backlight alone is a factor of 250 in nits before a single pixel is drawn over anything, and
     * the whole product thesis is the light below the system slider's own floor. A user who wants
     * the shade alone switches it off; a user who wants the app to work does nothing.
     *
     * Kept as a preference rather than a constant because it has a visible cost the user may not
     * want: while it is on, their own brightness slider moves and does not apply. That is a taste,
     * not a safety floor, which is the line CLAUDE.md draws between the two.
     */
    val lowerBacklight: Flow<Boolean> = store.data.map { it[Keys.LOWER_BACKLIGHT] ?: true }

    /**
     * How far the shade is tinted, 0–100 (CONTEXT.md: **warmth**).
     *
     * **Defaults to 0**, for the same reason [DEFAULT_DIM_LEVEL] is modest: a colour cast nobody
     * asked for is indistinguishable from a broken screen, and warmth is only worth having because
     * someone chose it.
     *
     * Coerced on read as well as on write, like [dimLevel] — a value outside the range can only come
     * from a build with a different one, and it feeds a slider that would throw on it.
     *
     * A separate control from the dim level, but not an independent one: the applied tint is scaled
     * by the headroom the dim level leaves, because the two together decide whether anything
     * underneath stays legible. That arithmetic is `shadeValuesFor`'s, not this key's.
     */
    val warmth: Flow<Int> = store.data.map { (it[Keys.WARMTH] ?: 0).coerceIn(0, 100) }

    /**
     * Where warmth's tint sits between amber (0) and deep red (100) (CONTEXT.md: **warmth colour**).
     * `warmthTint` turns it into the colour the shade paints.
     *
     * **Unlike [warmth], it has no visible effect of its own**, so a non-zero default costs nothing:
     * at warmth 0 there is no tint to colour. That is why the default can be the colour we would
     * recommend rather than the one that does nothing. See [DEFAULT_WARMTH_COLOR].
     *
     * Coerced on read and on write, like [warmth].
     */
    val warmthColor: Flow<Int> =
        store.data.map { (it[Keys.WARMTH_COLOR] ?: DEFAULT_WARMTH_COLOR).coerceIn(0, 100) }

    /** The presets the user named, in the order they were first saved. A setting: welcome on a new phone. */
    val savedPresets: Flow<List<SavedPreset>> = store.data.map { decodeSavedPresets(it[Keys.SAVED_PRESETS]) }

    /**
     * Whether a tap on the launcher icon opens the **compact controls** instead of the full app
     * (`docs/phase-3.md` §2, shape iv — the icon's `<intent-filter>` is on `ControlsActivity`, and
     * this key decides whether it stays there or forwards to `MainActivity`).
     *
     * **It shipped `false` in Phase 3a and defaults `true` from Phase 4, and R9 is why.** The
     * argument that kept it off was that a first launcher tap landing on a dialog over another app
     * is a bad first meeting with an app nobody has used yet — and on the device it cannot happen.
     * `ControlsActivity.forwardIfUnusable()` returns to `MainActivity` whenever `canDrawShade()` or
     * `escapeHatchLive()` is false, and on a genuine first run both are, so the tap lands on the full
     * app with its explainer. The same reading killed the cost nobody had named: a translucent,
     * floating activity is given no starting window at all, so the bounce paints nothing.
     *
     * **One row that reading found, and it is not a bug to fix here.** `escapeHatchLive()` is in the
     * same guard, so a user who denied notifications gets the full app from the icon for good, with
     * nothing on screen saying why. The compact host is not an escape hatch (`docs/phase-2.md` §2),
     * and sending somebody to a dialog they cannot escape from is the worse failure. Its consequence
     * is `DOD.md`'s: the rule-5 question about this default is answerable only by testers who
     * granted notifications.
     *
     * **Named for the one route it moves, not for a mode.** There is no compact mode: the compact
     * host is always available from the other doors, and a name like `compact_mode` is one a later
     * phase reads as permission to branch the whole app on it.
     */
    val launcherCompact: Flow<Boolean> = store.data.map { it[Keys.LAUNCHER_COMPACT] ?: true }

    /** Whether closing the controls over a running shade leaves the bubble behind (CONTEXT.md). */
    val controlsBubble: Flow<Boolean> = store.data.map { it[Keys.CONTROLS_BUBBLE] ?: true }

    /**
     * Whether the user has already been asked, on throwing the bubble away, if they want it at all.
     * Asked once per install; a restore carrying it over is a user who has already answered.
     */
    val bubbleAsked: Flow<Boolean> = store.data.map { it[Keys.BUBBLE_ASKED] ?: false }

    /**
     * How many times the shade has been started, by anything. Read only by the tip's one-time ask
     * (`tip/TipAsk.kt`); it is history rather than live state, so a restore carrying it is harmless.
     */
    val shadeStarts: Flow<Int> = store.data.map { it[Keys.SHADE_STARTS] ?: 0 }

    /**
     * Whether the home screen has mentioned the tip and been answered. Once per install, and a
     * restore carrying it over is somebody who has already answered — like [bubbleAsked].
     */
    val tipAsked: Flow<Boolean> = store.data.map { it[Keys.TIP_ASKED] ?: false }

    /**
     * The nightly window, as one value (CONTEXT.md: **schedule**).
     *
     * **One `Flow` for the three keys, like [shadeIntent] and for a related reason.** A reader that
     * could take them apart is a reader that can see the old on-time beside the new off-time — a
     * torn window, briefly inverted, at the exact moment something is arming an alarm from it. They
     * are *not* written in one transaction the way [beginShade]'s pair is, because the picker writes
     * one edge at a time, which makes the single read more necessary rather than less.
     *
     * **Minutes since local midnight, as an `Int`**, the same shape and the same reasoning as
     * `auto_off_minutes`: storage holds a number and the domain holds a type. A `LocalTime`
     * serialised as `"22:00"` would be a string to parse on every read, with a locale-shaped hazard
     * (`22:00` against `10:00 PM`) in a file that must survive an in-app language change, and a
     * parse failure with nothing sensible to do about it.
     *
     * **Defaults: off, 22:00, 07:00.** Off, because a screen dimmer that starts dimming on a night
     * the user did not ask about is indistinguishable from a broken phone — the same argument that
     * makes [DEFAULT_DIM_LEVEL] modest and warmth zero. The two times are defaults for a *disabled*
     * schedule, so they cost nothing and exist only so the picker opens on something plausible
     * rather than on midnight.
     *
     * **The kind defaults to fixed times** (ADR-0013), which is what every install had before the sun
     * was an option, so nobody's schedule moves on an update. It is stored by name like [themeMode],
     * with an unknown name falling back to the default for the same reason.
     *
     * **The lent location comes from a second file**, and `combine` is what keeps this one value
     * rather than two Flows a reader could take apart: it re-emits when either file changes, with the
     * latest of both. That way a location read re-arms the alarm the way an edited time does.
     *
     * Kotlin note: `combine` is RxJS's `combineLatest`. It waits for one emission from each input,
     * then emits on every emission from either one.
     */
    val schedule: Flow<Schedule> =
        combine(store.data, lentLocationStore.data) { prefs, lent ->
            Schedule(
                enabled = prefs[Keys.SCHEDULE_ENABLED] ?: false,
                onAt = timeOf(prefs[Keys.SCHEDULE_ON_MINUTES] ?: DEFAULT_SCHEDULE_ON_MINUTES),
                offAt = timeOf(prefs[Keys.SCHEDULE_OFF_MINUTES] ?: DEFAULT_SCHEDULE_OFF_MINUTES),
                kind =
                    prefs[Keys.SCHEDULE_KIND]?.let { name ->
                        runCatching { enumValueOf<ScheduleKind>(name) }.getOrNull()
                    } ?: ScheduleKind.FixedTimes,
                lent = lentLocationOf(lent),
            )
        }

    /**
     * The lent location, or `null` unless all three keys are there. [lendLocation] writes them in one
     * transaction, so a partial set can only mean a file from a build that stored something else.
     * Coerced into range like every number read here.
     */
    private fun lentLocationOf(prefs: Preferences): Location.Lent? {
        val latitude = prefs[LentKeys.LATITUDE_TENTHS] ?: return null
        val longitude = prefs[LentKeys.LONGITUDE_TENTHS] ?: return null
        val zoneId = prefs[LentKeys.ZONE_ID] ?: return null
        return Location.Lent(
            Coordinates(latitude.coerceIn(-900, 900) / 10.0, longitude.coerceIn(-1800, 1800) / 10.0),
            zoneId,
        )
    }

    /**
     * The on-instant of the night most recently acted on, or `0L` for none.
     *
     * **The one preference in this file that records what the app did rather than what the person
     * asked for**, and it is what makes raising the shade for a window found already open safe.
     * Without it, *the window is open and the shade is down* is indistinguishable from *the user
     * pressed Stop ten minutes ago* — and a reconcile that cannot tell those apart is a Stop button
     * that undoes itself at the next process start.
     *
     * **Deliberately not part of [schedule], and that placement is load-bearing rather than tidy.**
     * The reconcile writes it and the schedule's own collector is what triggers the reconcile, so
     * folding it in would re-emit that flow on every write and re-arm the alarm a second time. It
     * terminates — the second reconcile is a no-op — but it doubles every arm, and the reading that
     * asks whether exactly one alarm is armed reads that log.
     *
     * `0L` is no window and therefore reconcilable, which is the right answer on a fresh install.
     */
    val scheduleHonouredAt: Flow<Long> = store.data.map { it[Keys.SCHEDULE_HONOURED_AT] ?: 0L }

    /**
     * The theme mode, read once, before any Activity exists.
     *
     * The one place a `suspend` read is worth its cost: `MainApplication.onCreate` needs the answer
     * to call `applyThemeMode` *before* the first window is created, and a `Flow` collected in a
     * composition arrives a frame too late — which is a light flash on every cold start for a user
     * who chose dark.
     */
    suspend fun themeModeNow(): ThemeMode = themeMode.first()

    /**
     * The launcher preference, read once, before any Activity exists.
     *
     * The same shape and the same justification as [themeModeNow], and the same one caller:
     * `MainApplication` needs both before the first window, because a value that arrives after the
     * first composition arrives a frame too late — and here that frame is a whole activity launch,
     * with the wrong surface already on screen by the time the answer says it should not have been.
     *
     * Two one-shot reads rather than one combined one. Nothing writes the theme mode and this key
     * together, so there is no torn read to prevent — [ShadeIntent] exists for the pair that *is*
     * written in one transaction — and it costs nothing either way: DataStore serves `store.data`
     * from its in-memory cache after the first collection, so the second `first()` is not a second
     * disk read.
     */
    suspend fun launcherCompactNow(): Boolean = launcherCompact.first()

    /**
     * The stored intent, read once, for a caller with no lifetime to collect a `Flow` in.
     *
     * The same shape and the same justification as [themeModeNow]: a `Flow` is the right answer for
     * anything on screen, and the wrong one for code that must have a value *before* it can decide
     * whether to exist at all. Phase 2's boot receiver is exactly that — it runs inside `goAsync()`,
     * has roughly ten seconds, and either starts the service or finishes.
     *
     * **One read for both values, which is the point of [ShadeIntent].** `store.data.first()` reads
     * the file once and answers both keys off the same snapshot, so the receiver cannot see a
     * *running* written by one transaction beside a deadline from another.
     */
    suspend fun shadeIntentNow(): ShadeIntent = shadeIntent.first()

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDimLevel(level: Int) {
        store.edit { it[Keys.DIM_LEVEL] = level.coerceIn(0, 100) }
    }

    suspend fun setAutoOff(choice: AutoOff) {
        store.edit { it[Keys.AUTO_OFF_MINUTES] = choice.minutes }
    }

    suspend fun setPauseLength(length: PauseLength) {
        store.edit { it[Keys.PAUSE_MINUTES] = length.minutes }
    }

    /**
     * The user wants the shade up, and this is when it comes down. **One transaction, both keys.**
     *
     * There is deliberately no `setShadeRunning` beside this and no `setDeadline` under it. A caller
     * that can write *running* without the deadline is a caller that can leave a stale deadline
     * behind a fresh start — the shade would come down minutes after going up, on a deadline set
     * hours ago — and `DataStore.edit` being transactional is what makes writing them together free
     * rather than a lock.
     *
     * **Also the "give me two more hours" path**, called with `running` already `true` when the user
     * taps a different auto-off chip. Writing the same `true` back costs nothing and keeps the
     * invariant, which is why that case did not need a third method.
     *
     * **The third key is the clock, and it is written here rather than passed in.** Every caller
     * would hand it the same `System.currentTimeMillis()`, and one of them is a `ViewModel`, which
     * has no `Context` to read an install time with. What the value is *for* lives in
     * [ShadeIntent.beganAtMillis].
     *
     * @param offAtMillis `null` for no deadline, stored as [NO_DEADLINE].
     * @param beganAtMillis overridable for tests; every caller in the app takes the default.
     */
    suspend fun beginShade(
        offAtMillis: Long?,
        beganAtMillis: Long = System.currentTimeMillis(),
    ) {
        store.edit {
            // Counted on the edge only: this is also how a running shade's deadline is rewritten.
            if (it[Keys.SHADE_RUNNING] != true) it[Keys.SHADE_STARTS] = (it[Keys.SHADE_STARTS] ?: 0) + 1
            it[Keys.SHADE_RUNNING] = true
            it[Keys.OFF_AT_MILLIS] = offAtMillis ?: NO_DEADLINE
            it[Keys.SHADE_BEGAN_AT] = beganAtMillis
        }
    }

    /**
     * The shade is down, however it got there — the user, the notification's Stop action, the
     * deadline arriving, or the boot receiver tidying up after a deadline that passed while the
     * phone was off.
     *
     * Clears the deadline with the flag, so the next read cannot find one belonging to a shade that
     * is no longer up.
     *
     * @param honouredAt the on-instant of the night this ending spends, or `null` to leave the
     *   marker alone. **It is a parameter rather than a decision made here, and it has no default**:
     *   which endings spend a night is the shade's policy and lives in `Deadlines.kt` beside the
     *   pure function that answers it, while this file is keys, defaults and transactions. A default
     *   of `null` would be the trap — the one call site that must not spend a night is the one that
     *   looks most like it wants the short form. It is written in the *same* transaction as the two
     *   keys above so that a crash between them cannot leave a night unspent after a Stop.
     */
    suspend fun endShade(honouredAt: Long?) {
        store.edit {
            it[Keys.SHADE_RUNNING] = false
            it[Keys.OFF_AT_MILLIS] = NO_DEADLINE
            // Removed rather than zeroed: absent is what "no start of ours" means everywhere else.
            it.remove(Keys.SHADE_BEGAN_AT)
            if (honouredAt != null) it[Keys.SCHEDULE_HONOURED_AT] = honouredAt
        }
    }

    suspend fun setLowerBacklight(enabled: Boolean) {
        store.edit { it[Keys.LOWER_BACKLIGHT] = enabled }
    }

    suspend fun setWarmth(warmth: Int) {
        store.edit { it[Keys.WARMTH] = warmth.coerceIn(0, 100) }
    }

    suspend fun setWarmthColor(warmthColor: Int) {
        store.edit { it[Keys.WARMTH_COLOR] = warmthColor.coerceIn(0, 100) }
    }

    /**
     * All three of a preset's values in **one** edit, rather than the three setters above in a row.
     *
     * One transaction, so the store never holds a half-applied preset — the new dim level under the
     * old tint — for a process death or a backup to catch, and it is one disk write instead of
     * three. It does not promise the shade a single repaint: the service collects these as separate
     * `Flow`s, and the ramp bounds every combination of them anyway.
     */
    suspend fun setDimLevelAndWarmth(
        dimLevel: Int,
        warmth: Int,
        warmthColor: Int,
    ) {
        store.edit {
            it[Keys.DIM_LEVEL] = dimLevel.coerceIn(0, 100)
            it[Keys.WARMTH] = warmth.coerceIn(0, 100)
            it[Keys.WARMTH_COLOR] = warmthColor.coerceIn(0, 100)
        }
    }

    /**
     * Saves the dim level, warmth and colour **as they are stored now** under [name], replacing a
     * preset of that name if there is one. Read inside the same edit as the write, so the values
     * saved are the ones the shade is showing rather than a screen's copy of them a frame old.
     */
    suspend fun saveCurrentAsPreset(name: String) {
        store.edit {
            val preset =
                SavedPreset(
                    name = name.trim().take(PRESET_NAME_MAX_LENGTH),
                    dimLevel = (it[Keys.DIM_LEVEL] ?: DEFAULT_DIM_LEVEL).coerceIn(0, 100),
                    warmth = (it[Keys.WARMTH] ?: 0).coerceIn(0, 100),
                    warmthColor = (it[Keys.WARMTH_COLOR] ?: DEFAULT_WARMTH_COLOR).coerceIn(0, 100),
                )
            it[Keys.SAVED_PRESETS] = encodeSavedPresets(decodeSavedPresets(it[Keys.SAVED_PRESETS]).withSaved(preset))
        }
    }

    suspend fun renamePreset(
        from: String,
        to: String,
    ) {
        store.edit {
            val presets = decodeSavedPresets(it[Keys.SAVED_PRESETS])
            it[Keys.SAVED_PRESETS] = encodeSavedPresets(presets.renamed(from, to.trim().take(PRESET_NAME_MAX_LENGTH)))
        }
    }

    suspend fun deletePreset(name: String) {
        store.edit {
            it[Keys.SAVED_PRESETS] = encodeSavedPresets(decodeSavedPresets(it[Keys.SAVED_PRESETS]).without(name))
        }
    }

    suspend fun setLauncherCompact(enabled: Boolean) {
        store.edit { it[Keys.LAUNCHER_COMPACT] = enabled }
    }

    suspend fun setControlsBubble(enabled: Boolean) {
        store.edit { it[Keys.CONTROLS_BUBBLE] = enabled }
    }

    suspend fun setBubbleAsked() {
        store.edit { it[Keys.BUBBLE_ASKED] = true }
    }

    suspend fun setTipAsked() {
        store.edit { it[Keys.TIP_ASKED] = true }
    }

    suspend fun setScheduleEnabled(enabled: Boolean) {
        store.edit { it[Keys.SCHEDULE_ENABLED] = enabled }
    }

    /**
     * **The window is written as a pair, in one `edit`**, and there is deliberately no setter for
     * one edge.
     *
     * The three shapes a window can have are properties of the *pair*, so a caller that could move
     * one edge without the other is a caller that can transiently create the degenerate
     * `onAt == offAt` window — which answers *never* — while the user is halfway through a picker.
     * The screen picks one time at a time; this writes both. It is the same rule [beginShade]
     * follows for the flag and the deadline, for the same reason.
     */
    suspend fun setScheduleWindow(
        onAt: LocalTime,
        offAt: LocalTime,
    ) {
        store.edit {
            it[Keys.SCHEDULE_ON_MINUTES] = minutesOf(onAt)
            it[Keys.SCHEDULE_OFF_MINUTES] = minutesOf(offAt)
        }
    }

    suspend fun setScheduleKind(kind: ScheduleKind) {
        store.edit { it[Keys.SCHEDULE_KIND] = kind.name }
    }

    /**
     * Stores a location the phone reported, rounded to 0.1°, with the zone it was read in. **One
     * transaction for all three**, because a latitude from one read beside a zone from another is a
     * location nobody was ever at.
     *
     * This does not check whether a window is open. Replacing a location during one would move that
     * night's sunset and with it the night's identity (ADR-0013's consequences). That is the caller's
     * policy, like `honouredAt` on [endShade], and this file stays keys, defaults and transactions.
     */
    suspend fun lendLocation(
        coordinates: Coordinates,
        zoneId: String,
    ) {
        lentLocationStore.edit {
            it[LentKeys.LATITUDE_TENTHS] = (coordinates.latitude * 10).roundToInt().coerceIn(-900, 900)
            it[LentKeys.LONGITUDE_TENTHS] = (coordinates.longitude * 10).roundToInt().coerceIn(-1800, 1800)
            it[LentKeys.ZONE_ID] = zoneId
        }
    }

    /** Records that a night has been acted on. No screen calls this; see [scheduleHonouredAt]. */
    suspend fun setScheduleHonouredAt(onInstant: Long) {
        store.edit { it[Keys.SCHEDULE_HONOURED_AT] = onInstant }
    }
}

/**
 * Where the slider starts on a first run.
 *
 * Deliberately modest. Someone who installs a screen dimmer and lands on a nearly-black screen has
 * no way to tell a working app from a broken phone, and the control to fix it is the thing they can
 * no longer see.
 */
const val DEFAULT_DIM_LEVEL = 40

/**
 * Where the warmth colour bar starts on a first run: **the middle, which is also the recommendation.**
 *
 * The tint there is `#8D2900`, about the hue of a 1000 K light, which is where Twilight's own guidance
 * puts its ideal colour temperature. Against the amber end it lets through less green light (30% of the
 * page's green against 34% at full warmth), and text contrast stays 5.8 : 1 against 6.0. The deep-red
 * end is still one drag away for a fully dark room, where red spares night vision. The numbers are
 * `warmthTint`'s, and ADR-0010's sixth amendment has the reasoning.
 *
 * **The photometry is the whole argument, because reading speed was measured and had no opinion.**
 * The night-reading review proposed 25 on the grounds that deep red looks darker and red text reads
 * slower; three nights of the debug reading test, 288 trials over colours 0/25/50/100, put every
 * colour within noise of this one (ratios 0.95, 1.00 and 1.04, all intervals crossing 1.0, accuracy at
 * ceiling throughout). So this stays 50, and moving it would need a reason of a different kind —
 * spectrum, taste or a tester — rather than a faster page. ADR-0010's eighth amendment has the run.
 */
const val DEFAULT_WARMTH_COLOR = 50

/**
 * The lent location's DataStore file name. The backup rules name the file on disk it becomes,
 * `datastore/lent_location.preferences_pb`, and `BackupRulesTest` holds the two names together.
 */
const val LENT_LOCATION_STORE = "lent_location"

/** 22:00, in minutes since local midnight. The plan's own example, and the shape of the thing. */
const val DEFAULT_SCHEDULE_ON_MINUTES = 22 * 60

/** 07:00, in minutes since local midnight. */
const val DEFAULT_SCHEDULE_OFF_MINUTES = 7 * 60
