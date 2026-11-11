# 上游合并避坑手册（upstream → dev）

> 写作时点：2026-11-11，`dev` HEAD `ccfb5c38`（2.0.91 / 293）。
> 这是 [`2026-11-11-a2a-upstream-merge-strategy.md`](./2026-11-11-a2a-upstream-merge-strategy.md) 的**执行后补篇**。
> 裁决书讲"该怎么合"，这份讲"合的时候会在哪儿翻车、以及怎么一眼看出翻车了"。
> 裁决书里 **有 3 处与实测不符**，第 6 节逐条列出，照它做会走弯路。

---

## 0. 怎么用

下次拉上游、或者做任何 A2A × git-panel 的合并，按这个顺序走：

1. 先跑**第 1 节判据速查表**里的 6 条命令，30 秒内知道当前树是干净还是烂的；
2. 动了 `GitCli.kt` 就看**第 2 节**——那里的坑全是 `git` 不报冲突、测试也不报错的静默故障；
3. 要构建就按**第 4 节**走，别信 `./gradlew` 直接跑；
4. 要推送就按**第 5 节**，别把 token 拼进 URL。

---

## 1. 判据速查表（复制就能跑）

| # | 检查项 | 命令 | 期望值 |
|---|---|---|---|
| 1 | JGit 坐标唯一 | `grep -c 'implementation("org.eclipse.jgit' src/android/app/build.gradle.kts` | **1** |
| 2 | 命令源唯一 | 见下方 **2.0 自维护判据**（不要硬编码函数名单） | 同名定义残留 **0** |
| 3 | parse 委托齐全 | `grep -c 'GitCliParse\.' src/android/app/src/main/java/com/openminis/app/git/GitCli.kt` | **5**（1 个 `NOISE_LINE` + 4 个委托） |
| 4 | 冲突标记清零 | `grep -rn '^<<<<<<< \|^>>>>>>> ' --include='*.kt' --include='*.kts' --include='*.pro' . \| grep -v /build/` | 空 |
| 5 | gradle 内存是我们那份 | `grep -nE 'jvmargs' src/android/gradle.properties` | `-Xmx1536m` + kotlin.daemon `-Xmx3072m` |
| 6 | 三组测试 | 见第 1.1 | 11 + 48 + 27 = **86 全绿** |

> **#2 踩过的坑**：最初写成 `grep -c 'probeCmd\|branchesCmd\|statusCmd' GitCli.kt` 期望 0，实测 **5**——差点误判成"命令源没搬干净"。
> 那 5 处全是 **`GitCliCmd.probeCmd(...)` / `GitCliCmd.branchesCmd(root)` 这样的委托调用**（`GitCli.kt:348,360,366,408` 与 `:225` 的注释），正是应该留下的东西。
> **判残留必须匹配 `fun <名字>` 定义行**，匹配裸名字永远大于 0。

> **#3 的 5 处**：`GitCli.kt:147` 的 `NOISE_LINE` + `:311/315/319/328` 的 `splitRename` / `parseRemote` / `parseCommitDetail` / `parseCommitNumstat` 委托。
> 若这里变成 1，说明 4 个 parse 又被求回 `GitCli.kt` 了（2.3 记的那个事故）。

### 1.1 测试命令（三组，缺一不可）

```bash
cd src/android
env -u _JAVA_OPTIONS -u JAVA_TOOL_OPTIONS ./gradlew :app:testReleaseUnitTest \
  --tests 'com.openminis.app.data.repository.BuiltinLaneRetirementTest' \
  --tests 'com.openminis.app.git.GitCliParseTest' \
  --tests '*GitHostRepoTest' \
  --console=plain --no-daemon
```

> `env -u _JAVA_OPTIONS` **不是可选项**，原因见 4.1。

判绿要读 `build/test-results/testReleaseUnitTest/TEST-*.xml` 里的 `tests=` / `failures=`，不要只读 `BUILD SUCCESSFUL`——`--tests` 写错名字会「成功跑 0 个用例」然后照样 BUILD SUCCESSFUL。

### 1.2 还要核「这批测试结果是不是覆盖了当前这棵树」

