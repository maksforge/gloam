package app.gloam.ui.settings

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.gloam.MainApplication
import app.gloam.data.AppPreferences
import app.gloam.shade.DebugMinBacklight
import app.gloam.shade.DimSettings
import app.gloam.shade.canDrawShade
import app.gloam.shade.platformMinBacklight
import app.gloam.shade.platformWindowAlphaClamp
import app.gloam.shade.readBacklightTop
import app.gloam.shade.shadeOnScreen
import app.gloam.shade.shadeValuesFor
import app.gloam.shade.startShade
import app.gloam.shade.stopShade
import app.gloam.theme.AppTheme
import app.gloam.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The timed reading test behind `docs/night-reading-research.md` §3: **does the warmth colour slow
 * reading at a real night-time dim level?** It is the check the research asks for before
 * `DEFAULT_WARMTH_COLOR` moves from 50 towards 25, and it asks nobody to judge a colour by eye.
 *
 * ## What it does
 *
 * It takes over the real shade — dim level as chosen, warmth 100, backlight lowered — and puts a white
 * page with one short sentence on it under that shade. The reader taps *True* or *False*. The time from
 * the frame the sentence appears to the tap is the measurement, and a wrong answer shows the sentence
 * was not really read. Between blocks only the **warmth colour** changes, over [COLOURS].
 *
 * Why these choices:
 *
 * - **Under the real shade, not a simulation of it.** This Activity is an ordinary window, so the
 *   shade sits above it exactly as it sits above a reading app.
 * - **A white page with small black text**, because the research finds that is where dim light bites
 *   first: over a light page the tint's own colour matters most, and small letters lose legibility
 *   before large ones.
 * - **Warmth 100**, so the colour has as much effect as it ever can. If full warmth shows no
 *   difference, no lower warmth will.
 * - **Blocks in a mirrored order** (a shuffled pass, then the same pass reversed, twice), so warming
 *   up, adapting further to the dark and getting tired all fall evenly on every colour.
 *
 * ## What it leaves behind
 *
 * **The user's settings, exactly as they were** — dim level, warmth, colour, the backlight toggle,
 * and whether the shade was running and until when. They are put back when the test finishes *and*
 * when it is left early. And **one row per trial in `files/reading-test.csv`**, appended across
 * sessions so that several nights can be pooled:
 *
 * ```bash
 * adb shell run-as io.github.srednimax.gloam.debug cat files/reading-test.csv
 * ```
 *
 * The on-screen summary is a first look. The decision is taken on the pooled file.
 */
class ReadingTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Load-bearing, for the reason `DebugSettings` gives for its sweep: a screen that reaches its
        // inactivity timeout drops into the DIM policy and pins the panel to its floor, which would
        // change the light partway through a block and look exactly like a colour effect.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val preferences = (application as MainApplication).preferences
        setContent {
            AppTheme {
                ReadingTest(preferences = preferences, onClose = ::finish)
            }
        }
    }
}

/**
 * The four colours compared: both ends of the bar, the shipped default, and the value the research
 * proposes. 50 is also the reference the summary compares the others against.
 */
private val COLOURS = listOf(0, 25, 50, 100)

private const val REFERENCE_COLOUR = 50
private const val WARMTH = 100
private const val TRIALS_PER_BLOCK = 6

/** Unscored, at the reference colour, so the first scored block is not also the reader's first go. */
private const val PRACTICE_TRIALS = 6

/**
 * Time for the eye to settle on the test's own light. Not full dark adaptation, which takes the best
 * part of forty minutes — the instructions ask for the room to be dark beforehand.
 */
private const val ADAPT_SECONDS = 60
private const val COUNTDOWN_SECONDS = 4
private const val BLANK_MILLIS = 500L

/** Long enough for any real reading of a seven-word sentence; a miss past it counts as wrong. */
private const val TRIAL_TIMEOUT_MILLIS = 8_000L

/** Faster than anyone reads and decides. A tap this quick was aimed before the sentence was read. */
private const val ANTICIPATION_MILLIS = 300L

private const val TAG = "GloamRead"
private const val RESULTS_FILE = "reading-test.csv"
private const val RESULTS_HEADER =
    "session,language,text_sp,dim_level,warmth,backlight_top,min_backlight,override,shade_alpha," +
        "warmth_alpha,colour,block,sentence,is_true,answer,correct,rt_ms"

