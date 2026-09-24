package dev.archetype.client

import com.mojang.blaze3d.platform.InputConstants
import dev.archetype.minecraft.CastPayload
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
    private val slots = mutableListOf<KeyMapping>()

    override fun onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> state = null }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> state = null }
        cycleClass = KeyMappingHelper.registerKeyMapping(KeyMapping("key.archetype.cycle_class", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, category))
        for (index in 0 until 8) {
            val default = if (index == 0) GLFW.GLFW_KEY_R else GLFW.GLFW_KEY_UNKNOWN
            slots += KeyMappingHelper.registerKeyMapping(KeyMapping("key.archetype.slot_${index + 1}", InputConstants.Type.KEYSYM, default, category))
        }
        ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE) { packet, context ->
            context.client().execute { state = packet }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val current = state ?: return@register
            if (client.player == null) return@register
            while (cycleClass.consumeClick()) {
                if (current.classes.isNotEmpty()) {
                    val index = current.classes.indexOf(current.activeClass)
                    ClientPlayNetworking.send(SelectClassPayload(current.classes[(index + 1) % current.classes.size]))
                }
            }
            slots.forEachIndexed { index, binding ->
                while (binding.consumeClick()) {
                    val grant = current.grants.getOrNull(index) ?: break
                    val target = (client.hitResult as? EntityHitResult)?.entity?.uuid
                    ClientPlayNetworking.send(CastPayload(current.activeClass, grant.name, current.generation, target))
                }
            }
        }
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("archetype", "abilities")) { graphics, _ ->
            val current = state ?: return@addLast
            val client = Minecraft.getInstance()
            if (client.player == null || current.activeClass.isEmpty()) return@addLast
            val left = 8
            var top = graphics.guiHeight() - 18 - (current.grants.size + current.resources.size + 1) * 11
            graphics.text(client.font, current.activeClass, left, top, 0xFFE6D8B0.toInt())
            top += 11
            current.grants.forEachIndexed { index, grant ->
                val key = slots.getOrNull(index)?.translatedKeyMessage?.string ?: "-"
                val cooldown = if (grant.cooldownTicks > 0) "  ${"%.1f".format(grant.cooldownTicks / 20.0)}s" else ""
                graphics.text(client.font, "$key  ${grant.name}$cooldown", left, top, 0xFFFFFFFF.toInt())
                top += 11
            }
            current.resources.forEach { resource ->
                graphics.text(client.font, "${resource.name}: ${resource.amount.toInt()}/${resource.maximum.toInt()}", left, top, 0xFF8AD9EC.toInt())
                top += 11
            }
        }
    }
}
