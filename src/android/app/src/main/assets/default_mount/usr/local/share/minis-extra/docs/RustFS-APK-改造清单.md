# RustFS × Minis Ultra：可行性验证与改造清单

> 验证日期：2026-11-14　目标设备：本机 aarch64 / Ubuntu 24.04 PRoot / 6 核 / 15GB RAM
> RustFS 版本：**1.0.5**（2026-11-10 发布，Apache-2.0）

## 0. 结论

RustFS 在本机 PRoot 环境里**跑通了**：单节点单盘模式，健康检查 2 秒通过，S3 基本操作、版本化、生命周期、对象锁、CORS、预签名 GET/PUT 全部实测可用。
分片上传（Multipart Upload）在 PRoot 下返回 `500 InternalError: File is corrupted`，单次 PUT 正常（24MB / 0.21s）。
把二进制做成按需下载的可选组件（不塞进 APK），常驻内存 70MB，可以直接当作 APK 的统一对象存储层。

## 1. 实测数据

| 项目 | 实测结果 |
|---|---|
| 二进制体积 | 237MB（解压）/ 95.3MB（zip）/ 69.2MB（deb），`aarch64-unknown-linux-musl` 静态链接 |
| 冷启动 | 健康检查 `200` 用时 2s |
| 常驻内存 | VmRSS 70MB / VmHWM 75MB（空载 + 200KB 对象） |
| 8MB PutObject | 0.09s |
| 24MB 单次 PutObject | 0.21s |
| 24MB GetObject | 0.07s（内容校验一致） |
| Range GET 1KB | 0.006s（视频拖动进度可用） |
| 预签名 GET（无凭据） | `200`，2 字节对象 0.027s |
| 预签名 PUT（curl 直传） | `200`，50KB 落桶成功 |
| 版本化 | 开启后同一 key 列出 4 个版本 |
| 生命周期 | 规则 `expire-offloads` 写入并回读成功 |
| Object Lock 桶 | 创建成功（WORM 可用） |
| CORS | 写入成功（WebView 直连可用） |
| 控制台 | `http://127.0.0.1:9001/rustfs/console/` → 200 |
| 分片上传 | **失败**：UploadPart → `500 File is corrupted`（关掉灵活校验后同样失败） |
| 数据目录 | `/var/minis/shared/rustfs/data`（实测 200KB 对象占 536KB，含元数据开销） |

## 2. 可直接落地的功能改造（按优先级）

### P0-1 统一对象存储层（附件 / 工作区 / 共享 / 落盘产物）

现状：`attachments`、`workspace`、`shared`、`offloads` 各走各的文件路径，`minis://` 只能本机解析。
改造：四类资源各建一个桶，`minis://` 解析加一层后端选择：

```
minis-attachments  ← /var/minis/attachments
minis-workspace    ← /var/minis/workspace
minis-shared       ← /var/minis/shared
minis-offloads     ← /var/minis/offloads   （7 天生命周期）
```

命中桶的请求 302 到预签名 URL，未命中回落到本地文件。收益：WebView 原生 Range、链接可外发、缓存/统计由 S3 统一管。

### P0-2 会话版本历史替代 checkpoint 拷贝

桶开 versioning 后，`file_edit` 每次写生成新版本，`file_checkpoint` 只存 `versionId` 指针，回滚 = `GET ?versionId=...`。
省掉整份文件拷贝，长会话的文件历史从 O(n×size) 掉到 O(delta)。

### P0-3 offloads 自动过期

```bash
aws --endpoint-url http://127.0.0.1:9000 s3api put-bucket-lifecycle-configuration \
  --bucket minis-offloads --lifecycle-configuration '{"Rules":[{"ID":"expire-7d","Status":"Enabled","Filter":{"Prefix":""},"Expiration":{"Days":7}}]}'
```

### P0-4 预签名直链（APK 侧 3KB 实现）

`/var/minis/workspace/rustfs-apk/S3Presigner.kt`，纯 `javax.crypto`，替掉 `aws-android-sdk-s3`（2.4MB）。
支持 GET / PUT，WebView 里的 `<video>` 直接拖进度，前端 `<input type=file>` 直传桶，Kotlin 侧不碰文件流。

### P0-5 备份与快照

```bash
# 每晚 03:20 打包 /var/minis 上传，桶开 Object Lock 防误删
minis-rustfs backup
# crontab 由 cronjob 工具托管（AlarmManager），不依赖 app 常驻
```

### P1-6 事件驱动：新对象自动触发 agent

```bash
RUSTFS_NOTIFY_ENABLE=true
RUSTFS_NOTIFY_WEBHOOK_ENABLE_PRIMARY=on
RUSTFS_NOTIFY_WEBHOOK_ENDPOINT_PRIMARY=http://127.0.0.1:9317/webhook
RUSTFS_NOTIFY_WEBHOOK_QUEUE_DIR_PRIMARY=/var/minis/shared/rustfs/events
RUSTFS_OUTBOUND_ALLOW_ORIGINS=http://127.0.0.1:9317
```

本地起 60 行 HTTP 服务接事件：截图落地 → OCR；APK 落地 → 自动审计；录音落地 → 转写。队列目录保证 app 被杀后事件不丢。

