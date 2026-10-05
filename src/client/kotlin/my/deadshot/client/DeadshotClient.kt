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
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

object DeadshotClient : ClientModInitializer {
    private const val MOD_ID = "deadshot"
    private var enabled = true
    private lateinit var toggleKey: KeyMapping

    override fun onInitializeClient() {
        val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"))
        // زر تفعيل / تعطيل الايم (حرف V افتراضياً)
        toggleKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.deadshot.toggle", InputConstants.KEY_V, category)
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val player = client.player ?: return@register

            while (toggleKey.consumeClick()) {
                enabled = !enabled
                val status = if (enabled) Component.literal("ON").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                else Component.literal("OFF").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                player.sendSystemMessage(Component.literal("[Deadshot] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD).append(status))
            }

            if (!enabled || client.screen != null) return@register

            handleAim(player)
        }
    }

    private fun handleAim(player: Player) {
        val mainItem = player.mainHandItem.item
        val isBow = player.isUsingItem && (player.useItem.item is BowItem || player.useItem.item is CrossbowItem)
        val isMace = mainItem == Items.MACE

        if (!isBow && !isMace) return

        // المدى: 45 بلوكة للقوس، و 5 بلوكات للـ Mace
        val range = if (isBow) 45.0 else 5.0
        val target = findBestTarget(player, range) ?: return

        if (isBow) {
            aimBow(player, target)
        } else if (isMace) {
            aimDirect(player, target.boundingBox.center)
        }
    }

    // البحث عن أقرب هدف حي (مع إعطاء الأولوية للوحوش وتجاهل ما وراء الجدران)
    private fun findBestTarget(player: Player, range: Double): LivingEntity? {
        val level = player.level()
        val box = player.boundingBox.inflate(range)
        val entities = level.getEntitiesOfClass(LivingEntity::class.java, box) {
            it != player && it.isAlive && it !is ArmorStand && player.hasLineOfSight(it)
        }

        return entities.minByOrNull { entity ->
            val dist = player.distanceTo(entity)
            // إعطاء أولوية للوحوش (Enemy)
            if (entity is Enemy) dist else dist + 10.0
        }
    }

    // توجيه الايم المباشر (للـ Mace)
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

    // توجيه الايم البالستي للقوس (حساب الجاذبية والمسافة)
    private fun aimBow(player: Player, target: LivingEntity) {
        val eyePos = player.eyePosition
        val targetPos = target.boundingBox.center
        val dx = targetPos.x - eyePos.x
        val dz = targetPos.z - eyePos.z
        val horizontalDist = sqrt(dx * dx + dz * dz)

        // حساب قوة شد القوس
        val useTicks = player.useItem.useDuration - player.useItemRemainingTicks
        var velocity = BowItem.getPowerForTime(useTicks).toDouble() * 3.0
        if (velocity < 0.1) velocity = 1.0 // سرعة افتراضية كحد أدنى

        // معادلة سقوط السهم بفعل الجاذبية في ماينكرافت (Gravity compensation)
        val gravity = 0.05
        val time = horizontalDist / velocity
        val drop = 0.5 * gravity * time * time

        // استهداف منتصف جسم الكائن مع رفع الزاوية لتعويض السقوط
        val targetY = targetPos.y + drop
        val dy = targetY - eyePos.y

        val yaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, horizontalDist))).toFloat()

        player.yRot = yaw
        player.xRot = Mth.clamp(pitch, -90.0f, 90.0f)
    }
}
