# Minis Ultra 前端重构指导（给 Kimi 模型）

> 依据：① `ui-redesign/audit.md`（11-18 全 App 31 页走查 + Apple HIG 审计）
> ② `index-v9.html`（11-18 定稿的 iOS 风可交互设计稿，真实磨砂玻璃材质）
> ③ dev 分支 `996ea56` 之后的实际代码状态。
> **设计稿定稿后 dev 又前进了**——按本文档对齐，不要照搬 v9 静态稿里已过时的部分。

---

## 一、设计稿之后已经变了的东西（照 v9 会做错）

| 项 | 现状（dev@996ea56） | v9 设计稿的说法 | 怎么处理 |
|---|---|---|---|
| 计划条数据源 | `ce31f6f` 已删除 **自动计划板写入**（T-subagent-plan-autolog）。计划板只剩 agent_plan 工具写入，`PlanGoalCard.kt` 有 DONE_LINGER 滞留逻辑 | 「清单 3/8 · 子代理 0」常驻顶栏条 | 条改为**条件出现**：有 agent_plan 数据或有活动子代理才显示，否则隐藏；不要给它做常驻布局占位 |
| 顶栏第二抽屉行 | `356bd8e` 已落地：第二行**互斥展开、无外框**（ChatTopRightSecondRow.kt、A2aPanelActivity.kt） | v9 抽屉按旧版画 | 以 356bd8e 的交互为准重绘对应浮层，视觉风格沿用 v9 磨砂材质 |
| AddProviderScreen | 已抽取 `AddProviderCredentials.kt`；文件行数上限被棘轮下压到 **873 行** | 设计稿无此约束 | 改这个文件必须先看还剩多少余量，超线就抽新文件，绝不允许调 baseline 上升 |
| ProviderType.gateway | G-P3 已全链路落地后端（store/router/GatewayProvider，Room DB v10），**但零 UI** | v9 完全没有 gateway 页面 | 见下面「最大缺口」 |

## 二、最大缺口：Gateway（G-P3）完全没有前端

后端已就位（`app/gateway/`，别动它），UI 一张都没有。需要新建：

1. **渠道管理页**（`ui/settings/` 或新 `ui/gateway/`）：渠道列表（名称、协议 openai/anthropic、baseUrl、sortOrder），增删改。数据走 `GatewayRepository`（`gateway/store`），**UI 不得 import router 内部状态类**——只读 repository 暴露的方法。
2. **账号管理**：每渠道下多账号（优先级、并发上限 maxConcurrent）。API key 只进 `GatewayKeyStore`，UI 输入框一次性写入，**列表只显掩码（sk-…abcd），永不回显明文、永不落 Room**。
3. **模型别名映射页**：alias → channel+target 映射，供模型选择器用。
4. **账号健康徽章**：把 Router 状态可视化——正常 / 退避中 / 冷却中（倒计时）/ 熔断开（半开重试）/ 429 垫底。数据由 repository/router 提供 `snapshot()` 之类的只读出口（如没有就在 router 加一个只读方法，不改语义）。
5. **ProviderDetailScreen gateway 分支**：gateway 类型实例详情页显示渠道拓扑与聚合状态，而不是普通 provider 的 key/quota 页。
6. **ModelEntryPicker**：gateway 别名模型要出现在模型选择器里（G-P3 已接线 catalog，检查 `ModelCatalogCache` 的 gateway 来源是否已喂到 picker；没喂就补）。

## 三、v9 要落地的视觉规范（这些没变，照做）

- 真实磨砂玻璃：半透明材质 + 背景实时模糊，**无灰色蒙版、无灰板底色、无外框**（v9 最后一轮修正的核心）。
- 三层浮层：三点功能 / 任务清单 / 角色团队，全部同材质。
- 单色素图标统一（SF Symbols 风），替换现状三套混用图标。
- 深浅双主题同源色板，消除「深色聊天渐变 + 纯白设置」的明暗跳变。

## 四、设置 IA 重构（audit P0，仍未动）

1. 所有设置子页加**导航栏返回按钮**（现在只能侧滑/系统返回）。
2. 40+ 入口超长列表：收拢分组、高级参数（压缩阈值/重试次数/间隔/触发次数）收进二级「Agent 运行时」页。
3. 搜索去重：顶栏搜索与右下浮动球二选一。
4. ⋮ 菜单 15 项分层：高危（YOLO 全授权）独立权限模式页 + 状态条。
5. 敏感信息（API key / 环境变量 / SSH 主机）独立分组 + 遮挡展示。

## 五、硬约束（违反任意一条 CI 必挂）

1. **架构棘轮**：`scripts/ci/check_architecture_policy.py`，文件行数天花板只降不升。新 Composable 抽新文件，别往大文件里塞。
2. **CJK ratchet**：用户可见文案一律进 `res/values/strings.xml` + `values-zh/`（+其余 16 locale 至少英文兜底），Kotlin 里不许出现硬编码中文字符串；法语等撇号要 aapt 转义。
3. **string refs / untranslated**：所有 `R.string` 引用必须可解析，新增 key 必须翻译。
4. **测试命令**：没有 `compileDebugKotlin`；编译验证用 `:app:testReleaseUnitTest`（--tests 过滤跑 JVM 单测）。
5. **Room schema**：DB 已是 v10（`schemas/…/10.json`），gateway 表不许改列；UI 层新增查询走 DAO + repository，改表要出 11.json 迁移。
6. **分层**：`ui/` 不得被 `tools/`、`gateway/` import；UI 只消费 repository 暴露的接口。
7. 提交推 dev 触发 `android-apk.yml` CI。

## 六、建议顺序

1. 先做 gateway 三张管理页（缺口最大，且是新功能门面）；
2. 修计划条条件显示（小改，顺手）；
3. 设置 IA P0（返回按钮 → 分组收拢）；
4. 最后统一磨砂材质与图标（纯视觉全局扫）。

每步独立提交，`testReleaseUnitTest` + 四门禁绿了再推下一条。
