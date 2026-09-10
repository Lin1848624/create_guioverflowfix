# Create GUI Overflow Fix

机械动力计数过滤界面修复 —— 针对 Minecraft 1.20.1 · Forge 的客户端修复模组。

安装 Bigger Stacks、Stacc 等提高物品堆叠上限的模组后，机械动力（Create）的计数过滤数值调节界面（智能溜槽、黄铜漏斗、黄铜隧道等）会随堆叠上限一起膨胀，导致窗口超出屏幕、卡死甚至崩溃。本模组修复这一问题。

## 问题原因

机械动力的 `FilteringBehaviour#createBoard()` 在构建计数过滤的界面数据时，直接把被过滤物品的最大堆叠数当作数值调节范围：

```java
int maxAmount = filter.getItem() instanceof FilterItem ? 64 : filter.getMaxStackSize();
```

当提高堆叠上限的模组把 `getMaxStackSize()` 抬到百万级时，`ValueSettingsScreen` 会由该值推导界面宽度、里程碑数量与坐标映射，相关循环次数也随之线性增长；当堆叠上限达到 `Integer.MAX_VALUE` 时，宽度计算还会发生整数溢出。

## 修复方式

本模组通过 Mixin 拦截 `FilteringBehaviour#createBoard()` 中对 `ItemStack#getMaxStackSize()` 的调用，把返回值收拢到 64：

```java
@Redirect(method = "createBoard", remap = false,
        at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/item/ItemStack;m_41741_()I",
                remap = false))
private int createGuiOverflowFix$capMaxStackSize(ItemStack stack) {
    return Math.min(stack.getMaxStackSize(), MAX_VALUE_CAP); // MAX_VALUE_CAP = 64
}
```

64 与机械动力自身对“过滤物品（Filter Item）”分支写死的上限一致，因此正常情况下的界面行为与原版相同，只是不再可能被撑大。

## 兼容性

| 项目 | 值 |
| --- | --- |
| Minecraft | 1.20.1 |
| 加载器 | Forge 47 及以上 |
| Java | 17 |
| 机械动力（Create） | 6.0.8（开发与测试版本） |

关于机械动力版本的说明：

- 本模组只在**机械动力 6.0.8 + Forge 47.4.22 + Minecraft 1.20.1** 上实际测试过。
- `mods.toml` 中机械动力的依赖范围写作 `[6.0.8,)`，含义是允许在 6.0.8 及更高版本上加载。这**只表示不阻止更高版本加载，不代表已经在那些版本上验证过**；其他版本可能有效，也可能无效。
- 机械动力 6.0.8 以下的版本没有做过任何测试，依赖范围也不允许它们加载。
- Mixin 的注入目标固定为 `FilteringBehaviour#createBoard()`。如果某个机械动力版本改动了该方法，注入会失败并导致游戏在启动阶段报错退出。遇到这种情况请移除本模组并反馈具体的机械动力版本号。

## 安装

1. 安装对应版本的 Minecraft Forge（47 及以上）。
2. 将本模组与机械动力一起放入 `mods` 目录。
3. 本模组只修改客户端界面，服务端不需要安装，装在服务端也不会生效。

## 已知限制

- 安装本模组后，计数过滤可设置的最大值为 64，无法再通过计数过滤选取更大的数值。物品本身的大堆叠能力不受影响，机械动力对数值的存储与传输逻辑也没有改动。
- 如果在安装本模组之前已经把过滤数值设置得很大，建议打开一次界面重新调整。
- 本模组只处理上述界面膨胀问题，不涉及机械动力或其他模组的其他问题。

## 构建

```bash
# 需要 JDK 17。gradle.properties 中的 org.gradle.java.home 指向本机 JDK 路径，换机器时需修改。
gradle build
```

产物位于 `build/libs/create_guioverflowfix-<版本号>.jar`，该文件已经过 `reobfJar` 处理，可以直接放入 `mods` 目录使用。

编译期依赖：Forge 1.20.1-47.4.22、Mixin 0.8.5，以及放在 `libs/` 下的 Create 6.0.8、Ponder 1.0.91、Flywheel 1.0.5、Registrate 1.3.3（仅用于编译，运行时不打包）。

## 设计文档

`docs/specs/2026-08-24-create-gui-overflow-fix-design.md` 记录了问题定位过程与当时的候选方案。

需要注意：最终发布的是文档“备选方案”里收拢最大值的思路，文档 §4 描述的方案 A（固定宽度数值条、比例坐标映射、K/M/B 紧凑显示）与 §6 的配置项均未实现。实际功能以本 README 为准。

## 许可

本项目以 MIT 协议开源，详见 [LICENSE](LICENSE)。

## 相关链接

- 仓库：https://github.com/Lin1848624/create_guioverflowfix
- 下载：https://github.com/Lin1848624/create_guioverflowfix/releases

## 致谢

问题定位参考了机械动力上游 issue #6026（Stack Size above 64 causes issues on brass count filter）。
