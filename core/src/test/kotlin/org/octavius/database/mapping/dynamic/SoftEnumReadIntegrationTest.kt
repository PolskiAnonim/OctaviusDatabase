package org.octavius.database.mapping.dynamic

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.getOrThrow
import org.octavius.database.AbstractIntegrationTest
import org.octavius.domain.test.softEnum.FeatureFlag


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SoftEnumReadIntegrationTest: AbstractIntegrationTest() {

    override val packagesToScan: List<String> = listOf("org.octavius.domain.test.softEnum")

    @Test
    fun `should read soft_enum from database via SELECT function`() {
        // Arrange
        val sql = "SELECT to_dynamic_dto('feature_flag', 'dark_theme') AS flag"

        // Act
        val result = dataAccess.rawQuery(sql)
            .toSingle()
            .getOrThrow()

        // Assert
        assertThat(result).isNotNull
        val flagValue = result!!["flag"]

        assertThat(flagValue).isInstanceOf(FeatureFlag::class.java)
        assertThat(flagValue).isEqualTo(FeatureFlag.DarkTheme)
    }

    @Test
    fun `should read soft_enum list (array) from database`() {
        // Arrange
        // Symulujemy tablicę flag: array[soft_enum(...), soft_enum(...)]
        val sql = """
            SELECT ARRAY[
                to_dynamic_dto('feature_flag', 'beta_access'),
                to_dynamic_dto('feature_flag', 'legacy_support')
            ] AS flags
        """.trimIndent()

        // Act
        val result = dataAccess.rawQuery(sql)
            .toSingle()
            .getOrThrow()

        // Assert
        val flags = result!!["flags"] as List<*>
        assertThat(flags).hasSize(2)
        assertThat(flags[0]).isEqualTo(FeatureFlag.BetaAccess)
        assertThat(flags[1]).isEqualTo(FeatureFlag.LegacySupport)
    }
}
