package com.createfix.createguioverflowfix.mixin;

import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复智能溜槽（Smart Chute）等使用 {@link FilteringBehaviour} 的物品在超大堆叠数下
 * 数值调节界面崩溃 / 卡死、且数值范围被放大到上亿的根因。
 *
 * <p>根因：{@code FilteringBehaviour.createBoard()} 里
 * {@code maxValue = filter.getMaxStackSize()}（对应 {@code ItemStack.m_41741_()}）。
 * 当 Bigger Stacks / Stacc 等模组把物品堆叠上限放大到几亿时，这个 maxValue 也随之
 * 变成几亿：</p>
 * <ul>
 *     <li>数值调节界面的范围变成 0 ~ 327M（数字过于庞大，无法正常使用）；</li>
 *     <li>{@code ValueSettingsScreen} 的窗口宽度、里程碑循环、最近坐标扫描都随
 *     maxValue 线性膨胀，导致整数溢出崩溃或长时间卡死。</li>
 * </ul>
 *
 * <p>修法：把 {@code getMaxStackSize()} 的返回值收拢到 {@link #MAX_VALUE_CAP}（64）。
 * 注意 {@code createBoard()} 里对 {@code FilterItem}（放入了过滤器的过滤物品）的分支
 * 本来就是写死的 64，只有「普通物品」分支才走 {@code getMaxStackSize()}；这里把它也
 * 收拢到 64，两个分支行为一致，既保住原来的 0~64 调节体验，也彻底消除溢出。</p>
 *
 * <p>混入目标 {@code createBoard} 是 Create 自有方法（不在 searge 映射里），故
 * {@code @Redirect} 需 {@code remap = false}。但 Mixin 会把这个 {@code remap = false}
 * 同时作用到 {@code @At} 的目标上（{@code owner.remap ? at.remap : false}），导致
 * Minecraft 方法的 Mojmap 名不会被 refmap 翻译。因此 {@code @At} 的 target 必须直接
 * 写 searge 名 {@code m_41741_}（即 {@code ItemStack.getMaxStackSize()}），并显式
 * {@code remap = false}。方法体里的 {@code stack.getMaxStackSize()} 由 reobfJar 负责
 * 转成 {@code m_41741_}，无需处理。</p>
 */
@Mixin(FilteringBehaviour.class)
public abstract class FilteringBehaviourMixin {

    /** 数值调节界面的最大值上限，与 FilterItem 分支原本写死的 64 保持一致。 */
    @Unique
    private static final int MAX_VALUE_CAP = 64;

    @Redirect(
            method = "createBoard",
            remap = false,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;m_41741_()I",
                    remap = false
            )
    )
    private int createGuiOverflowFix$capMaxStackSize(ItemStack stack) {
        return Math.min(stack.getMaxStackSize(), MAX_VALUE_CAP);
    }
}