### P1-7 审计日志

`RUSTFS_AUDIT_WEBHOOK_*` 把每次 S3 操作打到本地端点，直接写进 Settings → Logs，拿到"哪个会话读了哪个文件"的完整链路。

### P1-8 内容寻址去重

key 规则 `sha256/<前2位>/<全哈希>`，同一附件重复上传只占一份。APK 产物、模型文件、devsuite payload 收益最大。

### P1-9 多端同步

PC / NAS 用 rclone 挂同一个桶：

```ini
[minis]
type = s3
provider = Other
access_key_id = minisxxxxxxxx
secret_access_key = xxxxxxxx
endpoint = http://<手机IP>:9000
acl = private
```

```bash
rclone sync /var/minis/shared minis:minis-shared --progress
rclone mount minis:minis-workspace ~/minis-ws --vfs-cache-mode writes
```

### P1-10 devsuite 离线包分发

payload 放 `minis-devsuite` 桶，`minis-devsuite-offline-list` 改成从桶取清单，断点续传走 Range，SHA256SUMS 校验。

### P2-11 子 agent 临时凭据

每个 sub-agent 发 STS 临时凭据，策略锁前缀 `sessions/<id>/*`，越权写入直接被 S3 拒。

### P2-12 敏感附件加密

`RUSTFS_KMS_ENABLE` + `RUSTFS_KMS_BACKEND`，证件照、密钥截图落桶即加密；桶开 Object Lock 合规模式防删改。

### P2-13 控制台与应用内嵌面板

`http://127.0.0.1:9001/rustfs/console/` 直接 WebView 打开就是运维面板：桶浏览、用量、预签名生成。

### P2-14 静态报告托管

`minis-web` 桶 + CORS，`workspace` 里的 HTML 报告直接 `http://127.0.0.1:9000/minis-web/report/index.html` 打开，相对资源全走 S3，绕开 `minis://` 解析。

### P2-15 MCP server

新增 `minis_s3`：`list / get / put / presign` 四个工具，agent 直接读写桶，不用 shell 拼命令。

## 3. 三种部署形态

| 形态 | 做法 | APK 体积 | 适用 |
|---|---|---|---|
| 设备内嵌 | 二进制放 `/var/minis/shared/rustfs`，按需下载（95.3MB zip），`minis-rustfs start` 起本机 9000 | +0KB | 单机、离线、备份 |
| 局域网 NAS | `docker run -d -p 9000:9000 -e RUSTFS_ACCESS_KEY=... -e RUSTFS_SECRET_KEY=... -v /data:/data rustfs/rustfs:1.0.5` | +0KB | 多设备、大容量 |
| 公网 | 单节点 + TLS，`RUSTFS_SERVER_DOMAINS=s3.example.com` 开虚拟主机风格 | +0KB | 远程访问、分享 |

## 4. 已交付

```bash
minis-rustfs start|stop|restart|status|log|url|env
minis-rustfs ls [bucket] [prefix]
minis-rustfs mb <bucket>
minis-rustfs put <local> <bucket> [key]
minis-rustfs get <bucket> <key> <dest>
minis-rustfs presign <bucket> <key> [ttl]
minis-rustfs rm <bucket> <key>
minis-rustfs backup            # 打包 /var/minis → s3://minis-backup/backup/
minis-rustfs restore <key> [dest]
```

- 脚本：`/usr/local/bin/minis-rustfs`
- 二进制与密钥：`/var/minis/shared/rustfs/`（`env.sh` 权限 600）
- Kotlin 预签名：`/var/minis/workspace/rustfs-apk/S3Presigner.kt`

## 5. 工程约束（实测踩到的）

1. **分片上传在 PRoot 下坏**：`UploadPart` 稳定 500 `File is corrupted`，关掉 flexible checksum 同样失败。手机端走单次 PUT（S3 上限 5GB，够用）；要分片就把 RustFS 放到 Termux 原生或 PC/NAS。
2. **硬杀进程 + 动数据目录 = 池进 recovery_required**：日志 `classify_pool_meta_absence` / `store init failed`。正确姿势：`minis-rustfs stop` 等 2 秒，再动 `data/`。
3. **单盘单节点无冗余**：EC 要 ≥4 盘；手机端靠 `minis-rustfs backup` + 第二台设备兜底。
4. **常驻内存 70MB**：后台被杀后按需拉起，别做 boot 自启。
5. **二进制 237MB**：只解压一次，别打进 APK assets，走 devsuite 式按需下载 + SHA256SUMS 校验。

## 6. 验证清单

- [x] 1.0.5 aarch64-musl 在 PRoot 启动（2s）
- [x] 建桶 / 上传 / 下载 / 列表
- [x] 版本化 4 版本
- [x] 生命周期规则
- [x] Object Lock 桶
- [x] CORS
- [x] 预签名 GET / PUT
- [x] 控制台 `/rustfs/console/`
- [x] 单次 PUT 24MB 校验一致
- [ ] 分片上传（PRoot 下失败，原生环境待验）
- [ ] 多节点 EC（需要 ≥4 盘）
