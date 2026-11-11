# Agent2API 内置网关 × 上游合并思路

> 写作时点：2026-11-11，`dev` HEAD `9ffe45ff`（2.0.91 / 293），`ahead=104 behind=2`（上游最新 `6b648bf4` #307）。
> 这份文档不是"计划"，是**合并动工前必须先读的裁决书**：每个接触点都已经查了证据、定了取哪边、给了验收方法。
> 它同时记录一个**当前 dev 上真实存在、每次冷启动都会触发**的缺陷（第 3 节）。

---

## 0. 一句话结论

A2A 内置网关和上游是**两条基本不相交的代码线** —— 104 个 dev 提交里，与 git-panel 相关的只有 4 个，其中 2 个是手抄上游修复。真正需要裁决的接触点只有 **5 个**，其中 4 个是机械冲突（取哪边有唯一正确答案），1 个是**必须修的架构缺陷**：A2A 同步出来的 provider 行，每次启动都会被我们自己的"退役车道清退"逻辑当成垃圾删掉，还顺手记墓碑。

---

## 1. 载荷事实：为什么必须留在 APK 里

| 载荷 | 体积 | 位置 |
|---|---|---|
| `liba2a_server.so` | 16,447,056 B | `jniLibs/arm64-v8a/` |
| `liba2a_providers.so` | 11,040,808 B | 同上 |
| `liba2a_web_providers.so` | 3,192,888 B | 同上 |
| `assets/a2a/`（面板 UI zip 等） | 6.4 MB | `assets/a2a/` |
| **合计** | **≈ 36.4 MB**（三件套 29.3 MiB + assets） | |

代码面：`app/src/main/java/com/openminis/app/a2a/` 下 **10 个 .kt**，全仓 `a2a/` 相关新增文件 **63 个**。

**为什么不能改成"首次启动下载"**：

1. 本机无 root（`ro.build.type=user`，无 su），装不了 KernelSU 模块，没有系统级目录可外置；
2. `assets/` 和 `jniLibs/` 之外的目录都要过 `purgeRetiredBuiltInLaneFiles()` 这类"按目录名清理"的代码 —— 第 3 节记录的那次事故就是这么来的（`builtin-plugins/a2a` 被 `deleteRecursively()`，账号每次重启归零）；
3. `liba2a_server.so` 是 bionic 产物（NDK r29 交叉编译），必须作为 native lib 被 `System.loadLibrary` 直接加载，解压到 `filesDir` 再 dlopen 会丢掉 `nativeLibraryDir` 的 SELinux 上下文。

**结论：载荷随 APK 走，网关随进程活。这条是硬约束，任何"精简包体"的提案先过这一关。**

---

## 2. 五个接触点：合并决定清单

### 接触点 1 — JGit 依赖重复（必冲突，机械）

**证据**：`git merge-tree` 写出的合并树里，同一坐标出现两次：

```
build.gradle.kts:471  implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.1.202611041320-r")
build.gradle.kts:588  implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.1.202611041320-r")
```

来源：dev `e95b81cc` 手抄了 #307，上游 `6b648bf4` 自己也带上了这一行。两边都从无到有，Git 不认为是冲突（两处都是新增），但合并后 Gradle 会因重复声明报错或产生重复类。

**裁决：删上游那处，留 dev 那处。**
理由不是"哪个先来"，而是 dev 那处的注释绑定了本机内存预算（第 5 节），删了会丢掉"JGit 进入 compile classpath 是 3072m 不够的原因之一"这段因果记录。

---

### 接触点 2 — `GitCli.kt` 的两条外移路线（必冲突，需手工裁决）

这是整次合并唯一需要动脑的地方。三方改动各自独立，但落在同一个文件上：

