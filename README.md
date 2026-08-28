# Create GUI Overflow Fix — 设计文档

- 日期：2026-08-24（v2，按 Create 6.0.8 修正）
- 目标平台：Minecraft 1.20.1 · Forge 47.4.22 · Create 6.0.8（依赖 Flywheel / Ponder）
- mod id：`create_guioverflowfix`
- 状态：已按 6.0.8 反编译结果修正根因与修复点

---

## 1. 背景与问题

在 Minecraft Java 版 1.20.1 上安装机械动力（Create）6.0.8，并配合提高物品堆叠上限的模组（本机实际为 `biggerstacks-1.20.1-2026.06.17-all.jar`，可将堆叠数抬到百万乃至 `Integer.MAX_VALUE` 级）时：

- 打开"计数过滤"类**数值调节界面**（**智能溜槽 Smart Chute**、黄铜漏斗 Brass Funnel、黄铜隧道 Brass Tunnel 等）会崩溃或卡死。
- 该问题为 Create 自身的已知界面渲染 bug（上游 issue #6026 "Stack Size above 64 causes issues on brass count filter"）。
- 目前没有专门模组能完美解决。

## 2. 根因（已按 Create 6.0.8 反编译确认）

数值调节界面在 6.0.8 中由 `com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen` 渲染（**不再是旧版的 `ScrollInput`**）。因果链如下：

1. 智能溜槽用 `FilteringBehaviour`，其 `createBoard()` 生成界面数据：

   ```java
   // FilteringBehaviour.createBoard()
   int maxAmount = filter.getItem() instanceof FilterItem ? 64 : filter.getMaxStackSize(); // = 物品堆叠上限
   return new ValueSettingsBoard(..., maxAmount, 16, ...);
   ```

   即 `ValueSettingsBoard.maxValue` = 过滤物品的最大堆叠数，被 Bigger Stacks 抬到极大值。

2. `ValueSettingsScreen.init()` 用 `maxValue` 线性推导界面宽度，**无任何上限**：

   ```java
   int milestoneCount = maxValue / board.milestoneInterval() + 1;      // maxValue/16 + 1
   this.valueBarWidth = (maxValue + 1) * scale + 1 + milestoneCount * milestoneSize;
   int width = this.maxLabelWidth + 14 + (this.valueBarWidth + 10);
   this.setWindowSize(width, height);
   ```

   - `valueBarWidth ≈ maxValue × 1.25`，随堆叠数**无限变长**；
   - `maxValue == Integer.MAX_VALUE` 时 `(maxValue + 1)` 直接 int 溢出为负数。

3. 渲染与交互循环同样无界，直接导致崩溃 / 卡死：

   - `renderWindow()` 的里程碑循环 `for (milestone < milestoneCount)`（最多 ~1.3 亿次图标绘制）；
   - `renderWindow()` 的条底循环 `for (w < valueBarWidth)`；
   - `getClosestCoordinate()` 的 `while (column <= maxValue)`。

这就是用户观察到的"长度没设上限、跟着堆叠数值无限变长、最后超出屏障"。

**关键点**：修 `ValueSettingsScreen` 一处即可覆盖所有受影响界面（智能溜槽 / 黄铜漏斗 / 黄铜隧道等，它们共用 `ValueSettingsScreen` + `ValueSettingsBoard`）。

## 3. 目标与成功标准

- 无论计数过滤数值多大（含 `Integer.MAX_VALUE`），打开界面都不崩溃、不卡死、窗口不超出屏幕。
- 大数值以紧凑、可读的形式显示在光标文本中（如 `1,000,000` → `1.00M`、`2,147,483,647` → `2.15B`）。
- **只改渲染/交互层**，不改动 Create 的数值存储 / 传输逻辑，不影响 Bigger Stacks 的实际堆叠功能。
- 正常值（如 ≤ 999,999）的显示与原版一致，无副作用。
- 保留**全范围数值可选**：即便堆叠上限是数十亿，也能沿固定宽度的数值条按比例选取。

## 4. 方案（已确认：方案 A = Mixin）

用 **Mixin** 精准拦截 `ValueSettingsScreen` 的宽度计算与坐标映射，把"无上限的几何/循环"替换为"有界几何 + 比例坐标"，并对光标文本做紧凑格式化。

修复分四层（相互独立、叠加生效）：

1. **宽度硬性 clamp**：`@Inject` 到 `init()`（obf `m_7856_`）尾部，把 `valueBarWidth` 固定为常量 `BAR_WIDTH`（默认 200px），并以 `maxLabelWidth + 14 + BAR_WIDTH + 10` 重设窗口尺寸。
2. **比例坐标映射**（保留全范围）：
   - `@Overwrite getCoordinateOfValue(row, column)`：`x = guiLeft + maxLabelWidth + 18 + (column / maxValue) × BAR_WIDTH`，`y` 不变。
   - `@Overwrite getClosestCoordinate(mouseX, mouseY)`：由 x 比例反推 `column`，`row` 由 y 反推，按住 Shift 时吸附到里程碑（`milestoneInterval` 的整数倍）。