Gradle 会**复用**上次的 `test-results` XML。所以"XML 里写着 0 failures"只证明**那一次**跑的是绿的，不证明**你现在这棵树**是绿的。

本次就撞上了：XML 时间戳 **11:27**，合并提交 **12:58**——差 91 分钟。判据是**对比测试时刻与源码最后改动时刻**：

```bash
cd /tmp/openminis
# 1) 测试结果生成时间
ls --time-style=+%H:%M:%S -la src/android/app/build/test-results/testReleaseUnitTest/TEST-*.xml | awk '{print $6}'

# 2) 11:27:00 之后是否还有 .kt/.kts 被改（期望 0）
find src/android/app/src src/android/app/build.gradle.kts \
     -newermt '2026-11-11 11:27:00' \( -name '*.kt' -o -name '*.kts' \) | wc -l
```

本次实测：9 个变更文件里最晚改动是 `GitCli.kt` **11:24:41**，早于测试 11:27；11:27 之后 `.kt/.kts` 改动计数 **0** → 那批 86 绿**确实覆盖了 `ccfb5c38` 的树**。

> 若计数不为 0，那批 XML 作废，必须重跑。**看时间戳，不看有没有 XML。**


---

## 2. 六个静默陷阱（git 不报冲突，测试也不报错）

### 2.0 判据要「自维护」，别硬编码函数名单

**这是判据 #2 的第二版，第一版是错的。**

第一版写的是枚举名单：

```bash
# ✗ 错：只列了 11 个名字
grep -cE '^\s*fun (probeCmd|statusCmd|branchesCmd|logCmd|numstatCmd|remoteCmd|graphLogCmd|stashCmd|pushCmd|pullCmd|graphLogCmdFallback)\b' GitCli.kt
```

问题：`GitCliCmd.kt` 实际有 **17 个 `fun` = 15 个命令生成器 + 2 个辅助**（`gitPrefix`、`shellQuote`）。上面漏了 4 个：

```
commitDetailCmd  commitNumstatCmd  commitFileDiffCmd  buildCommitCommand
```

`buildCommitCommand` 连 `*Cmd` 正则都匹配不到（结尾是 `Command` 不是 `Cmd`）。**硬编码名单的判据会随着代码增长悄悄失效，而且失效方向是"假绿"**——最危险的那种。

**正确做法：从 `GitCliCmd.kt` 反查同名定义**，名单自动跟随源码：

```bash
cd /tmp/openminis
G=src/android/app/src/main/java/com/openminis/app/git/GitCli.kt
C=src/android/app/src/main/java/com/openminis/app/git/GitCliCmd.kt

hits=0
for n in $(grep -oE '^\s*fun [A-Za-z0-9_]+' "$C" | sed 's/^\s*fun //'); do
  c=$(grep -cE "^\s*fun ${n}\(" "$G")
  [ "$c" != "0" ] && { echo "残留: $n ($c)"; hits=$((hits+1)); }
done
echo "同名定义残留数: $hits (期望 0)"
```

本次实测：`GitCliCmd.kt` 17 个 fun，在 `GitCli.kt` 里的同名定义残留 **0**。参考值 `GitCli.kt` 共 12 个 fun（4 个 parse 委托 + 4 个 Cmd 委托 + 生命周期命令）。

> 这条判据同时防两件事：命令源被求回 `GitCli.kt`（2.3 那个事故），以及上游新增命令函数后有人手抄一份到 `GitCli.kt`。

---

### 2.1 JGit 重复坐标：git 看不见的冲突

**症状**：合并干净收口，`git status` 全绿，Gradle 报 duplicate coordinate / 重复类。

**成因**：两个分支各自**从无到有**引入同一行。git 看到的是两处独立 insertion，不是修改同一行，所以不判冲突，两行都保留。

**实测位置**：合并树 `build.gradle.kts:471`（dev 那份，带内存预算注释）与 `:588`（上游 #307 那份）。

**做法**：留 `:471`，删 `:588`，在 `:588` 原位留一段说明注释（说明为什么这里空了）。判据是第 1 节 #1。

