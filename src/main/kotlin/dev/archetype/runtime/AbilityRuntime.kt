package dev.archetype.runtime

import dev.archetype.definitions.*
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Minecraft objects stay in the adapter. Native calls return actual health deltas. */
interface WorldOps {
    fun validTarget(actor: UUID, target: UUID): Boolean
    fun heal(target: UUID, amount: Double): Double
    fun damage(actor: UUID, target: UUID, amount: Double, damageType: String): Double
    fun availableTarget(actor: UUID, target: UUID): Boolean = validTarget(actor, target)
    fun validTarget(actor: UUID, target: UUID, range: Double): Boolean = validTarget(actor, target)
    fun position(entity: UUID): Position? = null
    fun direction(entity: UUID): Vec = Vec(0.0, 0.0, 1.0)
    fun aim(actor: UUID, range: Double): UUID? = null
    fun ground(actor: UUID, range: Double): Position? = null
    fun loaded(position: Position): Boolean = false
    fun candidates(actor: UUID, origin: Position, radius: Double, limit: Int): List<EntityView> = emptyList()
    fun view(actor: UUID, target: UUID): EntityView? = null
    /** Returns server-verified players allied to actor near an authoritative event position. */
    fun nearbyAlliedPlayers(actor: UUID, origin: Position, radius: Double, limit: Int): List<UUID> = emptyList()
    fun lineOfSight(origin: Position, target: UUID): Boolean = false
    /** Replaces only Archetype's transient additive movement speed bonus; zero removes it. */
    fun movementSpeedBonus(target: UUID, amount: Double) {}
    /** A server-side sample in [0, 1); tests may provide a fixed sequence. */
    fun roll(actor: UUID): Double = ThreadLocalRandom.current().nextDouble()
    fun projectileOrigin(actor: UUID): Position? = position(actor)
    fun projectileStep(actor: UUID, from: Position, to: Position, entities: ProjectileEntities, excluded: Set<UUID>): ProjectileContact = ProjectileContact.Unavailable
    fun projectileVisible(position: Position) {}
}

sealed interface ProjectileContact {
    data class Miss(val position: Position) : ProjectileContact
    data class Entity(val target: UUID, val position: Position) : ProjectileContact
    data class Block(val position: Position, val normal: Vec = Vec(0.0, 1.0, 0.0)) : ProjectileContact
    data object Unavailable : ProjectileContact
}

data class PlayerRecord(
    val ownedClasses: MutableSet<String> = linkedSetOf(),
    val activeClasses: MutableSet<String> = linkedSetOf(),
    val resources: MutableMap<String, Double> = linkedMapOf(),
    val cooldowns: MutableMap<String, Int> = linkedMapOf(),
    val regenerationTimers: MutableMap<String, Int> = linkedMapOf(),
    val charges: MutableMap<String, ChargeState> = linkedMapOf(),
    val persistentStates: MutableMap<String, StateValue> = linkedMapOf(),
    val progression: MutableMap<String, TrackProgress> = linkedMapOf(),
)

data class TrackProgress(var earnedXp: Long = 0L,
    val awarded: MutableSet<String> = linkedSetOf(), val points: MutableMap<String, Int> = linkedMapOf(),
    val purchases: MutableMap<String, PointPurchase> = linkedMapOf())
data class PointPayment(val budget: String, val amount: Int)
data class PointPurchase(val payments: MutableList<PointPayment> = mutableListOf())
data class ProgressView(val track: String, val classId: String?, val earnedXp: Long, val level: Int,
    val points: Map<String, Int>)
data class TalentView(val tree: String, val node: String, val selection: UnlockSelection, val rank: Int,
    val maximum: Int, val cost: Int, val points: Int, val requiredLevel: Int, val reason: String)
sealed interface TalentResult {
    data object Applied : TalentResult
    data class Rejected(val reason: String) : TalentResult
}

data class ChargeState(var capacity: Int, var available: Int, var mode: RechargeMode, val timers: MutableList<Int> = mutableListOf())

sealed interface CastResult {
    data object Applied : CastResult
    data class Rejected(val reason: String) : CastResult
    data class Interrupted(val reason: String) : CastResult
}

data class StatusView(val status: String, val owner: UUID, val sourceClass: String, val grant: String, val application: String, val stacks: Int, val remainingTicks: Int?, val membership: Boolean)
data class TimerView(val name: String, val target: UUID, val sourceClass: String, val grant: String, val remainingTicks: Int)
data class BarrierView(val target: UUID, val owner: UUID, val remaining: Double, val remainingTicks: Int, val priority: Int)

/** All mutation runs on the server tick thread. ASVS 2.3.1, 2.3.4, 15.4.1. */
class AbilityRuntime(private val world: WorldOps, private val catalog: MechanicCatalog = BuiltinEffects.catalog) {
    var definitions = DefinitionSet(emptyMap(), emptyMap(), emptyMap(), emptyMap(), "")
        private set
    var generation: Long = 0
        private set
    private val players = linkedMapOf<UUID, PlayerRecord>()
    private val transientStates = mutableMapOf<UUID, MutableMap<String, StateValue>>()
    private val selection = TargetSelection(world)
    private var estimates = ExecutionEstimates(definitions, catalog)
    private data class Budget(var remaining: Int = 1024)
    private class Lifetime(val parent: Lifetime? = null) {
        var cancelled = false
        val active: Boolean get() = !cancelled && parent?.active != false
    }
    private data class Scope(
        val id: UUID, val owner: UUID, val classId: String, val grant: String, val ability: AbilityDef,
        val target: UUID?, val ground: Position?, val budget: Budget, val lifetime: Lifetime,
        val selected: Boolean = false, val dependencies: MutableMap<String, AreaDef> = linkedMapOf(),
        val targetGuard: TargetGuard? = null,
        val statusDependencies: MutableMap<String, StatusDef> = linkedMapOf(),
        val stateDependencies: MutableMap<String, StateDef> = linkedMapOf(),
        val projectileDependencies: MutableMap<String, ProjectileDef> = linkedMapOf(),
        val originDefinition: String = ability.id, val sourceDimension: String? = null,
        val controllerId: UUID? = null,
        val localState: MutableMap<String, StateValue> = linkedMapOf(),
        val statusState: MutableMap<String, StateValue>? = null,
    )
    private data class TargetGuard(val frame: Frame, val selector: Selector)
    private data class Scheduled(val scope: Scope, val due: Long, val effects: List<Effect>, val bindings: Map<String, Double>, val repeatsLeft: Int = 1, val every: Int = 0)
    private data class ChainTask(val scope: Scope, val due: Long, val effect: Effect.Chain, val origin: Position, val last: UUID, val visited: Set<UUID>, val hits: Int, val bindings: Map<String, Double>)
    private data class Area(
        val id: UUID, val scope: Scope, val definition: AreaDef, val lifetime: Lifetime, val fixed: Frame?, val attached: UUID?,
        val expires: Long, var nextSample: Long, var nextPulse: Long,
        val members: MutableMap<UUID, Lifetime> = linkedMapOf(),
    )
    private data class StatusKey(val owner: UUID, val classId: String, val grant: String, val application: String, val target: UUID, val membership: Lifetime?)
    private data class Contribution(
        val id: UUID, val key: StatusKey, val scope: Scope, val definition: StatusDef, val lifetime: Lifetime,
        val stackExpiries: MutableList<Long>, var nextPulse: Long,
        val localState: MutableMap<String, StateValue> = linkedMapOf(),
    )
    private data class TimerKey(val owner: UUID, val classId: String, val grant: String, val source: String, val name: String, val target: UUID)
    private data class Timer(val key: TimerKey, val scope: Scope, val lifetime: Lifetime, val due: Long, val expired: List<Effect>, val bindings: Map<String, Double>)
    private data class Barrier(
        val target: UUID, val scope: Scope, val lifetime: Lifetime, var remaining: Double,
        val expires: Long, val priority: Int, val damageType: String?, val depleted: List<Effect>, val order: Long,
    )
    private data class Projectile(
        val scope: Scope, val definition: ProjectileDef, val lifetime: Lifetime,
        var position: Position, var velocity: Vec, val expires: Long, val handle: Long,
        var remainingPierces: Int = definition.pierce, var remainingBounces: Int = definition.bounces,
        val hits: MutableMap<UUID, Pair<Int, Long>> = linkedMapOf(), val homingTarget: UUID? = null,
        val bindings: Map<String, Double> = emptyMap(),
    )
    private data class ProjectileReceipt(val target: UUID?, val position: Position)
    private data class ProjectileHandle(val scope: Scope, val receipts: MutableMap<ProjectileEvent, ProjectileReceipt> = linkedMapOf())
    private data class EventWait(
        val scope: Scope, val lifetime: Lifetime, val handle: Long, val event: ProjectileEvent, val due: Long,
        val matched: List<Effect>, val timedOut: List<Effect>, val bindings: Map<String, Double>,
    )
    private data class ParallelController(
        val scope: Scope, val effect: Effect.Parallel, val bindings: Map<String, Double>,
        val due: List<Long>, val branchLifetimes: List<Lifetime>,
        val completed: MutableSet<Int> = linkedSetOf(), val succeeded: MutableSet<Int> = linkedSetOf(),
        var finished: Boolean = false,
    )
    private data class ChannelController(val scope: Scope, var due: Long, val expires: Long?)
    private data class ChargeHold(val started: Long, val target: UUID?)
    private data class ConfirmHold(val started: Long, val target: UUID?, val ability: AbilityDef, val dimension: String?)
    private data class RecastWindow(val scope: Scope, val expires: Long)
    private data class Depletion(val scope: Scope, val effects: List<Effect>)
    private data class PassiveKey(val owner: UUID, val classId: String, val grant: String)
    private data class ControllerDependencies(
        val areas: MutableMap<String, AreaDef>, val statuses: MutableMap<String, StatusDef>,
        val states: MutableMap<String, StateDef>, val projectiles: MutableMap<String, ProjectileDef>, val createdStatuses: Set<String>,
    )
    private val scopes = linkedMapOf<UUID, Scope>()
    private val pending = mutableListOf<Scheduled>()
    private val chains = mutableListOf<ChainTask>()
    private val areas = mutableListOf<Area>()
    private val statuses = linkedMapOf<StatusKey, Contribution>()
    private val statusesByTarget = mutableMapOf<UUID, MutableList<Contribution>>()
    private val timers = linkedMapOf<TimerKey, Timer>()
    private val barriers = mutableListOf<Barrier>()
    private val projectiles = mutableListOf<Projectile>()
    private val projectileHandles = linkedMapOf<Long, ProjectileHandle>()
    private val eventWaits = mutableListOf<EventWait>()
    private val parallels = mutableListOf<ParallelController>()
    private var nextProjectileHandle = 1L
    private val pendingDepletions = mutableMapOf<UUID, MutableList<Depletion>>()
    private var barrierOrder = 0L
    private val passives = mutableMapOf<PassiveKey, Scope>()
    private val toggles = mutableMapOf<PassiveKey, Scope>()
    private val channels = mutableMapOf<PassiveKey, ChannelController>()
    private val chargeHolds = mutableMapOf<PassiveKey, ChargeHold>()
    private val confirmHolds = mutableMapOf<PassiveKey, ConfirmHold>()
    private val recasts = mutableMapOf<PassiveKey, RecastWindow>()
    private val suppressedActivations = mutableSetOf<UUID>()
    private val failedPassives = mutableSetOf<PassiveKey>()
    private val failures = ArrayDeque<String>()
    private val onlineClock = mutableMapOf<UUID, Long>()
    private val castsThisTick = mutableMapOf<UUID, Int>()
    private val talentActionsThisTick = mutableMapOf<UUID, Int>()
    private val ownerWorkThisTick = mutableMapOf<UUID, Int>()
    private var globalCastsThisTick = 0
    private var workThisTick = 4096

    fun record(player: UUID): PlayerRecord = players.getOrPut(player) { PlayerRecord() }

    fun progress(owner: UUID, trackId: String, classId: String? = null): ProgressView? {
        val track = definitions.progressionTracks[trackId] ?: return null
        val sourceClass = if (track.scope == ProgressScope.CLASS) classId ?: record(owner).activeClasses.firstOrNull()
            ?: return null else null
        if (sourceClass != null && (sourceClass !in definitions.classes || sourceClass !in record(owner).ownedClasses)) return null
        if (sourceClass != null && !trackAllowsClass(track, sourceClass)) return null
        val key = "${sourceClass ?: "player"}|$trackId"
        val state = record(owner).progression.getOrPut(key) { TrackProgress() }
        grantProgressAwards(state, track)
        val level = track.levels.lastOrNull { it.xp <= state.earnedXp }?.level ?: 1
        return ProgressView(trackId, sourceClass, state.earnedXp, minOf(level, track.capLevel ?: level), state.points.toMap())
    }

    fun awardXp(owner: UUID, trackId: String, amount: Long, classId: String? = null): Long {
        require(amount in 1..1_000_000L) { "progression award is out of range" }
        val track = definitions.progressionTracks[trackId] ?: return 0
        val before = progress(owner, trackId, classId) ?: return 0
        val key = "${before.classId ?: "player"}|$trackId"
        val state = record(owner).progression.getValue(key)
        val maximum = track.capLevel?.let { track.levels[it - 1].xp }
        val limit = if (maximum != null && !track.bankOverflow) maxOf(maximum, state.earnedXp) else 1_000_000_000_000L
        val next = (state.earnedXp + amount).coerceAtMost(limit).coerceAtMost(1_000_000_000_000L)
        val gained = next - state.earnedXp
        state.earnedXp = next
        grantProgressAwards(state, track)
        if (gained > 0) {
            reconcileReplacements(owner)
            players[owner]?.activeClasses?.firstOrNull()?.let { activatePassives(owner, it) }
            prune(releaseIdle = true)
        }
        return gained
    }

    fun selectTalent(owner: UUID, treeId: String, nodeId: String): TalentResult {
        if ((talentActionsThisTick[owner] ?: 0) >= 8) return TalentResult.Rejected("talent input limit reached")
        talentActionsThisTick[owner] = (talentActionsThisTick[owner] ?: 0) + 1
        val tree = definitions.unlockTrees[treeId] ?: return TalentResult.Rejected("unknown unlock tree")
        val node = tree.nodes[nodeId] ?: return TalentResult.Rejected("unknown unlock node")
        if (node.selection != UnlockSelection.TALENT) return TalentResult.Rejected("node is automatic")
        val track = definitions.progressionTracks[tree.track] ?: return TalentResult.Rejected("track is unavailable")
        val classId = if (track.scope == ProgressScope.CLASS) record(owner).activeClasses.firstOrNull()
            ?: return TalentResult.Rejected("class is not active") else null
        val view = progress(owner, tree.track, classId) ?: return TalentResult.Rejected("progress is unavailable")
        val state = record(owner).progression.getValue("${classId ?: "player"}|${tree.track}")
        val purchaseKey = "$treeId|$nodeId"
        val existing = state.purchases[purchaseKey]
        if ((existing?.payments?.size ?: 0) >= node.ranks) return TalentResult.Rejected("maximum rank reached")
        if (view.level < node.requiredLevel || node.prerequisites.any { !nodeActive(tree, it, state, view.level) })
            return TalentResult.Rejected("prerequisites are unmet")
        if (node.choiceGroup != null && tree.nodes.values.any { other ->
                other.id != node.id && other.choiceGroup == node.choiceGroup && nodeActive(tree, other.id, state, view.level)
            }) return TalentResult.Rejected("choice group is already selected")
        val cost = node.cost
        if (cost != null && (state.points[cost.budget] ?: 0) < cost.amount)
            return TalentResult.Rejected("insufficient talent points")
        if (state.purchases.size >= 128 && existing == null) return TalentResult.Rejected("talent selection limit reached")
        if (cost != null) state.points[cost.budget] = state.points.getValue(cost.budget) - cost.amount
        val purchase = state.purchases.getOrPut(purchaseKey) { PointPurchase() }
        purchase.payments += PointPayment(cost?.budget.orEmpty(), cost?.amount ?: 0)
        reconcileReplacements(owner)
        players[owner]?.activeClasses?.firstOrNull()?.let { activatePassives(owner, it) }
        prune(releaseIdle = true)
        return TalentResult.Applied
    }

