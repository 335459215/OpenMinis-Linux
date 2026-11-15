# 任务清单（PlanBoard）折叠紧凑化方案

## 现状问题（截图上半部分）

- 折叠态仍是整行卡片：宽 380dp、高 48dp，两行信息（标题 + 进度条）
- 单任务进程不建清单，用户看不到进度
- 展开/折叠状态不持久，每轮重建

## 目标

1. 单任务进程也渲染清单（1 条也显示）
2. 默认折叠
3. 折叠态收窄：最大 168dp、高 26dp、单行 11sp
4. 完成态自动收起，只留「已完成 n/n」

## 尺寸规格

| 元素 | 现值 | 新值 |
|---|---|---|
| 折叠态宽度 | 撑满（≈380dp） | `widthIn(max = 168.dp)`，内容自适应 |
| 折叠态高度 | 48dp | 26dp |
| 圆角 | 12dp | 13dp（胶囊） |
| 内边距 | 10×12dp | 8dp 横向 |
| 标题字号 | 13sp / 17sp 行高 | 11sp / 12sp，单行省略 |
| 状态点 | 8dp | 6dp |
| 计数 | 11sp 右侧 | 10sp 右侧，与标题同基线 |
| 展开面板 | 撑满 | `widthIn(max = 320dp)` |
| 进度条 | 3dp | 2dp |

## 行为规则

1. `items.isNotEmpty()` 即渲染，单任务同样显示（`total=1` 时胶囊只显示标题）。
2. `rememberSaveable { mutableStateOf(false) }`，默认折叠；展开状态按会话保存。
3. 点击胶囊切换展开/折叠，无长按、无菜单。
4. 全部完成：胶囊文字变「已完成 n/n」、状态点转绿，3 秒后自动收起。
5. 有 `failed` 项时自动展开一次，并把失败项排到面板首位。
6. 折叠态不显示进度条，只保留计数，省高度。

## 接入方式

1. 替换现有清单组件为 `PlanBoard.kt`（Compose，无第三方依赖）。
2. 调用点把 `items` 映射为 `PlanItemUi(title, status)`，status 取 `pending/active/done/failed`。
3. 宿主容器左侧留 12dp、右侧自适应，避免把胶囊拉满。
4. 单任务场景由 agent 侧保证至少一条 item（创建任务即建板）。

## 验收清单

- [ ] 单任务运行时有清单，且默认折叠
- [ ] 折叠态宽度 ≤168dp、高度 26dp
- [ ] 点击展开，面板宽度 ≤320dp
- [ ] 全部完成后 3 秒自动收起，文字为「已完成 n/n」
- [ ] 出现 failed 项自动展开一次
- [ ] 旋转屏幕/切换会话后折叠状态保留

## 参考实现

- 组件：`/var/minis/workspace/kotlin-memory/PlanBoard.kt`
- 原型：`/var/minis/workspace/kotlin-memory/plan-board-mock.html`
- 截图：`minis://browser/screenshot_1794641331.jpg`
