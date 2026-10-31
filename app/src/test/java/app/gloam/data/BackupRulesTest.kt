package app.gloam.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The lent location stays out of backup, checked against the file name DataStore will really use.
 *
 * **The failure this prevents is silent.** Renaming [LENT_LOCATION_STORE] without the rules XML leaves
 * an exclude that matches no file, the platform backs up the new one, and the user's location lands in
 * their Google backup with nothing on screen, in logcat or in the build to say so.
 *
 * DataStore's preferences file is `filesDir/datastore/<name>.preferences_pb`, which is the `file`
 * domain's `datastore/<name>.preferences_pb`. Read the way `AppLanguageTest` reads `locales_config.xml`:
 * a JVM test from the module directory, since `src/main/res` is a registered test input.
 */
class BackupRulesTest {
    @Test
    fun `cloud backup and device transfer both exclude the lent location's file`() {
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        val expected = "datastore/$LENT_LOCATION_STORE.preferences_pb"
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = rules.substringAfter("<$section>").substringBefore("</$section>")
            val excluded = Regex("""<exclude domain="file" path="([^"]+)"""").findAll(body).map { it.groupValues[1] }
            assertEquals("$section excludes", listOf(expected), excluded.toList())
        }
    }

    @Test
    fun `the pre-Android-12 rules exclude the same file`() {
        val rules = File("src/main/res/xml/backup_rules.xml").readText()
        val excluded = Regex("""<exclude domain="file" path="([^"]+)"""").findAll(rules).map { it.groupValues[1] }
        assertEquals(listOf("datastore/$LENT_LOCATION_STORE.preferences_pb"), excluded.toList())
    }
}