    fun respecTree(owner: UUID, treeId: String): Int {
        if ((talentActionsThisTick[owner] ?: 0) >= 8) return 0
        talentActionsThisTick[owner] = (talentActionsThisTick[owner] ?: 0) + 1
        val tree = definitions.unlockTrees[treeId] ?: return 0
        val track = definitions.progressionTracks[tree.track] ?: return 0
        val classId = if (track.scope == ProgressScope.CLASS) record(owner).activeClasses.firstOrNull() ?: return 0 else null
        val state = record(owner).progression["${classId ?: "player"}|${tree.track}"] ?: return 0
        var removed = 0
        for ((key, purchase) in state.purchases.toMap()) if (key.startsWith("$treeId|")) {
            state.purchases.remove(key)
            refundPayments(state, purchase)
            removed += purchase.payments.size
        }
        if (removed > 0) { reconcileReplacements(owner); prune(releaseIdle = true) }
        return removed
    }

    fun talentNodes(owner: UUID): List<TalentView> {
        val activeClass = players[owner]?.activeClasses?.firstOrNull()
        val nodes = mutableListOf<TalentView>()
        for (tree in definitions.unlockTrees.values) {
            val track = definitions.progressionTracks[tree.track] ?: continue
            val classId = if (track.scope == ProgressScope.CLASS) activeClass ?: continue else null
            val view = progress(owner, track.id, classId) ?: continue
            val state = record(owner).progression["${classId ?: "player"}|${track.id}"] ?: continue
            for (node in tree.nodes.values) {
                val rank = if (node.selection == UnlockSelection.AUTOMATIC)
                    if (nodeActive(tree, node.id, state, view.level)) 1 else 0
                    else state.purchases["${tree.id}|${node.id}"]?.payments?.size ?: 0
                val cost = node.cost?.amount ?: 0
                val points = node.cost?.budget?.let { state.points[it] ?: 0 } ?: 0
                val reason = when {
                    node.selection == UnlockSelection.AUTOMATIC -> "automatic"
                    rank >= node.ranks -> "max rank"
                    view.level < node.requiredLevel -> "level"
                    node.prerequisites.any { !nodeActive(tree, it, state, view.level) } -> "prerequisite"
                    node.choiceGroup != null && tree.nodes.values.any { other ->
                        other.id != node.id && other.choiceGroup == node.choiceGroup && nodeActive(tree, other.id, state, view.level)
                    } -> "choice"
                    cost > points -> "points"
                    else -> "available"
                }
                nodes += TalentView(tree.id, node.id, node.selection, rank, node.ranks, cost, points, node.requiredLevel, reason)
                if (nodes.size == 128) return nodes
            }
        }
        return nodes
    }

    private fun nodeActive(tree: UnlockTreeDef, nodeId: String, state: TrackProgress, level: Int): Boolean {
        val node = tree.nodes[nodeId] ?: return false
        if (level < node.requiredLevel) return false
        if (node.selection == UnlockSelection.TALENT && state.purchases["${tree.id}|$nodeId"]?.payments.isNullOrEmpty()) return false
        return node.prerequisites.all { nodeActive(tree, it, state, level) }
    }

    private fun reconcileSelections(owner: UUID) {
        val record = players[owner] ?: return
        for ((key, state) in record.progression) {
            val classId = key.substringBefore('|').takeUnless { it == "player" }
            val trackId = key.substringAfter('|')
            val track = definitions.progressionTracks[trackId]
            val level = if (track == null) 0 else minOf(track.levels.lastOrNull { it.xp <= state.earnedXp }?.level ?: 1,
                track.capLevel ?: Int.MAX_VALUE)
            var changed: Boolean
            do {
                changed = false
                val chosenGroups = mutableSetOf<String>()
                for ((purchaseKey, purchase) in state.purchases.toMap()) {
                    val treeId = purchaseKey.substringBefore('|')
                    val nodeId = purchaseKey.substringAfter('|')
                    val tree = definitions.unlockTrees[treeId]
                    val node = tree?.nodes?.get(nodeId)
                    val invalid = tree == null || node == null || tree.track != trackId ||
                        (track?.scope == ProgressScope.CLASS) != (classId != null) ||
                        node.selection != UnlockSelection.TALENT || level < node.requiredLevel ||
                        node.prerequisites.any { !nodeActive(tree, it, state, level) } ||
                        (node.choiceGroup != null && !chosenGroups.add("$treeId|${node.choiceGroup}"))
                    if (invalid) {
                        state.purchases.remove(purchaseKey)
                        refundPayments(state, purchase)
                        changed = true
                    }
                }
            } while (changed)
        }
        reconcileReplacements(owner)
    }

    private fun refundPayments(state: TrackProgress, purchase: PointPurchase) {
        for (payment in purchase.payments) if (payment.budget.isNotEmpty())
            state.points[payment.budget] = ((state.points[payment.budget] ?: 0) + payment.amount).coerceAtMost(1_000_000)
    }

    fun onEntityDeath(creditedPlayer: UUID?, victimPosition: Position? = null) {
        val owner = creditedPlayer ?: return
        if (owner !in players || !world.availableTarget(owner, owner)) return
        for (track in definitions.progressionTracks.values) {
            for (rule in track.earningRules) if (rule.event == ProgressEvent.ENTITY_DEATH) {
                // ASVS 2.3.1: recipients and the event position come from server state, never a client packet.
                val recipients = when (rule.recipients) {
                    ProgressRecipients.ACTOR -> listOf(owner)
                    ProgressRecipients.NEARBY_ALLIES -> victimPosition?.let {
                        world.nearbyAlliedPlayers(owner, it, rule.range, 128)
                    }.orEmpty()
                }.distinct().filter { it in players && world.availableTarget(it, it) && progress(it, track.id) != null }
                    .sortedBy(UUID::toString).take(128)
                if (recipients.isEmpty()) continue
                val share = if (rule.distribution == ProgressDistribution.SPLIT) rule.xp / recipients.size else rule.xp
                val remainder = if (rule.distribution == ProgressDistribution.SPLIT) rule.xp % recipients.size else 0L
                for ((index, recipient) in recipients.withIndex()) {
                    val amount = share + if (index < remainder) 1 else 0
                    if (amount > 0) awardXp(recipient, track.id, amount)
                }
            }
        }
    }

    private fun grantProgressAwards(state: TrackProgress, track: ProgressionTrackDef) {
        for (level in track.levels) if (state.earnedXp >= level.xp) for (award in level.awards) {
            val key = "${track.id}/${award.id}"
            if (key in state.awarded) continue
            require(state.awarded.size < 2048 && state.points.size < 128) { "progression record limit reached" }
            state.awarded += key
            state.points[award.budget] = ((state.points[award.budget] ?: 0) + award.amount).coerceAtMost(1_000_000)
        }
    }

    private fun reconcileProgression(owner: UUID) {
        val record = players[owner] ?: return
        for (track in definitions.progressionTracks.values) {
            if (track.scope == ProgressScope.PLAYER) progress(owner, track.id)
            else for (classId in record.ownedClasses) if (trackAllowsClass(track, classId))
                progress(owner, track.id, classId)
        }
        reconcileSelections(owner)
    }

    private fun trackAllowsClass(track: ProgressionTrackDef, classId: String): Boolean =
        track.classes.let { if (it.isEmpty()) classId.substringBefore(':') == track.id.substringBefore(':') else classId in it }
    fun readDeclaredState(player: UUID, classId: String, stateId: String, field: String): StateValue? {
        val definition = definitions.states[stateId] ?: return null
        if (definition.scope in setOf(StateScope.ACTIVATION, StateScope.STATUS)) return null
        val declared = definition.fields[field] ?: return null
        val key = "${if (definition.scope == StateScope.PLAYER) "player" else classId}|$stateId|$field"
        val values = if (definition.persistent) record(player).persistentStates else transientStates[player]
        return values?.get(key)?.let { normalizeState(it, declared) } ?: declared.initial
    }
    fun drainFailures(): List<String> = failures.toList().also { failures.clear() }
    fun toggleActive(owner: UUID, classId: String, grant: String): Boolean =
        toggles[PassiveKey(owner, classId, grant)]?.lifetime?.active == true
    fun channelActive(owner: UUID, classId: String, grant: String): Boolean =
        channels[PassiveKey(owner, classId, grant)]?.scope?.lifetime?.active == true
    fun releaseChannel(owner: UUID, classId: String, grant: String, clientGeneration: Long): Boolean {
        if (clientGeneration != generation) return false
        val key = PassiveKey(owner, classId, grant)
        val channel = channels[key]?.takeIf { it.scope.lifetime.active } ?: return false
        channel.scope.lifetime.cancelled = true
        prune(releaseIdle = true)
        return true
    }
    fun chargeActive(owner: UUID, classId: String, grant: String): Boolean =
        PassiveKey(owner, classId, grant) in chargeHolds
    fun confirmActive(owner: UUID, classId: String, grant: String): Boolean =
        PassiveKey(owner, classId, grant) in confirmHolds
    fun recastActive(owner: UUID, classId: String, grant: String): Boolean =
        recasts[PassiveKey(owner, classId, grant)]?.let { it.scope.lifetime.active && it.expires > now(owner) } == true
    fun grantsFor(owner: UUID, classId: String): Map<String, Grant> = grantsFor(owner, classId, definitions)

    private fun grantsFor(owner: UUID, classId: String, set: DefinitionSet): Map<String, Grant> {
        val base = set.classes[classId]?.grants ?: return emptyMap()
        val result = LinkedHashMap(base)
        for (tree in set.unlockTrees.values) {
            val track = set.progressionTracks[tree.track] ?: continue
            if (track.scope == ProgressScope.CLASS && !trackAllowsClass(track, classId)) continue
            val key = "${if (track.scope == ProgressScope.PLAYER) "player" else classId}|${track.id}"
            val state = players[owner]?.progression?.get(key) ?: TrackProgress()
            val level = minOf(track.levels.lastOrNull { it.xp <= state.earnedXp }?.level ?: 1,
                track.capLevel ?: Int.MAX_VALUE)
            for (node in tree.nodes.values) if (nodeActive(tree, node.id, state, level))
                for (unlock in node.abilities) set.abilities[unlock.ability]?.let { ability ->
                    result[unlock.grant] = Grant(unlock.grant, unlock.slot, ability)
                }
        }
        return result
    }
    fun effectiveAbility(owner: UUID, classId: String, grant: String): AbilityDef? =
        effectiveAbility(owner, classId, grant, definitions)

    private fun effectiveAbility(owner: UUID, classId: String, grant: String, set: DefinitionSet): AbilityDef? {
        val base = grantsFor(owner, classId, set)[grant]?.ability ?: return null
        val statusReplacements = statusesByTarget[owner].orEmpty().asSequence().filter { it.lifetime.active }
            .flatMap { status -> status.definition.replacements.asSequence().filter { it.grant == grant } }
        val earned = players[owner]?.progression.orEmpty()
        val talentReplacements = set.unlockTrees.values.asSequence().flatMap { tree ->
            val track = set.progressionTracks[tree.track] ?: return@flatMap emptySequence<AbilityReplacement>()
            if (track.scope == ProgressScope.CLASS && !trackAllowsClass(track, classId))
                return@flatMap emptySequence<AbilityReplacement>()
            val key = "${if (track.scope == ProgressScope.PLAYER) "player" else classId}|${track.id}"
            val state = earned[key] ?: TrackProgress()
            val level = minOf(track.levels.lastOrNull { it.xp <= state.earnedXp }?.level ?: 1, track.capLevel ?: Int.MAX_VALUE)
            tree.nodes.values.asSequence().filter { nodeActive(tree, it.id, state, level) }
                .flatMap { node -> node.empowerments.asSequence() }
                .flatMap { empowerment -> set.empowerments[empowerment]?.replacements?.asSequence() ?: emptySequence() }
                .filter { it.grant == grant }
        }
        val replacement = (statusReplacements + talentReplacements)
            .sortedWith(compareByDescending<AbilityReplacement> { it.priority }.thenBy { it.replacement })
            .firstOrNull() ?: return base
        return set.abilities[replacement.replacement] ?: base
    }
    fun releaseCharge(owner: UUID, classId: String, grant: String, target: UUID?, clientGeneration: Long): CastResult {
        if (clientGeneration != generation) return CastResult.Rejected("stale definitions")
        val key = PassiveKey(owner, classId, grant)
        val hold = chargeHolds.remove(key) ?: return CastResult.Rejected("charge is not active")
        val ability = effectiveAbility(owner, classId, grant)
            ?: return CastResult.Rejected("unknown grant")
        if (ability.activation != Activation.CHARGE) return CastResult.Rejected("ability is not charged")
        val elapsed = (now(owner) - hold.started).coerceAtLeast(0).coerceAtMost(ability.chargeMaxTicks.toLong()).toInt()
        if (elapsed < ability.chargeMinTicks) return CastResult.Rejected("charge is not ready")
        return castInternal(owner, classId, grant, target ?: hold.target, clientGeneration, elapsed)
    }
    fun statuses(target: UUID): List<StatusView> = statusesByTarget[target].orEmpty().filter { it.lifetime.active }.map {
        StatusView(it.definition.id, it.key.owner, it.key.classId, it.key.grant, it.key.application, it.stackExpiries.size,
            if (it.key.membership != null) null else (it.stackExpiries.max() - now(it.key.owner)).coerceAtLeast(0).toInt(), it.key.membership != null)
    }
    fun timers(owner: UUID): List<TimerView> = timers.values.filter { it.key.owner == owner && it.lifetime.active }.map {
        TimerView(it.key.name, it.key.target, it.key.classId, it.key.grant, (it.due - now(owner)).coerceAtLeast(0).toInt())
    }
    fun barriers(target: UUID): List<BarrierView> = barriers.filter { it.target == target && it.lifetime.active && it.expires > now(it.scope.owner) }.map {
        BarrierView(target, it.scope.owner, it.remaining, (it.expires - now(it.scope.owner)).toInt(), it.priority)
    }
    /** Called from the native damage pipeline after armor and magic mitigation, before vanilla absorption and health loss. */
    fun absorbNativeDamage(target: UUID, damageType: String, afterMitigation: Double, nativeAbsorption: Double): Double {
        require(afterMitigation.isFinite() && afterMitigation >= 0.0 && nativeAbsorption.isFinite() && nativeAbsorption >= 0.0) { "invalid native damage" }
        var pendingHealthLoss = (afterMitigation - nativeAbsorption).coerceAtLeast(0.0)
        if (pendingHealthLoss == 0.0) return 0.0
        var absorbed = 0.0
        val eligible = barriers.filter { barrier -> barrier.target == target && barrier.lifetime.active && barrier.expires > now(barrier.scope.owner) &&
            (barrier.damageType == null || barrier.damageType == damageType) }
            .sortedWith(compareByDescending<Barrier> { it.priority }.thenBy { it.order })
        for (barrier in eligible) {
            if (pendingHealthLoss <= 0.0) break
            val use = minOf(pendingHealthLoss, barrier.remaining)
            if (use <= 0.0) continue
            val depleted = use >= barrier.remaining
            barrier.remaining = (barrier.remaining - use).coerceAtLeast(0.0)
            pendingHealthLoss -= use
            absorbed += use
            if (depleted) {
                barrier.lifetime.cancelled = true
                if (barrier.depleted.isNotEmpty()) {
                    val callback = barrier.scope.copy(target = target, selected = true, targetGuard = null,
                        lifetime = Lifetime(barrier.scope.lifetime), budget = Budget())
                    pendingDepletions.getOrPut(target) { mutableListOf() } += Depletion(callback, barrier.depleted)
                }
            }
        }
        barriers.removeIf { !it.lifetime.active }
        return absorbed
    }
    /** Runs depletion gameplay only after the native hit has committed. */
    fun nativeDamageCommitted(target: UUID) {
        val callbacks = pendingDepletions.remove(target).orEmpty()
        for (callback in callbacks) if (callback.scope.lifetime.active) try {
            execute(callback.scope, callback.effects, linkedMapOf())
        } catch (failure: Exception) { fail(callback.scope, failure) }
        prune(releaseIdle = true)
    }
    /** Uses the native entity's observed health delta, not requested or mitigated damage. */
    fun onNativeHealthLoss(target: UUID, healthLost: Double) {
        if (!healthLost.isFinite() || healthLost <= 0.0) return
        val broken = statusesByTarget[target].orEmpty().filter { status ->
            status.lifetime.active && status.definition.breakOnHealthLoss?.let { healthLost >= it } == true
        }
        if (broken.isEmpty()) return
        val callbacks = broken.mapNotNull { status ->
            val root = scopes[status.scope.id] ?: return@mapNotNull null
            val callbackScope = statusScope(status, Budget()).copy(lifetime = Lifetime(root.lifetime))
            Triple(status, callbackScope, statusBindings(status))
        }
        // ASVS 2.3.4: all matching source contributions are cancelled once before any broken callback can recurse.
        broken.forEach { it.lifetime.cancelled = true }
        prune()
        for ((status, scope, bindings) in callbacks) if (scope.lifetime.active) try {
            execute(scope, status.definition.broken, bindings)
        } catch (failure: Exception) { fail(scope, failure) }
        prune(releaseIdle = true)
    }
    fun shutdown() { cancelWhere { true }; transientStates.clear(); chargeHolds.clear(); confirmHolds.clear(); recasts.clear() }