/**
 * The white of the page and the black of its text, **as literals, and deliberately so**. The house
 * rule is that colours come from `MaterialTheme`, but this is a test stimulus: it must not change when
 * the palette is regenerated or the app's theme is switched, for the same reason the shade's own tint
 * ends are constants in `ShadeRamp.kt`.
 */
private val PAGE = Color.White
private val INK = Color.Black

private data class TestConfig(
    val dimLevel: Int = 90,
    val textSp: Int = 12,
    val polish: Boolean,
)

/**
 * Where the test is.
 *
 * Kotlin note: a `sealed interface` with data classes is a discriminated union — the `when` over it
 * below must cover every case, the way a `switch` on a TypeScript union's `kind` can be made to.
 */
private sealed interface Stage {
    data object Setup : Stage

    data class Waiting(
        val headline: String,
        val secondsLeft: Int,
    ) : Stage

    data object Blank : Stage

    data class Showing(
        val sentence: ReadingSentence,
    ) : Stage

    data class Done(
        val summary: List<ColourSummary>,
        val aborted: String?,
    ) : Stage
}

/** A tap on an answer, timestamped where it lands rather than where the coroutine gets round to it. */
private data class Tap(
    val saidTrue: Boolean,
    val atNanos: Long,
)

private data class TrialRecord(
    val colour: Int,
    val answer: Boolean?,
    val rtMillis: Long?,
    val isTrue: Boolean,
) {
    val correct: Boolean get() = answer == isTrue
}

private data class ColourSummary(
    val colour: Int,
    val trials: Int,
    val correct: Int,
    val medianMillis: Long?,
    val logMean: Double?,
    val logSe: Double?,
)

/** Everything the test changes, read before it changes any of it. */
private data class Snapshot(
    val dimLevel: Int,
    val warmth: Int,
    val warmthColor: Int,
    val lowerBacklight: Boolean,
    val running: Boolean,
    val offAtMillis: Long?,
)

/**
 * Where the restore runs. **Not the composition's scope**, because the likeliest way a test ends
 * early is the back gesture, and that cancels the composition's scope at the moment the restore is
 * needed.
 *
 * Kotlin note: a scope made at file level lives as long as the process. The nearest JS analogue is a
 * promise nobody awaits and no component unmounting can cancel — which is the lifetime a clean-up that
 * must outlive its screen needs.
 */
private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

@Composable
private fun ReadingTest(
    preferences: AppPreferences,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val polishByDefault = LocalConfiguration.current.locales[0].language == "pl"

    var config by remember { mutableStateOf(TestConfig(polish = polishByDefault)) }
    var stage by remember { mutableStateOf<Stage>(Stage.Setup) }
    var runs by remember { mutableIntStateOf(0) }

    // Unlimited so a tap is never dropped while the session is between suspensions; stale taps are
    // drained before each sentence rather than trusted.
    val taps = remember { Channel<Tap>(Channel.UNLIMITED) }

    // The previous run's restore, so that "Run again" cannot snapshot the first run's test settings
    // as if they were the user's own.
    var restoring by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(runs) {
        if (runs == 0) return@LaunchedEffect
        restoring?.join()
        val snapshot = preferences.snapshot()
        Log.i(TAG, "snapshot $snapshot")
        try {
            stage = runSession(config, preferences, appContext, taps) { stage = it }
        } finally {
            restoring = restoreScope.launch { snapshot.restore(preferences, appContext) }
        }
    }

    // Kotlin note: `when` over a sealed type is exhaustive, so a new stage that nobody draws is a
    // compile error rather than a blank screen.
    when (val current = stage) {
        Stage.Setup ->
            Setup(
                config = config,
                onConfig = { config = it },
                canStart = context.canDrawShade(),
                onStart = { runs++ },
                onClose = onClose,
            )
        is Stage.Waiting -> Page { PageMessage(current.headline, current.secondsLeft.toString()) }
        // One branch for both, so the answer targets are the same composables from the blank to the
        // sentence and nothing on screen is rebuilt at the moment the timing starts.
        Stage.Blank, is Stage.Showing ->
            Trial(sentence = (current as? Stage.Showing)?.sentence, config = config, taps = taps)
        is Stage.Done ->
            Results(
                done = current,
                onAgain = { runs++ },
                onClose = onClose,
            )
    }
}

