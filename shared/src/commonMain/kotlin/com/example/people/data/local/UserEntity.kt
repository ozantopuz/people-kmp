package com.example.people.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.people.data.remote.UserDto
import com.example.people.domain.model.User

@Entity(tableName = "users")
internal data class UserEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val email: String,
    val firstSeenAt: Long,
    val addedAt: Long? = null,
    val snapshotPosition: Int = 0,
)

internal fun UserDto.toEntity(seenAt: Long, position: Int = 0): UserEntity = UserEntity(
    id = id, name = name, email = email, firstSeenAt = seenAt, snapshotPosition = position,
)

internal fun UserEntity.toDomain(): User = User(id, name, email, firstSeenAt, addedAt)

/** Runs inside the DAO transaction, using the current rows, not a pre-network cache copy. */
internal fun reconcileSnapshot(
    existing: List<UserEntity>,
    snapshot: List<UserEntity>,
    verifiedCreated: List<UserEntity>,
    missingCreatedIds: List<Long>,
): List<UserEntity> {
    val previous = existing.associateBy { it.id }
    val snapshotIds = snapshot.mapTo(mutableSetOf()) { it.id }
    val verified = verifiedCreated.associateBy { it.id }
    val missing = missingCreatedIds.toSet()
    val merged = linkedMapOf<Long, UserEntity>()
    existing.filter { it.addedAt != null }.forEach { local ->
        // A record visible in the snapshot exists even if a separate check said 404.
        if (local.id !in missing || local.id in snapshotIds) {
            val remote = verified[local.id]
            merged[local.id] = local.copy(
                name = remote?.name ?: local.name,
                email = remote?.email ?: local.email,
            )
        }
    }
    snapshot.distinctBy { it.id }.forEach { remote ->
        val old = previous[remote.id]
        // Keep first-seen time and local-create provenance when IDs overlap.
        merged[remote.id] = remote.copy(
            firstSeenAt = old?.firstSeenAt ?: remote.firstSeenAt,
            addedAt = old?.addedAt,
        )
    }
    return merged.values.toList()
}
