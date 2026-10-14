# 莫奈主题通道协议 v1

腕上工坊 app（`dev.codex.watchworkshop`）与控制中心 helper v6（`dev.codex.watchtheme`，运行在 Launcher 进程内）之间的私有文件 mailbox 协议。

实现位置：app 侧 `src/dev/codex/watchworkshop/ThemeRuntime.java`；Launcher 侧 `runtime-helper/native-src/dev/codex/watchtheme/ThemeBridge.java` 与 `ControlTheme.java`。协议版本 1，helperVersion 6。

## 1. 角色与边界

- app 不提供任意 shell 或路径入口，所有命令都由 `ThemeRuntime` 用固定常量和本次请求拼出，不接受调用方传入的命令或路径。
- 表盘背景采样、HCT 派生和实际应用都在 Launcher 进程内由 helper 完成。app 不在自身进程载入 helper，也不直接读表盘文件。
- app 只通过本机 root adbd 执行一条固定 shell：读 hello、写 request、等 ack。helper 只读写自己的 mailbox。
- 该通道不改动综合 Hook、supervisor、adbd、QQ 数据或 userdata；helper 部署只替换 `theme.jar`。

## 2. mailbox 与文件权限

路径：`/data/user_de/0/com.heytap.wearable.launcher/files/watchworkshop-theme`，即 helper 的 `createDeviceProtectedStorageContext().getFilesDir()` 下的 `watchworkshop-theme`。

- 目录权限 `0700`，属主是 Launcher 的 uid:gid，仅 Launcher 与 root 可访问。
- 文件权限 `0600`；helper 用 `AtomicFile` 写入，保证不会留下半截 JSON。
- 四个文件：`hello.json`（helper 启动时写）、`request.json`（app 请求）、`ack.json`（helper 回执，每次覆盖）、`previous.json`（apply 前的 before 快照）。

## 3. 握手：hello.json

```json
{"protocol":1,"helperVersion":6,"session":"<uuid>","pid":<launcher pid>}
```

helper 每次启动生成一个随机会话 `session`（重新启动即更换）。app 在一条 shell 里同时读 hello 与 `pidof com.heytap.wearable.launcher`，要求三者成立才继续：protocol=1、helperVersion=6、hello.pid 等于当前 Launcher PID。任一不符就拒绝操作并提示部署 helper v6。

## 4. 请求：request.json

```json
{
  "protocol": 1,
  "id": "<uuid>",
  "session": "<hello.session>",
  "action": "read | preview | apply | rollback",
  "createdAt": <epoch 毫秒>,
  "config": { "mode": "auto|fixed|original", "seedArgb": "#AARRGGBB", "corners": true }
}
```

- `id` 是 app 生成的 UUID；helper 只接受形如 `[a-f0-9-]{36}` 的 id，并忽略与上一条相同的 id。
- `read` 与 `rollback` 不带 config（回滚配置由 helper 从 previous.json 读取）；`preview` 与 `apply` 必须带 config。
- 写入方式：shell 先把请求 base64 解码到 `request.tmp`，`chown` 成目录属主、`chmod 600`，再 `mv` 为 `request.json`。重命名产生的 `MOVED_TO` 事件才会唤醒 helper。
- 请求体上限 8KB，超过记为 `OVERSIZED_REQUEST`。

## 5. 触发与运行模型

- helper 用 `FileObserver(MOVED_TO)` 只监听 `request.json` 的重命名到达事件，收到后切到 Launcher 主线程处理。
- helper 没有 Service、Binder、ContentProvider、BroadcastReceiver，不监听端口；没有唤醒锁；没有定时轮询或截屏。采样与量化由一个工作线程完成。
- `busy` 期间的请求会被直接丢弃（不写回执），app 会等到自己的等待窗口超时；同一 id 重复触发会被去重。

## 6. 校验与过期

- protocol 必须为 1；session 不等于当前 session 时回 `SESSION_CHANGED`。
- `age = now - createdAt`；当 `age < -2000ms`（时钟明显超前）或 `age > 15000ms` 时回 `EXPIRED`。也就是说请求自创建起 15 秒内有效，超过即作废。
- action 只接受 read/preview/apply/rollback，否则回 `INVALID_ACTION`。
- config 校验：mode 只能是 auto/fixed/original，否则 `INVALID_MODE`；seedArgb 必须匹配 `#RRGGBB` 或 `#FFRRGGBB`，否则 `INVALID_SEED`；corners 必须是布尔；config 缺失回 `CONFIG_MISSING`。

## 7. 动作语义

| action | 是否采样 | 是否写配置 | 回执内容 |
| --- | --- | --- | --- |
| read | 否 | 否 | 当前 state |
| preview | 仅 auto | 否 | state + preview |
| apply | 仅 auto | 写 previous.json，执行 managedSet | 应用后的 state |
| rollback | 恢复目标为 auto 时 | 用 previous.before 执行，成功后删除 previous.json | 恢复后的 state |

