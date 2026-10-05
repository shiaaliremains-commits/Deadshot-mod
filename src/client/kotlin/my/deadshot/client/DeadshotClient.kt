package my.deadshot.client

import com.mojang.blaze3d.platform.InputConstants
import kotlin.math.acos
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
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.phys.Vec3

object DeadshotClient : ClientModInitializer {
    private const val MOD_ID = "deadshot"

    private var bowAimEnabled = true
    private var meleeAimEnabled = true
    private var lockedTarget: LivingEntity? = null

    private lateinit var toggleBowKey: KeyMapping
    private lateinit var toggleMeleeKey: KeyMapping
    private var screenProbe: ((Minecraft) -> Any?)? = null

    override fun onInitializeClient() {
        val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"))

        toggleBowKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.deadshot.toggle_bow", InputConstants.KEY_V, category)
        )

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

    private fun isValidTarget(player: Player, target: LivingEntity?, range: Double): Boolean {
        if (target == null || !target.isAlive || target == player || target is ArmorStand) return false
        if (player.distanceTo(target) > range) return false
        return player.hasLineOfSight(target)
    }

    // نقطة الصدر بدقة (60% من طول الكائن)
    private fun getAimPoint(target: LivingEntity): Vec3 {
        val bb = target.boundingBox
        val height = bb.maxY - bb.minY
        val yOffset = height * 0.60
        return Vec3(target.x, bb.minY + yOffset, target.z)
    }

    private fun handleAim(player: Player) {
        val isBow = player.isUsingItem && (player.useItem.item is BowItem || player.useItem.item is CrossbowItem)

        // 1. نظام القوس
        if (isBow && bowAimEnabled) {
            val maxRange = 75.0
            if (!isValidTarget(player, lockedTarget, maxRange)) {
                lockedTarget = findBestTargetByCrosshair(player, maxRange)
            }
            val target = lockedTarget
            if (target != null) {
                aimBowAdaptive(player, target)
            }
            return
        }

        if (!isBow) {
            lockedTarget = null
        }

        // 2. نظام الايم العام
        if (meleeAimEnabled) {
            val target = findBestTargetByCrosshair(player, 5.5) ?: return
            aimDirect(player, getAimPoint(target))
        }
    }

    private fun angleToCrosshair(player: Player, target: LivingEntity): Double {
        val eyePos = player.eyePosition
        val toTarget = getAimPoint(target).subtract(eyePos).normalize()
        val look = player.lookAngle.normalize()
        val dot = look.dot(toTarget).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot))
    }

    private fun findBestTargetByCrosshair(player: Player, range: Double): LivingEntity? {
        val level = player.level()
        val box = player.boundingBox.inflate(range)
        val entities = level.getEntitiesOfClass(LivingEntity::class.java, box) {
            it != player && it.isAlive && it !is ArmorStand && player.hasLineOfSight(it)
        }

        return entities.minByOrNull { entity ->
            val angle = angleToCrosshair(player, entity)
            val dist = player.distanceTo(entity)
            angle * 1.8 + dist * 0.4
        }
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

    // حساب سرعة الكائن اللحظية المباشرة (0ms Delay بدون أي تنعيم متأخر)
    private fun getInstantVelocity(target: LivingEntity): Vec3 {
        val vx = target.x - target.xo
        val vy = target.y - target.yo
        val vz = target.z - target.zo
        val vel = Vec3(vx, vy, vz)
        return if (vel.lengthSqr() > 1e-6) vel else target.deltaMovement
    }

    // توجيه القوس بنظام التوقع الذكي والمحكوم (Adaptive Clamped Lead)
    private fun aimBowAdaptive(player: Player, target: LivingEntity) {
        val eyePos = player.eyePosition
        val aimPoint = getAimPoint(target)

        val useTicks = player.ticksUsingItem
        var arrowSpeed = BowItem.getPowerForTime(useTicks).toDouble() * 3.0
        if (arrowSpeed < 0.1) arrowSpeed = 1.0
        val effectiveSpeed = arrowSpeed * 0.96

        val dist = eyePos.distanceTo(aimPoint)
        val flightTime = dist / effectiveSpeed

        // سرعة الكائن اللحظية
        val vel = getInstantVelocity(target)

        // نحدد سقف التوقع: للخفاش 0.75 بلوكة كحد أقصى، وللوحوش العادية 2.0 بلوكة
        // حتى مستحيل السهم يطير بعيد في الهواء إذا كسر الكائن يمين أو يسار فجأة
        val maxLead = if (target is Bat) 0.75 else 2.0
        var leadVector = Vec3(vel.x * flightTime, vel.y * flightTime * 0.5, vel.z * flightTime)
        if (leadVector.length() > maxLead) {
            leadVector = leadVector.normalize().scale(maxLead)
        }

        val predictedX = aimPoint.x + leadVector.x
        val predictedZ = aimPoint.z + leadVector.z
        val predictedY = aimPoint.y + leadVector.y.coerceIn(-0.6, 0.6)

        val dx = predictedX - eyePos.x
        val dz = predictedZ - eyePos.z
        val horizontalDist = sqrt(dx * dx + dz * dz)

        // حساب سقوط السهم بالجاذبية
        val gravity = 0.05
        val time = horizontalDist / effectiveSpeed
        val drop = 0.5 * gravity * time * time

        val targetY = predictedY + drop
        val dy = targetY - eyePos.y

        val yaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, horizontalDist))).toFloat()

        player.yRot = yaw
        player.xRot = Mth.clamp(pitch, -90.0f, 90.0f)
    }
}