    fun installRecord(player: UUID, record: PlayerRecord) {
        for ((key, amount) in record.resources.toMap()) definitions.resources[key.substringAfterLast('|')]?.let {
            record.resources[key] = amount.coerceIn(it.minimum, it.maximum)
        }
        players[player] = record
        reconcileStates(record.persistentStates)
        for ((key, value) in record.persistentStates) {
            val parts = key.split('|')
            if (parts.size != 3) continue
            val field = definitions.states[parts[1]]?.fields?.get(parts[2]) ?: continue
            if (normalizeState(value, field) == null) failures += "saved state $key is incompatible; its value remains stored for migration"
        }
        reconcileCharges(player, record)
        reconcileProgression(player)
        record.activeClasses.firstOrNull()?.let { activatePassives(player, it) }
    }

    fun onDeath(player: UUID) {
        chargeHolds.keys.removeIf { it.owner == player }
        confirmHolds.keys.removeIf { it.owner == player }
        transientStates[player]?.keys?.removeIf { it.startsWith("player|") }
        failedPassives.removeIf { it.owner == player }
        cancelTargetStatuses(player)
        cancelTargetTimers(player)
        cancelTargetBarriers(player)
        cancelWhere { it.owner == player }
        val record = players[player] ?: return
        for ((key, _) in record.resources.toMap()) definitions.resources[key.substringAfterLast('|')]?.let { record.resources[key] = it.initial }
    }

    fun onLogout(player: UUID) {
        chargeHolds.keys.removeIf { it.owner == player }
        confirmHolds.keys.removeIf { it.owner == player }
        transientStates.remove(player)
        failedPassives.removeIf { it.owner == player }
        cancelTargetStatuses(player); cancelTargetTimers(player); cancelTargetBarriers(player); cancelWhere { it.owner == player }
    }

    fun publish(candidate: DefinitionSet) {
        if (candidate.fingerprint == definitions.fingerprint) return
        val incompatible = stateCompatibility(candidate)
        require(incompatible.isEmpty()) { incompatible.joinToString("; ") }
        chargeHolds.keys.removeIf { effectiveAbility(it.owner, it.classId, it.grant, candidate) !=
            effectiveAbility(it.owner, it.classId, it.grant) }
        confirmHolds.entries.removeIf { (key, hold) -> effectiveAbility(key.owner, key.classId, key.grant, candidate) != hold.ability }
        cancelWhere { scope ->
            (if (statuses.values.any { it.lifetime.active && it.scope.id == scope.id && it.definition.replacements.isNotEmpty() })
                candidate.classes[scope.classId]?.grants?.get(scope.grant)?.ability != scope.ability
            else effectiveAbility(scope.owner, scope.classId, scope.grant, candidate) != scope.ability) ||
                scope.dependencies.any { (id, definition) -> candidate.areas[id] != definition } ||
                scope.statusDependencies.any { (id, definition) -> candidate.statuses[id] != definition } ||
                scope.stateDependencies.any { (id, definition) -> candidate.states[id] != definition }
                || scope.projectileDependencies.any { (id, definition) -> candidate.projectiles[id] != definition }
        }
        for (record in players.values) for ((key, amount) in record.resources.toMap()) candidate.resources[key.substringAfterLast('|')]?.let {
            record.resources[key] = amount.coerceIn(it.minimum, it.maximum)
        }
        definitions = candidate
        players.values.forEach { reconcileStates(it.persistentStates) }
        transientStates.values.forEach(::reconcileStates)
        estimates = ExecutionEstimates(candidate, catalog)
        players.forEach { (owner, record) -> reconcileCharges(owner, record) }
        players.keys.forEach(::reconcileProgression)
        failedPassives.clear()
        generation++
        for ((player, record) in players) record.activeClasses.firstOrNull()?.let { activatePassives(player, it) }
    }

    private fun stateCompatibility(candidate: DefinitionSet): List<String> {
        val errors = mutableListOf<String>()
        for ((id, previous) in definitions.states) {
            val next = candidate.states[id] ?: continue // Removed fields remain dormant in saved records.
            if (previous.scope != next.scope || previous.persistent != next.persistent) errors += "$id changes state scope or persistence without a migration"
            for ((name, oldField) in previous.fields) {
                val newField = next.fields[name] ?: continue
                if (oldField.type != newField.type || (oldField.type == StateType.ENUM && !newField.choices.containsAll(oldField.choices)))
                    errors += "$id.$name changes state type or removes enum modes without a migration"
            }
        }
        for (record in players.values) for ((key, value) in record.persistentStates) {
            val parts = key.split('|')
            if (parts.size != 3) continue
            val definition = candidate.states[parts[1]] ?: continue
            val field = definition.fields[parts[2]] ?: continue
            if (!definition.persistent || normalizeState(value, field) == null)
                errors += "$key contains saved state incompatible with the candidate"
        }
        return errors.distinct().take(32)
    }

    fun selectClass(player: UUID, classId: String): Boolean {
        if (classId !in definitions.classes) return false
        val previous = record(player).activeClasses.firstOrNull()
        chargeHolds.keys.removeIf { it.owner == player && it.classId != classId }
        confirmHolds.keys.removeIf { it.owner == player && it.classId != classId }
        if (previous != null && previous != classId) transientStates[player]?.keys?.removeIf { it.startsWith("$previous|") }
        cancelWhere { it.owner == player && it.classId != classId }
        record(player).apply {
            ownedClasses += classId
            activeClasses.clear()
            activeClasses += classId
        }
        reconcileProgression(player)
        failedPassives.removeIf { it.owner == player && it.classId != classId }
        activatePassives(player, classId)
        return true
    }

    private fun activatePassives(player: UUID, classId: String) {
        if (!world.availableTarget(player, player)) return
        val grants = grantsFor(player, classId)
        for ((name, grant) in grants) {
            val ability = effectiveAbility(player, classId, name) ?: grant.ability
            if (ability.activation != Activation.PASSIVE) continue
            val key = PassiveKey(player, classId, name)
            if (key in passives || key in failedPassives) continue
            val (areas, statuses, states, projectiles, createdStatuses) = controllerDependencies(ability.effects)
            val scope = Scope(UUID.randomUUID(), player, classId, name, ability, null, null, Budget(), Lifetime(),
                dependencies = areas, statusDependencies = statuses, stateDependencies = states, projectileDependencies = projectiles, sourceDimension = world.position(player)?.dimension)
            scopes[scope.id] = scope
            passives[key] = scope
            try {
                require(estimate(ability.effects) <= 1024) { "passive setup work limit exceeded" }
                require(areas.values.all { area -> (estimate(area.enter) + estimate(area.exit) + estimate(area.periodic) + buffWork(area)) * area.selector.limit + 1 <= 1024 && estimate(area.expired) <= 1024 }) { "passive area pulse work limit exceeded" }
                require(createdStatuses.all { estimate(statuses.getValue(it).bodies) <= 1024 }) { "passive status pulse work limit exceeded" }
                require(projectiles.values.all { projectile -> listOf(projectile.entityHit, projectile.blockHit, projectile.expiry).all { estimate(it) <= 1024 } }) { "passive projectile callback work limit exceeded" }
                require(ability.effects.flatMap { catalog.descendants(it).toList() }.filterIsInstance<Effect.WaitFor>().all {
                    estimate(it.matched) <= 1024 && estimate(it.timedOut) <= 1024
                }) { "passive event wait callback work limit exceeded" }
                require(estimatedProjectiles(ability.effects) + this.projectiles.count { it.scope.owner == player } <= 32 &&
                    estimatedProjectiles(ability.effects) + this.projectiles.size <= 512 &&
                    estimatedProjectiles(ability.effects) + projectileHandles.size <= 2048) { "passive projectile limit reached" }
                require(estimatedWaits(ability.effects) + eventWaits.count { it.scope.owner == player } <= 64 &&
                    estimatedWaits(ability.effects) + eventWaits.size <= 1024) { "passive event wait limit reached" }
                require(estimatedParallels(ability.effects) + parallels.count { it.scope.owner == player } <= 32 &&
                    estimatedParallels(ability.effects) + parallels.size <= 256) { "passive parallel limit reached" }
                execute(scope, ability.effects, linkedMapOf())
            } catch (failure: Exception) {
                failedPassives += key
                fail(scope, failure)
            } finally { prune(releaseIdle = true) }
        }
    }

    fun cast(player: UUID, classId: String, grantName: String, target: UUID?, clientGeneration: Long): CastResult =
        castInternal(player, classId, grantName, target, clientGeneration, null)

