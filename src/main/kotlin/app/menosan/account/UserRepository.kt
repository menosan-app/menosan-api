package app.menosan.account

import app.menosan.db.Db
import app.menosan.db.Users
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class User(
    val id: UUID,
    val firebaseUid: String,
    val email: String,
    val displayName: String?,
    val createdAt: Instant,
) {
    override fun toString() = "User(id=$id)"
}

interface UserRepository {
    suspend fun findByFirebaseUid(firebaseUid: String): User?

    suspend fun createIfAbsent(firebaseUid: String, email: String, displayName: String?, now: Instant): Pair<User, Boolean>
}

class ExposedUserRepository(private val db: Db) : UserRepository {
    override suspend fun findByFirebaseUid(firebaseUid: String): User? = db.tx {
        Users.selectAll().where { Users.firebaseUid eq firebaseUid }.singleOrNull()?.toUser()
    }

    override suspend fun createIfAbsent(
        firebaseUid: String,
        email: String,
        displayName: String?,
        now: Instant,
    ): Pair<User, Boolean> = db.tx {
        val inserted = Users.insertIgnore {
            it[Users.firebaseUid] = firebaseUid
            it[Users.email] = email
            it[Users.displayName] = displayName
            it[consentedAt] = now.atOffset(ZoneOffset.UTC)
            it[createdAt] = now.atOffset(ZoneOffset.UTC)
        }.insertedCount
        val user = Users.selectAll().where { Users.firebaseUid eq firebaseUid }.single().toUser()
        user to (inserted > 0)
    }

    private fun ResultRow.toUser() = User(
        id = this[Users.id],
        firebaseUid = this[Users.firebaseUid],
        email = this[Users.email],
        displayName = this[Users.displayName],
        createdAt = this[Users.createdAt].toInstant(),
    )
}