**为什么留 `:471` 而不是"哪个先来"**：那处的注释绑定了本机内存预算的因果记录（JGit 进 compile classpath 是 Kotlin daemon 需要 3072m 的原因之一），删了以后没人知道为什么堆要给这么大。

---

### 2.2 `NOISE_LINE` 是 `val`，按「函数块」扫会把它整段吞掉

**这是本次唯一真正让人卡住的地方。**

`GitCli.kt` 里：

```kotlin
private val NOISE_LINE = Regex(
    "(?i)\\bkilled\\b|/bin/bash: line \\d+|^\\(\\s*…",
)
```

`val` 初始化表达式**块内没有 `{`**。任何"从 `fun xxx` 往下扫到配平花括号"的脚本都会因为 `{` 计数为负/不配平，一路扫进紧随其后的 `parseProbe`，把它一起搬走或删掉。

**症状**：`error: unresolved reference 'parseProbe'`（`GitCli.kt:470`），而 diff 看起来"只是搬了个函数"。

**做法**：按 `val`/`fun` 切片时，**先判断首行是不是 `val`，`val` 只吃到第一个行尾的 `)` 或该表达式结束**，不要用花括号配平。搬完立刻 `grep -c 'fun parseProbe'` 应为 **1**，且花括号总数 `{` 与 `}` 相等。

**如果已经删掉了**：`parseProbe` 的完整实现可以从 `git show <上一步sha>:<path>` 取回，本次是 26 行、`fcf16a1f` 补回。

---

### 2.3 测试全绿 ≠ 结构符合裁决

**我实际犯过**：把 4 个 parse 函数求回 `GitCli.kt`、删掉 `GitCliParse.kt`，`GitCliParseTest` + `GitHostRepoTest` 照样 **59/59 全绿**。

原因：那 4 个函数在两边**逐字节等价**，测试从公开 API 打进去，看不出实现住在哪个文件。

**结论**：
- 测试是**行为**判据，不是**结构**判据；
- 结构要用第 1 节 #2/#3 的 `grep -c` 来判；
- 谁把 `GitCliParse.kt` 删了，测试不会拦你，下次合并才会炸。

---

### 2.4 行数不能当验收判据

裁决书接触点 2 写 `GitCli.kt` 收口后 **689 行**。**这是错的**——689 是 dev 单侧做了 parse 外移后的数，叠加上游 #306 的命令外移之后正确答案是 **572**。

| 文件 | 行数 | 归属 |
|---|---|---|
| `GitCliCmd.kt` | **173** | 上游 #306，17 个 `fun` = 15 个命令生成器 + `gitPrefix` / `shellQuote` |
| `GitCliParse.kt` | **142** | dev `ee27f32f`，4 个纯解析函数 |
| `GitCli.kt` | **572** | 数据模型 + 4 个薄委托 + 生命周期命令 |

> 看到 572 就去"把缺的 117 行找回来"会直接把结构搞坏。**判结构用 grep，不用 `wc -l`。**

---

### 2.5 `git add` 和 `git status` 写在同一块里，读到的是 add 之前的状态

**症状**：`git add` 之后紧接着 `git status --short`，输出里还写着 `D  GitCliParse.kt` + `?? GitCliParse.kt`，看起来像"文件被删了"。

**真相**：shell 是顺序执行的，那段 `status` 在 `add` **之前**就跑完了。再读一遍就正常（变成 `M`）。

**做法**：`add` 之后**单独**再跑一次 `git status`；核一致性用

```bash
git diff --name-only | wc -l        # 未暂存文件数，期望 0
git diff --cached --shortstat       # 暂存内容
```

另：`MM` 表示**索引和工作区都改过**，别当成"有一种改动"。要分别看 `git diff`（工作区 vs 索引）和 `git diff --cached`（索引 vs HEAD）。

---

### 2.6 裁决书点名的测试根本不存在

裁决书验收清单第 4 项写 `theA2aRowSurvivesTheBootPurge` 绿。

**实际名字是 4 个**（`BuiltinLaneRetirementTest.kt`）：

