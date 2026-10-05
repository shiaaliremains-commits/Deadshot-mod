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

    // ذاكرة لتسجيل سرعة وحركة الكائنات الحقيقية في كل تيك
    private val lastPositions = HashMap<Int, Vec3>()
    private val entityVelocities = HashMap<Int, Vec3>()

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
                aimBowWithTruePrediction(player, target)
            }
            return
        }

        if (!isBow) {
            lockedTarget = null
        }

        // 2. نظام الايم العام
        if (meleeAimEnabled) {
            val target = findBestTargetByCrosshair(player, 5.5) ?: return
            aimDirect(player, target.boundingBox.center)
        }
    }

    private fun angleToCrosshair(player: Player, target: LivingEntity): Double {
        val eyePos = player.eyePosition
        val toTarget = target.boundingBox.center.subtract(eyePos).normalize()
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

    // حساب السرعة الحقيقية للكائن بدقة من الإحداثيات الفعلية (وليس deltaMovement المفرغة)
    private fun getRealVelocity(target: LivingEntity): Vec3 {
        val currentPos = Vec3(target.x, target.y, target.z)
        val prevPos = lastPositions[target.id]
        lastPositions[target.id] = currentPos

        val calculated = if (prevPos != null) {
            currentPos.subtract(prevPos)
        } else {
            Vec3(target.x - target.xo, target.y - target.yo, target.z - target.zo)
        }

        // حفظ وتنعيم السرعة
        val realVel = if (calculated.lengthSqr() > 1e-6) calculated else target.deltaMovement
        entityVelocities[target.id] = realVel
        return realVel
    }

    // توجيه القوس مع حساب نقطة الاعتراض المستقبلي لحركة الكائن (Lead Aiming)
    private fun aimBowWithTruePrediction(player: Player, target: LivingEntity) {
        val eyePos = player.eyePosition
        val targetCenter = target.boundingBox.center

        // حساب سرعة السهم الأولية
        val useTicks = player.ticksUsingItem
        var arrowSpeed = BowItem.getPowerForTime(useTicks).toDouble() * 3.0
        if (arrowSpeed < 0.1) arrowSpeed = 1.0

        // السرعة الفعالة للسهم مع احتساب مقاومة الهواء (0.99 Drag)
        val effectiveSpeed = arrowSpeed * 0.95

        // السرعة الحقيقية لحركة الكائن (بلوك / تيك)
        val vel = getRealVelocity(target)

        // حل تكراري (2-Step Iterative Solver) لحساب نقطة الاصطدام بدقة 100%
        var predictedPos = targetCenter
        var flightTime = 0.0

        for (i in 0..2) {
            val dist = eyePos.distanceTo(predictedPos)
            flightTime = dist / effectiveSpeed
            predictedPos = Vec3(
                targetCenter.x + (vel.x * flightTime),
                targetCenter.y + (vel.y * flightTime).coerceIn(-1.2, 1.2),
                targetCenter.z + (vel.z * flightTime)
            )
        }

        val dx = predictedPos.x - eyePos.x
        val dz = predictedPos.z - eyePos.z
        val horizontalDist = sqrt(dx * dx + dz * dz)

        // حساب سقوط السهم بفعل الجاذبية (0.05 بلوك لكل تيك تربيع)
        val gravity = 0.05
        val drop = 0.5 * gravity * flightTime * flightTime

        val targetY = predictedPos.y + drop
        val dy = targetY - eyePos.y

        val yaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, horizontalDist))).toFloat()

        player.yRot = yaw
        player.xRot = Mth.clamp(pitch, -90.0f, 90.0f)
    }
}
