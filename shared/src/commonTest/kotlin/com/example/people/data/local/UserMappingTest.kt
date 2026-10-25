package com.example.people.data.local

import com.example.people.data.remote.UserDto
import com.example.people.domain.model.User
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserMappingTest {
    @Test
    fun mapsV2FieldsWithoutServerTimestamp() {
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString<UserDto>(
            """{"id":42,"name":"Ada Lovelace","email":"ada@example.com","gender":"female","status":"active"}"""
        )
        val entity = dto.toEntity(seenAt = 1_000, position = 3)
        assertEquals(3, entity.snapshotPosition)
        assertNull(entity.addedAt)
        assertEquals(User(42, "Ada Lovelace", "ada@example.com", 1_000), entity.toDomain())
    }

    @Test
    fun localCreationMetadataSurvivesDomainMapping() {
        val entity = UserEntity(42, "Ada", "ada@example.com", firstSeenAt = 100, addedAt = 90)
        assertEquals(User(42, "Ada", "ada@example.com", 100, 90), entity.toDomain())
    }
}