    private fun castInternal(player: UUID, classId: String, grantName: String, target: UUID?, clientGeneration: Long,
        heldTicks: Int?): CastResult {
        if (clientGeneration != generation) return CastResult.Rejected("stale definitions")
        if ((castsThisTick[player] ?: 0) >= 8 || globalCastsThisTick >= 256) return CastResult.Rejected("input limit reached")
        castsThisTick[player] = (castsThisTick[player] ?: 0) + 1
        globalCastsThisTick++
        val record = record(player)
        if (record.activeClasses.firstOrNull() != classId) return CastResult.Rejected("class is not active")
        val grant = grantsFor(player, classId)[grantName] ?: return CastResult.Rejected("unknown grant")
        val ability = effectiveAbility(player, classId, grantName) ?: grant.ability
        if (ability.activation == Activation.PASSIVE) return CastResult.Rejected("ability cannot be cast")
        val toggleKey = PassiveKey(player, classId, grantName)
        val recastWindow = if (ability.activation == Activation.RECAST)
            recasts[toggleKey]?.takeIf { it.scope.lifetime.active && it.expires > now(player) && it.scope.ability == ability }
        else null
        val isRecast = recastWindow != null
        val effects = if (isRecast) ability.recastEffects else ability.effects
        if (ability.activation == Activation.TOGGLE) toggles[toggleKey]?.takeIf { it.lifetime.active }?.let {
            it.lifetime.cancelled = true
            prune(releaseIdle = true)
            return CastResult.Applied
        }
        if (ability.activation == Activation.CHANNEL && channelActive(player, classId, grantName))
            return CastResult.Rejected("channel is already active")
        if (!world.availableTarget(player, player)) return CastResult.Rejected("actor is unavailable")
        // ASVS 2.3.1, 2.3.2: authoritative restrictions are checked before any cost, charge, or cooldown commits.
        if (statusesByTarget[player].orEmpty().any { it.lifetime.active && ActionRestriction.ACTIVATE in it.definition.restrictions })
            return CastResult.Rejected("ability activation is restricted")
        var requestedTarget = target ?: recastWindow?.scope?.target
        if (ability.activation == Activation.CONFIRM) {
            val previous = confirmHolds.remove(toggleKey)
            if (previous != null && previous.ability == ability &&
                now(player) - previous.started < ability.confirmWindowTicks &&
                previous.dimension == world.position(player)?.dimension) requestedTarget = target ?: previous.target
            else {
                val cooldownKey = "$classId|$grantName"
                if (cooldownKeys(cooldownKey, ability).any { (key, _) -> (record.cooldowns[key] ?: 0) > 0 })
                    return CastResult.Rejected("cooldown is active")
                if (ability.charges?.let { chargeState(record, cooldownKey, it).available <= 0 } == true)
                    return CastResult.Rejected("no ability charges available")
                if (confirmHolds.size >= 128 || confirmHolds.keys.count { it.owner == player } >= 16)
                    return CastResult.Rejected("confirmation limit reached")
                confirmHolds[toggleKey] = ConfirmHold(now(player), target, ability, world.position(player)?.dimension)
                return CastResult.Applied
            }
        }
        if (ability.activation == Activation.CHARGE && heldTicks == null) {
            if (toggleKey in chargeHolds) return CastResult.Rejected("charge is already held")
            val cooldownKey = "$classId|$grantName"
            if (cooldownKeys(cooldownKey, ability).any { (key, _) -> (record.cooldowns[key] ?: 0) > 0 })
                return CastResult.Rejected("cooldown is active")
            if (ability.charges?.let { chargeState(record, cooldownKey, it).available <= 0 } == true)
                return CastResult.Rejected("no ability charges available")
            if (chargeHolds.size >= 128 || chargeHolds.keys.count { it.owner == player } >= 16)
                return CastResult.Rejected("charge hold limit reached")
            chargeHolds[toggleKey] = ChargeHold(now(player), target)
            return CastResult.Applied
        }
        val requiresTarget = effects.any { needsTarget(it, ability.targeting.type == Targeting.Type.GROUND) }
        val selectedTarget = requestedTarget ?: if (requiresTarget) world.aim(player, ability.targeting.range) else null
        if (requiresTarget && (selectedTarget == null || !world.validTarget(player, selectedTarget, ability.targeting.range))) return CastResult.Rejected("target is unavailable")
        val ground = if (ability.targeting.type == Targeting.Type.GROUND) world.ground(player, ability.targeting.range) else null
        if ((ability.targeting.type == Targeting.Type.GROUND || effects.any { needsGround(it) }) && (ground == null || !world.loaded(ground))) return CastResult.Rejected("ground target is unavailable")
        val cooldownKey = "$classId|$grantName"
        val cooldowns = if (isRecast) emptyList() else cooldownKeys(cooldownKey, ability)
        if (cooldowns.any { (key, _) -> (record.cooldowns[key] ?: 0) > 0 }) return CastResult.Rejected("cooldown is active")
        val charges = if (isRecast) null else ability.charges?.let { chargeState(record, cooldownKey, it) }
        if (charges != null && charges.available <= 0) return CastResult.Rejected("no ability charges available")
        // Known oversized setup is rejected before payment. Dynamic query work is checked during execution.
        if (estimate(effects) > 1024) return CastResult.Rejected("ability work limit exceeded")
        val (dependencies, statusDependencies, stateDependencies, projectileDependencies, createdStatuses) = controllerDependencies(effects)
        if (dependencies.values.any { area -> (estimate(area.enter) + estimate(area.exit) + estimate(area.periodic) + buffWork(area)) * area.selector.limit + 1 > 1024 || estimate(area.expired) > 1024 }) return CastResult.Rejected("area pulse work limit exceeded")
        if (createdStatuses.any { estimate(statusDependencies.getValue(it).bodies) > 1024 }) return CastResult.Rejected("status pulse work limit exceeded")
        if (projectileDependencies.values.any { projectile -> listOf(projectile.entityHit, projectile.blockHit, projectile.expiry).any { estimate(it) > 1024 } })
            return CastResult.Rejected("projectile callback work limit exceeded")
        val allBodies = listOf(effects) + dependencies.values.flatMap { listOf(it.enter, it.periodic, it.exit, it.expired) } + statusDependencies.values.map { it.bodies }
        if (allBodies.any { body -> body.flatMap { catalog.descendants(it).toList() }.filterIsInstance<Effect.SetTimer>().any { estimate(it.expired) > 1024 } })
            return CastResult.Rejected("timer expiry work limit exceeded")
        if (allBodies.any { body -> body.flatMap { catalog.descendants(it).toList() }.filterIsInstance<Effect.WaitFor>().any {
                estimate(it.matched) > 1024 || estimate(it.timedOut) > 1024
            } }) return CastResult.Rejected("event wait callback work limit exceeded")
        val knownKeys = initialStatusKeys(player, classId, grantName, ability, selectedTarget, effects)
        val refreshedSlots = knownKeys.count { statuses[it]?.lifetime?.active == true }
        val contributions = (estimatedStatuses(effects) - refreshedSlots).coerceAtLeast(0)
        if (contributions + statuses.size > 4096 || contributions + statuses.values.count { it.key.owner == player } > 256) return CastResult.Rejected("status contribution limit reached")
        if (knownKeys.filter { statuses[it]?.lifetime?.active != true }.groupingBy { it.target }.eachCount().any { (id, count) -> count + statusesByTarget[id].orEmpty().count { it.lifetime.active } > 64 }) return CastResult.Rejected("status contribution limit reached")
        val created = estimatedAreas(effects)
        if (created + areas.size > 512 || created + areas.count { it.scope.owner == player } > 32) return CastResult.Rejected("area limit reached")
        val refreshedTimers = initialTimerKeys(player, classId, grantName, selectedTarget, effects).count { timers[it]?.lifetime?.active == true }
        val createdTimers = (estimatedTimers(effects) - refreshedTimers).coerceAtLeast(0)
        if (createdTimers + timers.size > 1024 || createdTimers + timers.values.count { it.key.owner == player } > 64) return CastResult.Rejected("timer limit reached")
        val createdBarriers = effects.flatMap { catalog.descendants(it).toList() }.count { it is Effect.Shield }
        if (createdBarriers + barriers.size > 1024 || createdBarriers + barriers.count { it.scope.owner == player } > 64 ||
            createdBarriers + barriers.count { it.target == (selectedTarget ?: player) } > 64) return CastResult.Rejected("barrier limit reached")
        val createdProjectiles = estimatedProjectiles(effects)
        if (createdProjectiles + projectiles.size > 512 || createdProjectiles + projectiles.count { it.scope.owner == player } > 32 ||
            createdProjectiles + projectileHandles.size > 2048)
            return CastResult.Rejected("projectile limit reached")
        val createdWaits = estimatedWaits(effects)
        if (createdWaits + eventWaits.size > 1024 || createdWaits + eventWaits.count { it.scope.owner == player } > 64)
            return CastResult.Rejected("event wait limit reached")
        val createdParallels = estimatedParallels(effects)
        if (createdParallels + parallels.size > 256 || createdParallels + parallels.count { it.scope.owner == player } > 32)
            return CastResult.Rejected("parallel limit reached")
        if (ability.activation == Activation.CHANNEL && (channels.size >= 128 || channels.keys.count { it.owner == player } >= 16))
            return CastResult.Rejected("channel limit reached")
        if (ability.activation == Activation.RECAST && !isRecast &&
            (recasts.size >= 128 || recasts.keys.count { it.owner == player } >= 16))
            return CastResult.Rejected("recast window limit reached")
        val slots = initialSlots(effects)
        if (slots + pending.size + chains.size + eventWaits.size + parallels.size > 2048 ||
            slots + pending.count { it.scope.owner == player } + chains.count { it.scope.owner == player } + eventWaits.count { it.scope.owner == player } + parallels.count { it.scope.owner == player } > 128)
            return CastResult.Rejected("scheduled work limit exceeded")
        val amounts = mutableListOf<Pair<String, Double>>()
        for (cost in if (isRecast) emptyList() else ability.costs) {
            val resource = definitions.resources[cost.resource] ?: return CastResult.Rejected("resource is unavailable")
            val amount = try { cost.amount.value(emptyMap()) } catch (_: Exception) { return CastResult.Rejected("invalid cost") }
            if (!amount.isFinite() || amount !in 0.0..1_000_000.0) return CastResult.Rejected("invalid cost")
            val key = balanceKey(classId, resource)
            val already = amounts.filter { it.first == key }.sumOf { it.second }
            if (balance(record, classId, resource) - already - amount < resource.minimum) return CastResult.Rejected("insufficient ${resource.id}")
            amounts += key to amount
        }
        for ((key, amount) in amounts) record.resources[key] = record.resources.getValue(key) - amount
        if (charges != null) {
            // ASVS 2.3.1, 2.3.4: one validated use is consumed in the same server turn as its cost and cooldown.
            charges.available--
            if (charges.mode == RechargeMode.PARALLEL || charges.timers.isEmpty())
                charges.timers += requireNotNull(ability.charges).rechargeTicks
        }
        // ASVS 2.3.1, 2.3.4: commit every applicable cooldown in the same server turn as costs and charge consumption.
        for ((key, ticks) in cooldowns) if (ticks > 0) record.cooldowns[key] = ticks
        val scope = Scope(UUID.randomUUID(), player, classId, grantName, ability, if (ability.targeting.type == Targeting.Type.GROUND) null else selectedTarget, ground, Budget(), Lifetime(), dependencies = dependencies,
            statusDependencies = statusDependencies, stateDependencies = stateDependencies, projectileDependencies = projectileDependencies,
            sourceDimension = world.position(player)?.dimension)
        scopes[scope.id] = scope
        if (ability.activation == Activation.TOGGLE) toggles[toggleKey] = scope
        if (ability.activation == Activation.CHANNEL)
            channels[toggleKey] = ChannelController(scope, now(player) + ability.channelEveryTicks,
                ability.channelMaxTicks?.let { now(player) + it })
        val initialBindings = linkedMapOf<String, Double>()
        if (heldTicks != null) {
            initialBindings["charge.held_ticks"] = heldTicks.toDouble()
            initialBindings["charge.fraction"] = heldTicks.toDouble() / ability.chargeMaxTicks
        }
        try {
            execute(scope, effects, initialBindings)
            if (isRecast) {
                recasts.remove(toggleKey)
                recastWindow.scope.lifetime.cancelled = true
            } else if (ability.activation == Activation.RECAST && scope.lifetime.active &&
                effectiveAbility(player, classId, grantName) == ability) {
                recasts[toggleKey] = RecastWindow(scope, now(player) + ability.recastWindowTicks)
            }
        }
        catch (failure: Exception) {
            fail(scope, failure)
            return CastResult.Interrupted(failure.message ?: "effect failed")
        } finally { prune(releaseIdle = true) }
        return CastResult.Applied
    }

    fun tick(onlinePlayers: Collection<UUID>) {
        castsThisTick.clear()
        talentActionsThisTick.clear()
        ownerWorkThisTick.clear()
        globalCastsThisTick = 0
        workThisTick = 4096
        val online = onlinePlayers.toSet()
        cancelWhere { it.owner !in online || !world.availableTarget(it.owner, it.owner) || (it.sourceDimension != null && world.position(it.owner)?.dimension != it.sourceDimension) }
        for (player in online) {
            onlineClock[player] = now(player) + 1
            val record = players[player] ?: continue
            record.cooldowns.replaceAll { _, ticks -> (ticks - 1).coerceAtLeast(0) }
            tickCharges(player, record)
            for (resource in definitions.resources.values) {
                val regen = resource.regeneration ?: continue
                val classes = if (resource.scope == ResourceScope.CLASS) record.activeClasses else if (record.activeClasses.isNotEmpty()) setOf("") else emptySet()
                for (classId in classes) {
                    val key = balanceKey(classId, resource)
                    val timer = (record.regenerationTimers[key] ?: regen.everyTicks) - 1
                    if (timer <= 0) {
                        val current = record.resources.getOrPut(key) { resource.initial }
                        record.resources[key] = (current + regen.amount).coerceAtMost(resource.maximum)
                        record.regenerationTimers[key] = regen.everyTicks
                    } else record.regenerationTimers[key] = timer
                }
            }
        }
        for ((key, hold) in chargeHolds.toMap()) {
            if (key.owner !in online || !world.availableTarget(key.owner, key.owner) ||
                players[key.owner]?.activeClasses?.firstOrNull() != key.classId) {
                chargeHolds.remove(key)
                continue
            }
            val maximum = effectiveAbility(key.owner, key.classId, key.grant)?.chargeMaxTicks ?: 0
            if (maximum <= 0) chargeHolds.remove(key)
            else if (now(key.owner) - hold.started >= maximum)
                releaseCharge(key.owner, key.classId, key.grant, hold.target, generation)
        }
        confirmHolds.entries.removeIf { (key, hold) ->
            key.owner !in online || !world.availableTarget(key.owner, key.owner) ||
                players[key.owner]?.activeClasses?.firstOrNull() != key.classId ||
                now(key.owner) - hold.started >= hold.ability.confirmWindowTicks ||
                hold.dimension != world.position(key.owner)?.dimension ||
                effectiveAbility(key.owner, key.classId, key.grant) != hold.ability
        }
        for ((_, window) in recasts.toMap()) if (window.expires <= now(window.scope.owner))
            window.scope.lifetime.cancelled = true
        for (player in online) players[player]?.activeClasses?.firstOrNull()?.let { activatePassives(player, it) }
        barriers.removeIf { !it.lifetime.active || it.expires <= now(it.scope.owner) }
        for (projectile in projectiles.toList()) if (projectile.lifetime.active) try { tickProjectile(projectile) } catch (failure: Exception) { fail(projectile.scope, failure) }
        // Timed contribution expiry precedes owned controllers; membership reconciliation precedes status pulses.
        for (status in statuses.values.toList()) if (status.lifetime.active) try { tickStatus(status, pulse = false) } catch (failure: Exception) { fail(status.scope, failure) }
        for (area in areas.toList()) if (area.lifetime.active) try { tickArea(area) } catch (failure: Exception) { fail(area.scope, failure) }
        for (status in statuses.values.toList()) if (status.lifetime.active) try { tickStatus(status, pulse = true) } catch (failure: Exception) { fail(status.scope, failure) }
        for (timer in timers.values.toList()) if (timer.lifetime.active && timer.due <= now(timer.key.owner)) try { expireTimer(timer) } catch (failure: Exception) { fail(timer.scope, failure) }
        for (wait in eventWaits.toList()) if (wait.lifetime.active && wait.due <= now(wait.scope.owner)) try { timeoutWait(wait) } catch (failure: Exception) { fail(wait.scope, failure) }
        for (parallel in parallels.toList()) if (!parallel.finished && parallel.scope.lifetime.active) try { advanceParallel(parallel) } catch (failure: Exception) { fail(parallel.scope, failure) }
        for (channel in channels.values.toList()) if (channel.scope.lifetime.active) try { advanceChannel(channel) } catch (failure: Exception) { fail(channel.scope, failure) }
        val due = pending.filter { it.due <= now(it.scope.owner) }.take(256)
        pending.removeAll(due.toSet())
        for (task in due) if (task.scope.lifetime.active) try {
            execute(task.scope, task.effects, task.bindings.toMutableMap())
            if (task.repeatsLeft > 1) schedule(task.scope, task.every, task.effects, task.bindings, task.repeatsLeft - 1, task.every)
        } catch (failure: Exception) { fail(task.scope, failure) }
        val dueChains = chains.filter { it.due <= now(it.scope.owner) }.take(256)
        chains.removeAll(dueChains.toSet())
        for (task in dueChains) if (task.scope.lifetime.active) try { advanceChain(task) } catch (failure: Exception) { fail(task.scope, failure) }
        prune(releaseIdle = true)
    }

    private fun execute(scope: Scope, effects: List<Effect>, results: MutableMap<String, Double>) {
        val context = Execution(scope, results)
        for (effect in effects) {
            if (!scope.lifetime.active) return
            if (scope.id in suppressedActivations && scope.originDefinition == scope.ability.id) return
            charge(scope, 1)
            val mechanic = catalog.mechanic(effect)
            if (effect !is Effect.Parallel) for (numeric in mechanic.inputs(effect).values)
                refreshStateReads(scope, results, numeric)
            effect.resultName?.let { name -> mechanic.resultFields.forEach { results.remove("result.$name.$it") } }
            // Target loss skips dependent work. It never reads an earlier target's result or invents zero.
            if (effect !is Effect.Parallel && mechanic.inputs(effect).values.any { it.unavailable(results) }) continue
            val outcome = mechanic.run(effect, context)
            if (outcome != null) {
                require(outcome.keys.all { it in mechanic.resultFields } && outcome.values.all { it.isFinite() }) { "invalid mechanic result" }
                effect.resultName?.let { name -> for ((field, value) in outcome) results["result.$name.$field"] = value }
            }
        }
    }

    private fun refreshStateReads(scope: Scope, results: MutableMap<String, Double>, numeric: Numeric) {
        if (numeric !is Numeric.Expression) return
        for (variable in Expression.variables(numeric.source).filter { it.startsWith("state.") }) {
            val segments = variable.split('.')
            val state = definitions.states["${segments[1]}:${segments[2]}"] ?: error("state is unavailable")
            val value = stateNumber(readState(scope, state, segments[3])) ?: error("state field is not numeric")
            results[variable] = value
        }
    }

