package app.menosan.entries

import app.menosan.db.Db
import app.menosan.db.WasteEntries
import app.menosan.taxonomy.WasteCategory
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class ExposedEntryRepository(private val db: Db) : EntryRepository {
    override suspend fun listForWeek(userId: UUID, weekStart: LocalDate): List<WasteEntry> = db.tx {
        WasteEntries.selectAll()
            .where { (WasteEntries.userId eq userId) and (WasteEntries.weekStart eq weekStart) }
            .orderBy(WasteEntries.createdAt to SortOrder.DESC, WasteEntries.id to SortOrder.DESC)
            .map { it.toEntry() }
    }

    override suspend fun find(userId: UUID, id: UUID): WasteEntry? = db.tx {
        WasteEntries.selectAll()
            .where { (WasteEntries.id eq id) and (WasteEntries.userId eq userId) }
            .singleOrNull()?.toEntry()
    }

    override suspend fun upsert(userId: UUID, entry: WasteEntry): UpsertOutcome = db.tx {
        require(entry.userId == userId) { "entry.userId must match userId" }
        val now = entry.updatedAt.atOffset(ZoneOffset.UTC)
        // ON CONFLICT (id) DO NOTHING keeps concurrent retries of the same create idempotent (NFR8).
        val inserted = WasteEntries.insertIgnore {
            it[id] = entry.id
            it[WasteEntries.userId] = userId
            it[name] = entry.name
            it[category] = entry.category.name
            it[subcategoryCode] = entry.subcategoryCode
            it[quantity] = entry.quantity
            it[entrySource] = entry.source.name
            it[createdAt] = entry.createdAt.atOffset(ZoneOffset.UTC)
            it[weekStart] = entry.weekStart
            it[updatedAt] = now
            it[receivedAt] = now
        }.insertedCount > 0
        if (inserted) return@tx UpsertOutcome.CREATED

        val updated = WasteEntries.update({ (WasteEntries.id eq entry.id) and (WasteEntries.userId eq userId) }) {
            it[name] = entry.name
            it[category] = entry.category.name
            it[subcategoryCode] = entry.subcategoryCode
            it[quantity] = entry.quantity
            it[entrySource] = entry.source.name
            it[updatedAt] = now
        }
        if (updated > 0) UpsertOutcome.UPDATED else UpsertOutcome.OWNED_BY_OTHER_USER
    }

    override suspend fun delete(userId: UUID, id: UUID): Boolean = db.tx {
        WasteEntries.deleteWhere { (WasteEntries.id eq id) and (WasteEntries.userId eq userId) } > 0
    }

    override suspend fun userIdsWithEntries(weekStart: LocalDate): List<UUID> = db.tx {
        WasteEntries.select(WasteEntries.userId)
            .where { WasteEntries.weekStart eq weekStart }
            .withDistinct()
            .orderBy(WasteEntries.userId)
            .map { it[WasteEntries.userId] }
    }
}

internal fun ResultRow.toEntry() = WasteEntry(
    id = this[WasteEntries.id],
    userId = this[WasteEntries.userId],
    name = this[WasteEntries.name],
    category = WasteCategory.valueOf(this[WasteEntries.category]),
    subcategoryCode = this[WasteEntries.subcategoryCode],
    quantity = this[WasteEntries.quantity],
    source = EntrySource.valueOf(this[WasteEntries.entrySource]),
    createdAt = this[WasteEntries.createdAt].toInstant(),
    weekStart = this[WasteEntries.weekStart],
    updatedAt = this[WasteEntries.updatedAt].toInstant(),
)
