package dev.archetype.minecraft

import com.mojang.brigadier.arguments.StringArgumentType
import dev.archetype.definitions.*
import dev.archetype.runtime.AbilityRuntime
import dev.archetype.runtime.CastResult
import dev.archetype.runtime.TalentResult
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

object ArchetypeMod : ModInitializer {
    private val log = LoggerFactory.getLogger("archetype")
    private var session: Session? = null
    private val mechanics = BuiltinEffects.catalog.effects.values.toMutableList()
    private var frozenCatalog: MechanicCatalog? = null

    /** Add-ons register during initialization, before the first server publishes definitions. */
    // ASVS 15.4.1-15.4.3: registration and freezing share the object's monitor.
    @Synchronized fun registerMechanic(mechanic: EffectMechanic<out Effect>) {
        check(frozenCatalog == null) { "mechanic registration is closed after server startup" }
        MechanicCatalog(mechanics + mechanic) // Validate IDs, configuration types and metadata before mutation.
        mechanics += mechanic
    }

    @Synchronized private fun freezeCatalog(): MechanicCatalog = frozenCatalog ?: MechanicCatalog(mechanics.toList()).also { frozenCatalog = it }

    override fun onInitialize() {
        Packets.register()
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val root = server.getWorldPath(LevelResource.ROOT).resolve("archetype")
            Files.createDirectories(root.resolve("packs"))
            val catalog = freezeCatalog()
            session = Session(server, root, catalog).also { it.loadInitial(); CombatBridge.runtime = it.runtime }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { CombatBridge.runtime = null; session?.runtime?.shutdown(); session?.saveAll(); session = null }
        ServerTickEvents.END_SERVER_TICK.register { session?.tick() }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            if (!ServerPlayNetworking.canSend(handler, StatePayload.TYPE)) {
                handler.disconnect(Component.literal("Archetype is required on the client"))
            } else session?.join(handler.player)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> session?.leave(handler.player.uuid) }
        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, _, _, _, _ -> CombatBridge.committed(entity.uuid, entity.health) }
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, source ->
            CombatBridge.committed(entity.uuid, entity.health)
            session?.runtime?.onDeath(entity.uuid)
            session?.runtime?.onEntityDeath((source.entity as? ServerPlayer)?.uuid, session?.world?.position(entity.uuid))
        }
        ServerPlayNetworking.registerGlobalReceiver(SelectClassPayload.TYPE) { payload, context ->
            context.server().execute {
                val current = session ?: return@execute
                if (!current.runtime.selectClass(context.player().uuid, payload.classId)) {
                    context.player().sendSystemMessage(Component.literal("Archetype: unknown class"))
                }
                current.sync(context.player())
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(CastPayload.TYPE) { payload, context ->
            context.server().execute {
                val current = session ?: return@execute
                when (val result = current.runtime.cast(context.player().uuid, payload.classId, payload.grant, payload.target, payload.generation)) {
                    CastResult.Applied -> Unit
                    is CastResult.Rejected -> context.player().sendSystemMessage(Component.literal("Archetype: ${result.reason}"))
                    is CastResult.Interrupted -> {
                        log.warn("Ability {} interrupted: {}", payload.grant, result.reason)
                        context.player().sendSystemMessage(Component.literal("Archetype: ability interrupted"))
                    }
                }
                current.sync(context.player())
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(ReleasePayload.TYPE) { payload, context ->
            context.server().execute {
                val current = session ?: return@execute
                when (current.runtime.effectiveAbility(context.player().uuid, payload.classId, payload.grant)?.activation) {
                    Activation.CHARGE -> when (val result = current.runtime.releaseCharge(context.player().uuid, payload.classId,
                        payload.grant, payload.target, payload.generation)) {
                        is CastResult.Rejected -> context.player().sendSystemMessage(Component.literal("Archetype: ${result.reason}"))
                        is CastResult.Interrupted -> log.warn("Charged ability {} interrupted: {}", payload.grant, result.reason)
                        CastResult.Applied -> Unit
                    }
                    Activation.CHANNEL -> current.runtime.releaseChannel(context.player().uuid, payload.classId, payload.grant, payload.generation)
                    else -> Unit
                }
                current.sync(context.player())
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(TalentSelectPayload.TYPE) { payload, context ->
            context.server().execute {
                val current = session ?: return@execute
                if (payload.generation == current.runtime.generation) {
                    when (val result = current.runtime.selectTalent(context.player().uuid, payload.tree, payload.node)) {
                        TalentResult.Applied -> Unit
                        is TalentResult.Rejected -> context.player().sendSystemMessage(Component.literal("Archetype: ${result.reason}"))
                    }
                }
                current.sync(context.player())
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(TalentRespecPayload.TYPE) { payload, context ->
            context.server().execute {
                val current = session ?: return@execute
                if (payload.generation == current.runtime.generation)
                    current.runtime.respecTree(context.player().uuid, payload.tree)
                current.sync(context.player())
            }
        }
        registerCommands()
    }

    private fun registerCommands() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("archetype")
                    .then(Commands.literal("list").executes { ctx ->
                        val current = session ?: return@executes 0
                        ctx.source.sendSuccess({ Component.literal("Classes: " + current.runtime.definitions.classes.keys.joinToString(", ")) }, false)
                        1
                    })
                    .then(Commands.literal("select")
                        .then(Commands.argument("class", StringArgumentType.word()).executes { ctx ->
                            val player = ctx.source.playerOrException
                            val current = session ?: return@executes 0
                            val id = StringArgumentType.getString(ctx, "class")
                            if (!current.runtime.selectClass(player.uuid, id)) {
                                ctx.source.sendFailure(Component.literal("Unknown class $id"))
                                0
                            } else {
                                current.sync(player)
                                1
                            }
                        }))
                    .then(Commands.literal("diagnostics")
                        .requires { source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN) }
                        .executes { ctx ->
                        val current = session ?: return@executes 0
                        val lines = current.diagnostics.ifEmpty { listOf("No manifest errors") }
                        lines.take(20).forEach { line -> ctx.source.sendSuccess({ Component.literal(line) }, false) }
                        1
                        })
                    .then(Commands.literal("inspect")
                        .requires { source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN) }
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            val current = session ?: return@executes 0
                            current.inspect(player).forEach { line -> ctx.source.sendSuccess({ Component.literal(line) }, false) }
                            1
                        }
                        .then(Commands.argument("player", StringArgumentType.word()).executes { ctx ->
                            val current = session ?: return@executes 0
                            val requested = StringArgumentType.getString(ctx, "player")
                            val player = current.server.playerList.players.firstOrNull { it.name.string.equals(requested, ignoreCase = true) }
                            if (player == null) {
                                ctx.source.sendFailure(Component.literal("Player $requested is not online"))
                                0
                            } else {
                                current.inspect(player).forEach { line -> ctx.source.sendSuccess({ Component.literal(line) }, false) }
                                1
                            }
                        }))
            )
        }
    }

    private class Session(val server: MinecraftServer, val root: Path, val catalog: MechanicCatalog) {
        private val compiler = ManifestCompiler(catalog)
        val world = MinecraftWorldOps(server)
        val runtime = AbilityRuntime(world, catalog)
        private val store = PlayerStore(root.resolve("players"))
        private val failedLoads = mutableSetOf<UUID>()
        private val knownPlayers = mutableSetOf<UUID>()
        var diagnostics: List<String> = emptyList()
            private set
        private var ticks = 0L
        private var pending: PackSnapshot? = null
        private var pendingAt = 0L
        private var attemptedFingerprint = ""

        fun loadInitial() {
            try { apply(PackCapture.capture(root.resolve("packs"))) }
            catch (failure: Exception) { diagnostics = listOf("pack capture: ${failure.message}"); log.error("Pack capture failed", failure) }
        }

        fun tick() {
            ticks++
            runtime.tick(server.playerList.players.map { it.uuid })
            runtime.drainFailures().forEach { log.warn("Ability interrupted: {}", it) }
            if (ticks % 1200L == 0L) saveAll()
            if (ticks % 10L == 0L) server.playerList.players.forEach(::sync)
            if (ticks % 4L != 0L) return
            try {
                val snapshot = PackCapture.capture(root.resolve("packs"))
                if (snapshot.fingerprint == runtime.definitions.fingerprint || snapshot.fingerprint == attemptedFingerprint) return
                if (snapshot.fingerprint != pending?.fingerprint) {
                    pending = snapshot
                    pendingAt = System.nanoTime()
                    return
                }
                if (System.nanoTime() - pendingAt >= 200_000_000L) {
                    apply(snapshot)
                    pending = null
                }
            } catch (failure: Exception) {
                diagnostics = listOf("pack capture: ${failure.message}")
                log.error("Pack capture failed", failure)
            }
        }

        private fun apply(snapshot: PackSnapshot) {
            attemptedFingerprint = snapshot.fingerprint
            when (val result = compiler.compile(snapshot)) {
                is CompileResult.Invalid -> {
                    diagnostics = result.diagnostics.map(Diagnostic::toString)
                    diagnostics.forEach { log.warn("Manifest: {}", it) }
                }
                is CompileResult.Valid -> {
                    val allAbilities = result.definitions.abilities.values + result.definitions.classes.values.flatMap { it.grants.values.map { grant -> grant.ability } }
                    val allEffects = allAbilities.flatMap { it.effects } + result.definitions.areas.values.flatMap { it.enter + it.periodic + it.exit + it.expired } + result.definitions.statuses.values.flatMap { it.bodies } + result.definitions.projectiles.values.flatMap { it.bodies }
                    val unknownTypes = allEffects
                        .flatMap { catalog.descendants(it).toList() }
                        .mapNotNull { effect -> when (effect) {
                            is Effect.Damage -> effect.damageType
                            is Effect.Shield -> effect.damageType
                            else -> null
                        } }
                        .filterNot(world::supportsDamageType)
                        .distinct()
                    if (unknownTypes.isNotEmpty()) {
                        diagnostics = unknownTypes.map { "damage_type: unknown native damage type $it" }
                        diagnostics.forEach { log.warn("Manifest: {}", it) }
                    } else {
                        // ASVS 15.4.1-15.4.2: validated captured bytes publish on the server tick thread.
                        try {
                            runtime.publish(result.definitions)
                            diagnostics = emptyList()
                            log.info("Published {} packs, {} classes, generation {}", result.definitions.packs.size, result.definitions.classes.size, runtime.generation)
                            server.playerList.players.forEach(::sync)
                        } catch (failure: IllegalArgumentException) {
                            diagnostics = listOf("state migration: ${failure.message ?: "incompatible state"}")
                            diagnostics.forEach { log.warn("Manifest: {}", it) }
                        }
                    }
                }
            }
        }

        fun join(player: ServerPlayer) {
            knownPlayers += player.uuid
            val saved = store.load(player.uuid)
            if (saved == null) {
                failedLoads += player.uuid
                log.error("Could not load Archetype state for {}; retaining the saved file", player.uuid)
                player.sendSystemMessage(Component.literal("Archetype: saved state could not be loaded; ask an operator"))
            } else runtime.installRecord(player.uuid, saved)
            sync(player)
        }

        fun leave(id: UUID) {
            runtime.onLogout(id)
            if (id in knownPlayers && id !in failedLoads) save(id)
            failedLoads -= id
            knownPlayers -= id
        }

        fun saveAll() { server.playerList.players.forEach { if (it.uuid in knownPlayers && it.uuid !in failedLoads) save(it.uuid) } }
        private fun save(id: UUID) {
            try { store.save(id, runtime.record(id)) }
            catch (failure: Exception) { log.error("Could not save Archetype state for {}", id, failure) }
        }

        fun sync(player: ServerPlayer) {
            if (!ServerPlayNetworking.canSend(player, StatePayload.TYPE)) return
            val record = runtime.record(player.uuid)
            val active = record.activeClasses.firstOrNull { it in runtime.definitions.classes }.orEmpty()
            val grants = runtime.grantsFor(player.uuid, active).values.filter { runtime.effectiveAbility(player.uuid, active, it.name)?.activation != Activation.PASSIVE }.take(128).map {
                val effective = runtime.effectiveAbility(player.uuid, active, it.name) ?: it.ability
                val charges = runtime.chargeState(player.uuid, active, it.name)
                GrantView(it.name, it.slot.orEmpty(), runtime.cooldownRemaining(player.uuid, active, it.name),
                    charges?.available ?: 0, charges?.capacity ?: 0, charges?.timers?.minOrNull() ?: 0,
                    effective.activation == Activation.TOGGLE, effective.activation == Activation.CHANNEL,
                    effective.activation == Activation.CHARGE,
                    effective.activation == Activation.CONFIRM,
                    effective.activation == Activation.RECAST,
                    runtime.toggleActive(player.uuid, active, it.name) || runtime.channelActive(player.uuid, active, it.name) ||
                        runtime.chargeActive(player.uuid, active, it.name) || runtime.confirmActive(player.uuid, active, it.name) ||
                        runtime.recastActive(player.uuid, active, it.name))
            }
            val resources = runtime.definitions.resources.values.take(128).map { resource ->
                val key = "${if (resource.scope == ResourceScope.PLAYER) "player" else active}|${resource.id}"
                ResourceView(resource.id, record.resources[key] ?: resource.initial, resource.maximum)
            }
            val progression = runtime.definitions.progressionTracks.values.take(32).mapNotNull { track ->
                runtime.progress(player.uuid, track.id, active)?.let { progress ->
                    TrackView(track.id, progress.level, progress.earnedXp, progress.points.values.sum())
                }
            }
            val talents = runtime.talentNodes(player.uuid).map { node ->
                TalentNodeView(node.tree, node.node, node.selection == UnlockSelection.AUTOMATIC,
                    node.rank, node.maximum, node.cost, node.points, node.requiredLevel, node.reason)
            }
            ServerPlayNetworking.send(player, StatePayload(runtime.generation, active, runtime.definitions.classes.keys.take(128), grants, resources, progression, talents))
        }

        fun inspect(player: ServerPlayer): List<String> {
            val id = player.uuid
            val record = runtime.record(id)
            val definitions = runtime.definitions
            val active = record.activeClasses.firstOrNull().orEmpty()
            val lines = mutableListOf("Archetype inspect ${player.name.string}: generation ${runtime.generation}, active class ${active.ifBlank { "none" }}")
            for ((grantName, grant) in runtime.grantsFor(id, active)) {
                val effective = runtime.effectiveAbility(id, active, grantName) ?: grant.ability
                val charges = runtime.chargeState(id, active, grantName)
                lines += "grant $grantName -> ${effective.id}, ${effective.activation.name.lowercase()}" +
                    (if (effective.id != grant.ability.id) " (base ${grant.ability.id})" else "") +
                    ", cooldown ${runtime.cooldownRemaining(id, active, grantName)} ticks" +
                    (charges?.let { ", charges ${it.available}/${it.capacity}, recharge ${it.timers.joinToString()}" } ?: "")
            }
            for (resource in definitions.resources.values) {
                val key = "${if (resource.scope == ResourceScope.PLAYER) "player" else active}|${resource.id}"
                lines += "resource ${resource.id}: ${record.resources[key] ?: resource.initial}/${resource.maximum}"
            }
            for (track in definitions.progressionTracks.values) runtime.progress(id, track.id, active)?.let { progress ->
                lines += "progress ${track.id}: level ${progress.level}, XP ${progress.earnedXp}, points ${progress.points}"
            }
            for (node in runtime.talentNodes(id)) lines += "talent ${node.tree}/${node.node}: ${node.rank}/${node.maximum}, ${node.reason}"
            for (state in definitions.states.values) if (state.scope != StateScope.ACTIVATION)
                for (field in state.fields.keys) lines += "state ${state.id}.$field: ${runtime.readDeclaredState(id, active, state.id, field)}"
            for (status in runtime.statuses(id)) lines += "status ${status.status}: ${status.stacks} stacks, source ${status.owner}/${status.grant}, remaining ${status.remainingTicks ?: "membership"}"
            for (timer in runtime.timers(id)) lines += "timer ${timer.name}: ${timer.remainingTicks} ticks, target ${timer.target}"
            for (barrier in runtime.barriers(id)) lines += "barrier: ${barrier.remaining} capacity, ${barrier.remainingTicks} ticks, priority ${barrier.priority}"
            if (lines.size == 1) lines += "No active class or effects"
            return lines.take(80)
        }
    }
}
