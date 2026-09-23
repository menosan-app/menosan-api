package app.menosan.interventions

import app.menosan.db.Db
import app.menosan.db.Interventions
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** Declaration order is the rule-based preference order (§6.2 step 2). */
enum class CostLevel { FREE, SAVES_MONEY, SMALL_ONE_TIME_COST }

enum class Effort { LOW, MEDIUM }

enum class InterventionType { PREVENT, REDUCE, REUSE }

/** One curated library item (`interventions` table, seeded by V3). */
data class Intervention(
    val id: UUID,
    val code: String,
    val subcategoryCode: String,
    val type: InterventionType,
    val title: String,
    val description: String,
    val howTo: List<String>,
    val costLevel: CostLevel,
    val effort: Effort,
    val active: Boolean = true,
)

/** Read access to the curated library. It's public content, so these reads aren't user-scoped. */
interface InterventionRepository {
    /** Active items for a subcategory, in no particular order. */
    suspend fun activeFor(subcategoryCode: String): List<Intervention>
}

class ExposedInterventionRepository(private val db: Db) : InterventionRepository {
    override suspend fun activeFor(subcategoryCode: String): List<Intervention> = db.tx {
        Interventions.selectAll()
            .where { (Interventions.subcategoryCode eq subcategoryCode) and (Interventions.active eq true) }
            .map { it.toIntervention() }
    }
}

internal fun ResultRow.toIntervention() = Intervention(
    id = this[Interventions.id],
    code = this[Interventions.code],
    subcategoryCode = this[Interventions.subcategoryCode],
    type = InterventionType.valueOf(this[Interventions.type]),
    title = this[Interventions.title],
    description = this[Interventions.description],
    howTo = this[Interventions.howTo],
    costLevel = CostLevel.valueOf(this[Interventions.costLevel]),
    effort = Effort.valueOf(this[Interventions.effort]),
    active = this[Interventions.active],
)