/**
 * The whole protocol, top to bottom. Returns the summary; the caller restores the settings whether it
 * returns, throws or is cancelled.
 *
 * Kotlin note: a `suspend` function reads like an `async` one — each `delay` or `receive` is an
 * `await` — but it runs inside the composition's coroutine, so leaving the screen cancels it at the
 * next suspension instead of letting it run on unseen.
 */
private suspend fun runSession(
    config: TestConfig,
    preferences: AppPreferences,
    context: Context,
    taps: ReceiveChannel<Tap>,
    show: (Stage) -> Unit,
): Stage.Done {
    val session = System.currentTimeMillis()
    val random = Random(session)
    val language = if (config.polish) "pl" else "en"
    val sentences = ReadingSentences.bank(config.polish).withIndex().shuffled(random)
    val firstPass = COLOURS.shuffled(random)
    val secondPass = COLOURS.shuffled(random)
    val order = firstPass + firstPass.reversed() + secondPass + secondPass.reversed()

    preferences.setLowerBacklight(true)
    preferences.setDimLevel(config.dimLevel)
    preferences.setWarmth(WARMTH)
    preferences.setWarmthColor(REFERENCE_COLOUR)
    preferences.beginShade(offAtMillis = null)
    context.startShade()

    // What the ramp should be doing, logged beside every trial so the light can be reconstructed later.
    // A fresh read of the top rather than the service's captured one; they agree unless the user moved
    // their brightness while the shade was already up.
    val top = readBacklightTop(context)
    val minBacklight = platformMinBacklight(DebugMinBacklight.value.value)
    val ramp =
        shadeValuesFor(
            DimSettings(config.dimLevel, WARMTH, lowerBacklight = true),
            top,
            minBacklight,
            platformWindowAlphaClamp(),
        )
    Log.i(TAG, "session $session: $config top=$top min=$minBacklight ramp=$ramp order=$order")

    val up = withTimeoutOrNull(5_000) { shadeOnScreen.first { it } }
    if (up == null) return Stage.Done(emptyList(), aborted = "The shade did not come up, so nothing was measured.")

    countdown(show, "Lights off. Reading starts in", ADAPT_SECONDS)
    countdown(show, "Practice", COUNTDOWN_SECONDS)
    var next = 0
    repeat(PRACTICE_TRIALS) { trial(sentences[next++].value, taps, show) }

    val results = File(context.filesDir, RESULTS_FILE)
    val records = mutableListOf<TrialRecord>()
    for ((block, colour) in order.withIndex()) {
        // A shade that came down — auto-off, the schedule, a ROM kill — turns every later trial into a
        // reading of an undimmed page. Stop rather than log it.
        if (!shadeOnScreen.value) {
            return Stage.Done(summarise(records), aborted = "The shade came down before block ${block + 1}.")
        }
        preferences.setWarmthColor(colour)
        countdown(show, "Block ${block + 1} of ${order.size}", COUNTDOWN_SECONDS)

        repeat(TRIALS_PER_BLOCK) {
            val (index, sentence) = sentences[next++]
            val (answer, rtMillis) = trial(sentence, taps, show)
            val record = TrialRecord(colour, answer, rtMillis, sentence.isTrue)
            records += record
            val row =
                listOf(
                    session,
                    language,
                    config.textSp,
                    config.dimLevel,
                    WARMTH,
                    top ?: "",
                    minBacklight,
                    ramp.backlight ?: "",
                    ramp.shadeAlpha,
                    ramp.warmthAlpha,
                    colour,
                    block,
                    "$language-$index",
                    sentence.isTrue,
                    answer ?: "",
                    record.correct,
                    rtMillis ?: "",
                ).joinToString(",")
            Log.i(TAG, row)
            withContext(Dispatchers.IO) {
                if (!results.exists()) results.writeText(RESULTS_HEADER + "\n")
                results.appendText(row + "\n")
            }
        }
    }

    val summary = summarise(records)
    Log.i(TAG, "session $session summary: $summary")
    return Stage.Done(summary, aborted = null)
}

private suspend fun countdown(
    show: (Stage) -> Unit,
    headline: String,
    seconds: Int,
) {
    for (left in seconds downTo 1) {
        show(Stage.Waiting(headline, left))
        delay(1_000)
    }
}

/**
 * One sentence: a blank, the sentence, and the first tap. Returns the answer and the time it took, or
 * two nulls if nobody answered in time.
 */