    private fun advanceParallel(controller: ParallelController) {
        if (controller.finished || !controller.scope.lifetime.active) return
        for ((index, branch) in controller.effect.branches.withIndex()) {
            if (index in controller.completed || controller.due[index] > now(controller.scope.owner)) continue
            val bindings = controller.bindings.toMutableMap()
            val branchScope = controller.scope.copy(lifetime = controller.branchLifetimes[index])
            execute(branchScope, branch.effects, bindings)
            controller.completed += index
            if (branch.successWhen == null || Execution(branchScope, bindings).test(branch.successWhen) == true) {
                controller.succeeded += index
                if (controller.effect.join == ParallelJoin.FIRST_SUCCESS) {
                    controller.branchLifetimes.forEachIndexed { other, lifetime -> if (other != index) lifetime.cancelled = true }
                    finishParallel(controller, true, index)
                    return
                }
            }
        }
        if (controller.completed.size == controller.effect.branches.size)
            finishParallel(controller, controller.effect.join == ParallelJoin.ALL &&
                controller.succeeded.size == controller.effect.branches.size, -1)
    }

    private fun finishParallel(controller: ParallelController, succeeded: Boolean, winner: Int) {
        controller.finished = true
        val bindings = controller.bindings.toMutableMap()
        bindings["parallel.index"] = winner.toDouble()
        bindings["parallel.completed"] = controller.succeeded.size.toDouble()
        execute(controller.scope, if (succeeded) controller.effect.then else controller.effect.failed, bindings)
    }

    private fun advanceChannel(channel: ChannelController) {
        val root = channel.scope
        val time = now(root.owner)
        if (channel.expires != null && time >= channel.expires) {
            root.lifetime.cancelled = true
            return
        }
        if (time < channel.due) return
        if (!payPeriodicCosts(root)) {
            root.lifetime.cancelled = true
            return
        }
        channel.due = time + root.ability.channelEveryTicks
        execute(root.copy(lifetime = Lifetime(root.lifetime), budget = Budget()), root.ability.effects, linkedMapOf())
    }

    private fun payPeriodicCosts(scope: Scope): Boolean {
        val record = record(scope.owner)
        val payments = mutableListOf<Pair<String, Double>>()
        for (cost in scope.ability.periodicCosts) {
            val resource = definitions.resources[cost.resource] ?: return false
            val amount = cost.amount.value(emptyMap())
            if (!amount.isFinite() || amount !in 0.0..1_000_000.0) return false
            val key = balanceKey(scope.classId, resource)
            if (balance(record, scope.classId, resource) - payments.filter { it.first == key }.sumOf { it.second } - amount < resource.minimum)
                return false
            payments += key to amount
        }
        for ((key, amount) in payments) record.resources[key] = record.resources.getValue(key) - amount
        return true
    }

    private inner class Execution(val scope: Scope, val results: MutableMap<String, Double>) : EffectExecution {
        private fun target(target: EffectTarget): UUID? {
            val id = if (target == EffectTarget.ACTOR) scope.owner else scope.target ?: return null
            if (target == EffectTarget.TARGET) scope.targetGuard?.let { guard ->
                val view = world.view(scope.owner, id) ?: return null
                if (!world.loaded(guard.frame.position) || !selection.matches(scope.owner, guard.frame, guard.selector, view)) return null
            }
            return id.takeIf {
                if (target == EffectTarget.ACTOR || scope.selected) world.availableTarget(scope.owner, id)
                else world.validTarget(scope.owner, id, scope.ability.targeting.range)
            }
        }
        override fun heal(target: EffectTarget, amount: Numeric): Healing? {
            val id = target(target) ?: return null
            val requested = amount.amount(results)
            val before = world.view(scope.owner, id)
            val restored = world.heal(id, requested).coerceIn(0.0, requested)
            val missingHealth = before?.let { (it.maximumHealth - it.health).coerceAtLeast(0.0) }
            return Healing(restored, (requested - (missingHealth ?: restored)).coerceAtLeast(0.0))
        }
        override fun damage(target: EffectTarget, amount: Numeric, damageType: String): Double? {
            val id = target(target) ?: return null
            return world.damage(scope.owner, id, amount.amount(results), damageType)
        }
        override fun readHealth(target: EffectTarget): Map<String, Double>? {
            val id = target(target) ?: return null
            val view = world.view(scope.owner, id) ?: return null
            require(view.health.isFinite() && view.maximumHealth.isFinite() && view.maximumHealth > 0.0 && view.health in 0.0..view.maximumHealth) { "invalid native health view" }
            return mapOf("health" to view.health, "maximum" to view.maximumHealth,
                "missing" to view.maximumHealth - view.health, "fraction" to view.health / view.maximumHealth)
        }
        override fun shield(effect: Effect.Shield): Map<String, Double>? {
            val id = target(effect.target) ?: return null
            val capacity = effect.capacity.amount(results)
            require(capacity > 0.0) { "barrier capacity must be positive" }
            require(barriers.size < 1024 && barriers.count { it.scope.owner == scope.owner } < 64 &&
                barriers.count { it.target == id } < 64) { "barrier limit reached" }
            barriers += Barrier(id, scope, Lifetime(scope.lifetime), capacity, now(scope.owner) + effect.durationTicks,
                effect.priority, effect.damageType, effect.depleted, barrierOrder++)
            return mapOf("capacity" to capacity)
        }
        override fun resource(id: String, amount: Numeric, spend: Boolean) = changeResource(record(scope.owner), scope.classId, id, amount.amount(results) * if (spend) -1 else 1)
        override fun setResource(id: String, value: Numeric?): Map<String, Double> {
            val definition = definitions.resources[id] ?: error("resource is unavailable")
            val record = record(scope.owner)
            val key = balanceKey(scope.classId, definition)
            val requested = value?.value(results) ?: definition.initial
            require(requested.isFinite() && requested in -1_000_000_000.0..1_000_000_000.0) { "resource value is out of range" }
            val previous = balance(record, scope.classId, definition)
            val current = requested.coerceIn(definition.minimum, definition.maximum)
            record.resources[key] = current
            return mapOf("previous" to previous, "current" to current, "changed" to kotlin.math.abs(current - previous))
        }
        override fun delay(ticks: Int, effects: List<Effect>, count: Int, every: Int) = schedule(scope, ticks, effects, results, count, every)
        override fun sequence(effects: List<Effect>) = execute(scope, effects, results)
        override fun parallel(effect: Effect.Parallel) {
            require(parallels.size < 256 && parallels.count { it.scope.owner == scope.owner } < 32) { "parallel limit reached" }
            requireCapacity(scope)
            val controller = ParallelController(scope, effect, results.toMap(),
                effect.branches.map { now(scope.owner) + it.afterTicks },
                effect.branches.map { Lifetime(scope.lifetime) })
            parallels += controller
            advanceParallel(controller)
        }
        override fun branch(condition: Condition, onTrue: List<Effect>, onFalse: List<Effect>) {
            val matches = condition(condition) ?: return
            execute(scope, if (matches) onTrue else onFalse, results)
        }

        override fun choose(effect: Effect.Choose): Map<String, Double> {
            val total = effect.options.sumOf(WeightedBranch::weight)
            val selected = minOf((sample() * total).toInt(), total - 1)
            var cumulative = 0
            val index = effect.options.indexOfFirst { option ->
                cumulative += option.weight
                selected < cumulative
            }
            execute(scope, effect.options[index].effects, results)
            return mapOf("index" to index.toDouble())
        }

        private fun sample() = world.roll(scope.owner).also { require(it.isFinite() && it >= 0.0 && it < 1.0) { "invalid chance sample" } }

        private fun condition(condition: Condition): Boolean? = when (condition) {
            is Condition.HasStatus -> target(condition.target)?.let { matchingStatuses(scope, it, condition.filter).isNotEmpty() }
            is Condition.StateIs -> definitions.states[condition.state]?.let { definition ->
                definition.fields[condition.field]?.let { readState(scope, definition, condition.field) == condition.value }
            }
            is Condition.ResourceAtLeast -> {
                val resource = definitions.resources[condition.resource] ?: error("resource is unavailable")
                refreshStateReads(scope, results, condition.amount)
                balance(record(scope.owner), scope.classId, resource) >= condition.amount.value(results)
            }
            is Condition.Compare -> {
                refreshStateReads(scope, results, condition.left)
                refreshStateReads(scope, results, condition.right)
                val left = condition.left.value(results)
                val right = condition.right.value(results)
                when (condition.operator) { "lt" -> left < right; "lte" -> left <= right; "eq" -> left == right; "gte" -> left >= right; "gt" -> left > right; else -> error("invalid comparison") }
            }
            is Condition.All -> condition.conditions.map(::condition).let { values -> if (values.any { it == null }) null else values.all { it == true } }
            is Condition.Any -> condition.conditions.map(::condition).let { values -> if (values.any { it == null }) null else values.any { it == true } }
            is Condition.Not -> condition(condition.condition)?.not()
            is Condition.Chance -> sample() < condition.probability
        }
        fun test(condition: Condition): Boolean? = condition(condition)
        override fun forEach(effect: Effect.ForEach) {
            val origin = spatial(scope, effect.origin) ?: return
            val frame = Frame(origin, world.direction(scope.owner))
            val targets = selection.select(scope.owner, frame, effect.selector, charge = { charge(scope, it) })
            for ((index, id) in targets.withIndex()) execute(scope.copy(target = id, selected = true, targetGuard = TargetGuard(frame, effect.selector)), effect.effects, (results + ("selection.index" to index.toDouble())).toMutableMap())
        }
        override fun chain(effect: Effect.Chain) {
            val first = scope.target ?: return
            val position = world.position(first) ?: return
            val actorPosition = world.position(scope.owner) ?: return
            val initial = effect.selector.copy(shape = Shape.Sphere(scope.ability.targeting.range))
            val view = world.view(scope.owner, first) ?: return
            if (!selection.matches(scope.owner, Frame(actorPosition), initial, view)) return
            hitChain(scope.copy(targetGuard = TargetGuard(Frame(actorPosition), initial)), effect, first, position, emptySet(), 0, results.toMap())
        }
        override fun area(effect: Effect.CreateArea) = createArea(scope, effect)
        override fun status(effect: Effect.ApplyStatus) {
            val id = target(effect.target) ?: return
            applyStatus(scope, effect.status, id, "${scope.originDefinition}/${effect.applicationId}")
        }
        override fun dispel(effect: Effect.Dispel): Map<String, Double>? {
            val id = target(effect.target) ?: return null
            val removed = matchingStatuses(scope, id, effect.filter).take(effect.count)
            val stacks = removed.sumOf { it.stackExpiries.size }
            // ASVS 2.3.1, 2.3.4: select and cancel once on the server thread, after charging all query work.
            removed.forEach { it.lifetime.cancelled = true }
            prune()
            return mapOf("contributions_removed" to removed.size.toDouble(), "stacks_removed" to stacks.toDouble())
        }
        override fun consumeStatus(effect: Effect.ConsumeStatus): Map<String, Double>? {
            val id = target(effect.target) ?: return null
            var remaining = effect.count
            val changes = mutableListOf<Pair<Contribution, Int>>()
            for (status in matchingStatuses(scope, id, effect.filter)) {
                if (remaining == 0) break
                val count = minOf(remaining, status.stackExpiries.size)
                changes += status to count
                remaining -= count
            }
            val fullyRemoved = changes.count { (status, count) -> count == status.stackExpiries.size }
            val partials = changes.filter { (status, count) -> count < status.stackExpiries.size }.map { it.first }
            val callbackWork = partials.sumOf { estimate(it.definition.stacksChanged) }
            // ASVS 2.3.2, 2.3.4: reserve all known callback work before consuming a stack.
            require(callbackWork <= scope.budget.remaining && callbackWork <= workThisTick &&
                (ownerWorkThisTick[scope.owner] ?: 0) + callbackWork <= 2048) { "ability work limit exceeded" }
            for ((status, count) in changes) {
                if (count == status.stackExpiries.size) status.lifetime.cancelled = true
                else repeat(count) {
                    val oldest = status.stackExpiries.indices.minBy { status.stackExpiries[it] }
                    status.stackExpiries.removeAt(oldest)
                }
            }
            prune()
            for (status in partials) if (status.lifetime.active) execute(statusScope(status, scope.budget), status.definition.stacksChanged, statusBindings(status))
            return mapOf("stacks_removed" to (effect.count - remaining).toDouble(),
                "contributions_removed" to fullyRemoved.toDouble())
        }
        override fun readStatus(effect: Effect.ReadStatus): Map<String, Double>? {
            val id = target(effect.target) ?: return null
            val matches = matchingStatuses(scope, id, effect.filter)
            return mapOf("contributions" to matches.size.toDouble(), "stacks" to matches.sumOf { it.stackExpiries.size }.toDouble())
        }
        override fun setTimer(effect: Effect.SetTimer) {
            val recipient = target(effect.target) ?: return
            val key = timerKey(scope, effect.name, recipient)
            // ASVS 2.2.3, 2.3.2: check the finite expiry pulse before allocating a controller.
            require(estimate(effect.expired) <= 1024) { "timer expiry work limit exceeded" }
            val existing = timers[key]?.takeIf { it.lifetime.active }
            if (existing == null) require(timers.size < 1024 && timers.values.count { it.key.owner == scope.owner } < 64) { "timer limit reached" }
            if (scopes[scope.id] == null) return
            // ASVS 2.3.1, 15.4.2: replacement and insertion share the server tick turn.
            existing?.lifetime?.cancelled = true
            timers[key] = Timer(key, scope, Lifetime(scope.lifetime), now(scope.owner) + effect.ticks, effect.expired, results.toMap())
            prune()
        }
        override fun cancelTimer(effect: Effect.CancelTimer): Map<String, Double>? {
            val recipient = target(effect.target) ?: return null
            val key = timerKey(scope, effect.name, recipient)
            val timer = timers.remove(key)?.takeIf { it.lifetime.active }
            timer?.lifetime?.cancelled = true
            prune()
            return mapOf("cancelled" to if (timer != null) 1.0 else 0.0)
        }
        override fun readTimer(effect: Effect.ReadTimer): Map<String, Double>? {
            val recipient = target(effect.target) ?: return null
            val key = timerKey(scope, effect.name, recipient)
            val remaining = timers[key]?.takeIf { it.lifetime.active }?.let { (it.due - now(scope.owner)).coerceAtLeast(0) } ?: 0L
            return mapOf("active" to if (remaining > 0) 1.0 else 0.0, "remaining_ticks" to remaining.toDouble())
        }
        override fun restoreCharge(effect: Effect.RestoreCharge): Map<String, Double> {
            val targetGrant = if (effect.grant == "self") scope.grant else effect.grant
            val state = chargeState(scope.owner, scope.classId, targetGrant)
                ?: return mapOf("restored" to 0.0, "available_charges" to 0.0)
            val restored = minOf(effect.count, state.capacity - state.available)
            if (restored > 0) {
                // ASVS 2.3.1, 2.3.4: each restored use consumes a missing recharge obligation once.
                state.available += restored
                if (state.mode == RechargeMode.PARALLEL) repeat(restored) { if (state.timers.isNotEmpty()) state.timers.removeAt(state.timers.lastIndex) }
                if (state.available == state.capacity) state.timers.clear()
            }
            return mapOf("restored" to restored.toDouble(), "available_charges" to state.available.toDouble())
        }
        override fun reduceCooldown(effect: Effect.ReduceCooldown): Map<String, Double> {
            val record = record(scope.owner)
            val targetGrant = if (effect.grant == "self") scope.grant else effect.grant
            val key = "${scope.classId}|$targetGrant"
            val remaining = ((record.cooldowns[key] ?: 0) - effect.ticks).coerceAtLeast(0)
            record.cooldowns[key] = remaining
            return mapOf("remaining_ticks" to remaining.toDouble())
        }
        override fun reduceRecharge(effect: Effect.ReduceRecharge): Map<String, Double> {
            val targetGrant = if (effect.grant == "self") scope.grant else effect.grant
            val definition = effectiveAbility(scope.owner, scope.classId, targetGrant)?.charges
            val state = chargeState(scope.owner, scope.classId, targetGrant)
            if (definition == null || state == null) return mapOf("restored" to 0.0, "available_charges" to 0.0, "next_recharge_ticks" to 0.0)
            val indices = when (effect.selection) {
                RechargeSelection.EARLIEST -> state.timers.indices.take(1)
                RechargeSelection.LATEST -> state.timers.indices.reversed().take(1)
                RechargeSelection.ALL -> state.timers.indices.toList()
            }
            // ASVS 2.3.1, 2.3.4: edit each selected timer once, then reconcile restored uses atomically.
            for (index in indices) state.timers[index] = (state.timers[index] - effect.ticks).coerceAtLeast(0)
            val restored = state.timers.count { it == 0 }
            state.timers.removeIf { it == 0 }
            state.available = (state.available + restored).coerceAtMost(state.capacity)
            if (state.mode == RechargeMode.SEQUENTIAL && state.available < state.capacity && state.timers.isEmpty()) state.timers += definition.rechargeTicks
            return mapOf("restored" to restored.toDouble(), "available_charges" to state.available.toDouble(),
                "next_recharge_ticks" to (state.timers.minOrNull() ?: 0).toDouble())
        }
        override fun reduceGroupCooldown(effect: Effect.ReduceGroupCooldown): Map<String, Double> {
            val record = record(scope.owner)
            val key = "group|${effect.group}"
            val remaining = ((record.cooldowns[key] ?: 0) - effect.ticks).coerceAtLeast(0)
            record.cooldowns[key] = remaining
            return mapOf("remaining_ticks" to remaining.toDouble())
        }
        override fun reduceGlobalCooldown(effect: Effect.ReduceGlobalCooldown): Map<String, Double> {
            val record = record(scope.owner)
            val remaining = ((record.cooldowns["global"] ?: 0) - effect.ticks).coerceAtLeast(0)
            record.cooldowns["global"] = remaining
            return mapOf("remaining_ticks" to remaining.toDouble())
        }
        override fun setState(effect: Effect.SetState): Map<String, Double> {
            val (definition, field) = stateField(effect.state, effect.field)
            val value = when (val input = effect.value) {
                is StateInput.Number -> StateValue.Number(input.value.value(results))
                is StateInput.Flag -> StateValue.Flag(input.value)
                is StateInput.Mode -> StateValue.Mode(input.value)
            }
            return writeState(scope, definition, effect.field, field, value)
        }
        override fun addState(effect: Effect.AddState): Map<String, Double> {
            val (definition, field) = stateField(effect.state, effect.field)
            val previous = readState(scope, definition, effect.field) as? StateValue.Number ?: error("numeric state required")
            val amount = effect.amount.value(results)
            require(amount.isFinite() && amount in -1_000_000.0..1_000_000.0) { "state addition is out of range" }
            return writeState(scope, definition, effect.field, field, StateValue.Number(previous.value + amount))
        }
        override fun resetState(effect: Effect.ResetState): Map<String, Double> {
            val (definition, field) = stateField(effect.state, effect.field)
            return writeState(scope, definition, effect.field, field, field.initial)
        }
        override fun readState(effect: Effect.ReadState): Map<String, Double> {
            val (definition, _) = stateField(effect.state, effect.field)
            return stateNumber(readState(scope, definition, effect.field))?.let { mapOf("value" to it) } ?: emptyMap()
        }
        override fun readStatusState(effect: Effect.ReadStatusState): Map<String, Double>? {
            val id = target(effect.target) ?: return null
            val (definition, _) = stateField(effect.state, effect.field)
            val contributions = matchingStatuses(scope, id, effect.filter).filter { it.definition.state == effect.state }
            val value = contributions.sumOf { contribution ->
                stateNumber(readState(statusScope(contribution, scope.budget), definition, effect.field))
                    ?: error("status state field is not numeric")
            }
            return mapOf("value" to value, "contributions" to contributions.size.toDouble())
        }
        override fun writeStatusState(effect: Effect.WriteStatusState): Map<String, Double>? {
            val id = target(effect.target) ?: return null
            val (definition, field) = stateField(effect.state, effect.field)
            val matches = matchingStatuses(scope, id, effect.filter).filter { it.definition.state == effect.state }
            val setValue = effect.value?.let { input -> when (input) {
                is StateInput.Number -> StateValue.Number(input.value.value(results))
                is StateInput.Flag -> StateValue.Flag(input.value)
                is StateInput.Mode -> StateValue.Mode(input.value)
            } }
            val amount = effect.amount?.value(results)
            if (amount != null) require(amount.isFinite() && amount in -1_000_000.0..1_000_000.0) { "status state addition is out of range" }
            var previous = 0.0
            var current = 0.0
            for (contribution in matches) {
                val local = statusScope(contribution, scope.budget)
                val old = readState(local, definition, effect.field)
                val raw = when (effect.operation) {
                    StatusStateOperation.SET -> setValue ?: error("status state value is missing")
                    StatusStateOperation.ADD -> StateValue.Number((old as? StateValue.Number ?: error("numeric status state required")).value +
                        (amount ?: error("status state amount is missing")))
                    StatusStateOperation.RESET -> field.initial
                }
                val outcome = writeState(local, definition, effect.field, field, raw)
                previous += outcome["previous"] ?: 0.0
                current += outcome["current"] ?: 0.0
            }
            return if (field.type == StateType.ENUM) mapOf("contributions" to matches.size.toDouble())
                else mapOf("contributions" to matches.size.toDouble(), "previous" to previous, "current" to current)
        }
        override fun launchProjectile(effect: Effect.LaunchProjectile): Map<String, Double>? {
            val definition = definitions.projectiles[effect.projectile] ?: error("projectile is unavailable")
            val start = world.projectileOrigin(scope.owner) ?: return null
            if (!world.loaded(start)) return null
            val toward = when (effect.direction) {
                ProjectileDirection.AIM -> world.direction(scope.owner)
                ProjectileDirection.TARGET -> scope.target?.takeIf { world.availableTarget(scope.owner, it) }?.let {
                    world.position(it)?.takeIf { point -> point.dimension == start.dimension }?.value?.minus(start.value)
                } ?: return null
                ProjectileDirection.GROUND -> scope.ground?.takeIf { it.dimension == start.dimension }?.value?.minus(start.value) ?: return null
            }
            if (!toward.finite() || toward.lengthSquared() < 1e-12) return null
            val parameters = linkedMapOf<String, Double>()
            for ((name, parameter) in definition.parameters) {
                val value = effect.arguments[name]?.value(results) ?: parameter.default ?: error("required projectile parameter is missing")
                require(value.isFinite() && value in parameter.minimum..parameter.maximum) { "projectile parameter $name is outside its declared bounds" }
                parameters["params.$name"] = value
            }
            require(projectiles.size < 512 && projectiles.count { it.scope.owner == scope.owner } < 32) { "projectile limit reached" }
            require(projectileHandles.size < 2048 && nextProjectileHandle < 9_007_199_254_740_992L) { "projectile handle limit reached" }
            val handle = nextProjectileHandle++
            projectileHandles[handle] = ProjectileHandle(scope)
            projectiles += Projectile(scope, definition, Lifetime(scope.lifetime), start, toward.normalized() * (definition.speed / 20.0),
                now(scope.owner) + definition.lifetimeTicks, handle, homingTarget = if (definition.homingDegreesPerTick != null) scope.target else null,
                bindings = parameters)
            return mapOf("launched" to 1.0, "handle" to handle.toDouble())
        }
        override fun waitFor(effect: Effect.WaitFor) {
            val value = results[effect.handle] ?: return
            require(value.isFinite() && value >= 1.0 && value <= 9_007_199_254_740_991.0 && value % 1.0 == 0.0) { "invalid projectile handle" }
            val handleId = value.toLong()
            val handle = projectileHandles[handleId] ?: return
            require(handle.scope.id == scope.id) { "projectile handle belongs to another cast" }
            val receipt = handle.receipts[effect.event]
            if (receipt != null) {
                execute(scope.copy(target = receipt.target, ground = receipt.position, selected = receipt.target != null, targetGuard = null),
                    effect.matched, results.toMutableMap())
                return
            }
            require(eventWaits.size < 1024 && eventWaits.count { it.scope.owner == scope.owner } < 64) { "event wait limit reached" }
            requireCapacity(scope)
            eventWaits += EventWait(scope, Lifetime(scope.lifetime), handleId, effect.event, now(scope.owner) + effect.timeoutTicks,
                effect.matched, effect.timedOut, results.toMap())
        }
    }