| 提交 | 侧 | 做了什么 |
|---|---|---|
| `0b1b62cf` #306 | 上游 | 17 个 shell 命令生成函数 → `GitCliCmd.kt`，`GitCli.kt` 只留 parse（52 insertions / 166 deletions） |
| `ee27f32f` | dev | 4 个 parse 函数 → `GitCliParse.kt`，`GitCli.kt` 留薄委托（818 → 689 行） |
| `1e1e8f6d` | dev | 手抄 #306 的**行为修复**（branchesCmd 引号/分隔符 + 坏 HEAD 历史 sha 直传回退），但改的是 `GitCli.kt` 里那些函数的**函数体** |

叠加结果：上游**删掉**的函数，dev **改过**它的身体 → Git 层 `modify/delete` 冲突，文本合并必挂。

行数现状：dev `GitCli.kt` 724 行，上游 670 行；上游留 11 个 `fun parse`，dev 里 17 个 shell 命令函数全在（因为没搬）。

**裁决（三步，顺序不能换）**：

1. **`GitCliCmd.kt` 收编为唯一命令源** —— 取上游文件，不保留 dev 里的命令函数副本。
2. **把 `1e1e8f6d` 的修复 patch 进 `GitCliCmd.branchesCmd`** —— 对照那次提交的 diff 逐条搬，别凭记忆重写。这是行为修复，丢了就是功能回退。
3. **`GitCliParse.kt` 独立保留** —— 它管**解析**，`GitCliCmd` 管**命令生成**，两者正交，互不覆盖。`GitCli.kt` 里那 4 个委托（`splitRename` / `parseRemote` / `parseCommitDetail` / `parseCommitNumstat`）原样不动，所有既有 `GitCli.X` 调用点继续编译。

**验收**：`GitCliParseTest` 与上游 GitCliCmd 相关测试**同时**绿。任何一边红了都说明第 2 步搬错或第 3 步多删。

---

### 接触点 3 — git-panel 编排层（零冲突，直接取上游）

**证据**：`git diff --stat dev upstream/main` 在这几个文件上**输出为空**：

```
app/src/main/java/com/openminis/app/git/GitHostRepo.kt
app/src/test/java/com/openminis/app/git/GitHostRepoTest.kt
app/src/main/java/com/openminis/app/ui/git/GitPanelViewModel.kt
```

API 面 `diff`（`fun` / `class` / `object` 行）也**逐行相同** → dev 的手抄版与上游 #307 版本**字节级等价**。

**裁决：合并时这些文件无脑取上游。dev 侧零损失。**
这条的意义是：`e95b81cc` 这个手抄提交在合并后**完全被上游吸收**，不留任何遗留技术债 —— 手抄是当时抢时间的正确选择，不是错误。

---

### 接触点 4 — proguard JMX 规则（重复但无害）

**证据**：dev `9ffe45ff` 加了 `-dontwarn java.lang.management.**`（注释说明"Android 既无该包也无 MBean"）；合并树里 `java.lang.management` 命中 **2 次**。

**裁决：合并后去重到一条**，保留带理由的那条注释。`-dontwarn` 重复不报错，但两行相同规则会让下一个人以为"这是两个不同问题"。

---

### 接触点 5 — `gradle.properties`（**必须 ours，绝不可取上游**）

两份文件已经完全分叉，且**优化的不是同一件事**：

| | dev | 上游 |
|---|---|---|
| `org.gradle.jvmargs` | `-Xmx1536m -XX:MaxMetaspaceSize=384m` | `-Xmx4096m` |
| `kotlin.daemon.jvmargs` | `-Xmx3072m -XX:MaxMetaspaceSize=512m` | `-Xmx4096m` |
| 构建策略 | **两阶段**：Kotlin 在 daemon，R8 单独一轮 | 单阶段，worker 压到 1 |
| 显式 JDK | `/opt/jdk17`、`java-21-openjdk-arm64`，自动探测关 | 无 |

**裁决：`git checkout --ours src/android/gradle.properties`。**

理由（写在这里，免得下次有人"顺手统一"）：