private suspend fun trial(
    sentence: ReadingSentence,
    taps: ReceiveChannel<Tap>,
    show: (Stage) -> Unit,
): Pair<Boolean?, Long?> {
    show(Stage.Blank)
    delay(BLANK_MILLIS)
    while (taps.tryReceive().isSuccess) Unit // taps made before the sentence was up answer nothing

    show(Stage.Showing(sentence))
    // `withFrameNanos` resumes at the start of the next frame, which is the frame that draws the
    // sentence, and its clock is `System.nanoTime` — the same one the tap is stamped with. What is
    // left over is the panel's own latency, which is the same for every colour.
    val shownAt = withFrameNanos { it }
    val tap = withTimeoutOrNull(TRIAL_TIMEOUT_MILLIS) { taps.receive() } ?: return null to null
    return tap.saidTrue to (tap.atNanos - shownAt) / 1_000_000
}

/**
 * Per colour: accuracy over every trial, and time over the correct, unanticipated ones.
 *
 * **Times are compared on a log scale**, because reaction times are skewed and a slowdown is a ratio:
 * a colour that makes reading 10% slower adds more milliseconds to a hard sentence than to an easy one.
 */
private fun summarise(records: List<TrialRecord>): List<ColourSummary> =
    COLOURS.map { colour ->
        val mine = records.filter { it.colour == colour }
        val times =
            mine
                .filter { it.correct }
                .mapNotNull { it.rtMillis }
                .filter { it >= ANTICIPATION_MILLIS }
                .sorted()
        val logs = times.map { ln(it.toDouble()) }
        val mean = if (logs.isEmpty()) null else logs.average()
        val se =
            if (mean == null || logs.size < 2) {
                null
            } else {
                sqrt(logs.sumOf { (it - mean).pow(2) } / (logs.size - 1) / logs.size)
            }
        ColourSummary(colour, mine.size, mine.count { it.correct }, times.getOrNull(times.size / 2), mean, se)
    }

private suspend fun AppPreferences.snapshot(): Snapshot {
    val intent = shadeIntentNow()
    return Snapshot(
        dimLevel = dimLevel.first(),
        warmth = warmth.first(),
        warmthColor = warmthColor.first(),
        lowerBacklight = lowerBacklight.first(),
        running = intent.running,
        offAtMillis = intent.offAtMillis,
    )
}

/**
 * Put back what [snapshot] read. A shade that was running gets its own deadline back, even one that
 * passed during the test, so the service takes it down exactly as it would have. A shade that was not
 * running is ended without spending a scheduled night (`honouredAt = null`), because a debug test is
 * not a person pressing Stop.
 */
private suspend fun Snapshot.restore(
    preferences: AppPreferences,
    context: Context,
) {
    preferences.setDimLevel(dimLevel)
    preferences.setWarmth(warmth)
    preferences.setWarmthColor(warmthColor)
    preferences.setLowerBacklight(lowerBacklight)
    if (running) {
        preferences.beginShade(offAtMillis)
    } else {
        preferences.endShade(honouredAt = null)
        context.stopShade()
    }
    Log.i(TAG, "restored $this")
}

@Composable
private fun Setup(
    config: TestConfig,
    onConfig: (TestConfig) -> Unit,
    canStart: Boolean,
    onStart: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val sessions by produceState(initialValue = 0) {
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    File(context.filesDir, RESULTS_FILE)
                        .readLines()
                        .drop(1)
                        .map { it.substringBefore(',') }
                        .distinct()
                        .size
                }.getOrDefault(0)
            }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.snug),
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.base),
        ) {
            Text("Reading test: warmth colour", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Run it at night in a dark room, after five minutes with the lights off. The test sets " +
                    "the dim level below, warmth 100 and a lowered backlight, then compares colours " +
                    "${COLOURS.joinToString()}. Your own settings come back when it ends or when you leave.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Read each sentence and tap True or False as quickly as you can without guessing. " +
                    "About eight minutes.",
                style = MaterialTheme.typography.bodyMedium,
            )

            ChoiceRow("Dim level", listOf(80, 90, 100), config.dimLevel, { "$it" }) {
                onConfig(config.copy(dimLevel = it))
            }
            ChoiceRow("Text", listOf(12, 16), config.textSp, { "$it sp" }) {
                onConfig(config.copy(textSp = it))
            }
            ChoiceRow("Sentences", listOf(true, false), config.polish, { if (it) "Polski" else "English" }) {
                onConfig(config.copy(polish = it))
            }

            if (!canStart) {
                Text(
                    "Gloam needs its overlay permission before it can dim anything.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                Button(onClick = onStart, enabled = canStart) { Text("Start") }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
            Text(
                "$sessions session(s) saved in files/$RESULTS_FILE",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * One labelled row of chips.
 *
 * Kotlin note: `<T>` makes this one composable for ints and booleans alike, the way a generic React
 * component takes a type parameter; `label` turns each value into its text.
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
            for (choice in choices) {
                FilterChip(
                    selected = choice == selected,
                    onClick = { onSelect(choice) },
                    label = { Text(label(choice)) },
                )
            }
        }
    }
}

/** The white page every timed screen is drawn on. */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxSize()
                .background(PAGE)
                .safeDrawingPadding(),
    ) {
        content()
    }
}

