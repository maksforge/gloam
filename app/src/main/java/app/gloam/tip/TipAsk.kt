package app.gloam.tip

import java.util.concurrent.TimeUnit

/** How many times the shade has to have been started before the tip is mentioned. */
const val TIP_ASK_AFTER_STARTS = 10

/** How long Gloam has to have been installed before the tip is mentioned. */
val TIP_ASK_AFTER_MILLIS = TimeUnit.DAYS.toMillis(7)

/**
 * Whether the home screen should mention the tip — **once per install, and only to somebody who
 * already uses the app**: a week installed *and* ten starts. Either alone is a weak signal; an app
 * installed a week ago and opened once is not one somebody would thank anyone for.
 *
 * [installedAtMillis] is the package's `firstInstallTime`, which a restore onto a new phone does not
 * carry, so a moved install waits its own week — but [asked] *is* backed up, so somebody who has
 * already answered is never asked again on their next phone.
 */
fun tipAskDue(
    asked: Boolean,
    shadeStarts: Int,
    installedAtMillis: Long,
    nowMillis: Long,
): Boolean =
    !asked &&
        shadeStarts >= TIP_ASK_AFTER_STARTS &&
        nowMillis - installedAtMillis >= TIP_ASK_AFTER_MILLIS
