package org.octavius.database.mapping.dynamic

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octavius.data.builder.toSingle
import org.octavius.data.getOrThrow
import org.octavius.data.toDataObject
import org.octavius.database.AbstractIntegrationTest
import org.octavius.domain.test.dynamic.DynamicProfile
import org.octavius.domain.test.dynamic.UserStats
import org.octavius.domain.test.dynamic.UserWithDynamicProfile

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamicDtoMappingTest: AbstractIntegrationTest() {

    override val packagesToScan: List<String> = listOf("org.octavius.domain.test.dynamic")

    override val sqlToExecuteOnSetup: String = """
        CREATE TABLE dynamic_users (
           user_id SERIAL PRIMARY KEY,
           username TEXT NOT NULL
        );

        CREATE TABLE dynamic_profiles (
           profile_id SERIAL PRIMARY KEY,
           user_id INT REFERENCES dynamic_users(user_id),
           role TEXT NOT NULL,
           permissions TEXT[]
        );

        INSERT INTO dynamic_users (username) VALUES ('dynamic_user_1');
        INSERT INTO dynamic_profiles (user_id, role, permissions) VALUES (1, 'administrator', ARRAY['read', 'write', 'delete']);
    """.trimIndent()


    @Test
    fun `should map a dynamically created nested object using dynamic_dto and jsonb_build_object`() {
        // Arrange: Definiujemy zapytanie, które w locie tworzy zagnieżdżoną strukturę
        val sql = """
            SELECT
                u.user_id,
                u.username,
                (
                    SELECT dynamic_dto(
                        'profile_dto',
                        jsonb_build_object(
                            'role', p.role,
                            'permissions', p.permissions,
                            'lastLogin', '2024-01-01T12:00:00'
                        )
                    )
                    FROM dynamic_profiles p WHERE p.user_id = u.user_id
                ) AS profile
            FROM dynamic_users u
            WHERE u.user_id = @userId
        """.trimIndent()

        // Act: Wykonujemy zapytanie za pomocą naszego DAL-a
        // Używamy toSingle() aby dostać surową mapę i móc ją zbadać
        val result = dataAccess.rawQuery(sql)
            .toSingle("userId" to 1)
            .getOrThrow() // Używamy getOrThrow dla uproszczenia w teście

        assertThat(result).isNotNull
        assertThat(result!!["profile"]).isInstanceOf(DynamicProfile::class.java)
        // Assert: Sprawdzamy, czy konwersja zadziałała
        val userWithProfile = result.toDataObject<UserWithDynamicProfile>()

        assertThat(userWithProfile.userId).isEqualTo(1)
        assertThat(userWithProfile.username).isEqualTo("dynamic_user_1")
        assertThat(userWithProfile.profile).isNotNull
        assertThat(userWithProfile.profile.role).isEqualTo("administrator")
        assertThat(userWithProfile.profile.permissions).containsExactly("read", "write", "delete")
        assertThat(userWithProfile.profile.lastLogin).isEqualTo("2024-01-01T12:00:00")
    }

    @Test
    fun `should correctly map a different dynamic DTO to prove polymorphism`() {
        // Arrange: Zapytanie, które zwraca zupełnie inną dynamiczną strukturę
        val sql = """
            SELECT dynamic_dto(
                'user_stats_dto',
                jsonb_build_object(
                    'postCount', 150,
                    'commentCount', 3000
                )
            ) AS stats
        """.trimIndent()

        // Act
        val result = dataAccess.rawQuery(sql)
            .toSingle()
            .getOrThrow()

        // Assert
        val stats = result!!["stats"] as UserStats
        assertThat(stats.postCount).isEqualTo(150)
        assertThat(stats.commentCount).isEqualTo(3000)
    }
}
