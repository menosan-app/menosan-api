package app.menosan.entries

import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.common.WeekCalc
import app.menosan.plugins.ApiJson
import app.menosan.reports.ReportService
import app.menosan.taxonomy.Taxonomy
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

private val log = LoggerFactory.getLogger("app.menosan.entries")

/** Most items one `POST /v1/entries/sync` may carry (upserts + deletes). */
const val SYNC_MAX_ITEMS = 500

/**
 * Entry rules (plan §4, §9 BE-1): validation, timestamp bounds, week editability, ownership,
 * idempotent upserts, and the late-sync trigger (§5.6). Every call is scoped by `userId`.
 */
class EntryService(
    private val repo: EntryRepository,
    private val taxonomy: Taxonomy,
    private val clock: Clock,
    private val reports: ReportService,
) {
    class PutResult(val entry: WasteEntry, val created: Boolean)

    suspend fun list(userId: UUID, weekStart: LocalDate): List<WasteEntry> = repo.listForWeek(userId, weekStart)

    /** `PUT /v1/entries/{id}`: create or update. */
    suspend fun put(userId: UUID, id: UUID, request: EntryPutRequest): PutResult {
        val (result, lateWeek) = save(userId, id, validateEntry(request, taxonomy))
        lateWeek?.let { notifyLateEntries(userId, setOf(it)) }
        return result
    }

    /** `DELETE /v1/entries/{id}`: a missing id (or another user's id) is a no-op. */
    suspend fun delete(userId: UUID, id: UUID) {
        val existing = repo.find(userId, id) ?: return
        requireEditable(existing)
        repo.delete(userId, id)
    }

    /**
     * `POST /v1/entries/sync`: upserts first, then deletes, each item on its own. One bad item never fails
     * the batch. Report regeneration for late creates runs once per affected week, after all items.
     */
    suspend fun sync(userId: UUID, upserts: List<JsonElement>, deletes: List<JsonElement>): List<SyncResult> {
        if (upserts.size + deletes.size > SYNC_MAX_ITEMS) {
            throw ApiException(
                ErrorCode.VALIDATION_FAILED,
                "Too many items in one sync. Send at most $SYNC_MAX_ITEMS.",
                JsonObject(mapOf("field" to JsonPrimitive("upserts"), "max" to JsonPrimitive(SYNC_MAX_ITEMS))),
            )
        }
        val lateWeeks = linkedSetOf<LocalDate>()
        val results = ArrayList<SyncResult>(upserts.size + deletes.size)

        for (item in upserts) {
            val rawId = (item as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            results += syncItem(userId, SyncOp.UPSERT, rawId) { id ->
                val request = ApiJson.decodeFromJsonElement<EntryPutRequest>(item)
                val (result, lateWeek) = save(userId, id, validateEntry(request, taxonomy))
                lateWeek?.let { lateWeeks += it }
                result.entry
            }
        }
        for (item in deletes) {
            val rawId = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            results += syncItem(userId, SyncOp.DELETE, rawId) { id ->
                delete(userId, id)
                null
            }
        }

        notifyLateEntries(userId, lateWeeks)
        return results
    }

    private suspend fun syncItem(
        userId: UUID,
        op: SyncOp,
        rawId: String?,
        block: suspend (UUID) -> WasteEntry?,
    ): SyncResult {
        val id = parseUuidOrNull(rawId)
            ?: return SyncResult(rawId, op, SyncStatus.INVALID, null, "The entry id must be a UUID.")
        return try {
            SyncResult(id.toString(), op, SyncStatus.OK, block(id)?.toDto(clock), null)
        } catch (e: ApiException) {
            val status = when (e.code) {
                ErrorCode.WEEK_CLOSED -> SyncStatus.WEEK_CLOSED
                ErrorCode.INVALID_TIMESTAMP -> SyncStatus.INVALID_TIMESTAMP
                ErrorCode.CONFLICT -> SyncStatus.CONFLICT
                ErrorCode.VALIDATION_FAILED -> SyncStatus.INVALID
                else -> SyncStatus.ERROR
            }
            // On WEEK_CLOSED, send the server copy so the app can revert its local change (plan §10 AN-1).
            val current = if (status == SyncStatus.WEEK_CLOSED) repo.find(userId, id)?.toDto(clock) else null
            SyncResult(id.toString(), op, status, current, e.message)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SerializationException) {
            SyncResult(id.toString(), op, SyncStatus.INVALID, null, "The entry has missing or invalid fields.")
        } catch (_: IllegalArgumentException) {
            SyncResult(id.toString(), op, SyncStatus.INVALID, null, "The entry has missing or invalid fields.")
        } catch (e: Exception) {
            log.error("Sync item failed: {} (op={})", e.javaClass.name, op)
            SyncResult(id.toString(), op, SyncStatus.ERROR, null, "Something went wrong. Please try again.")
        }
    }

    /** Returns the saved entry and, for a create in a closed week, that week (late sync, §5.6). */
    private suspend fun save(userId: UUID, id: UUID, input: EntryInput): Pair<PutResult, LocalDate?> {
        val now = clock.instant()
        val existing = repo.find(userId, id)
        if (existing != null) return update(userId, existing, input) to null

        checkCreateTimestamp(input.createdAt, now)
        val weekStart = WeekCalc.weekStart(input.createdAt)
        val entry = WasteEntry(
            id = id, userId = userId, name = input.name, category = input.category,
            subcategoryCode = input.subcategoryCode, quantity = input.quantity, source = input.source,
            createdAt = input.createdAt, weekStart = weekStart, updatedAt = now,
        )
        return when (repo.upsert(userId, entry)) {
            UpsertOutcome.CREATED -> PutResult(entry, created = true) to weekStart.takeIf { WeekCalc.isClosed(it, clock) }
            // A concurrent request of the same user created this id first, so this call updated it (last write wins).
            UpsertOutcome.UPDATED -> PutResult(repo.find(userId, id) ?: entry, created = false) to null
            UpsertOutcome.OWNED_BY_OTHER_USER -> throw conflict()
        }
    }

    private suspend fun update(userId: UUID, existing: WasteEntry, input: EntryInput): PutResult {
        if (existing.createdAt.truncatedTo(ChronoUnit.MILLIS) != input.createdAt) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "createdAt can't be changed.", fieldDetails("createdAt"))
        }
        // Replaying an unchanged entry succeeds in any week, so a retried create after the week closed isn't an error.
        if (existing.hasSameContent(input)) return PutResult(existing, created = false)
        requireEditable(existing)

        val updated = WasteEntry(
            id = existing.id, userId = userId, name = input.name, category = input.category,
            subcategoryCode = input.subcategoryCode, quantity = input.quantity, source = input.source,
            createdAt = existing.createdAt, weekStart = existing.weekStart, updatedAt = clock.instant(),
        )
        return when (repo.upsert(userId, updated)) {
            UpsertOutcome.UPDATED -> PutResult(updated, created = false)
            UpsertOutcome.CREATED -> PutResult(updated, created = true) // deleted concurrently, written back
            UpsertOutcome.OWNED_BY_OTHER_USER -> throw conflict()
        }
    }

    private fun requireEditable(entry: WasteEntry) {
        if (!WeekCalc.isCurrentWeek(entry.weekStart, clock)) {
            throw ApiException(
                ErrorCode.WEEK_CLOSED,
                "This entry is from a past week and can't be changed.",
                JsonObject(mapOf("weekStart" to JsonPrimitive(entry.weekStart.toString()))),
            )
        }
    }

    private suspend fun notifyLateEntries(userId: UUID, weeks: Set<LocalDate>) {
        for (week in weeks.sorted()) {
            try {
                reports.onLateEntry(userId, week)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The entry is already saved; don't fail the request over the report refresh.
                log.error("onLateEntry failed for weekStart={}: {}", week, e.javaClass.name)
            }
        }
    }

    private fun conflict() = ApiException(ErrorCode.CONFLICT, "This entry id is already in use.", fieldDetails("id"))
}

private fun WasteEntry.hasSameContent(input: EntryInput) =
    name == input.name && subcategoryCode == input.subcategoryCode && quantity == input.quantity && source == input.source
