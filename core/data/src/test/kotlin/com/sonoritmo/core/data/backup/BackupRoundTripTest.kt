package com.sonoritmo.core.data.backup

import com.google.common.truth.Truth.assertThat
import com.sonoritmo.core.data.TestData
import com.sonoritmo.core.domain.model.ProfileId
import com.sonoritmo.core.domain.model.ScheduleId
import com.sonoritmo.core.domain.model.SoundProfile
import org.junit.Test

/**
 * The exit criterion for this phase, made executable: export and import with no loss.
 *
 * The comparison is field by field on the *domain* objects rather than on the JSON, because
 * that is the promise the user cares about — that their configuration comes back — and it
 * is insensitive to formatting changes that do not lose anything.
 */
class BackupRoundTripTest {

    private fun exportAll(): String = BackupWriter.serialize(
        profiles = TestData.allProfiles(),
        schedules = TestData.allSchedules(),
        settings = TestData.settings(),
        exportedAt = TestData.EXPORTED_AT,
    ).json

    private fun readable(json: String): BackupPreview {
        val inspection = BackupReader.inspect(json)
        assertThat(inspection).isInstanceOf(BackupInspection.Readable::class.java)
        return (inspection as BackupInspection.Readable).preview
    }

    /** The rowid is local and must never travel; the imported profile is always unsaved. */
    private fun SoundProfile.asExported(): SoundProfile =
        copy(id = ProfileId.UNSAVED, zenRuleId = null)

    @Test
    fun `every profile field survives the round trip`() {
        val preview = readable(exportAll())

        val expected = TestData.allProfiles().map { it.asExported() }
        assertThat(preview.profiles).containsExactlyElementsIn(expected)
    }

    @Test
    fun `every schedule field survives the round trip`() {
        val preview = readable(exportAll())

        val expected = TestData.allSchedules()
            .groupBy { it.profileUuid }
            .mapValues { (_, list) ->
                list.map { it.copy(id = ScheduleId.UNSAVED) }.sortedBy { it.uuid }
            }

        assertThat(preview.schedulesByProfileUuid.keys).isEqualTo(expected.keys)
        expected.forEach { (uuid, schedules) ->
            assertThat(preview.schedulesByProfileUuid[uuid]).containsExactlyElementsIn(schedules)
        }
    }

    @Test
    fun `a window crossing midnight comes back as the same duration, not as an end time`() {
        val preview = readable(exportAll())
        val night = preview.schedulesByProfileUuid.getValue(TestData.NIGHT_UUID)
        val crossing = night.single { it.uuid == TestData.crossingMidnight().uuid }

        assertThat(crossing.startMinuteOfDay).isEqualTo(23 * 60)
        assertThat(crossing.durationMinutes).isEqualTo(8 * 60)
        assertThat(crossing.crossesMidnight).isTrue()
        assertThat(crossing.endMinuteOfDay).isEqualTo(7 * 60)
    }

    @Test
    fun `a full day window is expressible and survives`() {
        val preview = readable(exportAll())
        val night = preview.schedulesByProfileUuid.getValue(TestData.NIGHT_UUID)
        val fullDay = night.single { it.uuid == TestData.fullDay().uuid }

        assertThat(fullDay.durationMinutes).isEqualTo(1440)
        assertThat(fullDay.enabled).isFalse()
    }

    @Test
    fun `null and zero volumes stay distinguishable`() {
        val preview = readable(exportAll())
        val night = preview.profiles.single { it.uuid == TestData.NIGHT_UUID }

        // "do not touch the ring stream" and "set the notification stream to zero" are
        // different instructions and must not collapse into each other.
        assertThat(night.volumes.ring).isNull()
        assertThat(night.volumes.notification).isEqualTo(0)
        assertThat(night.volumes.voiceCall).isNull()
    }

    @Test
    fun `exporting twice produces identical bytes`() {
        // Stability is what makes the round trip testable at all: if the writer's output
        // depended on map or query order, no assertion about the format could hold.
        assertThat(exportAll()).isEqualTo(exportAll())
    }

    @Test
    fun `export import export is byte identical`() {
        val first = exportAll()
        val preview = readable(first)

        val second = BackupWriter.serialize(
            profiles = preview.profiles,
            schedules = preview.schedulesByProfileUuid.values.flatten(),
            settings = TestData.settings(),
            exportedAt = TestData.EXPORTED_AT,
        ).json

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `the checksum written by the exporter validates on import`() {
        val preview = readable(exportAll())

        assertThat(preview.checksumValid).isTrue()
        assertThat(preview.countsMismatch).isFalse()
    }

    @Test
    fun `settings round trip`() {
        val preview = readable(exportAll())
        val settings = requireNotNull(preview.settings)

        assertThat(settings.themeMode).isEqualTo("DARK")
        assertThat(settings.dynamicColor).isFalse()
        assertThat(settings.languageTag).isEqualTo("es-ES")
        assertThat(settings.maxReliabilityMode).isTrue()
        assertThat(settings.defaultProfileUuid).isEqualTo(TestData.WORK_UUID)
    }

    @Test
    fun `a clean file produces no corrections and no rejections`() {
        val preview = readable(exportAll())

        assertThat(preview.corrections).isEmpty()
        assertThat(preview.rejections).isEmpty()
    }
}
