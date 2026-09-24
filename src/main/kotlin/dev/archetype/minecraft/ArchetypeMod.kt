package dev.archetype.minecraft

import com.mojang.brigadier.arguments.StringArgumentType
import dev.archetype.definitions.*
import dev.archetype.runtime.AbilityRuntime
import dev.archetype.runtime.CastResult
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

    override fun onInitialize() {
        Packets.register()
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val root = server.getWorldPath(LevelResource.ROOT).resolve("archetype")
            Files.createDirectories(root.resolve("packs"))
            session = Session(server, root).also { it.loadInitial() }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { session?.saveAll(); session = null }
        ServerTickEvents.END_SERVER_TICK.register { session?.tick() }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            if (!ServerPlayNetworking.canSend(handler, StatePayload.TYPE)) {
                handler.disconnect(Component.literal("Archetype is required on the client"))
            } else session?.join(handler.player)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> session?.leave(handler.player.uuid) }
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, _ ->
            if (entity is ServerPlayer) session?.runtime?.onDeath(entity.uuid)
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
                        }),
            )
        }
    }

    private class Session(val server: MinecraftServer, val root: Path) {
        private val compiler = ManifestCompiler()
        private val world = MinecraftWorldOps(server)
        val runtime = AbilityRuntime(world)
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
                    val unknownTypes = allAbilities
                        .flatMap { ability -> ability.effects.flatMap { it.descendants().toList() } }
                        .filterIsInstance<Effect.Damage>()
                        .map { it.damageType }
                        .filterNot(world::supportsDamageType)
                        .distinct()
                    if (unknownTypes.isNotEmpty()) {
                        diagnostics = unknownTypes.map { "damage_type: unknown native damage type $it" }
                        diagnostics.forEach { log.warn("Manifest: {}", it) }
                    } else {
                        // ASVS 15.4.1-15.4.2: validated captured bytes publish on the server tick thread.
                        runtime.publish(result.definitions)
                        diagnostics = emptyList()
                        log.info("Published {} packs, {} classes, generation {}", result.definitions.packs.size, result.definitions.classes.size, runtime.generation)
                        server.playerList.players.forEach(::sync)
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
            val classDef = runtime.definitions.classes[active]
            val grants = classDef?.grants?.values?.take(128)?.map {
                GrantView(it.name, it.slot.orEmpty(), record.cooldowns["$active|${it.name}"] ?: 0)
            }.orEmpty()
            val resources = runtime.definitions.resources.values.take(128).map { resource ->
                val key = "${if (resource.scope == ResourceScope.PLAYER) "player" else active}|${resource.id}"
                ResourceView(resource.id, record.resources[key] ?: resource.initial, resource.maximum)
            }
            ServerPlayNetworking.send(player, StatePayload(runtime.generation, active, runtime.definitions.classes.keys.take(128), grants, resources))
        }
    }
}
