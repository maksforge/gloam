package app.gloam.ui.dim

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gloam.MainApplication
import app.gloam.data.AppPreferences
import app.gloam.data.DEFAULT_WARMTH_COLOR
import app.gloam.shade.AutoOff
import app.gloam.shade.Schedule
import app.gloam.shade.ShadeEnd
import app.gloam.shade.ShadeStart
import app.gloam.shade.beginShadeAt
import app.gloam.shade.endShadeAt
import app.gloam.shade.shadeOnScreen
import app.gloam.tip.TIP_ASK_AFTER_STARTS
import app.gloam.tip.TipJar
import app.gloam.tip.tipAskDue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * @param dimLevel 0–100, the one value the product is about.
 * @param warmth 0–100, how far the shade is tinted. Its *applied* strength is not this number
 *   — the ramp scales it by the headroom the dim level leaves — which is why the slider shows what
 *   was asked for rather than what the composite ended up with.
 * @param warmthColor 0–100, where the tint sits between amber and deep red. The default here is only
 *   the first frame's; the stored default is [DEFAULT_WARMTH_COLOR], and the two agree so that frame
 *   does not paint a different colour from the next.
 * @param running whether the user has asked for the shade. **The stored intent, not the live state
 *   of the service** — the service can be killed by the ROM without the user having changed their
 *   mind, and this is the value that survives that.
 * @param lowerBacklight whether Gloam may take the backlight down before it draws the shade. Whether
 *   it *can* on this device is a different question, and not one a `ViewModel` can answer — it needs
 *   a `Context`, so the screen reads it.
 * @param autoOff how long a hand-started shade stays up. What was chosen, not what is left.
 * @param offAtMillis the instant the shade next comes down, or `null` for no deadline. Paired with
 *   [running] rather than derived from [autoOff]: the deadline was fixed when the shade started, and
 *   the choice can have moved since.
 * @param schedule the nightly window, for the summary row that reports it. **Read-only here** — this
 *   `ViewModel` has no setter for it, because the screen that edits a schedule is the schedule's
 *   own. Whether the battery exemption puts that schedule at risk is a live `Context` read and
 *   therefore the host's, not this one's.
 * @param onScreen whether the shade's window is up in this process — [shadeOnScreen], the live half
 *   that [running] deliberately is not.
 */
data class DimUiState(
    val dimLevel: Int = 0,
    val warmth: Int = 0,
    val warmthColor: Int = DEFAULT_WARMTH_COLOR,
    val running: Boolean = false,
    val lowerBacklight: Boolean = true,
    val autoOff: AutoOff = AutoOff.Default,
    val offAtMillis: Long? = null,
    val schedule: Schedule = Schedule(enabled = false, onAt = LocalTime.MIDNIGHT, offAt = LocalTime.MIDNIGHT),
    val onScreen: Boolean = false,
) {
    /**
     * **What the Start/Stop button and the deadline line show**: asked for *and* on screen.
     *
     * After HyperOS kills the process the intent still says running and nothing is drawn, and the
     * button then offers *Start* rather than a *Stop* for a shade that is not there. Anything that
     * decides what *should* happen — the deadline reconcile, the boot receiver — keeps reading
     * [running]; this is only for saying what *is*.
     *
     * Kotlin note: a property with a getter in the class body is not part of `copy()` or `equals()`,
     * so it can never disagree with the two fields it is computed from.
     */
    val shadeUp: Boolean
        get() = running && onScreen
}

/**
 * What the tip's one-time ask is decided from, less the install time — that needs a `Context`, so
 * the screen supplies it to [tipAskDue].
 *
 * @param available whether Play will sell a tip here. Nothing is asked where nothing can be bought.
 */
data class TipAskInputs(
    val asked: Boolean = true,
    val shadeStarts: Int = 0,
    val available: Boolean = false,
)