- 上游的 4096+4096 是在**别人设备 / 不同 worker 数**下测出来的；本机可用内存约 **5 GB**，两峰叠加直接撞 LMK。
- dev 版把两个峰值**在时间上拆开**：Kotlin 编译（daemon 3072）与 R8（Gradle 4608，见文件末尾配方）互不重叠，4608 < 5 GB 才成立。
- dev 版注释已经写明："Collapsing both into one invocation re-uses one Gradle heap for R8 and OOMs" —— 这是实测结论，不是理论。
- 上游版没有 `java.installations.paths`，在本机会探测到无 javac 的 JRE。

**这一条如果取错，表现是构建中途 `Killed`，而不是编译报错 —— 最容易误判成"代码有问题"。**

---

## 3. 必须修的缺陷：A2A 行每次冷启动都被当退役车道删掉

### 3.1 证据链

```
A2aProviderBridge.kt:46        const val INSTANCE_ID = "builtin-agent2api-local"     ← 以 builtin- 开头
BuiltinLaneRetirement.kt:52    id.startsWith("builtin-") && id !in activeInstanceIds ← 命中，判死
BuiltinFreeLanes.kt:163        RETIRED_IDS = setOf("builtin-a2a-gateway")            ← 记的是旧 id，拦不住新 id
assets/free_lanes.json         instanceIds = [builtin-opencode-zen, builtin-kilo]    ← activeIds 不含 A2A
ProviderConfigStore.kt:445     purgeRetiredBuiltInLanes(activeIds)                   ← 在 seedBuiltInFreeLanes 里，无条件跑
ProviderRepository.kt:173      configStore.seedBuiltInFreeLanes()                     ← 配置初始加载完成时调一次
```

`ProviderRepository.kt` 的启动序列（`configLoadComplete` 之后）：

```kotlin
try {
    ensureVoiceTemplateModels()
    configStore.seedBuiltInFreeLanes()   // ← 这里面第一件事就是 purge
} catch (e: Exception) { ... }
```

purge 走的是 `repo.removeInstance()`，一条链全带走：

```
removeInstance(id)
  ├─ config.instances.removeAll        ← 行没了
  ├─ config.modelEntries.removeAll     ← 模型没了
  ├─ modelGroups.memberEntryIds.removeAll + agentLoopModelEntryIds.removeAll
  ├─ 空分组被删（含 defaultPrimaryGroupId / defaultSubGroupId 置空）
  ├─ FreeLaneBootstrap.forgetInstance(config, id)   ← **记实例墓碑**
  ├─ deleteApiKey(id)                                ← 凭证没了
  ├─ OAuthManager.logout()（若曾是 OAuth 行）
  └─ prefs.remove("voiceShadowDisabled.$id")
```

而重建发生在**网关起来之后**：`A2aKeepAliveService` 每轮循环 → `A2aGateway.ensureStarted()` → `isRunning()` 为真 → `A2aProviderBridge.sync()` → `addInstance()`。

### 3.2 后果

- **每次开机有一段窗口**：A2A 行已被 purge，网关还没起 →「AI 服务商」里看不到 Agent2API，模型选择器里它的模型消失。用户感知是"A2A 又没了，过一会儿自己回来"。
- **有状态的引用不会自动恢复**：`modelGroups.memberEntryIds`、`agentLoopModelEntryIds` 被清掉后，重建只加行和模型，**不恢复分组归属与 agent-loop 成员资格** → 用户配好的 agent-loop 槽位静默失效。
- **墓碑在累积**：每启动一次记一条（`record` 幂等，所以只一条），但 `addInstance` **当下不检查墓碑**。这条链路能自愈，纯粹是依赖"addInstance 不检查墓碑"这个当前事实 —— 哪天有人给 `addInstance` 加一句墓碑检查（这是很自然的加固方向），A2A 行就**永久消失**，且表现为"删了再也不回来"，极难定位。

> 这不是噪点，是定时炸弹。修它不是美化代码，是拆引信。

### 3.3 这是同类事故的第二次

`ProviderConfigStore.purgeRetiredBuiltInLaneFiles()` 里已经躺着一条注释：