    private fun stateField(id: String, name: String): Pair<StateDef, StateField> {
        val definition = definitions.states[id] ?: error("state is unavailable")
        return definition to (definition.fields[name] ?: error("state field is unavailable"))
    }

    private fun stateKey(scope: Scope, definition: StateDef, field: String): String =
        "${if (definition.scope == StateScope.PLAYER) "player" else scope.classId}|${definition.id}|$field"

    private fun stateMap(scope: Scope, definition: StateDef): MutableMap<String, StateValue> = when (definition.scope) {
        StateScope.ACTIVATION -> scope.localState
        StateScope.STATUS -> scope.statusState ?: error("status state is unavailable outside a status contribution")
        StateScope.CLASS, StateScope.PLAYER -> if (definition.persistent) record(scope.owner).persistentStates
            else transientStates.getOrPut(scope.owner) { linkedMapOf() }
    }

    private fun readState(scope: Scope, definition: StateDef, field: String): StateValue {
        val key = stateKey(scope, definition, field)
        val declared = definition.fields.getValue(field)
        return normalizeState(stateMap(scope, definition).getOrPut(key) { declared.initial }, declared) ?: declared.initial
    }

    private fun writeState(scope: Scope, definition: StateDef, name: String, field: StateField, raw: StateValue): Map<String, Double> {
        val value = normalizeState(raw, field) ?: error("state value does not match declaration")
        val old = readState(scope, definition, name)
        stateMap(scope, definition)[stateKey(scope, definition, name)] = value
        val previous = stateNumber(old)
        val current = stateNumber(value)
        return if (previous != null && current != null) mapOf("previous" to previous, "current" to current) else emptyMap()
    }

    private fun stateNumber(value: StateValue): Double? = when (value) {
        is StateValue.Number -> value.value
        is StateValue.Flag -> if (value.value) 1.0 else 0.0
        is StateValue.Mode -> null
    }

    private fun normalizeState(value: StateValue, field: StateField): StateValue? = when (field.type) {
        StateType.BOOLEAN -> value as? StateValue.Flag
        StateType.ENUM -> (value as? StateValue.Mode)?.takeIf { it.value in field.choices }
        StateType.NUMBER, StateType.INTEGER -> (value as? StateValue.Number)?.takeIf {
            it.value.isFinite() && it.value in -1_000_000_000.0..1_000_000_000.0 &&
                (field.type != StateType.INTEGER || it.value % 1.0 == 0.0)
        }?.let { StateValue.Number(it.value.coerceIn(field.minimum!!, field.maximum!!)) }
    }

    private fun reconcileStates(values: MutableMap<String, StateValue>) {
        for ((key, value) in values.toMap()) {
            val parts = key.split('|')
            if (parts.size != 3) continue
            val field = definitions.states[parts[1]]?.fields?.get(parts[2]) ?: continue
            normalizeState(value, field)?.let { values[key] = it }
        }
    }

    private fun tickProjectile(projectile: Projectile) {
        val source = projectile.scope
        val pulse = source.copy(budget = Budget())
        charge(pulse, 1)
        val from = projectile.position
        val homing = projectile.definition.homingDegreesPerTick
        if (homing != null) projectile.homingTarget?.takeIf { world.availableTarget(source.owner, it) }?.let { target ->
            world.position(target)?.takeIf { it.dimension == from.dimension }?.let { targetPosition ->
                val direction = targetPosition.value - from.value
                if (direction.finite() && direction.lengthSquared() > 1e-12)
                    projectile.velocity = steer(projectile.velocity, direction, Math.toRadians(homing))
            }
        }
        val next = Position(from.dimension, from.value + projectile.velocity)
        val excluded = projectile.hits.filter { (_, record) ->
            record.first >= projectile.definition.repeatHits || now(source.owner) < record.second
        }.keys
        val contact = world.projectileStep(source.owner, from, next, projectile.definition.entities, excluded)
        when (contact) {
            ProjectileContact.Unavailable -> projectile.lifetime.cancelled = true
            is ProjectileContact.Miss -> {
                if (contact.position.dimension != from.dimension || !world.loaded(contact.position)) {
                    projectile.lifetime.cancelled = true
                    return
                }
                projectile.position = contact.position
                world.projectileVisible(contact.position)
                projectile.velocity = projectile.velocity + Vec(0.0, -projectile.definition.gravity / 400.0, 0.0)
            }
            is ProjectileContact.Entity -> {
                if (contact.position.dimension != from.dimension || !world.availableTarget(source.owner, contact.target) || contact.target in excluded) {
                    projectile.lifetime.cancelled = true
                    return
                }
                val previous = projectile.hits[contact.target]?.first ?: 0
                projectile.hits[contact.target] = previous + 1 to (now(source.owner) + projectile.definition.repeatIntervalTicks)
                if (projectile.remainingPierces > 0) {
                    projectile.remainingPierces--
                    projectile.position = Position(from.dimension, contact.position.value + projectile.velocity.normalized() * 0.01)
                    projectile.velocity = projectile.velocity + Vec(0.0, -projectile.definition.gravity / 400.0, 0.0)
                } else projectile.lifetime.cancelled = true
                if (source.lifetime.active)
                    execute(pulse.copy(target = contact.target, ground = contact.position, selected = true, targetGuard = null),
                        projectile.definition.entityHit, projectile.bindings.toMutableMap())
                emitProjectileEvent(projectile, ProjectileEvent.ENTITY_HIT, ProjectileReceipt(contact.target, contact.position))
            }
            is ProjectileContact.Block -> {
                if (contact.position.dimension != from.dimension || !world.loaded(contact.position) || !contact.normal.finite() || contact.normal.lengthSquared() < 0.5) {
                    projectile.lifetime.cancelled = true
                    return
                }
                if (projectile.remainingBounces > 0) {
                    projectile.remainingBounces--
                    val normal = contact.normal.normalized()
                    projectile.velocity = projectile.velocity - normal * (2.0 * projectile.velocity.dot(normal))
                    projectile.position = Position(from.dimension, contact.position.value + normal * 0.01)
                } else projectile.lifetime.cancelled = true
                if (source.lifetime.active)
                    execute(pulse.copy(target = null, ground = contact.position, selected = false, targetGuard = null),
                        projectile.definition.blockHit, projectile.bindings.toMutableMap())
                emitProjectileEvent(projectile, ProjectileEvent.BLOCK_HIT, ProjectileReceipt(null, contact.position))
            }
        }
        if (projectile.lifetime.active && now(source.owner) >= projectile.expires) {
            projectile.lifetime.cancelled = true
            execute(pulse.copy(target = null, ground = projectile.position, selected = false, targetGuard = null),
                projectile.definition.expiry, projectile.bindings.toMutableMap())
            emitProjectileEvent(projectile, ProjectileEvent.EXPIRY, ProjectileReceipt(null, projectile.position))
        }
    }

