package app.menosan.entries

import app.menosan.taxonomy.WasteCategory
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class EntrySource { MANUAL, PHOTO }

class WasteEntry(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val category: WasteCategory,
    val subcategoryCode: String,
    val quantity: Int,
    val source: EntrySource,
    val createdAt: Instant,
    val weekStart: LocalDate,
    val updatedAt: Instant,
) {
    override fun toString() = "WasteEntry(id=$id, subcategory=$subcategoryCode, quantity=$quantity, weekStart=$weekStart)"
}

enum class UpsertOutcome { CREATED, UPDATED, OWNED_BY_OTHER_USER }

interface EntryRepository {
    suspend fun listForWeek(userId: UUID, weekStart: LocalDate): List<WasteEntry>

    suspend fun find(userId: UUID, id: UUID): WasteEntry?

    suspend fun upsert(userId: UUID, entry: WasteEntry): UpsertOutcome

    suspend fun delete(userId: UUID, id: UUID): Boolean

    suspend fun userIdsWithEntries(weekStart: LocalDate): List<UUID>
}