```kotlin
// [A2A-data-guard] 这一版重新内置了 Agent2API 网关（见 app/a2a/），
// 所以 `builtin-plugins/a2a` 不再是「退役 lane 的残留」。
// 原来这里无条件 deleteRecursively()，会在每次启动时把网关的
// agent2api.db、account.key 和插件包一起删掉 —— 用户加好的账号
// 每次重启都归零。目录本身由 a2a 模块自己管理，这里不再动它。
```

**同一个根因在文件层犯过一次，修了；在 instance 层没修。** 根因是一句话：*我们的清退逻辑不认识我们自己的 A2A 东西。* 任何"按前缀/目录名判定归属"的清理代码，都必须显式排除 A2A 的资产。

### 3.4 修法（最小改动，保住凭证与老用户的行）

**不改 id** —— `builtin-agent2api-local` 是 `addInstance` 写入 `provider_config.json` 的既有值，老用户机器上存的就是它；改 id 等于丢行、丢 `apikey_<id>`、丢墓碑语义。

在 veto 集合里加白名单：

```kotlin
// BuiltinFreeLanes.kt —— 紧挨 RETIRED_IDS
/**
 * A2A 网关自己管理的行：不是 lane，不进 manifest，按 id 常驻 veto 集合。
 *
 * 与 [RETIRED_IDS] 方向相反：那边记"已经下线的 id"，这边记"虽然带 builtin-
 * 前缀但归 a2a 模块所有、任何清理都不许碰的 id"。两者都必须在
 * [BuiltinLaneRetirement] 的判定之前生效，否则带 builtin- 前缀的自有行会被
 * 当退役 lane 删掉 —— 文件层已经炸过一次（见 purgeRetiredBuiltInLaneFiles
 * 的 [A2A-data-guard]），instance 层不能再来一次。
 */
val A2A_RESIDENT_IDS: Set<String> = setOf("builtin-agent2api-local")
```

`seedBuiltInFreeLanes()` 的 activeIds 改组：

```kotlin
val activeIds = lanes.map { it.instanceId }.toSet() +
    BuiltinFreeLanes.A2A_RESIDENT_IDS +
    if (!manifest.remoteOk) FreeLaneManifest.loadActiveIds(context) else emptySet()
```

新增一条测试（`BuiltinLaneRetirementTest`）：

```kotlin
@Test
fun theA2aRowSurvivesTheBootPurge() {
    // The A2A gateway seeds its provider row at网关就绪之后 —— 晚于 purgpass。
    // 若它被当成 manifest 掉的 builtin- 行，每次冷启动都会先删后建，且连带
    // 清掉分组/agent-loop 归属与凭证。
    val config = ProviderConfig(
        instances = mutableListOf(instance("builtin-agent2api-local")),
    )
    assertTrue(BuiltinLaneRetirement.instancesToPurge(config).isEmpty())
}
```

`instancesToPurge` 本身**不用改** —— 白名单通过 `activeInstanceIds` 生效，维持"单一判定点"的设计。

---

## 4. 三条设计原则（应写进 a2a 包的注释）

1. **载荷随 APK 走，网关随进程活。**
   三个 `.so` 在 `jniLibs/`，面板在 `assets/a2a/`。任何"按目录名清理"的代码，动手前先问：这个目录归谁？A2A 的资产由 a2a 模块自己管理。

2. **同步失败不能拖垮网关。**
   `A2aGateway` 里 `A2aProviderBridge.sync()` 整段 `runCatching` 且吞异常，注释写明"失败不能影响网关本身"。**网关起来是硬目标，provider 行是软目标** —— 这个优先级不能反。反过来做的后果是：provider 同步的一次异常让整个网关起不来，用户连 Web 面板都进不去。

3. **A2A 行不 seed 静态模型表。**
   `A2aProviderBridge` 写的是 `providerType=openAI` + `customBaseURL=http://127.0.0.1:18901` → `isThirdPartyOpenAICompat()` 返回 true → `addInstance` **跳过** `builtInModels` 播种，交给 `refreshModels()` 打 `/v1/models`。
   这是正确的：网关上了什么模型，面板里就该出现什么。**不要为了"首屏不为空"给它加静态 seed** —— 那会让面板长期显示网关根本没有的模型，重演 Grok 返回 GPT-5.5 那类 bug。

