package app.menosan.entries

import app.menosan.common.notImplemented
import app.menosan.taxonomy.WasteCategory
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class EntrySource { MANUAL, PHOTO }

/** One logged waste entry (plan §7 `waste_entries`). `toString` hides the free-text name. */
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

/**
 * Persistence for waste entries. **Owned by BE-1.** Every method is scoped by `userId` (NFR2, NFR6),
 * except [userIdsWithEntries], which the weekly job uses. Business rules (validation, timestamps,
 * week editability) live in [EntryService]; this layer only stores rows.
 */
interface EntryRepository {
    /** Entries of [userId] in [weekStart], newest first (`createdAt` desc, then `id` desc). */
    suspend fun listForWeek(userId: UUID, weekStart: LocalDate): List<WasteEntry>

    suspend fun find(userId: UUID, id: UUID): WasteEntry?

    /**
     * Inserts [entry], or updates the row with the same id if it belongs to [userId].
     * An update changes only `name`, `category`, `subcategoryCode`, `quantity`, `source`, and `updatedAt`:
     * `createdAt` and `weekStart` never change. On insert, `received_at` is set to `entry.updatedAt`.
     * An id that belongs to another user is left untouched and returns [UpsertOutcome.OWNED_BY_OTHER_USER].
     */
    suspend fun upsert(userId: UUID, entry: WasteEntry): UpsertOutcome

    /** Returns true if a row was deleted. Deleting a missing id is not an error (idempotent). */
    suspend fun delete(userId: UUID, id: UUID): Boolean

    /** Users who logged anything in [weekStart]; used by the weekly report job (BE-3). */
    suspend fun userIdsWithEntries(weekStart: LocalDate): List<UUID>
}

object StubEntryRepository : EntryRepository {
    override suspend fun listForWeek(userId: UUID, weekStart: LocalDate) = notImplemented("Entries")
    override suspend fun find(userId: UUID, id: UUID) = notImplemented("Entries")
    override suspend fun upsert(userId: UUID, entry: WasteEntry) = notImplemented("Entries")
    override suspend fun delete(userId: UUID, id: UUID) = notImplemented("Entries")
    override suspend fun userIdsWithEntries(weekStart: LocalDate) = notImplemented("Entries")
}