- apply 由 helper 调一次 `managedSet(mode, seed, corners, palette)`：一次性把配置写进 `codex_control_theme` 并在 Launcher 主线程应用一次，然后回读状态校验。
- auto 必须采样成功（当前表盘的背景或预览可解码且取色成功），否则回 `SAMPLE_FAILED`，原设置保持不变。fixed 由所选种子色直接派生 HCT 调色板，不依赖采样。
- 校验规则：回读后要求 `mode` 一致、`corners` 一致；fixed 时还要求 `seed` 一致。不满足回 `VERIFY_FAILED`，并且如果本次已经调用过 `managedSet`，helper 会用 before 自动回写一次。
- auto 不要求回读的 seed 等于草稿或旧值（种子随表盘变化），这是预期行为，不算失败。

## 8. 回执：ack.json

```json
{
  "protocol": 1,
  "id": "<同请求 id>",
  "session": "<当前 session>",
  "pid": <launcher pid>,
  "ok": true,
  "code": "",
  "completedAt": <epoch 毫秒>,
  "state": { ... },
  "preview": { ... },
  "canRollback": true,
  "rollbackConfig": { "mode": "...", "seedArgb": "#AARRGGBB", "corners": true }
}
```

- app 校验 protocol=1、id 等于本次请求、session 等于 hello.session、pid 等于 hello.pid（也是当前 Launcher PID）。任一不符即视为身份不匹配，不采用该回执。
- `ok=false` 时按 `code` 转成中文提示；`state` 必须 `ready=true` 且 `helperVersion=6`，否则视为未就绪。
- `state` 只保留白名单字段：ready、mode、seed、active、onActive、off、onOff、helperVersion、corners、applies、error。不落盘 source、fingerprint、key、style 等个人表盘信息。
- `preview` 字段：seed、active、onActive、off、onOff、secondary、muted、status。
- `canRollback` 与 `rollbackConfig` 取决于 previous.json 是否存在。

## 9. 等待策略

app 在写请求的同一条 shell 里最多轮询 100 次、每次 100ms（约 10 秒），检查 `ack.json` 是否已经包含本次 id。超时就报“等待主题确认超时，请重新读取状态”。这是一次请求的有界等待，不是后台轮询服务。

## 10. 错误码

helper 发出的 code：

| code | 含义 |
| --- | --- |
| SESSION_CHANGED | Launcher 已重载，session 已更换，需要重新读取 |
| EXPIRED | 请求创建超过 15 秒，或系统时钟明显超前 |
| INVALID_ACTION | action 不在 read/preview/apply/rollback 内 |
| INVALID_MODE | mode 不是 auto/fixed/original |
| INVALID_SEED | seedArgb 不是 `#RRGGBB` 或 `#FFRRGGBB` |
| CONFIG_MISSING | preview/apply 缺少 config |
| OVERSIZED_REQUEST | 请求体超过 8KB |
| SAMPLE_FAILED | auto 取色失败，原设置保留 |
| VERIFY_FAILED | 回读状态与目标不一致 |
| APPLY_FAILED 或其它异常信息 | 应用阶段未确认，app 显示统一失败提示 |
| INVALID_REQUEST | 请求无法解析为合法 JSON |

app 侧提示：SESSION_CHANGED → “Launcher 已重载，请重新读取后操作”；SAMPLE_FAILED → “暂时无法读取当前表盘配色，原设置保留”；EXPIRED → “请求已过期，请重新操作”；其余统一为“主题操作未确认，请重新读取或恢复上一次设置”。回滚缺少 previous.json 时同样走这条通用提示。

## 11. 安全边界

- app 没有任意命令入口；mailbox 仅 Launcher uid 与 root 可访问。
- 该协议只修改 Launcher 的主题偏好和主题 mailbox，不改动综合 Hook、supervisor、adbd 或其他应用用户数据；helper 部署只替换 `/data/local/tmp/watchface-theme/theme.jar`，并保留旧文件备份。
- 导出的 `theme-draft.json` 仍是 `applied=false` 的草稿：导出只把草稿写到 app 的导出目录，不经过本协议，不构成执行。每一次草稿导出都不能当作已应用。
- 读取结果与预览都只是展示数据。只有 helper 实际应用并回读确认后，界面才显示“已应用”。

## 12. 版本与部署

- 协议版本 1，helperVersion 6；hello 与 state 都带 helperVersion。
- helper 由用户手动运行 `deploy-theme-helper.ps1` 部署：只替换 `theme.jar`，核对 Hook 基线与哈希、备份旧文件，重启 Launcher 让既有 supervisor 重载，失败自动恢复备份。
- app 与 helper 任一版本不匹配都会在握手阶段被拒绝，不会退化成直接写文件。

## 13. 验证状态

- 已验证：真实设备从 app 发起 read/preview/apply/rollback，关联回执与实际颜色一致。
- auto 取色在换表盘 / 换样式时是否按预期触发并成功。
- 已验证：Launcher 重载后 hello 新 pid/session 出现，固定配置与 before 快照保留。
- 已验证：恢复设置由 managedSet 在宿主即时应用，无需再次重载。
- helperVersion 与 Launcher Hook 版本不匹配时的拒绝表现。

## Android API 依据

目录事件由 [FileObserver](https://developer.android.com/reference/android/os/FileObserver) 接收；配置及回执文件用 [AtomicFile](https://developer.android.com/reference/android/util/AtomicFile) 写入。当前实现保留 observer 强引用，普通主题操作不重载 Launcher。