    private fun emitProjectileEvent(projectile: Projectile, event: ProjectileEvent, receipt: ProjectileReceipt) {
        projectileHandles[projectile.handle]?.receipts?.putIfAbsent(event, receipt)
        val matching = eventWaits.filter { it.handle == projectile.handle && it.event == event && it.lifetime.active }
        eventWaits.removeAll(matching.toSet())
        for (wait in matching) if (wait.scope.lifetime.active) {
            val callback = wait.scope.copy(budget = Budget(), lifetime = Lifetime(wait.scope.lifetime),
                target = receipt.target, ground = receipt.position, selected = receipt.target != null, targetGuard = null)
            execute(callback, wait.matched, wait.bindings.toMutableMap())
        }
    }

    private fun timeoutWait(wait: EventWait) {
        if (!eventWaits.remove(wait) || !wait.lifetime.active) return
        val callback = wait.scope.copy(budget = Budget(), lifetime = Lifetime(wait.scope.lifetime))
        execute(callback, wait.timedOut, wait.bindings.toMutableMap())
    }

    private fun steer(velocity: Vec, desired: Vec, maximumAngle: Double): Vec {
        val speed = sqrt(velocity.lengthSquared())
        val initial = velocity.normalized()
        val goal = desired.normalized()
        val angle = acos(initial.dot(goal).coerceIn(-1.0, 1.0))
        if (angle <= maximumAngle) return goal * speed
        val axis = initial.cross(goal).let { if (it.lengthSquared() > 1e-12) it.normalized() else
            initial.cross(if (kotlin.math.abs(initial.y) < 0.9) Vec(0.0, 1.0, 0.0) else Vec(1.0, 0.0, 0.0)).normalized() }
        return (initial * cos(maximumAngle) + axis.cross(initial) * sin(maximumAngle)) * speed
    }

    private fun expireTimer(timer: Timer) {
        if (timers[timer.key] !== timer || !timer.lifetime.active) return
        timers.remove(timer.key)
        timer.lifetime.cancelled = true
        // Ordinary expiry gets a new source-owned lifetime. Cancelled timers never run this body.
        val root = scopes[timer.scope.id] ?: return
        val callback = timer.scope.copy(lifetime = Lifetime(root.lifetime), budget = Budget(), target = timer.key.target, selected = true)
        execute(callback, timer.expired, timer.bindings.toMutableMap())
    }

    private fun timerKey(scope: Scope, name: String, target: UUID): TimerKey {
        // A grant timer persists across casts. Controller timers belong to their status or area instance.
        val source = scope.controllerId?.toString() ?: "grant"
        return TimerKey(scope.owner, scope.classId, scope.grant, source, name, target)
    }

    private fun matchingStatuses(scope: Scope, target: UUID, filter: StatusFilter): List<Contribution> {
        val candidates = statusesByTarget[target].orEmpty()
        // ASVS 2.3.2: the recipient index bounds each query to its 64 contribution slots.
        charge(scope, candidates.size)
        return candidates.filter { status ->
            status.lifetime.active && (filter.status == null || status.definition.id == filter.status) &&
                status.definition.tags.containsAll(filter.tags) && when (filter.source) {
                    StatusSource.ANY -> true
                    StatusSource.ACTOR -> status.key.owner == scope.owner
                    StatusSource.GRANT -> status.key.owner == scope.owner && status.key.classId == scope.classId && status.key.grant == scope.grant
                }
        }
    }

    private fun hitChain(scope: Scope, effect: Effect.Chain, target: UUID, position: Position, visited: Set<UUID>, previousHits: Int, bindings: Map<String, Double>) {
        execute(scope.copy(target = target, selected = true), effect.effects, (bindings + mapOf("chain.index" to previousHits.toDouble(), "chain.hit_count" to previousHits.toDouble())).toMutableMap())
        if (!scope.lifetime.active || previousHits + 1 >= effect.maxTargets) return
        val task = ChainTask(scope, now(scope.owner) + effect.delayTicks, effect, position, target, visited + target, previousHits + 1, bindings)
        if (effect.delayTicks > 0) {
            requireCapacity(scope)
            chains += task
        } else advanceChain(task)
    }

    private fun advanceChain(task: ChainTask) {
        if (!world.loaded(task.origin)) return
        val excluded = if (task.effect.revisit) setOf(task.last) else task.visited
        val targets = selection.select(task.scope.owner, Frame(task.origin), task.effect.selector.copy(limit = 1), excluded, charge = { charge(task.scope, it) })
        val target = targets.firstOrNull() ?: return
        val position = world.position(target) ?: return
        hitChain(task.scope.copy(targetGuard = TargetGuard(Frame(task.origin), task.effect.selector)), task.effect, target, position, task.visited, task.hits, task.bindings)
    }

    private fun createArea(scope: Scope, effect: Effect.CreateArea) {
        val definition = definitions.areas[effect.area] ?: error("area is unavailable")
        require(areas.size < 512 && areas.count { it.scope.owner == scope.owner } < 32) { "area limit reached" }
        val attached = (effect.anchor as? Anchor.Attached)?.let { if (it.entity == EffectTarget.ACTOR) scope.owner else scope.target }
        val position = when (val anchor = effect.anchor) {
            is Anchor.Fixed -> spatial(scope, anchor.position)
            is Anchor.Attached -> attached?.let(world::position)
        } ?: return
        if (!world.loaded(position)) return
        val time = now(scope.owner)
        val area = Area(UUID.randomUUID(), scope, definition, Lifetime(scope.lifetime), if (attached == null) Frame(position, world.direction(scope.owner)) else null, attached,
            definition.durationTicks?.let { time + it } ?: Long.MAX_VALUE, time + definition.sampleTicks, time + definition.periodicTicks)
        scope.dependencies[definition.id] = definition
        areas += area
        reconcileArea(area, Frame(position, world.direction(attached ?: scope.owner)), scope.budget)
    }

    private fun frame(area: Area): Frame? {
        val fixed = area.fixed
        if (fixed != null) return fixed.takeIf { world.loaded(it.position) }
        val anchor = area.attached ?: return null
        if (!world.availableTarget(area.scope.owner, anchor)) return null
        val position = world.position(anchor) ?: return null
        return Frame(position, world.direction(anchor)).takeIf { world.loaded(it.position) }
    }

    private fun tickArea(area: Area) {
        val frame = frame(area) ?: run { area.lifetime.cancelled = true; prune(); return }
        val time = now(area.scope.owner)
        if (time >= area.expires) {
            area.lifetime.cancelled = true
            prune()
            execute(area.scope.copy(target = null, ground = frame.position, targetGuard = null, budget = Budget(), originDefinition = area.definition.id, controllerId = area.id), area.definition.expired, linkedMapOf())
            return
        }
        val budget = Budget()
        // Reconcile before pulses too, so departing members cannot receive a late pulse.
        if (time >= area.nextSample || (area.definition.periodicTicks > 0 && time >= area.nextPulse)) {
            reconcileArea(area, frame, budget)
            area.nextSample = time + area.definition.sampleTicks
        }
        if (area.definition.periodicTicks > 0 && time >= area.nextPulse) {
            area.nextPulse = time + area.definition.periodicTicks
            for ((target, lifetime) in area.members.toMap()) execute(area.scope.copy(target = target, ground = frame.position, selected = true, budget = budget, lifetime = lifetime, originDefinition = area.definition.id, controllerId = area.id, targetGuard = TargetGuard(frame, area.definition.selector)), area.definition.periodic, linkedMapOf())
        }
    }

    private fun reconcileArea(area: Area, frame: Frame, budget: Budget) {
        val pulseScope = area.scope.copy(ground = frame.position, budget = budget, lifetime = area.lifetime, selected = true, targetGuard = null, originDefinition = area.definition.id, controllerId = area.id)
        val targets = selection.select(area.scope.owner, frame, area.definition.selector, charge = { charge(pulseScope, it) }).toSet()
        for (old in area.members.keys.toList()) if (old !in targets) {
            area.members.remove(old)?.cancelled = true
            prune()
            execute(pulseScope.copy(target = old), area.definition.exit, linkedMapOf())
        }
        for (target in targets) if (target !in area.members && area.lifetime.active) {
            val lifetime = Lifetime(area.lifetime)
            area.members[target] = lifetime
            for (status in area.definition.buffs) {
                charge(pulseScope, 1)
                applyStatus(pulseScope.copy(target = target, lifetime = lifetime), status, target, "${area.definition.id}/buffs/$status", lifetime)
            }
            execute(pulseScope.copy(target = target, lifetime = lifetime, targetGuard = TargetGuard(frame, area.definition.selector)), area.definition.enter, linkedMapOf())
        }
    }

    private fun applyStatus(scope: Scope, id: String, target: UUID, application: String, membership: Lifetime? = null) {
        if (!scope.lifetime.active) return
        val definition = definitions.statuses[id] ?: error("status is unavailable")
        // ASVS 2.3.1: immunity is decided from server-owned active contributions before a control source can refresh or attach.
        if (definition.controlCategories.isNotEmpty() && statusesByTarget[target].orEmpty().any { active ->
                active.lifetime.active && active.definition.immunities.any { it in definition.controlCategories }
            }) return
        val key = StatusKey(scope.owner, scope.classId, scope.grant, application, target, membership)
        val time = now(scope.owner)
        val existing = statuses[key]?.takeIf { it.lifetime.active }
        if (existing != null) {
            val before = existing.stackExpiries.size
            val maximum = definition.stacks?.maximum ?: 1
            val expiry = time + definition.durationTicks
            if (definition.stacks?.duration != StackDuration.PER_STACK) {
                if (before < maximum) existing.stackExpiries += expiry
                existing.stackExpiries.replaceAll { expiry }
            } else if (before < maximum) existing.stackExpiries += expiry
            else {
                val oldest = existing.stackExpiries.indices.minBy { existing.stackExpiries[it] }
                existing.stackExpiries[oldest] = expiry
            }
            enforceImmunity(target, definition.immunities)
            val callbackScope = statusScope(existing, scope.budget)
            execute(callbackScope, definition.refreshed, statusBindings(existing))
            if (before != existing.stackExpiries.size) execute(callbackScope, definition.stacksChanged, statusBindings(existing))
            return
        }
        // ASVS 2.3.2, 2.3.4: allocation and insertion occur together on the server tick thread.
        require(statuses.size < 4096 && statuses.values.count { it.key.owner == scope.owner } < 256 && statusesByTarget[target].orEmpty().size < 64) { "status contribution limit reached" }
        val root = scopes[scope.id] ?: return
        val lifetime = Lifetime(membership ?: root.lifetime)
        val contribution = Contribution(UUID.randomUUID(), key, scope, definition, lifetime, mutableListOf(time + definition.durationTicks), time + definition.periodicTicks)
        root.statusDependencies[id] = definition
        statuses[key] = contribution
        statusesByTarget.getOrPut(target) { mutableListOf() } += contribution
        enforceImmunity(target, definition.immunities)
        syncSpeed(target)
        execute(statusScope(contribution, scope.budget), definition.applied, statusBindings(contribution))
        if (definition.replacements.isNotEmpty()) reconcileReplacements(target)
    }

    private fun reconcileReplacements(owner: UUID) {
        confirmHolds.entries.removeIf { (key, hold) -> key.owner == owner && effectiveAbility(owner, key.classId, key.grant) != hold.ability }
        val protected = statusesByTarget[owner].orEmpty().filter { it.lifetime.active && it.definition.replacements.isNotEmpty() }
            .map { it.scope.id }.toSet()
        val running = pending.map { it.scope.id }.toSet() + chains.map { it.scope.id } + areas.map { it.scope.id } +
            projectiles.map { it.scope.id } + eventWaits.map { it.scope.id } + parallels.map { it.scope.id } +
            toggles.values.map { it.id } + channels.values.map { it.scope.id } + recasts.values.map { it.scope.id } +
            passives.values.map { it.id }
        for (scope in scopes.values.toList()) if (scope.owner == owner && scope.id in running &&
            effectiveAbility(owner, scope.classId, scope.grant) != scope.ability) {
            if (scope.id in protected) cancelActivationWorkPreservingForm(scope.id)
            else scope.lifetime.cancelled = true
        }
        players[owner]?.activeClasses?.firstOrNull()?.let { classId ->
            grantsFor(owner, classId).keys.forEach { grant -> chargeState(owner, classId, grant) }
        }
    }

    private fun cancelActivationWorkPreservingForm(scopeId: UUID) {
        suppressedActivations += scopeId
        pending.removeIf { it.scope.id == scopeId }
        chains.removeIf { it.scope.id == scopeId }
        toggles.entries.removeIf { it.value.id == scopeId }
        channels.entries.removeIf { it.value.scope.id == scopeId }
        recasts.entries.removeIf { it.value.scope.id == scopeId }
        passives.entries.removeIf { it.value.id == scopeId }
        parallels.filter { it.scope.id == scopeId }.forEach { controller ->
            controller.finished = true
            controller.branchLifetimes.forEach { it.cancelled = true }
        }
        areas.filter { it.scope.id == scopeId }.forEach { it.lifetime.cancelled = true }
        projectiles.filter { it.scope.id == scopeId }.forEach { it.lifetime.cancelled = true }
        eventWaits.filter { it.scope.id == scopeId }.forEach { it.lifetime.cancelled = true }
        timers.values.filter { it.scope.id == scopeId }.forEach { it.lifetime.cancelled = true }
        barriers.filter { it.scope.id == scopeId }.forEach { it.lifetime.cancelled = true }
        statuses.values.filter { it.scope.id == scopeId && it.definition.replacements.isEmpty() }
            .forEach { it.lifetime.cancelled = true }
        pendingDepletions.values.forEach { callbacks -> callbacks.removeIf { it.scope.id == scopeId } }
        projectileHandles.entries.removeIf { it.value.scope.id == scopeId }
    }

    private fun enforceImmunity(target: UUID, categories: Set<String>) {
        if (categories.isEmpty()) return
        val controlled = statusesByTarget[target].orEmpty().filter { contribution ->
            contribution.lifetime.active && contribution.definition.controlCategories.any { it in categories }
        }
        if (controlled.isEmpty()) return
        // This is an explicit cleanse, not natural expiry. Each source and its descendants are cancelled independently.
        controlled.forEach { it.lifetime.cancelled = true }
        prune()
    }

    private fun statusBindings(status: Contribution) = linkedMapOf("status.stacks" to status.stackExpiries.size.toDouble())
    private fun statusScope(status: Contribution, budget: Budget) = status.scope.copy(target = status.key.target, ground = null, selected = true, targetGuard = null,
        lifetime = status.lifetime, budget = budget, originDefinition = status.definition.id, controllerId = status.id, statusState = status.localState)

    private fun tickStatus(status: Contribution, pulse: Boolean) {
        if (statuses[status.key] !== status) return
        val target = status.key.target
        val position = world.position(target)
        if (!world.availableTarget(status.key.owner, target) || (position != null && !world.loaded(position))) {
            status.lifetime.cancelled = true
            prune()
            return
        }
        val time = now(status.key.owner)
        val before = status.stackExpiries.size
        if (status.key.membership == null) status.stackExpiries.removeIf { it <= time }
        val budget = Budget()
        if (status.stackExpiries.isEmpty()) {
            removeStatus(status)
            status.lifetime.cancelled = true
            prune()
            syncSpeed(target)
            // Ordinary expiry gets a new source-owned scope. Cancellation never invokes expiry effects.
            val root = scopes[status.scope.id] ?: return
            execute(statusScope(status, budget).copy(lifetime = Lifetime(root.lifetime)), status.definition.expired, statusBindings(status))
            return
        }
        if (before != status.stackExpiries.size) execute(statusScope(status, budget), status.definition.stacksChanged, statusBindings(status))
        if (pulse && status.definition.periodicTicks > 0 && time >= status.nextPulse && status.lifetime.active) {
            status.nextPulse = time + status.definition.periodicTicks
            execute(statusScope(status, budget), status.definition.periodic, statusBindings(status))
        }
    }