---

## 5. 合并执行顺序

```bash
# 0. 前置：确认 dev 工作区干净
git status --porcelain            # 应为空

# 1. 合并（预期 4 个冲突文件）
git merge upstream/main
#    build.gradle.kts / GitCli.kt / proguard-rules.pro / gradle.properties

# 2. 接触点 1：build.gradle.kts —— 删上游那行 JGit（:588 附近），留 dev 的（:471）

# 3. 接触点 2：GitCli.kt
#    3a. 取上游版本（GitCliCmd 收编）
#    3b. 把 1e1e8f6d 的引号/分隔符 + 坏 HEAD sha 直传修复 patch 进 GitCliCmd.branchesCmd
#    3c. 保留 GitCliParse.kt 与 GitCli.kt 里 4 个 parse 委托

# 4. 接触点 4：proguard-rules.pro —— java.lang.management 去重到一条

# 5. 接触点 5：gradle.properties —— 必须 ours
git checkout --ours src/android/gradle.properties
git add src/android/gradle.properties

# 6. 打 A2A 白名单补丁（第 3.4 节）+ 新测试
#    然后重新 git add

# 7. 构建（两阶段，缺一不可）
./gradlew :app:compileReleaseKotlin
./gradlew :app:assembleRelease \
  -Dorg.gradle.jvmargs="-Xmx4608m -XX:MaxMetaspaceSize=768m"

# 8. 单元测试
./gradlew :app:testReleaseUnitTest \
  --tests '*GitCliParseTest' \
  --tests '*GitHostRepoTest' \
  --tests '*BuiltinLaneRetirementTest'
```

---

## 6. 验收清单

| # | 项目 | 通过判据 |
|---|---|---|
| 1 | 合并无残留冲突 | `git status` 无 `UU`/`AA`；`git grep -c 'org.eclipse.jgit' build.gradle.kts` == 1 |
| 2 | 命令源唯一 | `GitCli.kt` 里不再有 `probeCmd`/`statusCmd`/`branchesCmd` 等函数体（只有 parse 与委托） |
| 3 | 行为不回归 | `GitCliParseTest` + `GitHostRepoTest` + `BuiltinLaneRetirementTest` 全绿 |
| 4 | A2A 行抗清退 | 新测试 `theA2aRowSurvivesTheBootPurge` 绿；**真机**冷启动后「AI 服务商」里 A2A 行始终在 |
| 5 | 网关可用 | 冷启动 → 面板能打开 → `/v1/models` 有模型 → 面板模型列表非空 |
| 6 | 分组不丢 | 升级前把 A2A 某模型加进 agent-loop，升级后仍在 |
| 7 | 构建不 OOM | 两阶段都跑完，中途无 `Killed` |
| 8 | 版本/签名 | versionCode/versionName 递增；签名 DN 与已装包一致（覆盖安装不丢 filesDir / rootfs） |

---

## 7. 未验证项（如实标注）

- **A2A 行被 purge 后的实际用户可见窗口长度**：取决于 `A2aKeepAliveService` 启动时机与 `ensureStarted` 就绪耗时（`A2aGateway` 里是 `deadline = now + 20_000`）。未在不同机型上实测。
- **分组/agent-loop 归属被清后是否真的不恢复**：`addInstance` 只加行与模型，代码里未见恢复分组的路径；`FreeLaneBootstrap.restore` 是给内置免费档用的，A2A 不走它。结论基于静态阅读，未做真机对照实验。
- **上游 `behind=2` 之外是否还有未推送的 #307 后续修复**：本次只核到 `6b648bf4` 与 `0b1b62cf`。
- 第 1 节的体积是**当前工作区**的 `.so` 实测值，与 APK 内是否完全一致见历史记录（`.text` 段比对曾用于确认同一份二进制）。
