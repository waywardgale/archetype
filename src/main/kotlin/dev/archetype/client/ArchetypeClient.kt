package dev.archetype.client

import com.mojang.blaze3d.platform.InputConstants
import dev.archetype.minecraft.CastPayload
import dev.archetype.minecraft.ReleasePayload
import dev.archetype.minecraft.TalentSelectPayload
import dev.archetype.minecraft.TalentRespecPayload
import dev.archetype.minecraft.SelectClassPayload
import dev.archetype.minecraft.StatePayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.EntityHitResult
import org.lwjgl.glfw.GLFW

object ArchetypeClient : ClientModInitializer {
    private var state: StatePayload? = null
    private val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("archetype", "controls"))
    private lateinit var cycleClass: KeyMapping
    private lateinit var previousPage: KeyMapping
    private lateinit var nextPage: KeyMapping
    private lateinit var talentMenu: KeyMapping
    private lateinit var talentRespec: KeyMapping
    private val slots = mutableListOf<KeyMapping>()
    private val heldChannels = mutableMapOf<Int, Pair<String, String>>()
    private var page = 0
    private var talentPage = 0
    private var talentsOpen = false

    override fun onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> state = null; page = 0; talentPage = 0; talentsOpen = false; heldChannels.clear() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> state = null; page = 0; talentPage = 0; talentsOpen = false; heldChannels.clear() }
        cycleClass = KeyMappingHelper.registerKeyMapping(KeyMapping("key.archetype.cycle_class", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, category))
        previousPage = KeyMappingHelper.registerKeyMapping(KeyMapping("key.archetype.previous_page", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, category))
        nextPage = KeyMappingHelper.registerKeyMapping(KeyMapping("key.archetype.next_page", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, category))
        talentMenu = KeyMappingHelper.registerKeyMapping(KeyMapping("key.archetype.talents", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, category))
        talentRespec = KeyMappingHelper.registerKeyMapping(KeyMapping("key.archetype.respec", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSPACE, category))
        for (index in 0 until 8) {
            val default = if (index == 0) GLFW.GLFW_KEY_R else GLFW.GLFW_KEY_UNKNOWN
            slots += KeyMappingHelper.registerKeyMapping(KeyMapping("key.archetype.slot_${index + 1}", InputConstants.Type.KEYSYM, default, category))
        }
        ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE) { packet, context ->
            context.client().execute {
                if (state?.activeClass != packet.activeClass) { page = 0; talentPage = 0; heldChannels.clear() }
                state = packet
                page = page.coerceAtMost(((packet.grants.size - 1) / 8).coerceAtLeast(0))
                talentPage = talentPage.coerceAtMost(((packet.talents.size - 1) / 8).coerceAtLeast(0))
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val current = state ?: return@register
            if (client.player == null) return@register
            if (!client.mouseHandler.isMouseGrabbed) {
                for ((_, identity) in heldChannels) ClientPlayNetworking.send(ReleasePayload(identity.first, identity.second,
                    current.generation, null))
                heldChannels.clear()
                return@register
            }
            while (talentMenu.consumeClick()) talentsOpen = !talentsOpen
            while (cycleClass.consumeClick()) {
                if (current.classes.isNotEmpty()) {
                    val index = current.classes.indexOf(current.activeClass)
                    ClientPlayNetworking.send(SelectClassPayload(current.classes[(index + 1) % current.classes.size]))
                }
            }
            val pageCount = (((if (talentsOpen) current.talents.size else current.grants.size) + slots.size - 1) / slots.size).coerceAtLeast(1)
            while (previousPage.consumeClick()) if (talentsOpen) talentPage = (talentPage - 1 + pageCount) % pageCount else page = (page - 1 + pageCount) % pageCount
            while (nextPage.consumeClick()) if (talentsOpen) talentPage = (talentPage + 1) % pageCount else page = (page + 1) % pageCount
            slots.forEachIndexed { index, binding ->
                while (binding.consumeClick()) {
                    if (talentsOpen) {
                        val node = current.talents.getOrNull(talentPage * slots.size + index) ?: break
                        if (!node.automatic) ClientPlayNetworking.send(TalentSelectPayload(node.tree, node.node, current.generation))
                        continue
                    }
                    val grant = current.grants.getOrNull(page * slots.size + index) ?: break
                    val target = (client.hitResult as? EntityHitResult)?.entity?.uuid
                    ClientPlayNetworking.send(CastPayload(current.activeClass, grant.name, current.generation, target))
                    if (grant.channel || grant.charge) heldChannels[index] = current.activeClass to grant.name
                }
            }
            while (talentRespec.consumeClick()) if (talentsOpen) {
                val tree = current.talents.getOrNull(talentPage * slots.size)?.tree ?: continue
                ClientPlayNetworking.send(TalentRespecPayload(tree, current.generation))
            }
            for ((index, identity) in heldChannels.toMap()) if (!slots[index].isDown) {
                val target = (client.hitResult as? EntityHitResult)?.entity?.uuid
                ClientPlayNetworking.send(ReleasePayload(identity.first, identity.second, current.generation, target))
                heldChannels.remove(index)
            }
        }
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("archetype", "abilities")) { graphics, _ ->
            val current = state ?: return@addLast
            val client = Minecraft.getInstance()
            if (client.player == null || current.activeClass.isEmpty()) return@addLast
            val left = 8
            if (talentsOpen) {
                val visibleTalents = current.talents.drop(talentPage * slots.size).take(slots.size)
                val talentPages = ((current.talents.size + slots.size - 1) / slots.size).coerceAtLeast(1)
                var talentTop = graphics.guiHeight() - 18 - (visibleTalents.size + 2) * 11
                graphics.text(client.font, "Talents  ${talentPage + 1}/$talentPages  [M close]", left, talentTop, 0xFFE6D8B0.toInt())
                talentTop += 11
                visibleTalents.forEachIndexed { index, node ->
                    val key = slots.getOrNull(index)?.translatedKeyMessage?.string ?: "-"
                    val cost = if (node.cost > 0) "  ${node.cost} pts" else ""
                    graphics.text(client.font, "$key  ${node.tree}/${node.node}  ${node.rank}/${node.maximum}$cost  ${node.reason}",
                        left, talentTop, if (node.reason == "available") 0xFFB9E4B7.toInt() else 0xFFFFFFFF.toInt())
                    talentTop += 11
                }
                graphics.text(client.font, "[Backspace respec visible tree]", left, talentTop, 0xFFD6C58A.toInt())
                return@addLast
            }
            val visible = current.grants.drop(page * slots.size).take(slots.size)
            val pageCount = ((current.grants.size + slots.size - 1) / slots.size).coerceAtLeast(1)
            var top = graphics.guiHeight() - 18 - (visible.size + current.resources.size + current.progression.size + 1) * 11
            graphics.text(client.font, "${current.activeClass}  ${page + 1}/$pageCount", left, top, 0xFFE6D8B0.toInt())
            top += 11
            visible.forEachIndexed { index, grant ->
                val key = slots.getOrNull(index)?.translatedKeyMessage?.string ?: "-"
                val cooldown = if (grant.cooldownTicks > 0) "  ${"%.1f".format(grant.cooldownTicks / 20.0)}s" else ""
                val charges = if (grant.maximumCharges > 0) "  ${grant.availableCharges}/${grant.maximumCharges}" +
                    if (grant.rechargeTicks > 0) " (${"%.1f".format(grant.rechargeTicks / 20.0)}s)" else "" else ""
                val active = when {
                    grant.channel && grant.active -> "  CHANNEL"
                    grant.charge && grant.active -> "  CHARGING"
                    grant.confirm && grant.active -> "  CONFIRM"
                    grant.recast && grant.active -> "  RECAST"
                    grant.toggle && grant.active -> "  ON"
                    grant.toggle -> "  OFF"
                    else -> ""
                }
                graphics.text(client.font, "$key  ${grant.name}$active$charges$cooldown", left, top, 0xFFFFFFFF.toInt())
                top += 11
            }
            current.resources.forEach { resource ->
                graphics.text(client.font, "${resource.name}: ${resource.amount.toInt()}/${resource.maximum.toInt()}", left, top, 0xFF8AD9EC.toInt())
                top += 11
            }
            current.progression.forEach { track ->
                graphics.text(client.font, "${track.name}: L${track.level}  ${track.xp} XP  ${track.points} pts", left, top, 0xFFD6C58A.toInt())
                top += 11
            }
        }
    }
}
