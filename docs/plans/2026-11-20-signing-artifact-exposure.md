# 签名产物泄漏面：陈旧工作树 + `.gitignore` 覆盖不到 HEAD

> 发现日期：2026-11-20
> 严重度：中（未发生泄漏，但存在一次 `git add -A` 即泄漏的路径）
> 状态：已修复（`.gitignore` + `.git/info/exclude` 双层）

## 1. 现象

`/root/minis291` 是一个 detached HEAD 工作树（停在 `9ffe45f`），`git status` 显示 7 项未跟踪：

```
?? src/android/signing-release.dat
?? src/android/app/src/main/assets/default_mount/etc/profile.d/minis-extra.sh
?? src/android/app/src/main/assets/default_mount/usr/local/bin/minis-cas
?? src/android/app/src/main/assets/default_mount/usr/local/bin/minis-jail
?? src/android/app/src/main/assets/default_mount/usr/local/bin/minis-rustfs
?? src/android/app/src/main/assets/default_mount/usr/local/lib/miniscas/
?? src/android/app/src/main/assets/default_mount/usr/local/share/
```

其余 6 项是 `dev` 上**已被跟踪**的内容，只是该工作树的 HEAD 比 `dev` 早 157 个提交，所以显示为未跟踪 —— 无害。

`src/android/signing-release.dat` 不同。它是 4342 字节的 DER 编码 CMS SignedData（`30 82 10 f2 02 01 03`，含 OID `1.2.840.113549.1.7.1`），即本机的 APK 签名证书链与签名数据。

判据不是"文件存在"，而是"git 会不会收它"：

```bash
$ cd /root/minis291/src/android && git add -An .
add 'src/android/signing-release.dat'          # ← 会被暂存
```

## 2. 根因

`.gitignore` 是**版本控制里的普通文件**，跟着分支走。`minis291` 的 HEAD 停在 `9ffe45f`，那时还没有任何规则覆盖 `signing-release.dat`：

```bash
$ git check-ignore -v signing-release.dat
$ echo $?
1        # 不被忽略
```

全仓搜索确认该文件没有任何生产方或消费方（`grep -rn 'signing-release'` 无命中，排除 `/build/`），是废弃工作站手工配置签名时留下的残渣，不可再生地承载着这台机器的签名身份。

## 3. 影响

| 场景 | 后果 |
|---|---|
| 在该工作树 `git add -A && git commit && git push` | 签名证书链进入公开仓库历史，需改写历史才能清除 |
| 用 `git bisect` / 老 tag 检出做排障 | 同样触发，因为旧 HEAD 天然看不到新规则 |
| 只在自己的 `dev` 工作树工作 | 不受影响（`dev` 上无此文件） |

**为什么它比"未跟踪文件"更值得单独记一条**：`git status` 的 `??` 和构建产物长得一模一样，看的人不会警觉；而 `.gitignore` 的失效条件是"HEAD 落后"，恰好又是这个仓库的常态（4 个工作树里有 3 个 detached）。

## 4. 修复

双层写入。只做第一层不够 —— `minis291` 的 HEAD 永远看不到那一层。

### 4.1 `.gitignore`（随仓库分发，保护已更新的检出）

在 §Keystore files 段之后追加：

```gitignore
# [T-signing-secret-untracked] Binary signing exports that land next to
# release.keystore when a workstation configures release signing. These are
# DER-encoded CMS SignedData blobs (cert chain + signature used to sign the
# APK), machine-local and regenerable. Nothing in this tree reads or writes
# them, so they are pure leak surface: a `git add -A` from a detached build
# tree would push this machine's signing identity.
src/android/signing-release.dat
src/android/signing-release.der
src/android/release-key.dat
```

### 4.2 `.git/info/exclude`（跨 worktree，无视各自 HEAD）

`$(git rev-parse --git-common-dir)/info/exclude` 是**多工作树共享**的文件，不受任何分支内容影响：

```gitignore
# [local-guard] Signing material that must never be staged, regardless of
# which branch a worktree happens to be checked out at.
src/android/signing-release.dat
src/android/signing-release.der
src/android/release-key.dat
src/android/signing.properties
*.jks
*.keystore
```

### 4.3 验证

两条都必须过：

```bash
$ cd /root/minis291/src/android
$ git check-ignore -v signing-release.dat
.git/info/exclude:13:src/android/signing-release.dat	signing-release.dat

$ git add -An . | grep -i signing
$        # 无输出 = 已挡住
```

## 5. 防复发

`repo-sync-guard` 技能的 `preflight.sh` 新增第 6 段「密钥泄漏面」，在动代码和推送前自动跑三类检查：

```bash
# ① 已被跟踪的敏感文件（已在历史里）
git ls-files | grep -Ei '<密钥模式>'

# ② 未被跟踪、也未被忽略（下一次 git add -A 就进去）
git ls-files --others --exclude-standard | grep -Ei '<密钥模式>'

# ③ 未推送提交里夹带的
git diff --name-only origin/<branch>..HEAD | grep -Ei '<密钥模式>'
```

任一命中即 `STOP` 退出。只检查 ① 会漏掉本案 —— `signing-release.dat` 从未被跟踪过。

## 6. 同源问题：为什么会有这么多陈旧工作树

`git worktree list`：

```
/root/minis291     9ffe45f (detached HEAD)
/root/codex-clean  6f0a0d4 (detached HEAD)
/root/minis-dev    4633982 [dev]
/tmp/headchk       12d9b06 (detached HEAD)
```

4 个里 3 个 detached，`minis291` 落后 `dev` 157 个提交。这些是排障和验证的副产物，没有回收机制。

**建议**（未在本 PR 实施，超出本次修复范围）：

- 排查/验证用 `git worktree add --detach` 时固定放 `/tmp`，并定期 `git worktree prune`；
- 长期运行的工作树只保留 `dev` 一个；
- 任何 `git add -A` 在 detached worktree 里都应被视为高危动作。