@Composable
private fun PageMessage(
    headline: String,
    detail: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(headline, color = INK, fontSize = 16.sp, textAlign = TextAlign.Center)
        Text(detail, color = INK, fontSize = 32.sp, modifier = Modifier.padding(top = Spacing.tight))
    }
}

/**
 * A trial screen: the sentence, or a fixation mark where it will appear, above the two answers.
 *
 * The answers are drawn during the blank too, so nothing on screen moves when the sentence arrives
 * except the sentence itself.
 */
@Composable
private fun Trial(
    sentence: ReadingSentence?,
    config: TestConfig,
    taps: Channel<Tap>,
) {
    Page {
        Column(modifier = Modifier.fillMaxSize().padding(Spacing.base)) {
            Spacer(Modifier.weight(1f))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth().height(120.dp).padding(horizontal = Spacing.base),
            ) {
                Text(
                    text = sentence?.text ?: "+",
                    color = INK,
                    fontSize = config.textSp.sp,
                    lineHeight = (config.textSp * 1.4f).sp,
                    fontFamily = FontFamily.SansSerif,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.base)) {
                Answer(
                    if (config.polish) "Fałsz" else "False",
                    saysTrue = false,
                    taps = taps,
                    modifier = Modifier.weight(1f),
                )
                Answer(
                    if (config.polish) "Prawda" else "True",
                    saysTrue = true,
                    taps = taps,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * An answer target. **It fires on the press, not on release**: a click waits for the finger to lift,
 * which adds however long the finger rested to every time measured.
 */
@Composable
private fun Answer(
    label: String,
    saysTrue: Boolean,
    taps: Channel<Tap>,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .height(96.dp)
                .border(1.dp, INK, RoundedCornerShape(12.dp))
                .pointerInput(saysTrue) {
                    detectTapGestures(onPress = { taps.trySend(Tap(saysTrue, System.nanoTime())) })
                },
    ) {
        Text(label, color = INK, fontSize = 20.sp)
    }
}

@Composable
private fun Results(
    done: Stage.Done,
    onAgain: () -> Unit,
    onClose: () -> Unit,
) {
    val reference = done.summary.firstOrNull { it.colour == REFERENCE_COLOUR }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.snug),
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.base),
        ) {
            Text("Results", style = MaterialTheme.typography.headlineSmall)
            done.aborted?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                buildString {
                    appendLine("colour  correct  median   vs $REFERENCE_COLOUR (95%)")
                    for (row in done.summary) {
                        append(row.colour.toString().padStart(6))
                        append("  ${row.correct}/${row.trials}".padEnd(9))
                        append((row.medianMillis?.let { "$it ms" } ?: "-").padStart(8))
                        appendLine("   ${versus(row, reference)}")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "Plus means slower than colour $REFERENCE_COLOUR. One session is a first look; the decision " +
                    "is taken on files/$RESULTS_FILE pooled over several nights.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                Button(onClick = onAgain) { Text("Run again") }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

/** How much slower a colour read than the reference, as a percentage with a 95% interval. */
private fun versus(
    row: ColourSummary,
    reference: ColourSummary?,
): String {
    if (row.colour == REFERENCE_COLOUR) return "reference"
    val mean = row.logMean ?: return "-"
    val se = row.logSe ?: return "-"
    val refMean = reference?.logMean ?: return "-"
    val refSe = reference.logSe ?: return "-"
    val difference = mean - refMean
    val half = 1.96 * sqrt(se * se + refSe * refSe)

    fun percent(x: Double) = "%+.0f".format((exp(x) - 1) * 100)
    return "${percent(difference)}% (${percent(difference - half)} to ${percent(difference + half)})"
}