3. **里程碑/条循环有界**：`@Redirect` `renderWindow()` 中用于 `milestoneCount` 的 `board.maxValue()` 调用，钳制里程碑数量（≤ `MAX_MILESTONES`）。
4. **紧凑格式化**：`@Inject` 到 `ValueSettingsFormatter.format()` 的 RETURN，把光标文本里的超大整数缩写成 `K/M/B`（默认阈值 `1,000,000`，保留两位小数，`2.15B`）。

实现要点：

- Mixin 目标类：`com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen`、`...ValueSettingsFormatter`。
- 只改客户端渲染/交互，不改 `ValueSettingsBehaviour` / `FilteringBehaviour` 的服务端数值逻辑。
- 精确方法签名已通过反编译本机 `create-1.20.1-6.0.8.jar`（CFR）锁定（见下"构建与测试"）。
- Create 6.0.8 已把 UI 基类抽到 `net.createmod.catnip`（打包于 Flywheel/Ponder 依赖），`setWindowSize` / `guiLeft` / `guiTop` 等为 catnip 可读名，@Shadow 按名解析即可。

### 备选（已评估，不采用）

- **方案 B**：分别 mixin 每个具体 Screen。6.0.8 已统一为单一 `ValueSettingsScreen`，无需此方案。
- **方案 C**：直接 clamp 存储的计数值上限。能防崩但破坏 Bigger Stacks 大堆叠玩法，与"不改玩法"目标冲突。

## 5. 项目结构

基于 Forge MDK 1.20.1（ForgeGradle 47，Java 17）：

```
D:\mcmod\create_gui_bug_fix\
├── build.gradle            # ForgeGradle + Mixin 支持
├── gradle.properties       # 版本号、mappings 等
├── settings.gradle
├── gradle\wrapper\         # wrapper 等
├── src\main\java\com\createfix\createguioverflowfix\
│   ├── CreateGuiOverflowFix.java            # 主 Mod 类
│   └── mixin\
│       ├── ValueSettingsScreenMixin.java     # 核心修复（宽度 + 比例坐标 + 循环有界）
│       └── ValueSettingsFormatterMixin.java  # 紧凑格式化
├── src\main\resources\
│   ├── META-INF\mods.toml                   # Forge mod 元数据
│   ├── create_guioverflowfix.mixins.json    # mixin 配置
│   └── pack.mcmeta
└── docs\specs\...                           # 本设计文档
```

- 依赖：Create（仅编译期引用其类；运行时 Mixin 注入），Forge 47.4.x。
- 产物：一个 `.jar`，丢进 `mods/` 目录即可生效。

## 6. 配置项（可选，默认值即可用）

- `barWidth`：数值条最大显示宽度（像素），默认 `200`。
- `formatThreshold`：触发紧凑格式化的数值阈值，默认 `1,000,000`。
- `maxMilestones`：里程碑标记数量上限，默认 `16`。
- 采用轻量配置文件（`config/create_guioverflowfix.properties`），未配置时用内置默认值。

## 7. 构建与测试

1. **构建**：本机已验证 `maven.minecraftforge.net` / `files.minecraftforge.net` / `repo1.maven.org` / `libraries.minecraft.net` / `piston-meta.mojang.com` 可达；需 JDK 17（当前本机仅 JDK 25，需先装 17）。
2. **Mixin 目标**（已反编译 `create-1.20.1-6.0.8.jar` 锁定）：
   - `ValueSettingsScreen.m_7856_()`（= Mojmap `init()`）— 注入宽度 clamp；
   - `ValueSettingsScreen.getCoordinateOfValue(int,int):Vec2` — 覆盖为比例坐标；
   - `ValueSettingsScreen.getClosestCoordinate(int,int):ValueSettings` — 覆盖为反比例；
   - `ValueSettingsScreen.renderWindow(...)` 内 `board.maxValue()` 第 1 次调用 — Redirect 里程碑计数；
   - `ValueSettingsFormatter.format(ValueSettings):MutableComponent` — 注入紧凑格式化。
3. **测试**：
   - 在测试实例（Forge 47.4.22 + Create 6.0.8 + Bigger Stacks）放入本 mod。
   - 复现：把智能溜槽 / 黄铜漏斗计数过滤数值调至超大（1,000,000、2,147,483,647）。
   - 验收：不崩溃、不卡死、窗口不超屏；大数值以 `1.00M` 等紧凑形式显示；正常值显示不变；大范围仍可沿比例条选取。

## 8. 风险与开放问题

- **崩溃点交叉确认**：用户提供的 `D:\Downloads` 崩溃报告为无关的 `createendertransmission` NPE（`SmartFluidTank.setFluid` null savedData），非本次 GUI bug。因此以 Create 6.0.8 反编译代码为准定位根因（已明确为 `ValueSettingsScreen` 无界几何/循环）。
- **Create 版本差异**：mixin 目标锁定 6.0.8；若日后升到 6.0.9/6.0.10 等补丁版，`ValueSettingsScreen` 签名大概率一致，但实现时以实际 jar 反编译为准。
- **网络限制**：GitHub 域名不可达，不影响 Forge 构建源；Create 源码依赖改为直接反编译本机 jar。