    private fun syncSpeed(target: UUID) {
        val bonuses = statusesByTarget[target].orEmpty().filter { it.lifetime.active }.mapNotNull { it.definition.speed }
        val amount = if (bonuses.firstOrNull()?.combination == BonusCombination.CAPPED_ADD) bonuses.sumOf { it.amount }.coerceAtMost(bonuses.first().cap!!)
            else bonuses.maxOfOrNull { it.amount } ?: 0.0
        world.movementSpeedBonus(target, amount)
    }

    private fun cancelTargetStatuses(target: UUID) {
        statusesByTarget[target].orEmpty().forEach { it.lifetime.cancelled = true }
        prune()
    }
    private fun cancelTargetTimers(target: UUID) {
        timers.values.filter { it.key.target == target }.forEach { it.lifetime.cancelled = true }
        prune()
    }
    private fun cancelTargetBarriers(target: UUID) {
        barriers.filter { it.target == target }.forEach { it.lifetime.cancelled = true }
        pendingDepletions.remove(target)
        prune()
    }

    private fun removeStatus(status: Contribution) {
        statuses.remove(status.key)
        statusesByTarget[status.key.target]?.let { contributions ->
            contributions.remove(status)
            if (contributions.isEmpty()) statusesByTarget.remove(status.key.target)
        }
        if (status.definition.replacements.isNotEmpty()) reconcileReplacements(status.key.target)
    }

    private fun spatial(scope: Scope, target: SpatialTarget): Position? = when (target) {
        SpatialTarget.ACTOR -> world.position(scope.owner)
        SpatialTarget.TARGET -> scope.target?.let(world::position) ?: scope.ground
        SpatialTarget.GROUND -> scope.ground
    }

    private fun charge(scope: Scope, amount: Int) {
        require(amount >= 0 && scope.budget.remaining >= amount && workThisTick >= amount && (ownerWorkThisTick[scope.owner] ?: 0) + amount <= 2048) { "ability work limit exceeded" }
        scope.budget.remaining -= amount
        workThisTick -= amount
        ownerWorkThisTick[scope.owner] = (ownerWorkThisTick[scope.owner] ?: 0) + amount
    }

    private fun requireCapacity(scope: Scope) {
        require(pending.size + chains.size + eventWaits.size + parallels.size < 2048 &&
            pending.count { it.scope.owner == scope.owner } + chains.count { it.scope.owner == scope.owner } + eventWaits.count { it.scope.owner == scope.owner } + parallels.count { it.scope.owner == scope.owner } < 128) { "scheduled work limit exceeded" }
    }

    private fun schedule(scope: Scope, ticks: Int, effects: List<Effect>, results: Map<String, Double>, repeatsLeft: Int = 1, every: Int = 0) {
        if (!scope.lifetime.active) return
        require(ticks > 0 && repeatsLeft in 1..64 && (repeatsLeft == 1 || every > 0)) { "scheduled work requires a positive interval and finite count" }
        requireCapacity(scope)
        pending += Scheduled(scope, now(scope.owner) + ticks, effects, results.toMap(), repeatsLeft, every)
    }

    private fun cancelWhere(predicate: (Scope) -> Boolean) {
        scopes.values.filter(predicate).forEach { it.lifetime.cancelled = true }
        prune()
    }

    private fun prune(releaseIdle: Boolean = false) {
        val removed = statuses.values.filter { !it.lifetime.active }
        removed.forEach(::removeStatus)
        removed.map { it.key.target }.toSet().forEach(::syncSpeed)
        barriers.removeIf { !it.lifetime.active }
        projectiles.removeIf { !it.lifetime.active }
        eventWaits.removeIf { !it.lifetime.active }
        parallels.removeIf { it.finished || !it.scope.lifetime.active }
        pendingDepletions.values.forEach { callbacks -> callbacks.removeIf { !it.scope.lifetime.active } }
        pendingDepletions.entries.removeIf { it.value.isEmpty() }
        passives.entries.removeIf { !it.value.lifetime.active }
        toggles.entries.removeIf { !it.value.lifetime.active }
        channels.entries.removeIf { !it.value.scope.lifetime.active }
        recasts.entries.removeIf { !it.value.scope.lifetime.active || it.value.expires <= now(it.value.scope.owner) }
        timers.entries.removeIf { !it.value.lifetime.active }
        pending.removeIf { !it.scope.lifetime.active }
        chains.removeIf { !it.scope.lifetime.active }
        areas.removeIf { !it.lifetime.active }
        val retained = pending.map { it.scope.id }.toSet() + chains.map { it.scope.id } + parallels.map { it.scope.id } + areas.map { it.scope.id } + projectiles.map { it.scope.id } + eventWaits.map { it.scope.id } + statuses.values.map { it.scope.id } +
            timers.values.map { it.scope.id } + barriers.map { it.scope.id } + pendingDepletions.values.flatMap { callbacks -> callbacks.map { it.scope.id } } + passives.values.map { it.id } + toggles.values.map { it.id } + channels.values.map { it.scope.id } + recasts.values.map { it.scope.id }
        scopes.entries.removeIf { !it.value.lifetime.active || (releaseIdle && it.key !in retained) }
        suppressedActivations.removeIf { it !in scopes }
        projectileHandles.entries.removeIf { (_, handle) -> handle.scope.id !in scopes }
    }

    private fun fail(scope: Scope, failure: Exception) {
        scopes[scope.id]?.lifetime?.cancelled = true
        scope.lifetime.cancelled = true
        if (failures.size >= 32) failures.removeFirst()
        failures += "${scope.classId}/${scope.grant}: ${failure.message ?: "effect failed"}"
        prune()
    }

    private fun needsTarget(effect: Effect, groundMode: Boolean = false): Boolean {
        if (effect is Effect.WaitFor) return effect.timedOut.any { needsTarget(it, groundMode) }
        val mechanic = catalog.mechanic(effect)
        return (if (groundMode) mechanic.needsEntityTarget(effect) else mechanic.needsTarget(effect)) ||
            (!mechanic.providesTarget && mechanic.nested(effect).any { body -> body.any { needsTarget(it, groundMode) } })
    }
    private fun needsGround(effect: Effect): Boolean = if (effect is Effect.WaitFor) effect.timedOut.any(::needsGround) else catalog.mechanic(effect).let { mechanic ->
        mechanic.needsGround(effect) || mechanic.nested(effect).any { body -> body.any { needsGround(it) } }
    }
    private fun estimate(effects: List<Effect>) = estimates.measure(effects, ExecutionEstimates.Kind.WORK)
    private fun estimatedAreas(effects: List<Effect>) = estimates.measure(effects, ExecutionEstimates.Kind.AREAS)
    private fun initialSlots(effects: List<Effect>) = estimates.measure(effects, ExecutionEstimates.Kind.SLOTS)
    private fun estimatedStatuses(effects: List<Effect>) = estimates.measure(effects, ExecutionEstimates.Kind.STATUSES)
    private fun estimatedTimers(effects: List<Effect>) = estimates.measure(effects, ExecutionEstimates.Kind.TIMERS)
    private fun estimatedProjectiles(effects: List<Effect>) = estimates.measure(effects, ExecutionEstimates.Kind.PROJECTILES)
    private fun estimatedWaits(effects: List<Effect>) = estimates.measure(effects, ExecutionEstimates.Kind.WAITS)
    private fun estimatedParallels(effects: List<Effect>) = estimates.measure(effects, ExecutionEstimates.Kind.PARALLELS)

    private fun initialTimerKeys(owner: UUID, classId: String, grant: String, target: UUID?, effects: List<Effect>): Set<TimerKey> =
        effects.filterIsInstance<Effect.SetTimer>().mapNotNull { effect ->
            (if (effect.target == EffectTarget.ACTOR) owner else target)?.let { TimerKey(owner, classId, grant, "grant", effect.name, it) }
        }.toSet()

    private fun now(owner: UUID) = onlineClock[owner] ?: 0L
    private fun buffWork(area: AreaDef) = estimates.buffs(area, ExecutionEstimates.Kind.WORK)

    private fun initialStatusKeys(owner: UUID, classId: String, grant: String, ability: AbilityDef, target: UUID?, effects: List<Effect>): Set<StatusKey> {
        val keys = linkedSetOf<StatusKey>()
        fun collect(effects: List<Effect>) {
            for (effect in effects) when (effect) {
                is Effect.ApplyStatus -> (if (effect.target == EffectTarget.ACTOR) owner else target)?.let {
                    keys += StatusKey(owner, classId, grant, "${ability.id}/${effect.applicationId}", it, null)
                }
                // Branches, selections, controllers and continuations have dynamic allocation requirements.
                is Effect.Branch, is Effect.ForEach, is Effect.Chain, is Effect.CreateArea, is Effect.Delay, is Effect.Repeat -> Unit
                else -> catalog.mechanic(effect).nested(effect).forEach(::collect)
            }
        }
        collect(effects)
        return keys
    }

    private fun controllerBodies(area: AreaDef) = area.enter + area.periodic + area.exit + area.expired
    private fun controllerDependencies(effects: List<Effect>): ControllerDependencies {
        val areaDefinitions = linkedMapOf<String, AreaDef>()
        val statusDefinitions = linkedMapOf<String, StatusDef>()
        val stateDefinitions = linkedMapOf<String, StateDef>()
        val projectileDefinitions = linkedMapOf<String, ProjectileDef>()
        val createdStatuses = mutableSetOf<String>()
        fun collectStatus(id: String, collect: (List<Effect>) -> Unit) {
            val status = definitions.statuses[id] ?: return
            statusDefinitions[id] = status
            if (createdStatuses.add(id)) collect(status.bodies)
        }
        fun collect(body: List<Effect>) {
            for (effect in body.flatMap { catalog.descendants(it).toList() }) {
                val mechanic = catalog.mechanic(effect)
                for (ref in mechanic.states(effect)) definitions.states[ref]?.let { stateDefinitions[ref] = it }
                for (ref in mechanic.projectiles(effect)) {
                    val projectile = definitions.projectiles[ref] ?: continue
                    if (projectileDefinitions.putIfAbsent(ref, projectile) == null) collect(projectile.bodies)
                }
                for (numeric in mechanic.inputs(effect).values.filterIsInstance<Numeric.Expression>())
                    for (variable in Expression.variables(numeric.source).filter { it.startsWith("state.") }) {
                        val parts = variable.split('.')
                        val ref = "${parts[1]}:${parts[2]}"
                        definitions.states[ref]?.let { stateDefinitions[ref] = it }
                    }
                for (ref in mechanic.statuses(effect)) definitions.statuses[ref]?.let { statusDefinitions[ref] = it }
                for (ref in mechanic.createdStatuses(effect)) collectStatus(ref, ::collect)
                for (ref in mechanic.areas(effect)) {
                    val area = definitions.areas[ref] ?: continue
                    if (areaDefinitions.putIfAbsent(ref, area) == null) {
                        collect(controllerBodies(area))
                        area.buffs.forEach { collectStatus(it, ::collect) }
                    }
                }
            }
        }
        collect(effects)
        return ControllerDependencies(areaDefinitions, statusDefinitions, stateDefinitions, projectileDefinitions, createdStatuses)
    }
    private fun changeResource(record: PlayerRecord, classId: String, id: String, delta: Double): Double {
        val resource = definitions.resources[id] ?: error("resource is unavailable")
        val key = balanceKey(classId, resource)
        val current = balance(record, classId, resource)
        if (delta < 0 && current + delta < resource.minimum) error("insufficient resource")
        val next = (current + delta).coerceIn(resource.minimum, resource.maximum)
        record.resources[key] = next
        return kotlin.math.abs(next - current)
    }
    private fun balance(record: PlayerRecord, classId: String, resource: ResourceDef): Double = record.resources.getOrPut(balanceKey(classId, resource)) { resource.initial }
    private fun balanceKey(classId: String, resource: ResourceDef): String = "${if (resource.scope == ResourceScope.PLAYER) "player" else classId}|${resource.id}"

    fun chargeState(player: UUID, classId: String, grant: String): ChargeState? {
        val definition = effectiveAbility(player, classId, grant)?.charges ?: return null
        return chargeState(record(player), "$classId|$grant", definition)
    }

    fun cooldownRemaining(player: UUID, classId: String, grant: String): Int {
        val ability = effectiveAbility(player, classId, grant) ?: return 0
        val record = record(player)
        return cooldownKeys("$classId|$grant", ability).maxOfOrNull { (key, _) -> record.cooldowns[key] ?: 0 } ?: 0
    }

    private fun cooldownKeys(grantKey: String, ability: AbilityDef): List<Pair<String, Int>> =
        listOf(grantKey to ability.cooldownTicks) + ability.cooldownGroups.map { "group|$it" to ability.cooldownTicks } +
            if (ability.globalCooldownTicks > 0) listOf("global" to ability.globalCooldownTicks) else emptyList()

    private fun chargeState(record: PlayerRecord, key: String, definition: ChargeDef): ChargeState {
        val state = record.charges.getOrPut(key) { ChargeState(definition.maximum, definition.maximum, definition.mode) }
        val oldTimers = state.timers.filter { it in 1..72_000 }.sorted()
        state.capacity = definition.maximum
        state.available = state.available.coerceIn(0, definition.maximum)
        state.mode = definition.mode
        val missing = definition.maximum - state.available
        state.timers.clear()
        if (missing > 0) {
            if (definition.mode == RechargeMode.SEQUENTIAL) state.timers += oldTimers.firstOrNull() ?: definition.rechargeTicks
            else {
                state.timers += oldTimers.take(missing)
                repeat(missing - state.timers.size) { state.timers += definition.rechargeTicks }
            }
        }
        return state
    }

    private fun reconcileCharges(owner: UUID, record: PlayerRecord) {
        for (key in record.charges.keys.toList()) {
            val definition = effectiveAbility(owner, key.substringBefore('|'), key.substringAfter('|'))?.charges ?: continue
            chargeState(record, key, definition)
        }
    }

    private fun tickCharges(owner: UUID, record: PlayerRecord) {
        for ((key, state) in record.charges) {
            val definition = effectiveAbility(owner, key.substringBefore('|'), key.substringAfter('|'))?.charges ?: continue
            chargeState(record, key, definition)
            state.timers.replaceAll { it - 1 }
            val restored = state.timers.count { it <= 0 }
            state.timers.removeIf { it <= 0 }
            state.available = (state.available + restored).coerceAtMost(state.capacity)
            if (state.mode == RechargeMode.SEQUENTIAL && state.available < state.capacity && state.timers.isEmpty()) state.timers += definition.rechargeTicks
        }
    }
}

private fun Numeric.value(results: Map<String, Double>): Double = when (this) {
    is Numeric.Constant -> value
    is Numeric.Expression -> Expression.evaluate(source, results)
}
private fun Numeric.amount(results: Map<String, Double>): Double = value(results).also { require(it.isFinite() && it in 0.0..1_000_000.0) { "effect amount must be finite and within the work limit" } }
private fun Numeric.unavailable(results: Map<String, Double>): Boolean = this is Numeric.Expression && Expression.variables(source).any { it !in results }
