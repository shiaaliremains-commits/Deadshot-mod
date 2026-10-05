package my.deadshot.client

import com.mojang.blaze3d.platform.InputConstants
import kotlin.math.atan2
import kotlin.math.sqrt
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.phys.Vec3

object DeadshotClient : ClientModInitializer {
    private const val MOD_ID = "deadshot"

    private var bowAimEnabled = true
    private var meleeAimEnabled = true

    private lateinit var toggleBowKey: KeyMapping
    private lateinit var toggleMeleeKey: KeyMapping
    private var screenProbe: ((Minecraft) -> Any?)? = null

    override fun onInitializeClient() {
        val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"))

        // زر تفعيل/تعطيل ايم القوس (حرف V)
        toggleBowKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.deadshot.toggle_bow", InputConstants.KEY_V, category)
        )

        // زر تفعيل/تعطيل الايم العام لجميع الأسلحة ولليد الفارغة (حرف X)
        toggleMeleeKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.deadshot.toggle_melee", InputConstants.KEY_X, category)
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val player = client.player ?: return@register

            while (toggleBowKey.consumeClick()) {
                bowAimEnabled = !bowAimEnabled
                notify(player, "Bow Aim", bowAimEnabled)
            }

            while (toggleMeleeKey.consumeClick()) {
                meleeAimEnabled = !meleeAimEnabled
                notify(player, "General Aim", meleeAimEnabled)
            }

            if (isScreenOpen(client)) return@register

            handleAim(player)
        }
    }

    private fun buildScreenProbe(mc: Minecraft): (Minecraft) -> Any? {
        val guiField = runCatching { mc.javaClass.getField("gui") }.getOrNull()
        val gui = guiField?.let { runCatching { it.get(mc) }.getOrNull() }
        val guiMethod = gui?.javaClass?.methods?.firstOrNull { it.name == "screen" && it.parameterCount == 0 }
        if (guiField != null && guiMethod != null) {
            return { m -> runCatching { guiMethod.invoke(guiField.get(m)) }.getOrNull() }
        }

        val mcMethod = mc.javaClass.methods.firstOrNull { it.name == "screen" && it.parameterCount == 0 }
        if (mcMethod != null) {
            return { m -> runCatching { mcMethod.invoke(m) }.getOrNull() }
        }

        val mcField = runCatching { mc.javaClass.getField("screen") }.getOrNull()
        if (mcField != null) {
            return { m -> runCatching { mcField.get(m) }.getOrNull() }
        }

        return { _ -> null }
    }

    private fun isScreenOpen(client: Minecraft): Boolean {
        val probe = screenProbe ?: buildScreenProbe(client).also { screenProbe = it }
        return probe(client) != null
    }

    private fun notify(player: Player, name: String, state: Boolean) {
        val status = if (state) Component.literal("ON").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
        else Component.literal("OFF").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
        player.sendSystemMessage(
            Component.literal("[Deadshot] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("$name: ").withStyle(ChatFormatting.WHITE))
                .append(status)
        )
    }

    private fun handleAim(player: Player) {
        val isBow = player.isUsingItem && (player.useItem.item is BowItem || player.useItem.item is CrossbowItem)

        // 1. نظام القوس
        if (isBow && bowAimEnabled) {
            val target = findTarget(player, 45.0) ?: return
            aimBow(player, target)
            return
        }

        // 2. نظام الايم العام (لأي سلاح أو بدون سلاح)
        if (meleeAimEnabled && !isBow) {
            val target = findTarget(player, 5.5) ?: return
            aimDirect(player, target.boundingBox.center)
        }
    }

    private fun findTarget(player: Player, range: Double): LivingEntity? {
        val level = player.level()
        val box = player.boundingBox.inflate(range)
        val entities = level.getEntitiesOfClass(LivingEntity::class.java, box) {
            it != player && it.isAlive && it !is ArmorStand && player.hasLineOfSight(it)
        }

        return entities.minByOrNull { player.distanceTo(it) }
    }

    private fun aimDirect(player: Player, targetPos: Vec3) {
        val eyePos = player.eyePosition
        val dx = targetPos.x - eyePos.x
        val dy = targetPos.y - eyePos.y
        val dz = targetPos.z - eyePos.z
        val horizontalDist = sqrt(dx * dx + dz * dz)

        val yaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, horizontalDist))).toFloat()

        player.yRot = yaw
        player.xRot = pitch
    }

    private fun aimBow(player: Player, target: LivingEntity) {
        val eyePos = player.eyePosition
        val targetPos = target.boundingBox.center
        val dx = targetPos.x - eyePos.x
        val dz = targetPos.z - eyePos.z
        val horizontalDist = sqrt(dx * dx + dz * dz)

        // حساب وقت الشد بدقة عبر ticksUsingItem
        val useTicks = player.ticksUsingItem
        var velocity = BowItem.getPowerForTime(useTicks).toDouble() * 3.0
        if (velocity < 0.1) velocity = 1.0

        val gravity = 0.05
        val time = horizontalDist / velocity
        val drop = 0.5 * gravity * time * time

        val targetY = targetPos.y + drop
        val dy = targetY - eyePos.y

        val yaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, horizontalDist))).toFloat()

        player.yRot = yaw
        player.xRot = Mth.clamp(pitch, -90.0f, 90.0f)
    }
}