```
aResidentRowIsNeverPurgedByTheDefaultSet
aResidentSurvivesEvenWhenTheManifestRuled
theResidentIdMatchesTheBridgeConstant
aResidentIsNotALaneForManifestDedupe
```

`grep theA2aRowSurvivesTheBootPurge` 返回空，会被误读成"测试没写"。**要 grep `aResident`。**

---

## 3. A2A 白名单：阈值放在哪儿是有讲究的

修法落在两个文件，**缺一不可**：

```kotlin
// BuiltinFreeLanes.kt
val RESIDENT_INSTANCE_IDS: Set<String> = setOf("builtin-agent2api-local")
```

```kotlin
// BuiltinLaneRetirement.kt — instancesToPurge 内部，when 的第一支
when {
    id in BuiltinFreeLanes.RESIDENT_INSTANCE_IDS -> false   // 硬地板
    id in BuiltinFreeLanes.RETIRED_IDS -> true
    id.startsWith("builtin-") && id !in activeInstanceIds -> true
    else -> false
}
```

**为什么不能放在调用方**：seed 通道的 active set 是**只由 manifest 算出来的**（`seedBuiltInFreeLanes` → `ALL_INSTANCE_IDS`），而 manifest 根本不认识"不是车道、但属于我们"的行。放调用方 = 把保证押在"每个未来的调用方都记得加回来"上。

**为什么 id 不能改名**：`builtin-agent2api-local` 是**既有存储值**，改了就孤儿化所有已装用户的行。

**防漂移**：`theResidentIdMatchesTheBridgeConstant` 把这里的字面量与 `A2aProviderBridge.INSTANCE_ID` 钉在一起，两边改一边测试就红。

---

## 4. 构建环境的坑

### 4.1 `_JAVA_OPTIONS` 是追加语义，会盖掉命令行 `-Xmx`

`/etc/profile.d/minis.sh:76-77` 会注入：

```sh
export _JAVA_OPTIONS="${_JAVA_OPTIONS:+$_JAVA_OPTIONS }-Xmx${_jvm_mb}m -XX:MaxMetaspaceSize=512m"
```

`_JAVA_OPTIONS` 是 JVM **最后**读取的选项，**优先级高于命令行**。所以：

- 你传 `-Xmx3072m`，JVM 实际吃到 `1892m`；
- Kotlin daemon 要 3072m → 起不来 → `Failed connecting to the daemon in 4 retries` ×4。

**判据**：日志里出现 `Picked up _JAVA_OPTIONS:` 就说明中了。成功那次的日志里这一行**必须是 0 次**。

**做法**：

```bash
env -u _JAVA_OPTIONS -u JAVA_TOOL_OPTIONS ./gradlew …
```

### 4.2 `gradle.properties` 必须 ours，取错的表现是 `Killed` 而不是编译错误

| 来源 | `org.gradle.jvmargs` | `kotlin.daemon.jvmargs` | 阶段 |
|---|---|---|---|
| dev（必须留） | `-Xmx1536m -XX:MaxMetaspaceSize=384m` | `-Xmx3072m` | 两阶段，峰值在时间上拆开 |
| 上游 #307 | `-Xmx4096m` | `-Xmx4096m` | 单阶段，峰值叠加 |

本机可用约 5GB。取上游那份会**同时**吃掉 8GB 预算，表现是构建中途 `Killed`（OOM killer），不是编译报错——很容易误判成"代码有问题"。

### 4.3 两阶段构建，顺序不能换

```bash
./gradlew :app:compileReleaseKotlin          # 先单独编译
./gradlew :app:assembleRelease \
  -Dorg.gradle.jvmargs="-Xmx4608m -XX:MaxMetaspaceSize=768m"   # 再打包，R8 单轮吃满
```

### 4.4 proguard JMX 只留一条

`-dontwarn java.lang.management.**` 在 `proguard-rules.pro` 里 **计数必须为 1**。重复虽然无害，但说明合并把两边都留下了，值得顺手清。

---

## 5. 推送

`origin` 是 `https://github.com/335459215/OpenMinis-Linux.git`，**URL 里没有内嵌凭据**，直连 `git push` 会报 `could not read Username for 'https://github.com'`。