class DimViewModel(
    private val preferences: AppPreferences,
    private val tipJar: TipJar,
    onScreen: Flow<Boolean> = shadeOnScreen,
) : ViewModel() {
    // Its own flow rather than three more fields in `DimUiState`: that combine, below, is at its arity
    // limit, and this answers a question about the app rather than about the shade.
    val tipAsk: StateFlow<TipAskInputs> =
        combine(preferences.tipAsked, preferences.shadeStarts, tipJar.offers) { asked, starts, offers ->
            TipAskInputs(asked, starts, offers.isNotEmpty())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TipAskInputs())

    init {
        // Play is only asked for prices once the ask could be due.
        viewModelScope.launch {
            if (!preferences.tipAsked.first() && preferences.shadeStarts.first() >= TIP_ASK_AFTER_STARTS) {
                tipJar.refresh()
            }
        }
    }

    fun answerTipAsk() {
        viewModelScope.launch { preferences.setTipAsked() }
    }

    val state: StateFlow<DimUiState> =
        combine(
            preferences.dimLevel,
            // Warmth and its colour paired for the same arity reason as the two pairs below, and
            // they are one section of the screen as well.
            combine(preferences.warmth, preferences.warmthColor, ::Pair),
            // The intent and the live window paired, because they are the two halves of one
            // question — what the button says — and the arity limit below leaves no sixth slot.
            combine(preferences.shadeIntent, onScreen, ::Pair),
            preferences.lowerBacklight,
            // Kotlin note: `combine` is typed for two to five flows and the schedule is the sixth,
            // so the two that already belong to one section of the screen are paired first. Nesting
            // rather than the vararg overload, which hands back an `Array<Any?>` and a cast per
            // field — the type checker is doing real work here and is worth keeping.
            combine(preferences.autoOff, preferences.schedule, ::Pair),
        ) { level, (warmth, warmthColor), (intent, up), lowerBacklight, (autoOff, schedule) ->
            DimUiState(
                dimLevel = level,
                warmth = warmth,
                warmthColor = warmthColor,
                running = intent.running,
                lowerBacklight = lowerBacklight,
                autoOff = autoOff,
                offAtMillis = intent.offAtMillis,
                schedule = schedule,
                onScreen = up,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DimUiState())

    /**
     * Written on every drag, and that is deliberate rather than careless.
     *
     * DataStore serialises its writes and the running service is collecting this same `Flow`, so
     * writing here is what makes the shade follow the slider live. Debouncing would buy a few file
     * writes at the cost of the one thing that makes the control feel connected to the screen.
     */
    fun setDimLevel(level: Int) {
        viewModelScope.launch { preferences.setDimLevel(level) }
    }

    /**
     * Written on every drag like [setDimLevel], and for the same reason: the running service
     * collects this same `Flow`, so the tint follows the slider live.
     */
    fun setWarmth(warmth: Int) {
        viewModelScope.launch { preferences.setWarmth(warmth) }
    }

    /** Written on every drag, like [setWarmth]: the service repaints the tint as the handle moves. */
    fun setWarmthColor(warmthColor: Int) {
        viewModelScope.launch { preferences.setWarmthColor(warmthColor) }
    }

    /**
     * Writes a preset's three values. **Starting the shade is not this one's job** — the screen does
     * that after calling this, through the same permission branch as its Start button, because
     * starting a service needs a `Context`.
     */
    fun applyPreset(preset: Preset) {
        viewModelScope.launch {
            preferences.setDimLevelAndWarmth(preset.dimLevel, preset.warmth, preset.warmthColor)
        }
    }

    /**
     * Records the intent **and the deadline it comes with**, in one write.
     *
     * **Starting the service is the screen's job, not this one's** — a `ViewModel` has no `Context`
     * to start one with, and giving it one is how a `ViewModel` starts outliving the thing it was
     * scoped to. The deadline is this one's, because it is arithmetic over a stored preference.
     *
     * The settings are read from their `Flow`s rather than from [state]: `state` holds defaults
     * until its first emission arrives, and the shade can be started from a cold launch before that
     * lands. [beginShadeAt] does that reading, resolves every deadline that is live, and stores the
     * earliest with the flag.
     */
    fun beginShade() {
        viewModelScope.launch { preferences.beginShadeAt(ShadeStart.ByHand) }
    }

    /**
     * The shade is down, by the button or because its deadline turned out to have passed.
     *
     * **The reason is a parameter because the two callers are not the same event**, even though both
     * spend the night: the Stop button is somebody deciding, and the resume reconcile is a deadline
     * they were already promised arriving late. A single `endShade()` here would have made the
     * distinction invisible at exactly the layer that knows it.
     */
    fun endShade(reason: ShadeEnd) {
        viewModelScope.launch { preferences.endShadeAt(reason) }
    }

    /**
     * The chip. **While the shade is up this also rewrites the deadline, from now rather than from
     * when the shade started** — which is the reading somebody expects from tapping "2 hours" while
     * looking at a screen, and it makes the control usable as "give me two more hours" without
     * inventing a second one.
     *
     * `beginShade` with `running` already `true` rather than a third setter: the pair exists so the
     * deadline is never written without the flag beside it.
     */
    fun setAutoOff(choice: AutoOff) {
        viewModelScope.launch {
            preferences.setAutoOff(choice)
            // The guard is load-bearing rather than an optimisation: `beginShadeAt` writes
            // `running = true` in the same transaction as the deadline, so without it a chip tap
            // would turn the shade **on** while it is off.
            if (preferences.shadeIntent.first().running) {
                preferences.beginShadeAt(ShadeStart.ByHand)
            }
        }
    }

    /**
     * Takes effect on a live shade without restarting it: the service collects the same `Flow`, so
     * switching this off releases the override and hands the user their own brightness slider back
     * while they are looking at it.
     */
    fun setLowerBacklight(enabled: Boolean) {
        viewModelScope.launch { preferences.setLowerBacklight(enabled) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[APPLICATION_KEY] as MainApplication
                    DimViewModel(app.preferences, app.container.tipJar)
                }
            }
    }
}
