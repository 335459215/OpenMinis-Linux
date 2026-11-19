# Android/APK 设计规范与可复用项目调研

> 2026-11-18 补充调研。此前只查了 Apple HIG 与 modern-web-guidance，没有专项检索 Android/APK 规范；本文补齐。项目星数与活跃状态来自 GitHub API 当日查询。

## 1. 官方规范（必须保留的 Android 行为）

### Android UI 设计与 Material 3
- UI Design 总纲：https://developer.android.com/design/ui
- Material 3：https://m3.material.io/
- Compose 组件总览：https://developer.android.com/develop/ui/compose/components
- 底部导航栏：https://developer.android.com/develop/ui/compose/components/navigation-bar
- 自适应导航：https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation

对 Minis Ultra 的约束：
1. 手机保留四个底部一级目的地；平板/横屏用 NavigationRail，不简单拉宽手机底栏。
2. 二级页用 Compose 导航返回栈；系统返回键、预测性返回、侧滑返回和屏幕内返回按钮结果一致。
3. 输入区与底部 Sheet 正确处理状态栏、导航栏和键盘 WindowInsets。
4. 顶部搜索只保留一个入口；大标题可借鉴 iOS，但折叠和滚动行为用 Compose TopAppBar 实现。

### 质量与无障碍
- App Quality：https://developer.android.com/quality
- 核心应用质量：https://developer.android.com/docs/quality-guidelines/core-app-quality
- 无障碍开发：https://developer.android.com/guide/topics/ui/accessibility/apps

必须补进实现验收：
- 触摸目标建议至少 48×48dp；ⓘ 图标本体可以小，但点击区域不能只有 16–18dp。
- 所有纯图标按钮有 contentDescription；⚠/颜色不能单独承载危险或启用状态。
- 支持系统字体放大、TalkBack 焦点顺序、深浅色对比度、键盘/外接键盘操作。
- 组件标签使用用户语言，不能把 contentDescription 写成图标文件名。

## 2. 可参考/复用项目（已核验）

| 项目 | 核验情况 | 借鉴用途 | 结论 |
|---|---|---|---|
| [Taewan-P/gpt_mobile](https://github.com/Taewan-P/gpt_mobile) | 1276★；Kotlin；2026-11-18 活跃；Material3 + Compose | 多模型聊天、提供商/API key 管理、聊天与设置结构 | **优先精读源码**，但不能直接照搬 Agent 工具卡/终端 |
| [alorma/Compose-Settings](https://github.com/alorma/Compose-Settings) | 570★；Kotlin；2026-11-17 活跃 | 菜单链接、开关、单选、设置分组；Material 3 / Expressive | **可试点复用**，先核对许可证、依赖体积和项目 Compose 版本 |
| [Schott12521/compose-cupertino](https://github.com/Schott12521/compose-cupertino) | 174★；Kotlin；2026-09 更新；Apple 风组件 fork | Cupertino Sheet、Dialog、日期选择、图标等 | **只做视觉/交互参考**，不建议整套替换底层，避免 Android 返回与无障碍退化 |
| [Tweener/czan](https://github.com/Tweener/czan) | 83★；Compose Multiplatform；基于 Material 3；Apache-2.0 | Atomic Design 组件分层、设计系统组织 | 可参考设计系统分层，不是现成业务 UI |

## 3. 对当前重设计方案的修正

采用 **“iOS 视觉皮肤 + Android 原生交互骨架”**，不是把整个 APK 伪装成 iOS：

- 视觉：iOS 语义色、大标题、inset grouped 圆角列表、低干扰单色 SVG 图标。
- 交互：Android 返回栈/预测性返回、Compose Scaffold、ModalBottomSheet、WindowInsets、48dp 触摸区域。
- 图标：优先 Material Symbols 中形状明确者；仅自绘少量 SF 风图标。不能为了“全图标”牺牲辨识度，新增/不常见的图标保留短标签。
- 描述：详细解释收进 ⓘ toggletip；YOLO 等危险说明不能全藏，至少保留一行风险摘要及开启后的常驻状态条。
- 实现顺序：先统一图标/返回键/触摸区/Insets，再改设置分组，最后做四 Tab；信息架构改动必须与导航返回一起验收。

## 4. 调研边界

- 已查官方文档入口、GitHub 元数据与 README；尚未把这些库编进 Minis Ultra APK 验证二进制体积、ProGuard/R8、性能和兼容性。
- 尚未逐行阅读 gpt_mobile；它只能作为聊天/设置参考，Minis 的 Agent 工具执行、计划、子代理等仍需定制。
- 最终采用哪个库前，需要在本项目源码中核对 Compose BOM/Kotlin 版本与许可证。