**不要**把 token 拼进 URL（会落盘到 `.git/config`、进 shell 历史、进 `ps`）。用临时 credential helper：

```bash
cat > /tmp/git-cred.sh <<'EOF'
#!/bin/sh
case "$1" in
  get) echo "username=x-access-token"; echo "password=${GITHUB_TOKEN}";;
  *)   exit 0;;
esac
EOF
chmod 700 /tmp/git-cred.sh

git -c credential.helper=/tmp/git-cred.sh push origin dev
rm -f /tmp/git-cred.sh
```

**推送后必须双向核验**（只看 push 的退出码不够）：

```bash
git ls-remote origin refs/heads/dev          # 应与本地 HEAD 相等
git fetch origin dev
git rev-list --left-right --count origin/dev...HEAD   # 期望 "0  0"
```

任何输出里可能带 token 的地方都过一遍 `sed -E 's#x-access-token:[^@]*#x-access-token:***#g'`。

---

## 6. 裁决书与实测的 3 处偏差

| # | 裁决书写的 | 实测 | 怎么办 |
|---|---|---|---|
| 1 | `GitCli.kt` 收口 **689 行** | **572 行** | 别拿行数验收，用 grep（见 2.4） |
| 2 | 第 3b 步"把 `1e1e8f6d` 的修复 patch 进 `GitCliCmd.branchesCmd`" | 上游 `6b648bf4` 的 `branchesCmd` **已自带**同一修复：单引号 `--format='…'`、`%1f` 分隔、第 4 列 `%(symref)`；坏 HEAD 的 `graphLogCmdFallback`（走 shas 绕开 HEAD）也在 | **空操作**，别去找 patch 点 |
| 3 | 验收清单第 4 项测试名 `theA2aRowSurvivesTheBootPurge` | 该名字不存在，实际是 4 个 `a…` 开头的用例 | grep `aResident`（见 2.6） |

> 第 2 条要小心一种误读：**"上游已带修复" ≠ "可以删 `GitCliParse.kt`"**。命令源归 `GitCliCmd`，解析源归 `GitCliParse`，两条外移是正交的，谁也不能顶替谁。

---

## 7. 本次合并的终态事实

```
合并提交  ccfb5c38  2026-11-11 12:58:36 +0800
父1       ea8a3e94  docs(merge): 裁决书
父2       6b648bf4  feat(git-panel): JGit host-native 双源 (#307)
已推      ea8a3e94..ccfb5c38  dev -> dev
远端      ccfb5c389df420e1fe5bcabadb1dacafc55d3808（与本地 HEAD 相等）
ahead/behind  0 / 0
工作区     干净
```

| 验证项 | 结果 |
|---|---|
| `BuiltinLaneRetirementTest` | 11 tests / 0 failures |
| `GitCliParseTest` | 48 tests / 0 failures |
| `GitHostRepoTest` | 27 tests / 0 failures |
| 合计 | **86 全绿**，`BUILD SUCCESSFUL in 2m 57s` |
| 架构策略 | `OK (1329 files, cap 800, 59 on baseline, 34 on forbidden-imports)` |
| 合并收口 | `.git/MERGE_HEAD` 已清除 |
| 上游并入完整性 | `git merge-base --is-ancestor upstream/main HEAD` → 是 |

---

## 8. 未完成 / 未验证（如实标注）

- **合并后的 release APK 没有重打。** 现有 `src/android/app/build/outputs/apk/release/app-release.apk` 时间戳 **09:25**，而合并提交是 **12:58**，即该包**产自合并前的树**。要装机必须先按 4.3 在合并后的树上重跑两阶段构建，再走签名与装机核对流程。
- **versionCode 仍是 293**，本次合并没动版本号。真要出包得先递增，否则覆盖安装会被拒。
- **A2A 行抗清退只到单元测试级别**，未做真机冷启动对照实验。「冷启动后 A2A 行是否始终在、分组归属是否保住」仍需上机验。
- **第 4.2 节的内存数字来自本机实测**（可用约 5GB），换机型/换容器限额需重测。
- **上游在 `6b648bf4` 之后是否还有 #307 相关修复**未核。
