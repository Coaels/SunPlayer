# SunPlayer — Folia 1.21.11 Carpet 式假人插件 · 方案锁定

> 本文档是最终确认的方案，实现期间以本文为准。凡与本文冲突的旧讨论一律作废。

## 1. 技术路线

**Carpet 式服务端内假人**：`ServerPlayer` 子类 + `PlayerList#placeNewPlayer`。

不采用、也不考虑：
- 独立客户端进程（mineflayer 之类塞进插件）
- 纯发包 NPC（展示实体 / 只有 player-info 包）

因为上面两条都做不到「被活塞/水流/TNT 推动」和「持有玩家票加载区块」。

## 2. 命令树（最终）

```
/sunplayer <name>                    生成假人，位置 = 执行者 ~ ~ ~
/sunplayer <name> <carpet命令...>    对已有假人执行动作
/sunplayer <name> remove             下线（保留持久化，重启复活）
/sunplayer <name> kill               彻底删除（清持久化，不复活）
/sunplayer list                      列出所有假人
```

- 生成不支持 `at` / `facing` / `in` 位置参数。
- 生成时游戏模式 = **执行者当前游戏模式**。
- 权限节点 `sunplayer.admin`，默认仅 OP。

## 3. 外观与语义

| 项 | 决定 |
|---|---|
| tab 显示 | 灰色 `[假人]` + 白色名字（**两段颜色**，不是整串同色） |
| 加入广播 | 原版 `xxx joined the game`，走 `placeNewPlayer` 自动产生 |
| 皮肤 | 查 Mojang 正版（名字对应），查不到/失败 → 固定 **Steve** |
| 白名单 | 天然绕过（不走登录流程） |
| 全服上限 | **20** 个假人 |
| 死亡 | **不自动复活**（与 Carpet 一致） |
| `drop` 无物品 | 报错给执行者，不静默 |

## 4. 名字规则

- 正则 `[A-Za-z0-9_]{1,16}`
- 不得与在线真人重名
- 已有同名假人 → 报错，不重复生成
- 离线 UUID：`OfflinePlayer:<name>` 的 MD5 标准算法

## 5. 配置（config.yml）

```yaml
max-fake-players: 20   # 全服上限
view-distance: 2       # 假人视距（区块），2-32；最省内存的一项
```

> 说明：`simulation-distance` 和 `idle-optimization` 已从配置中移除。
> 前者无法按玩家单独设置（Folia 按全局模拟距离 tick），后者没有实际收益
> （假人的 tick 主体是 `super.tick()+doTick()`，跳过它就没有物理了）。
> 真正有效的省内存手段是 `view-distance` 和 `max-fake-players`。

## 6. 动作包（对齐 Carpet /player）

| 类别 | 命令 |
|---|---|
| 手部 | `use` / `attack` / `swing`，均支持 `once\|continuous\|interval <n>` |
| 掉落 | `drop [once\|continuous\|interval n] [all\|mainhand\|offhand\|<0-40>]`、`dropStack [n]` |
| 移动 | `jump`、`move <方向>`、`look <yaw> <pitch>`、`turn` |
| 状态 | `sneak` / `unsneak`、`sprint` / `unsprint` |
| 物品 | `hotbar <0-8>`、`swapHands` |
| 其他 | `mount`、`dismount`、`stop` |

**明确不做**：`shadow`（依赖替换真实玩家，Folia 上必然崩线程检查）、`manipulate`。

## 7. 实现要点（Folia 关键约束）

1. `placeNewPlayer` **必须在拥有出生区块的那个 region 线程上执行**，因为它会无条件
   `serverLevel.getCurrentWorldData().connections.add(connection)`。用
   `RegionScheduler.execute(plugin, world, chunkX, chunkZ, ...)`。
2. 动作驱动**不注册任何调度器**：覆写 `ServerPlayer#tick()` 推进 `ActionPack`，
   天然在假人所属 region 上运行。
3. 移除路径：`origin.removePlayerImmediately(this, RemovalReason.X)` +
   `level().getCurrentWorldData().connections.remove(conn)`。
4. `folia-supported: true` 必须写进 `plugin.yml`，否则 Folia 拒绝加载。
5. 皮肤解析放在 `AsyncScheduler`，**在 `placeNewPlayer` 之前完成**，否则 tab 先刷默认皮肤。

## 8. 持久化

- 文件 `plugins/SunPlayer/fake-players.yml`
- 记录：world / x / y / z / yaw / pitch / gamemode / flying / created-at / creator
- 写盘：临时文件 + 原子 move
- 重启复活：`GlobalRegionScheduler` 延迟 → `getChunkAtAsync` → `RegionScheduler` 在正确 region 生成

## 9. 构建链

- 无 Gradle，装到工作区 `.tools/`（不污染系统）
- `paperweight-userdev` + `foliaDevBundle("1.21.11-R0.1-SNAPSHOT")`
- 构建峰值内存 ≤ 2GB（`-Xmx2G`，限制反编译线程），跑完 `gradlew --stop`
- **兜底**：paperweight 跑不通时，改用 Maven + 现成 Folia 运行时 jar 直接编译（<500MB，几十秒）

## 10. 阶段与验收

| Phase | 内容 | 验收 |
|---|---|---|
| 0 | Gradle + paperweight 构建链 | jar 能被 Folia 1.21.11 加载 |
| **1** | **最小假人 spike（go/no-go）** | 生成、tick、加载区块、**60s 不被踢**、干净移除 |
| 2 | 命令树 | 生成/动作/删除/列表可用 |
| 3 | 动作包 | 逐项真机验证 |
| 4 | 外观/tab/白名单/广播 | 客户端看到灰字+皮肤 |
| 5 | 持久化 + 重启复活 | 重启后自动回来 |

Phase 1 是关键闸门：若 `placeNewPlayer` 在 Folia region 线程上有意外问题，在 Phase 1 停下重新评估。

## 11. 实施状态（已全部完成并在 Folia 1.21.11 真机验证）

| Phase | 状态 | 验证方式与结果 |
|---|---|---|
| 0 构建链 | ✅ | Gradle 9.7.1 + paperweight `foliaDevBundle`，构建约 5 分钟，产出 40KB jar |
| 1 最小假人 | ✅ | 生成/移除无异常；`Connection.tick()` 空实现使假人存活 >30 分钟不被踢 |
| 1 区块加载 | ✅ | 孤立区域 `[10007,80,10007]` **只有假人一个玩家**时仍加载 169 区块并正常 tick |
| 2 命令树 | ✅ | `create / action / remove / kill / list` 全部可用 |
| 3 动作包 | ✅ | `move` 4 秒走约 17 格（正常步行速度）；`stop` 立即停住；空手 `drop` 正确报错 |
| 3 动作真实效果 | ✅ | `attack` 把面前的石头方块打掉（`/fill replace` 计数 1→0）；`use` 把木门打开（匹配到 `oak_door[open=true]`）；`jump` 采样 y 从 -59.2 → -58.7 顶点 → 回落 |
| 4 外观/白名单 | ✅ | 抓包确认正版名 `props=[textures]`、不存在账号 `props=[]`（→ Steve）；tab 为灰色 `[假人]` + 白色名字两段；游戏模式与执行者一致 |
| 4 白名单绕过 | ✅ | `white-list=true` 时，不在白名单里的假人 `NotAPremiumAcc` 仍能生成、复活并保持在线 |
| 4 数量上限 | ✅ | `max-fake-players: 2` 时第三个假人被拒（"假人数量已达上限 2"） |
| 5 持久化 | ✅ | `fake-players.yml` 原子写入；重启后自动复活；`kill` 后不再复活 |

### 实施中发现的三个关键点（与原计划的偏差）

1. **`Connection#tick()` 必须留空**，否则 `ServerGamePacketListenerImpl#tick()` 里的 keep-alive
   会在 15 秒后把假人踢掉。这是假人能长期存活的根本原因。
2. **物理必须手动调 `doTick()`**。原版玩家的实体 tick 是由上面那个监听器驱动的；既然关掉了它，
   就必须在 `FakePlayer#tick()` 里补 `this.doTick()`，否则假人会浮在空中永不下落、也不会移动。
3. **移动输入要写 `LivingEntity#zza/xxa/jumping`**，不是 `setLastClientInput`。
   后者只是客户端包的镜像，服务端物理从不读它（实测设置后位置完全不变）。

另外：authlib 7 的 `PropertyMap` 构造时就会 `ImmutableMultimap.copyOf`，皮肤属性必须在
构建 `PropertyMap` **之前**放进 `Multimap`。

## 12. 兼容性修复（2026-11-05 ~ 11-06，已完成并在实机插件栈上验证）

用户反馈的真实服务器日志里出现 4 个兼容问题，全部定位并修复：

1. **PacketEvents 踢人**（致命）：`PacketEvents failed to inject into a channel`。
   假人的连接是内存 `EmbeddedChannel`，PacketEvents 在 `PlayerJoinEvent` 里拿不到可注入的
   channel，一个 tick 后直接 `player.kickPlayer(...)`。
   → 修复：`CompatListener` 在 `PlayerKickEvent`(HIGHEST) 里取消对假人的踢出。
   已用 **PacketEvents 2.14.0 实机复现并验证修复**（日志出现"已拦截对假人 X 的踢出"且假人存活）。
   管理员 /kick、封禁、白名单、重启、同名顶号（DUPLICATE_LOGIN）仍然放行。
   依据：`ServerCommonPacketListenerImpl#disconnect(DisconnectionDetails)` 的字节码在
   `PlayerKickEvent` 被取消时直接 `return`，实体、区块票、tab 项都不会被移除。

2. **LuckTags NPE**（`LuckTags.java:88`，反编译确认是
   `Objects.requireNonNull(luckPerms.getUserManager().getUser(uuid))`）：
   假人没走登录流程，LuckPerms 从没为这个 UUID 加载过 User。
   → 修复：生成前在 **异步线程** 派发 `AsyncPlayerPreLoginEvent`（`LoginCompat.firePreLogin`）。
   已实机验证：装 LuckPerms 5.5.71 + LuckTags 1.4，假人加入时 NPE 消失。

3. **AuthMe 要求登录**：
   → 修复：`LoginCompat.ensureAuthMe`，加入后在实体调度器上按 1/21/61/121 tick 重试，
   用 AuthMe 公开 API（已注册 `forceLogin`，未注册 `forceRegister(pwd, autoLogin=true)`），
   全部反射、无硬依赖。
   **踩到的坑**：一开始用 32 位随机密码，AuthMe 密码长度上限默认 30，`forceRegister` 静默失败
   （不抛异常、也不写库）。是靠新增的 `debug` 配置把 AuthMe 发给假人的聊天消息打到控制台，
   看到"你的密码不符合要求"才定位到的。改成 16 位后注册成功。
   实机结果：SQLite 出现 `compattest3` / `rega` / `regtest` 三行；日志出现"已成功登录！"
   和"已通过 AuthMe 认证"；30 秒超时点没有"登录超时"踢出；`allowMovement: false` 的 limbo
   也没有卡住假人（新建假人 3 秒走了 8 格）。

4. **Multiverse-Core 把假人传送到 join 目的地**（`Failed to async teleport ... on join`）：
   → 修复：生成后在实体调度器上按 1/2/4/8/16/32/64 tick 做位置看门狗，只比较世界 + 水平距离
   （避免和自由落体冲突），偏离就 `teleportAsync` 拉回，并记录拉回后的实际坐标。
   已实机验证（用 `/tp` 模拟）：日志出现"被其他插件移动到了…已拉回 world -13.5 -60.0 -5.5"。
   注：Multiverse-Core 5.8.2 未声明 folia-supported，在纯 Folia 上直接拒绝加载，
   所以本地用 `/tp` 模拟它的行为；用户端 DivineMC 能加载它。

5. **顺带修掉的 bug**：`/sunplayer <名字> kill` 在假人已经 `remove`（下线但保留存档）之后
   无效——旧代码要求假人在线才处理。现在 `kill` 能直接删掉存档条目。
   实机验证：`remove` → `kill` → `fake-players.yml` 里该条目消失 → 重启不再复活。

### 验证环境

测试服装的是用户同款插件栈（Folia 1.21.11 + PacketEvents 2.14.0 + LuckPerms 5.5.71 +
LuckTags 1.4 + AuthMe 6.0.1 Folia），不是裸服。回归结果：

| 项目 | 结果 |
|---|---|
| 生成 / 列表 / remove / kill / 重启复活 | 通过（kill 后不再复活，其余复活） |
| 移动（move forward 3 秒） | 通过（13.2 格，≈4.4 格/秒） |
| PacketEvents 踢出 | 拦截成功，假人存活 |
| LuckTags NPE | 消失 |
| AuthMe 认证 / 超时踢出 / limbo | 通过（认证成功、无超时踢出、可正常移动） |
| 位置看门狗 | 通过（`/tp` 后被拉回生成点） |

### 代码与产物

- 新增 `CompatListener.java`（踢出拦截 + 离开兜底）、`LoginCompat.java`（预登录 + AuthMe）。
- `FakePlayerManager` 增加 `prepareLogin` / `scheduleJoinCompat` / `restoreAnchor` /
  `forgetPersisted` / `forgetOnline`；`FakeConnection` 增加 `debug` 消息打印。
- `config.yml` 增加 `compat.*` 四个开关和 `debug`。
- 产物：`build/libs/SunPlayer-0.1.0.jar`。

## 13. 用户实测反馈的两个问题（2026-11-06 下午）

### 13.1 `kill` 后假人还在原地（DivineMC 上 `NoSuchMethodError`）

用户日志：

```
[SunPlayer] 移除假人 Hong 失败: java.lang.NoSuchMethodError:
  'io.papermc.paper.threadedregions.RegionizedWorldData net.minecraft.server.level.ServerLevel.getCurrentWorldData()'
  at com.sunplayer.FakePlayerManager...
```

根因：`FakeConnection` 的假连接被 `PlayerList#placeNewPlayer` 加进了区域的
`RegionizedWorldData.connections`，而 `PlayerList#remove` **不会**把它摘掉，所以移除时手动
`level.getCurrentWorldData().connections.remove(...)`。这个方法在 Folia 1.21.11 有，但
**DivineMC（Folia 分支）没有**——调用点直接 `NoSuchMethodError`，整个移除任务在
`PlayerList.remove` 之前就抛了，于是插件已经把名字从 `list` 里删掉、也回了"已删除"，
实体却还在世界里。

修复（三层）：

1. 连接表清理改成 **直接调用失败就反射兜底**（找任意返回 `*RegionizedWorldData` 的无参方法，
   再取 `connections` 字段）；都失败就只打一条警告并继续——残留连接是惰性的
   （`Connection#tick()` 是空实现），不影响假人消失。
2. 移除改走 **实体调度器**（`EntityScheduler`），保证一定在实体所属区域线程上执行；失败
   自动重试 2 次（每秒）。
3. `PlayerList.remove` 之后检查 `fake.isRemoved()`，若仍未被移除则
   `level.removePlayerImmediately(..., DISCARDED)` 强制移除，并打警告。

> 中途试过更"优雅"的方案：让 `isConnected()` 返回 false，交给
> `RegionizedWorldData#tickConnections()` 自动剪枝。**实测会导致区域崩溃**
> （`cleanUpConnection` → `removeTicketAtLevel(pos=null)` NPE，因为假人没有
> `disconnectPos`/区块票据），已回滚。这条弯路记在这里，避免以后重犯。

### 13.2 位置看门狗把"自己走路"也拉回去了

第一版看门狗比较的是"水平距离 > 2 格"，于是 `/sunplayer X move forward` 走了两步就被判定
成"被传送"并拉回原地。实测发现（KillTest 走了 4 格又被拉回 -5.5）。

修复：改成在 `FakePlayer#tick()` 里做**逐 tick 位移检测**，只在生成后 100 tick（5 秒）内生效：

- 单个 tick 水平位移 > 8 格 → 只可能是传送（走路每 tick 最多约 0.6 格）；
- 或世界变了 → 被传送到别的世界。

命中就 `teleportAsync` 回生成点。这样插件在加入时把假人传走会被拉回，而正常走路、以及
5 秒后管理员的 `/tp` 都不受影响。

> 顺带发现：这个 Paper 版本里 `teleportAsync` **不会触发** `PlayerTeleportEvent`
> （用测试插件 `/tpprobe` 验证：事件监听器一次都没被调用，但假人确实被传送了），所以靠
> 监听传送事件拦不住 Multiverse。这正是改成逐 tick 检测的原因；`PlayerTeleportEvent` 的
> 拦截代码仍然保留，在会触发该事件的服务端上是更早的一道防线。

### 验证

- `kill`：`left the game` + 客户端收到 `entity_destroy`，无异常、无区域崩溃。
- 传送守卫：测试插件用 `teleportAsync` 把假人扔到 (70,-60,70)，日志
  "假人 TpGuard 在生成后被传送到了 world，已拉回生成点"，`list` 显示回到生成点。
- 走路回归：`move forward` 3 秒 13 格（-5.5 → 20.1），不再被拉回。
- 多次 kill / 重启复活 / AuthMe 认证均正常。

## 14. NPC 模式（2026-11-06）

### 14.1 命令

```
/sunplayer <名字> --npcmode [--command <命令,命令>] [--left|--right|--both]
                  [--look] [--look-range <格>] [--cooldown <tick>] [--as-console]
```

NPC 仍然是真实的 `ServerPlayer`（皮肤、tab、区块加载、事件全部照旧），只是：

- 不接受移动输入、不可推动（`isPushable`/`push` 返回空）；
- `hurtServer` / `isInvulnerableTo` 直接返回不可伤害，连 `/kill` 都杀不掉（用 `/sunplayer kill`）；
- `tick()` 末尾做"面向最近玩家"（`--look`），写 `yRot/yHeadRot/yBodyRot/xRot` 四件套，
  客户端由 `ServerEntity` 正常同步；
- NPC 参数在创建时一次性拷贝进 `FakePlayer`，之后只读，点击路径无锁。

### 14.2 点击检测：不走 NMS，走两个事件

| 点击 | 事件 | 关键点 |
|---|---|---|
| 左键 | `PrePlayerAttackEntityEvent` | 在 `Player#attack` 内、伤害之前触发；**PvP 关闭时也会触发**（只是 `willAttack=false`），取消即可不掉血 |
| 右键 | `PlayerInteractEntityEvent` | 含 `PlayerInteractAtEntityEvent` 子类；取消后手里的物品不会继续使用 |

两个事件都在点击者所在区域线程上触发，与 Folia 线程模型一致；命令再用点击者的
`EntityScheduler` 调度一次，保证 `@s` 实体在正确线程上。

### 14.3 命令执行

- 默认：`ServerPlayer#createCommandSourceStack()`，即玩家自己的权限（含 LuckPerms 等
  Bukkit 权限）。玩家没权限就失败并收到原版报错——这是刻意选择。
- `--as-console`：`MinecraftServer#createCommandSourceStack()` + `withEntity/withPosition/
  withRotation/withLevel(点击者)`，权限为控制台，但 `@s`、`~ ~ ~`、`^ ^ ^` 仍指向点击者。
  （`CommandSourceStack#hasPermission` 在 1.21.11 是 `permissionSet || bukkitPermission`，
  控制台源的 permissionSet 是 ALL。）

### 14.4 命令切分

Bukkit 只按空格切参数，`--command` 会把后续 token 重新拼回一个字符串，再按**顶层逗号**
切分：`[]`、`{}`、`""`/`''` 内的逗号不切，`\,` 是字面逗号。最多 32 条。

### 14.5 冷却

按 `(npc, 玩家)` 记 `System.nanoTime()`，最小 1 tick（顺带吞掉客户端为另一只手重复发送的
同一个右键包），默认 10 tick。

### 14.6 持久化

`fake-players.yml` 每个假人下多一个 `npc:` 段（enabled/left/right/commands/look/look-range/
cooldown/as-console），`remove` 保留、`kill` 删除，重启复活时一并恢复。

## 15. 删除失败的第二轮修复（2026-11-06 傍晚）

用户实测：`/sunplayer Bot_test kill` 提示"没有这个假人"，重启后假人又回来了。

根因是**两条路径都不够硬**：

1. `remove()` 只改了内存里的 `byName`/`persisted`，落盘要等异步 `detach` 结束才 `saveNow()`。
   只要移除任务没跑到（fork 上实体调度器不执行、实体已停止 tick、中途崩溃），
   `fake-players.yml` 里就还留着记录 → 重启复活；而内存里已经没了 → 再 `kill` 报"没有这个假人"。
2. 移除任务用的是 `EntityScheduler#run`，在部分 fork 上对假人这种"没有真实连接"的玩家可能
   永远不执行。

修复：

- **先落盘再移除**：`remove()` 改完 map 立刻 `saveNow()`。
- **区域调度器为主**：`RegionScheduler.execute(location)` 立即在该实体所在区域执行；
  实体调度器只作兜底，另加全局区域 2 秒看门狗，仍未移除就再试一次。
- **兜底查找**：`byName` 里没有时，扫描 `Bukkit.getOnlinePlayers()` 找 `FakePlayer` 实体
  （"不在列表但实体还在"的幽灵），`remove`/`kill` 都能清掉。
- **创建时先登记**：`placeNewPlayer` 之后立刻写 `byName`/`persisted`，再做设置游戏模式、
  tab 前缀、AuthMe/Multiverse 兼容；这些后续步骤失败只告警，不会留下无法删除的孤儿实体。

验证（本地 Folia 1.21.11 + 同一套插件栈）：6 个 NPC 重启后全部带配置复活；`remove` 一个、
`kill` 一个后实体立即消失（`tp` 报 "No entity was found"）；再重启，`remove` 的回来了、
`kill` 的没有回来；`fake-players.yml` 中对应条目一存一删，均符合预期。

### 15.1 死掉的假人删不掉（2026-11-06 晚间追加）

用户反馈："如果 bot 已经被杀死了，会导致无法删除"。

本地用 `/kill <假人>` 复现时是能删的（`/kill` → 假人躺下 → `/sunplayer X kill` → 实体回收），
所以问题只可能出在"**实体已经不在、但玩家列表里还留着**"这种幽灵态：

- `ServerPlayer` 死亡后如果被服务端/fork 提前 `remove` 掉实体，`fake.isRemoved()` 为真，
  而 `PlayerList` 里的条目还在（tab 里有、`/tp` 能定位、`Bukkit.getPlayer` 能拿到）；
- 旧代码里 `detach()` 看到 `isRemoved()` 就直接返回，`detachNow()` 也一样，
  于是这个玩家列表条目**永远清不掉**，再 `kill` 只会说"没有这个假人"。

修复：

- `detach()` 的短路条件改成 `isRemoved() && !isListed(fake)`：实体没了但列表还在，照样走移除；
- `detachNow()` 增加分支：实体已回收但仍在列表里时，只做 `PlayerList.remove` 清理列表，
  并打一条警告；
- `findUntracked()` 不再要求 `!isRemoved()`，否则幽灵永远找不到；
- `PlayerList.remove` 抛异常时，先用 `removePlayerImmediately` 兜底，再通过实体调度器重试一次；
- 每次移除结束打一条 `假人 X 已彻底移除（实体已回收/实体仍在）`，方便用户直接看出卡在哪一步。

已验证：`/kill DeathTest` → `/sunplayer DeathTest kill` → 列表 4/20、`tp` 报不存在、
控制台打印"已彻底移除（实体已回收）"。

## 16. 单 jar 双端（Folia + Paper，2026-11-06 晚）

### 16.1 结论先行：只有调度和区域连接表是平台相关的

假人本身是 `ServerPlayer` + 自定义 `Connection`，属于上游 NMS 机制，Paper 和 Folia 的
1.21.11 都是 Mojang 映射、同一个类名，所以 `FakePlayer` / `FakeConnection` / `ActionPack` /
`NpcOptions` / `NpcListener` / `CompatListener` / `LoginCompat` / `SkinResolver` / 命令类
**一行都没改**。全部差异收口到新文件 `ServerCompat`：

| 能力 | Folia | Paper |
|---|---|---|
| 按位置调度 | `RegionScheduler.execute/runDelayed` | `runTask` / `runTaskLater` |
| 全局调度 | `GlobalRegionScheduler` | `runTask` / `runTaskLater` |
| 按实体调度 | `EntityScheduler.run/runDelayed` | `runTask`（已经在主线程） |
| 异步 | `AsyncScheduler.runNow` | `runTaskAsynchronously` |
| 区域连接表清理 | `RegionizedWorldData#connections` | 不存在，直接 no-op |

平台检测：

```java
Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
```

存在就是 Folia。Paper 的 API jar 里带着 Folia 调度的**桩类**（调用会抛
`UnsupportedOperationException`），所以单 jar 可以直接引用这些类型，只要保证只在 `FOLIA`
为真时才真正调用。

### 16.2 最关键的一点：Paper 上的 `Connection#tick()` 会 tick 监听器

反编译 1.21.11 的 `Connection#tick()`：

```java
if (this.packetListener instanceof TickablePacketListener listener) {
    PacketProcessor.packetProcessing.push(listener);
    listener.tick();                     // → ServerGamePacketListenerImpl#tick()
}
```

而 `ServerGamePacketListenerImpl#tick()` → `keepConnectionAlive()` + `tickPlayer()`，后者里才是
`player.doTick()`。也就是说：

- **Folia 不调用 `Connection#tick()`**（它走 `RegionizedWorldData#tickConnections()`），
  所以空实现没问题；
- **Paper 会调用**，如果空实现 → 监听器永不 tick → 没有 `doTick`，同时也就没有保活超时。

结论：`FakeConnection#tick()` 两端都保持空实现（不 tick 监听器 = 不会被踢），物理由
`FakePlayer#tick()` 里手动 `doTick()` 补上。实测 Paper 上 `move forward` 3 秒走 13~15 格，
和 Folia 一致，且假人长时间在线不会被 keep-alive 踢。

`disconnect()` 的两个重载在 `FakeConnection` 里本来就是 no-op，所以不会往
`disconnectReqs` 队列里塞永远没人消费的请求。

### 16.3 验证矩阵

| 项目 | Folia 1.21.11（DivineMC 同源 API） | Paper 1.21.11 |
|---|---|---|
| 启用日志 | `Folia（区域线程）` | `Paper（主线程）` |
| 创建 / `list` | ✅ | ✅ |
| `move forward` 3 秒 | ✅ 13.3 格 | ✅ 15.4 格 |
| NPC 左右键 / `--left` / 冷却 | ✅ | ✅ |
| `--as-console` 提权 | ✅ | ✅（OP 环境） |
| `remove` / `kill` / 实体回收 | ✅ | ✅ |
| 重启复活（`remove` 回来、`kill` 不回） | ✅ | ✅ |
| AuthMe / PacketEvents / LuckPerms / LuckTags | ✅ | 未装（这些插件本身跨端通用） |

构建仍是同一个 jar，没有拆工程。

## 17. 账号隔离、死亡删除、权限拆分、补全重写（0.2.0）

### 17.1 为什么要加名字前缀

用户问"如果有同名玩家加入，是不是就会出生在假人的位置"，答案是**会，而且不止这一处**。

原来的实现里，假人 UUID 是 `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)`，和
`online-mode=false` 下真号的 UUID 算法**完全一致**。所以假人 `Steve` 和真人 `Steve` 是同一个
profile：

| 冲突 | 后果 |
|---|---|
| UUID 相同 | 共用 `playerdata/<uuid>.dat`：背包、坐标、经验、末影箱都是同一份 |
| 玩家列表占位 | 假人在线时真号登录会走重复登录判定 |
| 存档写回 | 假人下线时把空背包写进那个 `playerdata`，覆盖真人存档 |
| AuthMe | 假人先占了名字会被 `forceRegister` 注册成随机密码，真人回来登不上 |

修复方式是**给假人换一套 profile**：服务端名 `Bot_` + 裸名，UUID 由 `Bot_` 前缀名算出。
裸名只存在于插件内部（命令、`fake-players.yml`、tab 显示、日志），前缀名只出现在服务端和
第三方插件眼里。

- `FakePlayerManager#bareName(String)` 做前缀剥离，`get()` / `isPersisted()` / `forgetPersisted()` /
  `remove()` 全部走它，所以 `Bot_Steve` 和 `Steve` 都能定位到同一个假人；
- `FakePlayer#bareName()` 把裸名挂在实体上，避免每次再从 profile 名反推；
- 前缀长度受限（≤12）且会清洗非法字符，裸名上限 = `16 - 前缀长度`；
- 存档升级：加载时把已存条目按前缀规则归一化。用户之前手输的 `Bot_test` 会变成裸名 `test`，
  服务端名仍是 `Bot_test`，UUID 不变——**不会**变成 `Bot_Bot_test`。

### 17.2 测试中发现的真 bug：前缀容错查找误伤真人

改完前缀后，`CompatListener` 里还在用 `manager.get(player.getName())` 判断"这个玩家是不是假人"。
由于 `get()` 现在故意容错（`Steve` 也能命中 `Bot_Steve`），**真人 Steve 会被当成假人**。

实测复现：假人 `Bot_Twin` 在线时，真人 `Twin` 登录再退出，控制台打出

```
[SunPlayer] 假人 Twin 已离线（仍会保留在存档中，重启后复活）
```

假人的实体还在世界里跑，但已经从 `byName` 里被删掉了——变成孤儿实体，`list` 里看不到、
`kill` 也找不到（要等重启从存档复活）。同一个错误还会让真人的插件传送被取消、踢出被吞掉。

修复：新增 `FakePlayerManager#fakeOf(Player)`，**按实体类型判断**

```java
player instanceof CraftPlayer craft && craft.getHandle() instanceof FakePlayer fake
```

只有我们自己的子类才是假人，零误判。`onKick` / `onTeleport` / `onQuit` / `onDeath` 全部改用它，
`get(String)` 只留给"用户敲进来的名字"这一种场景。

顺带修掉一个误导日志：`remove`/`kill` 触发的退出事件原本也会打"假人已离线"。
现在 `forgetOnline` 返回"是否真的还在在线表里"，只有意外掉线才打这条警告。

### 17.3 死亡即删除

用户反馈"假人死掉后不会删除"。查代码后确认这不是 bug 而是**没实现**：没有任何
`PlayerDeathEvent` 监听，假人死了就是原版流程——躺 20 秒后自己站起来。

现在 `CompatListener#onDeath` 在 `MONITOR` 阶段接住，**下一 tick** 按 `kill` 处理：

```
[SunPlayer] 假人 GuestFake 已死亡，按 kill 处理（不再复活）
Bot_GuestFake was killed
[SunPlayer] 已从 AuthMe 注销假人账号 Bot_GuestFake
[SunPlayer] 假人 GuestFake 的实体已随死亡删除
```

延后一 tick 是为了让原版死亡消息先广播出去，否则玩家只会看到假人凭空消失。开关是
`compat.remove-on-death`，关掉回到原版行为。

注意：**创造模式的假人打不死**。`/kill` 对创造模式玩家的伤害被原版挡掉（实测
`damage ... generic` 会返回 "Target is invulnerable to the given damage type"，`Health` 保持 20）。
这是原版行为，验证时需要先 `gamemode survival`。

### 17.4 AuthMe 注销只在 kill 时做

用户的选择：**只有彻底删除才注销**。

- `remove`（下线、重启会回来）→ 保留账号，复活时直接 `forceLogin`，不会每次重启换一个新随机密码；
- `kill`（含死亡删除）→ `forceUnregister`，AuthMe 里不再留垃圾账号。

两个入口都覆盖：在线时走 `remove(permanent=true)`，已下线时走 `forgetPersisted()`。

踩到的坑：AuthMe 6.0.1 的方法名是 `forceUnregister(String)` / `forceUnregister(Player)`，
不是 `unregisterPlayer`。第一版写成后者，运行时报

```
AuthMe 注销假人 Bot_NpcLook 失败: java.lang.NoSuchMethodException:
fr.xephi.authme.api.v3.AuthMeApi.unregisterPlayer(org.bukkit.entity.Player)
```

用 `javap` 列出 `AuthMeApi` 全部方法后改正。优先用 `String` 重载（此时实体正在被摘除，
`Bukkit.getPlayerExact` 可能已经拿不到人），`Player` 重载只作兜底。

另外 `prepareLogin` 也必须改用**前缀名 + 前缀 UUID**。原来它用的是裸名，结果 LuckPerms/
AuthMe 给裸 UUID 建了数据，而真正生成出来的假人是前缀 UUID，两边对不上——Folia 上立刻复现
LuckTags 在 `PlayerJoinEvent` 的 NPE：

```
java.lang.NullPointerException at dev.bauhd.lucktags.LuckTags.handleJoin(LuckTags.java:88)
    at ...PlayerList.placeNewPlayer(PlayerList.java:310)
    at com.sunplayer.FakePlayerManager.createHere(FakePlayerManager.java:245)
```

### 17.5 权限拆分：普通人能用假人，不能用 NPC

`plugin.yml` 去掉命令上的 `permission`，改成三个节点：

| 节点 | 默认 | 覆盖 |
|---|---|---|
| `sunplayer.use` | `true` | 生成、动作、`remove`、`kill`、`list` |
| `sunplayer.npc` | `op` | 所有 `--` 参数（`--npcmode` 一族） |
| `sunplayer.admin` | `op` | children = 上面两个，兼容旧配置 |

判定点只有两处：`onCommand` 开头查 `sunplayer.use`，`createNpc` 开头查 `sunplayer.npc`。
未实现"只能杀自己的假人"这类细分——用户要的是"谁都能用"。

### 17.6 补全重写

用户反馈"命令补全有些乱，现在看不到 move 了，都是 `--` 的"。

旧实现的问题：先按"这个假人**现在**在不在线"二选一，不在线就把整屏 `--` 参数推给你。
一个被 `remove` 过（存档还在、实体不在）的假人正好落进这个分支，于是 `move`/`jump` 全消失；
另外 `args.length == 3` 时无条件推 `once/continuous/interval`，不管当前动作收不收这个参数。

新实现按参数位分别处理：

| 位置 | 内容 |
|---|---|
| 第 1 个参数 | `list` + 在线/存档里的假人裸名 |
| 假人在线 | `remove` / `kill` + 全部 18 个动作 |
| 假人已下线（存档在） | 只给 `remove` / `kill`（动作对离线假人必然失败） |
| 名字不存在 | `--` 参数，且**必须有 `sunplayer.npc`** |
| 已在敲 `--` | 继续给 `--` 参数 |
| `move` 后 | `forward/backward/left/right` |
| `turn` 后 | `left/right/back` |
| `hotbar` 后 | `0`-`8` |
| `use`/`attack`/`swing` 后 | `once/continuous/interval` |
| `drop` 后 | `once/continuous/interval` + `mainhand/offhand/hotbar` |

过滤统一走 `filter(candidates, typed)`，大小写不敏感——之前 `dropStack` 推荐、`dropstack`
实现，靠大小写不敏感才没出错。

### 17.7 验证

协议级验证用的是自写的 `testservers/bot/tabcomplete.mjs`（直接发 `tab_complete`
包、读 `tab_complete` 响应），命令/权限验证用 `permtest.mjs`。

| 项目 | Paper 1.21.11 | Folia 1.21.11 |
|---|---|---|
| 补全 `sunplayer ` | `list, NpcLeft, NpcPerm, NpcPermC, NpcTest` | 同 |
| 补全 `sunplayer NpcTest ` | 18 个动作 + `remove`/`kill` | 同 |
| 补全 `sunplayer NpcTest move ` | `backward, forward, left, right` | 同 |
| 补全（无 op）`sunplayer 新名字 ` | 空 | 空 |
| 无 op 生成假人 | ✅ `已生成假人 PlainOne（服务端名 Bot_PlainOne）` | ✅ |
| 无 op 用 `--npcmode` | ✅ 被拒 | ✅ 被拒 |
| 无 op 发动作 `move forward` | ✅ | ✅ |
| 真人 `Twin` 与假人 `Bot_Twin` 共存 | ✅ 互不影响 | ✅ |
| 死亡 → 删除 + 注销 | ✅ | ✅ |
| `remove` 保留 AuthMe / `kill` 注销 | — | ✅（`forceLogin` / `was unregistered`） |
| 重启复活（服务端名带 `Bot_`） | ✅ | ✅ |


## 18. NPC 改用 Mannequin 实体（0.3.0）

### 18.1 为什么要换

用户提出三点：NPC 不响应物理、不占游戏人数上限、最好是"纯发包"。

旧实现里 NPC 就是一个 `ServerPlayer`（假人），只是加了 `--npcmode` 标记，再靠 `hurtServer` / `isPushable` / `push` 三个 override 禁掉伤害和推动。但它是真玩家，所以：

- 占一个 `max-players` 名额（实测 4 个 NPC + 1 个真人就把 5 人服撑满）；
- 进 tab 列表、有 `playerdata/<uuid>.dat`；
- 跑完整玩家物理循环，只是被逐个 override 打补丁。

1.21.9 加入的原生 `Mannequin` 实体正好解决全部问题：它是 `LivingEntity`，长得像玩家、支持皮肤，但**不是玩家**。

### 18.2 关键 API（1.21.11）

```java
public interface Mannequin extends LivingEntity {
    void setProfile(ResolvableProfile profile);   // 皮肤
    void setImmovable(boolean immovable);         // 无物理
    void setDescription(Component description);   // 看向它时的说明
    void setSkinParts(SkinParts parts);
    void setPose(Pose pose, boolean fixed);
}
```

NMS 侧：`net.minecraft.world.entity.decoration.Mannequin extends Avatar extends LivingEntity`，
`EntityType.MANNEQUIN` 的协议 id 是 **83**。

皮肤走 `ResolvableProfile.resolvableProfile(PlayerProfile)`，而 `PlayerProfile` 就是 `LoginCompat`
里已有的那个（带 `textures` 属性）。

### 18.3 实现要点

| 需求 | 做法 |
|---|---|
| 不占玩家位 | 根本原因是它不是 `ServerPlayer`，不进 `PlayerList`。不需要任何额外处理 |
| 无物理 | `setImmovable(true)` + `setGravity(false)` + `setCollidable(false)` |
| 不可伤害 | `setInvulnerable(true)` |
| 不移动 | 每 4 tick 的 ticker 里 `enforceAnchor()`，位置偏离 0.01 格就传回生成点。防活塞、防插件传送 |
| 皮肤 | Mannequin 的 profile 组件 |
| 点击 | 原生 `PlayerInteractEntityEvent` / `PrePlayerAttackEntityEvent`，**不需要拦包** |

`FakePlayerManager` 里 NPC 从 `byName`（`Map<String, FakePlayer>`）挪到了独立的
`npcs`（`Map<String, Npc>`），因为 `Npc` 不是 `FakePlayer`。`FakePlayer` 里那些
`hurtServer` / `isInvulnerableTo` / `isPushable` / `push` 的 NPC 分支全部删掉了。

### 18.4 踩到的三个坑

**① 不开 chunk ticket 的话 NPC 会消失**

第一版设了 `setPersistent(false)`（想让存档只以 YAML 为准，避免重启后 YAML 复活一个、
区块又存一个）。结果实体在区块卸载时被丢弃：

```
[SunPlayer] NPC TestNpc 已生成于 world -9.5 -60.0 -5.5 ...
（几秒后客户端收到 0 个 type=83 的 spawn_entity）
```

非持久化实体在区块卸载时会被移除。修法是 `world.addPluginChunkTicket(...)` 按住那一个区块，
实体的生命周期就跟着票据走；`kill`/`remove` 时 `removePluginChunkTicket` 释放。

**② ticker 在 NPC 还没创建时就启动了**

`startNpcTicker()` 原本在 `onEnable` 后固定 100 tick 调用一次。但复活是异步的（要查 Mojang
皮肤），重启时 NPC 在 7 秒后才建好，而 ticker 在 7 秒时检查到 `npcs` 为空就直接返回、再也不启动。
于是 `--look` 完全没反应（客户端收到的包只有 `entity_metadata` / `sync_entity_position`，没有旋转）。

修法：把 `startNpcTicker()` 也放进 `createNpcHere()`，方法本身做成幂等。修完后实测
`sync_entity_position` 的 `yaw` 从 64 变成 90，NPC 会转向玩家。

**③ `Bukkit.getScheduler()` 在 Folia 上不存在**

第一版的 look ticker 用的是 `Bukkit.getScheduler().runTaskTimer(...)`，这在 Folia 上会直接抛
`UnsupportedOperationException`。新增了 `ServerCompat.runGlobalTimer(...)`，内部按平台分流到
`GlobalRegionScheduler.runAtFixedRate` 或 `BukkitScheduler.runTaskTimer`，并返回一个 `Runnable`
取消句柄（两种 API 的返回类型不同，所以不能直接返回任务对象）。

### 18.5 验证

Folia 1.21.11（带 AuthMe / LuckPerms / PacketEvents）：

| 项目 | 结果 |
|---|---|
| 实体类型 | `@e[type=minecraft:mannequin]` 命中 |
| 玩家列表 | NPC 在线时 `There are 0 of a max of 5 players online` |
| 无重力 | 在 y=-45 的空中生成，6 秒后仍在 `y=-45.00` |
| 左键 / 右键 | `[NPC-debug] interact MANNEQUIN(...) -> npc=TestNpc`，`tell @s NPC_OK` 送达 |
| `--look` | yaw 64 → 90，转向玩家 |
| `kill` | 实体消失、存档条目删除、chunk ticket 释放 |
| `remove` | 实体消失、存档保留 |
| 重启 | `已恢复 NPC TestNpc（Mannequin）`，`kill` 掉的没有回来 |

Paper 1.21.11：创建 / 点击（收到 `PAPER_OK`）/ `kill` / 玩家列表 `0 of a max of 5` 全部一致。

### 18.6 顺带的诊断能力

`NpcListener` 和 `FakePlayerManager` 里加了受 `debug: true` 控制的日志：

```
[NPC-debug] interact MANNEQUIN(c65a17e8) -> npc=TestNpc
[NPC-debug] trigger TestNpc firing 1 command(s)
[NPC-debug] run TestNpc -> tell @s NPC_OK
```

排查"点了没反应"时能一眼看出是事件没到、还是匹配失败、还是命令执行了但没送达。

## 19. 假人挂机限时（0.4.0）

### 19.1 需求

- `config.yml` 里能设"每个假人挂机多久后自动删除（kill）"，单位分钟
- 每个假人独立计时，每 1 分钟检查一次，不占性能
- 删除前 1 分钟 tellraw 假人的**主人**
- 新增 `/sunplayer <名字> renew`，执行后重置计时

### 19.2 三个设计决定

**① 截止时间是"墙上时钟"，并且持久化**

`FakePlayer.expiresAt` 存的是绝对毫秒时间戳（`System.currentTimeMillis() + 分钟数`），
同时写进 `fake-players.yml` 的 `expires-at`。这样：

- 重启不会给每个假人发一份新的时长（如果用"在线秒数"，重启就是无限续命）
- 停机期间到期的假人在 `reviveOne` 里直接被丢弃，不会先复活再等一分钟
- 代价：假人被 `remove` 下线期间计时照走。这与"时长配额"的语义一致——
  `remove` 只是让实体下线，配额不该因此停表

**② 一个全局 60 秒任务，不是每个假人一个 timer**

`ServerCompat.runGlobalTimer(..., 1200L, 1200L)` 注册一次，遍历 `byName` 做一次
`long` 比较。真正的删除动作再通过 `ServerCompat.runOnEntity` 派发到假人自己的区域线程——
Folia 的规矩是实体只能由拥有它的区域线程碰，而 `remove()` 里要 `snapshot()` 读坐标。

**③ 到期执行 `kill` 而不是 `remove`**

`remove` 会保留存档、重启后自动复活，等于给到期的假人免费续命。`kill` 才会
删存档 + 注销 AuthMe 账号（`releaseAccount`），和手动 `kill` 完全同一条路径。

### 19.3 主人从哪来

`FakePlayerStore.Entry` 里本来就有个 `creator` 字段，但一直是写死的 `"?"`。这次真正用起来：

| 字段 | 内容 |
|---|---|
| `owner` | 创建者的玩家名（YAML 键；老版 `creator` 作为回退读取） |
| `owner-id` | 创建者的 UUID，判断身份以它为准，改名不影响 |
| `expires-at` | 截止时间戳，0/缺省表示不限时 |

`FakePlayer` 上也存了一份 `ownerId` / `ownerName`，供 `renew` 判断和发消息用；
`ownerOf(sender)` 会解开 `/execute as <player> run ...` 的代理，所以命令方块里生成的假人也有主人。

老存档没有 owner：到期时没人可提醒，只在控制台打一行；同时 `renew` 对"无主假人"完全放开，
否则这种假人谁也续不了。

### 19.4 命令与配置

```yaml
auto-remove:
  minutes: 120        # 0 = 关闭
  warn-minutes: 1     # 0 = 不提醒；必须 ≥ 1，见 19.6
```

| 命令 | 说明 |
|---|---|
| `/sunplayer <名字> renew` | 把截止时间推到 `now + minutes`，在线/remove 下线状态都支持 |

权限：假人的**主人本人**，或 `sunplayer.admin`（默认 OP）。拒绝时会带上主人名字，
免得玩家不知道自己不是主人。

提醒消息（`sendToOwner`）是带 `click_event` 的 Adventure 组件，点一下就等于执行 `/sunplayer <名字> renew`：

```
[SunPlayer] 请注意！您的假人 Auto1 挂机时间过长，即将删除，如果需要继续挂机，
请输入 /sunplayer Auto1 renew 进行续时
```

### 19.5 实现位置

| 文件 | 改动 |
|---|---|
| `FakePlayer` | `ownerId` / `ownerName` / `volatile expiresAt` / `volatile expiryWarned` |
| `FakePlayerStore` | `Entry` 增加 `owner` / `ownerId` / `expiresAt`；删掉没人用的 `saveAll()` |
| `FakePlayerManager` | `minutesToMillis` / `deadlineFromNow` / `Owner` / `renew` / `startAutoRemoveTicker` / `checkDeadlines` / `checkDeadline` / `sendToOwner` |
| `SunPlayerCommand` | `renew` 子命令、补全、`list` 追加"剩余 N 分钟" |
| `SunPlayerPlugin` | 启动时 `startAutoRemoveTicker()`；`config.yml` 缺 `auto-remove` 段时提示一句 |
| `config.yml` / `plugin.yml` / `README.md` | 配置段、0.4.0、文档 |

`renew` 更新存档时**不**调 `snapshot(fake)`，而是复用 `persisted` 里已有的 `Entry` 只替换
`expires-at`——因为 `renew` 跑在命令线程上，而 `snapshot` 要读实体坐标，在 Folia 上那是别人的区域。

发给主人的消息也走 `runOnEntity(owner, ...)`：`checkDeadline` 此时在假人的区域线程上，
而收消息的是另一个玩家，不能跨区域写。

### 19.6 时间粒度，以及为什么 warn-minutes 必须 ≥ 1

检查周期是 60 秒，提醒窗口是 `warn-minutes` 分钟。设 `warn-minutes: 1` 时窗口正好 60 秒宽，
**恰好有一个检查点落在 `[截止-60s, 截止)` 里**，所以提醒总是出现在删除前的一次检查——
实际提前量在 0–60 秒之间（平均 30 秒）。

如果 `warn-minutes < 1`（比如想设 0.5 分钟），窗口比检查周期还窄，可能整个窗口没有任何检查点，
于是**不提醒直接删**。配置只接受整数分钟，最小 1；0 表示完全不提醒。

同理，删除最多比截止时间晚 60 秒（下一次检查才发现）。

### 19.7 验证

Folia 1.21.11（AuthMe / LuckPerms / PacketEvents / ArcMenu 全装，`minutes: 2`、`warn-minutes: 1`）：

| 项目 | 结果 |
|---|---|
| 生成 | `已生成假人 Auto1（服务端名 Bot_Auto1）` |
| 提醒 | 到期前一次检查收到完整提醒（含可点击命令），`挂机时间过长……进行续时` |
| 非主人续时 | `[SunPlayer] 只有假人的主人（Tester）或管理员可以续时。` |
| 主人续时 | `已为 Auto1 续时，剩余 2 分钟。` |
| 续时是否真的延长 | 原截止点过去后 `list` 里仍在（没续的话这次检查就删了） |
| 到期删除 | 续时后的截止点后一次检查：广播 `Bot_Auto1 left the game`、`已从 AuthMe 注销假人账号 Bot_Auto1`、`假人 Auto1 已彻底移除（实体已回收）`，无重试/警告 |
| 提醒（删除时） | `您的假人 Auto1 挂机时间到，已被自动删除。` |
| 每个假人独立 | 同一分钟内创建的 Auto2 不受 Auto1 删除影响，仍在线 |
| 存档 | Auto1 的条目消失；Auto2 带 `owner` / `owner-id` / `expires-at` |
| 停机期间到期 | 手动把 `expires-at` 改到过去再启动：`假人 Auto2 的挂机时间在停机期间已到期，不再恢复`，且没进 `list` |
| 下线状态续时 | `remove` 后 `renew` 仍返回 `已为 Auto3 续时`，`fake-players.yml` 的 `expires-at` 同步变大 |
| NPC | `TestNpc` 全程不受影响（`snapshotOf` 写 0） |

### 19.8 测试环境踩的两个坑（都不是插件问题）

**① bot 被 AuthMe 踢下线**

日志：`Tester lost connection: Login timeout exceeded, you have been kicked from the server`。
`messages_en.yml` 里 `timeout_error` 就是这句，来源是 AuthMe。它的
`security.unrestrictions.UnrestrictedName` 名单里只有 `ArcBot` / `ArcGuest`，
新的 bot 名字没进名单 → 未登录 → 超时踢出。把 `Tester` / `Snoop` 加进名单 + `authme reload` 解决。

**② "第二个假人仍在线" 第一次跑出来是 FAIL**

脚本在 `/sunplayer Auto2` 之后固定等 1.5 秒就 `list`，而创建是异步的（要查皮肤），
实体那一刻还没进玩家列表。改成轮询"已生成假人 Auto2"再查即可。**不是**插件丢假人。

## 20. 假人只显示单层皮肤（0.5.1）

### 20.1 现象与根因

用户反馈：假人的皮肤只有底层，帽子/外套/袖子/裤子那一层完全不显示（换成 Notch 这类带外层
的皮肤最明显）。不是皮肤数据的问题——`textures` 属性一直是完整的，真人也用同一份数据。

根因在**客户端信息**：一个玩家「哪几层皮肤可见」不是由纹理决定的，而是由
`ClientInformation.modelCustomisation` 这个 7 位掩码决定（cape / jacket / 左袖 / 右袖 /
左裤 / 右裤 / hat），服务端把它写进实体元数据 `Avatar.DATA_PLAYER_MODE_CUSTOMISATION`，
每个看到这个实体的客户端按位渲染外层。

真人登录时客户端会自己发一次 `ServerboundClientInformation`，默认值 `127`（七层全开）。
假人没有客户端，我们构造的是 `new ClientInformation(..., 0, ...)`，而
`ServerPlayer` 的构造函数会调用 `updateOptionsNoEvents()` 把这个值直接写进实体元数据：

```
invokevirtual #657  // Method updateOptionsNoEvents:(...ClientInformation;)V
...
getstatic #4052     // Field Avatar.DATA_PLAYER_MODE_CUSTOMISATION
invokevirtual #4396 // Method ClientInformation.modelCustomisation:()I
i2b
invokevirtual #4062 // SynchedEntityData.set
```

所以假人给所有客户端广播的掩码是 `0` → 外层全隐藏 → 看起来就是单层皮肤。
`Avatar.DEFAULT_MODEL_CUSTOMIZATION` 本身就是 `0`，不是我们抄错常量。

### 20.2 修法

`FakePlayerManager#clientInformation()` 的第 5 个参数 `0` 改为 `SKIN_PARTS_ALL = 0x7F`。
不用额外调 `updateOptions()`：构造函数里的 `updateOptionsNoEvents` 已经把值写进实体元数据，
且 `placeNewPlayer` 之后发送的 `add_entity` / `set_entity_data` 带的就是这份数据。

`revive` 走的是同一个 `createHere`，所以重启恢复的假人也自动是多层。

NPC 不用改：`Mannequin` 的构造函数自己设了 `Avatar.DATA_PLAYER_MODE_CUSTOMISATION = ALL_LAYERS`
（`defineSynchedData` 里 `getstatic ALL_LAYERS` → `SynchedEntityData.set`），所以装饰实体一直是完整的。

### 20.3 验证

Folia 1.21.11，用 `minecraft-protocol` 客户端连进去直接读实体元数据（`key 16` = 该掩码）：

| 对象 | 元数据 | 结果 |
|---|---|---|
| 假人 `Bot_MetaTest` | `{"key":9,"type":"float","value":20},{"key":16,"type":"byte","value":127}` | 七层全开 |
| 真人 `Tester`（对照） | 同上，`key 16 = 127` | 与假人完全一致 |

修复前这个参数是 `0`，而构造函数会把它原样写进元数据（见 20.1 的字节码），所以客户端拿到的
就是"外层全隐藏"；改成 `127` 后元数据与真人完全一致。测试脚本：
`testservers/bot/skinlayers3.mjs`（对照法，同时打印真人）。

## 21. 切世界后假人卡住、打不死、/kill 无效（0.6.0）

### 21.1 现象

用户（DivineMC，Folia 分支）把假人推进下界传送门后：假人站着不动、**打它不掉血**、`/gamemode`
对它无效、`/kill Bot_X` 也没反应，只有 `/sunplayer <名字> kill` 能清掉。

关键点：`/kill` 与 `/sunplayer kill` 走的是**两条完全不同的路径**。前者是原版命令，按名字从玩家
列表取实体再 `kill()`；后者用插件自己的 map，直接在实体区域线程里强制移除。所以"只有后者能
用"意味着**原版命令拿到的那个玩家对象已经不是正常玩家了**，而不是权限或参数问题。

### 21.2 两个真正的坑

**① 传送确认没人回（主因）**

原版把玩家传送到别的维度时，最后一步是 `connection.teleport(...)`：

```
ServerGamePacketListenerImpl#teleport → 设 awaitingPositionFromClient，发
ClientboundPlayerPositionPacket（带一个自增 id）
```

之后**只有客户端回 `ServerboundAcceptTeleportationPacket(id)` 才会执行**
`player.absSnapTo(awaitingPositionFromClient...)` 并清空等待状态（见 `handleAcceptTeleportPacket`
字节码：先比对 id 与 `awaitingTeleport`，再 `absSnapTo`）。假人没有客户端，没人回这个包，于是：

- 目标坐标永远不生效 → 视觉上"站着不动"
- 玩家区块加载器还指着旧世界的坐标 → 新位置的区块不被 tick → 实体的 tick/伤害/死亡都不推进
- 原版 `/kill`、`/gamemode` 作用在一个"还没落地"的玩家对象上 → 看起来毫无反应

**修法**：`FakeConnection#send` 记下 `ClientboundPlayerPositionPacket` 的 id，
`FakePlayer#tick()`（正好跑在实体所属区域线程上）调用
`ServerGamePacketListenerImpl#handleAcceptTeleportPacket(...)` 替假人确认。等价于真客户端的行为。

**② 缺一个"幽灵假人"检测**

即使确认了传送，分支服务端仍可能留下"实体已从世界移除、玩家列表里还留着条目"的残骸。
这种残骸只能被强制移除清掉，正好就是用户手里唯一有效的 `/sunplayer kill`。

**修法**：`FakePlayerManager#startStuckTicker()`，每 10 秒一轮，把每个假人交给它所在区块的区域线程，
在那边检查三件事：

| 检查 | 含义 |
|---|---|
| `fake.isRemoved()` | 实体已被移除 |
| `level.getEntity(uuid) == fake` | 实体还在这个世界里 |
| `isListed(fake)` | 玩家列表里还有它 |

三条都正常 → 什么都不做（并清空重试计数）。任意一条不成立、且距上次换世界超过 8 秒（避开正常
传送的中间态）→ 判定为幽灵：按当前位置存档 → `remove` → 1 秒后 `reviveOne` 重新上线，最多 3 次，
之后只留一行"请用 /sunplayer <名字> kill 手动删除"。

### 21.3 顺手修掉的一个线程 bug

恢复假人时 `prepareLogin()`（派发 `AsyncPlayerPreLoginEvent`，LuckPerms/AuthMe 靠它准备数据）原本在
`spawnRevived()` 里调用，也就是在**区域线程**上——原版直接抛
`IllegalStateException: AsyncPlayerPreLoginEvent may only be triggered asynchronously`，被 catch 后
只剩一行 warning，事件根本没派发出去。现在挪到 `reviveOne` 的异步链里（皮肤查询之后、调度生成之前）。

### 21.4 验证与观察（Folia 1.21.11）

| 项目 | 结果 |
|---|---|
| 传送门进下界 | `假人 PortalTest 切换世界: world -> world_nether (…)`，`本线程持有区域=true` |
| 到达后受击 | `[debug] 假人 PortalTest 受到伤害: ENTITY_ATTACK 5.0 … 世界=world_nether` |
| 到达后 `/damage` | 正常死亡并清档（`Bot_PortalTest died`） |
| 往返切换 | `world_nether -> world (0.5 100.0 0.5)`、`world -> world_nether (0.5 64.0 0.5)` 都正常 |
| 下界窒息 | `SUFFOCATION 1.0` 逐跳扣血直到死亡 → 说明新世界的 tick 与伤害链都正常 |
| 幽灵检测误报 | 假人在下界/主世界来回 40 多秒，日志里**没有**任何"状态异常" |

`debug: true` 时会额外打印：假人初始状态（模式/无敌/血量/区域归属）、每次受到的伤害、每次切换世界；
CompatListener 里还有一个只在该开关下工作的伤害监听器。

## 22. `/deop` 后所有假人被白名单踢下线（0.6.1）

### 22.1 现象

服主 `/deop` 自己的一瞬间，8 个假人同时 `lost connection: 你不在白名单内！…` 并 `left the game`，
日志里没有任何 SunPlayer 的拦截记录。假人是另一位玩家创建的，与创建者/主人无关。

### 22.2 根因

这句提示不是原版白名单的文本（原版是 `You are not white-listed on this server!`），而是第三方白名单插件。
这类插件在 `/deop`、`/whitelist reload` 或定时任务里会遍历在线玩家逐个校验，并对不合格的调用
`Player#kick(Component, Cause.WHITELIST)`。

假人**从不经过登录流程**（`PlayerList#placeNewPlayer` 直连），因此它永远不在任何白名单里；
而旧版 `CompatListener` 把 `PlayerKickEvent.Cause.WHITELIST` 归入"管理员故意"的集合，直接放行：

```java
INTENTIONAL = { KICK_COMMAND, BANNED, IP_BANNED, WHITELIST, RESTART_COMMAND, DUPLICATE_LOGIN }
```

于是插件踢得干净利落，且因为走的是"故意"分支，连一行日志都没有——正好对上"没有拦截记录、全部退光"。

另外一个顺带查明的机制（同一个 `ServerGamePacketListenerImpl#disconnect` 字节码）：

```
offset 85:  if (!TickThread.isTickThreadFor(player)) {
offset 89:      this.connection.disconnectSafely(details);   // 不派发 PlayerKickEvent
offset 97:      return;
            }
offset 204: new PlayerKickEvent(...); PluginManager.callEvent(event);
offset 256: if (event.isCancelled()) return;
```

也就是说**只有目标所在区域线程上的踢出才会派发 `PlayerKickEvent`**，跨线程踢出走
`Connection.disconnectSafely`。`FakeConnection` 把 `disconnect`/`handleDisconnection` 覆写成空实现，
所以跨线程踢出对假人根本无效——测试里必须用 `RegionScheduler` 把 kick 派发到假人所在区域线程，
否则测不到真实路径（第一版测试插件就是这么写错的：命令返回"已踢出"，假人却安然无恙）。

### 22.3 修改

1. `INTENTIONAL` 移除 `WHITELIST`：假人不参加白名单，针对它的白名单踢出一定是误伤。
   新增配置 `compat.ignore-whitelist-kick: true`，命中时取消事件并打印
   `已拦截白名单插件对假人 X 的踢出（假人不参与白名单）: <原因>`。
2. 每次踢出都记录原因类型（以前只有"拦截成功"才写日志，放行的踢出完全静默）：
   `假人 X 被踢出（WHITELIST|PLUGIN|BANNED|…）: <原因>`。
3. 新增 `auto-revive`：对**非故意**的掉线，从存档快照重新上线。用于兜住那些绕过 Bukkit 事件、
   直接断开连接的插件：
   - 第 1 次 5 秒，之后翻倍（`delay-seconds` × 2^(n-1)，单次上限 60 秒）
   - `max-attempts`（默认 6）次后放弃并写一行警告，避免和踢人的插件无限拉锯
   - 10 分钟窗口外自动重置计数，避免"一天被踢一次"的假人耗尽次数
   - 超过上限只在窗口内警告一次（曾用 `attempt == max+1` 判断，改配置后计数跳号就漏报）
4. `forgetOnline(String)` 改为 `forgetOnline(FakePlayer)`：掉线时刷新存档快照，
   自动复活用的是假人**当前**位置而不是创建时的位置。
5. `shutdown()` 先置 `shuttingDown` 标志：停机时玩家退出（`RESTART_COMMAND` 等）不会安排复活任务。
6. 故意掉线不复活：管理员 `/kick`、封禁、重启、同名顶号由 `intentionalKicks` 集合记录，
   `/sunplayer <名字> kill` 走 `remove(permanent=true)`，两者都不会触发 `auto-revive`。

注意 `PlayerKickEvent` 的取消只对"区域线程上的踢出"有效；跨线程踢走 `disconnectSafely`，
`FakeConnection` 的空实现本身就已经挡住了，所以两条路径都被覆盖。

### 22.4 验证

测试插件 `testservers/kicktest/`（`KickTest.jar`，只用于测试，不进发行包）：`/kicktest <whitelist|plugin|banned> <玩家>`，
内部用 `Bukkit.getRegionScheduler().execute(...)` 把 kick 派发到目标所在区域线程。

| 项目 | Folia 1.21.11 | Paper 1.21.11 |
|---|---|---|
| `WHITELIST` 踢出 | 拦截，假人留在 `list` 中 | 拦截，同上 |
| `PLUGIN` 踢出（`prevent-plugin-kicks: true`） | 拦截 | 拦截 |
| `BANNED` 踢出 | 踢出，**不**自动复活（存档保留，`list` 提示 `/sunplayer revive`） | 同上 |
| `PLUGIN` 踢出（`prevent-plugin-kicks: false`） | 5 秒后自动上线 → 再次被踢 → 10 秒后第 2 次 → 第 3 次超限，打印"一直被踢出…已停止尝试" | 同逻辑 |
| `/sunplayer <名字> kill` | 删除存档，12 秒内没有被复活 | — |
| 配置自动补全 | `已自动补上：compat.ignore-whitelist-kick, auto-revive.enabled, auto-revive.delay-seconds, auto-revive.max-attempts` | 同上 |

顺带的两个教训（写进测试流程）：

- 后台用 PowerShell `*>` 重定向服务端输出会写出 **UTF-16LE** 文件，`Select-String`/`grep` 都读不到中文；
  看中文日志用 `logs/latest.log`（服务端自己写的 UTF-8）。
- 用 `Set-Content` 改 UTF-8 的 `plugin.yml` 会按 ANSI 读写、把中文写成乱码，服务端直接
  `Invalid plugin.yml: MalformedInputException` 加载失败。改资源文件必须用编辑器（本仓库用 write/edit 工具）。

## 23. 上限计数、`INVALID_PLAYER_MOVEMENT` 刷屏、不受击退（0.6.2）

### 23.1 现象

1. "假人限制识别的是总玩家数，不是假人数量"——真人在线时上限提前触发。
2. 控制台疯狂刷（每 tick 一组）：

```
[SunPlayer] 已拦截对假人 test123 的踢出（INVALID_PLAYER_MOVEMENT）: Invalid move player packet received
Disconnected on accept teleport packet. Was not expecting position data from client at this time
```

3. 假人不受击退，打上去不动。

### 23.2 排查

**上限**：`SunPlayerCommand#create` 比较的是 `manager.count()`，而 `count()` 是
`byName.size() + npcs.size()`；`byName` 只有 `createHere` 一处 `put`，值类型是 `FakePlayer`，
真人不可能进去。用测试插件实测：8 个假人在线时 `fakeCount=8 / count=8`，第 9 个仍能建。
所以代码本身只数假人。真正会让它在"看起来没满"时触发的是两件事：

- `byName` 里留着**实体已被删除**的残骸（旧版本的幽灵假人、被外部插件直接删掉实体），
  `list` 会把它们显示出来，但数量会被它们顶满；
- **服务器玩家位**（`server.properties` 的 `max-players`）——假人是真正的 `PlayerList` 条目，
  2 真人 + 8 假人 = 10 时，第 9 个假人仍然能创建，但真玩家进不来，很容易被当成"假人上限"。

**刷屏**：`Cheat` 在 `fakeConnection.confirmTeleport(id)` 里无条件调用
`handleAcceptTeleportPacket`。字节码显示它只在一种情况下踢人：

```
id == awaitingTeleport 且 awaitingPositionFromClient == null
  → disconnect(Component.translatable("multiplayer.disconnect.invalid_player_movement"))
```

也就是"id 对得上、但没有等待中的位置"。传送确认成功后 `awaitingPositionFromClient` 被清空，
而 `awaitingTeleport` 保留旧值；只要再收到一个同 id 或服务端重新下发的位置包，下一 tick 就会撞上这个分支。
`FakeConnection` 没记录"我到底回过哪些 id"，于是每次位置包都回一次 → 每次都被判定非法移动 →
踢出被 `CompatListener` 取消 → 下一 tick 再来一遍。DivineMC 还会在踢之前先打一行
"Disconnected on accept teleport packet…"（原版 1.21.11 的 jar 里没有这句，是分支自己的日志），
所以看起来像刷屏。

**击退**：僵尸攻击实测表明物理本身没问题（修复后假人从 60.5 被推到 64.3）。假人"不动"时，
多数是被卡在传送等待状态：位置包一直没被正确应用，服务端每 tick 的位移校正把速度覆盖掉。

### 23.3 修改

1. `count()` 改为只统计**活着**的假人（`!isRemoved()`）和 NPC（`entity().isValid()`）；
   残骸不再顶满名额。新增 `fakeCount()` / `npcCount()` / `capacityText()`，
   后者带上前 12 个名字，便于一眼看出是谁占着名额。
2. 上限提示改为
   `假人数量已达上限（在线假人 8 + NPC 0，上限 8：a, b, c …）`，
   并补一句"只数假人和 NPC，不数真人"——把口径写在玩家看得到的地方。
3. `list` 头部改成 `列表 (假人 8/10，NPC 2)`；玩家位用满时红字提示
   `服务器玩家位已满 10/10（假人也占位）…可调大 max-players`。
4. `FakeConnection` 用反射读 `awaitingPositionFromClient`、`awaitingTeleport` 两个私有字段，
   **只在服务端确实在等这个 id 时**才回确认；否则丢弃 id（`debug: true` 时打印一行）。
   字段读不到（分支改名）时退回旧行为——少答一次传送远比永远不答安全。
5. 传送确认机制本身保留：没有它，穿传送门后假人会卡死（§21）。

### 23.4 验证（Folia 1.21.11，测试插件 `/kicktest spawn|runas|stat`）

| 项目 | 结果 |
|---|---|
| 8 假人时计数 | `假人=8 NPC=0 count=8 上限=10 服务器在线=8/30`，第 9 个假人可正常创建 |
| 达到上限再建 | `假人数量已达上限（在线假人 12，上限 10：A11, A10, A12, A1 …）。` |
| `list` 头部 | `列表 (假人 12/10)` |
| `/tp` 假人 | `Teleported Bot_A2 to 40.5, -60, 40.5` → `list` 显示 40.5，跨区块也生效 |
| 传送刷屏 | 日志里**没有** `INVALID_PLAYER_MOVEMENT`、也没有"忽略传送确认"（说明位置包压根没被回错） |
| 击退 | 僵尸一击把假人从 `60.5 -60 60.5` 推到 `64.3 -60 60.7`，随后正常被打死 |

测试工具说明：`testservers/kicktest` 现在有 `/kicktest spawn <名字> <x> <y> <z>`（反射调用
`SunPlayerPlugin#manager()` → `createAsync`）、`/kicktest runas <玩家> <命令>`（以假人身份执行命令，
用来走真实命令路径）、`/kicktest stat`（打印计数）。之所以不再用 minecraft-protocol bot：
新名字的 bot 会被 AuthMe 的登录超时直接踢掉，`/kicktest` 不经过登录，稳定得多。

## 24. 玩家打假人只有伤害、没有击退（0.6.3）

### 24.1 现象

"打假人，假人只会受伤，不会被击退；但是僵尸有击退"。即：真人左键能掉血，假人原地不动；
同一只假人被僵尸打却会被推开。0.6.2 里我误判为传送状态残留（§23.2 第三段），实际不是。

### 24.2 根因（反编译 `mojang_1.21.11` 确认）

击退本身在 `LivingEntity#hurtServer` 里，对所有伤害源一视同仁：

```java
if (!damageSource.is(DamageTypeTags.NO_KNOCKBACK)) {
    this.knockback(0.4F, d, d1, damageSource.getDirectEntity(), Cause.ENTITY_ATTACK);
}
```

但 `Player#attack` 在伤害结算之后，对**玩家受害者**多做了一步（Paper 补丁，用于通知客户端）：

```java
boolean flag3 = target.hurtOrSimulate(damageSource, f2);   // ← 这里已经 knockback 了
if (flag3) {
    this.causeExtraKnockback(target, this.getKnockback(...) + (flag1 ? 0.5F : 0.0F), deltaMovement);
    ...
}

public void causeExtraKnockback(Entity target, float strength, Vec3 currentMovement) {
    if (strength > 0.0F) { ... }                    // 普通空手/非冲刺 = 0，整块跳过
    if (target instanceof ServerPlayer && target.hurtMarked) {
        ...
        ((ServerPlayer)target).connection.send(new ClientboundSetEntityMotionPacket(target));
        target.hurtMarked = false;
        target.setDeltaMovement(currentMovement);   // ← 把击退速度原样收回
    }
}
```

`currentMovement` 是**受击前**的速度（`Player#attack` 第 1030 行取的），于是：服务端算出的
`(0.40, 0.36, 0)` 被覆盖回 `(0, 0, 0)`，速度改由 `ClientboundSetEntityMotionPacket` 交给客户端
去模拟——真人客户端收到就自己动，假人没有客户端，包被 `FakeConnection#send` 丢弃，击退就凭空消失。

僵尸的 `Mob` 路径用的是 `LivingEntity#causeExtraKnockback`，里面**没有** `ServerPlayer` 这段回收，
所以同样的假人被僵尸推得动。这正好解释"只有玩家打不动"。

### 24.3 修改

`FakePlayer` 覆盖 `LivingEntity#knockback(double,double,double,Entity,Cause)`（所有击退都经过它）：

1. 调用 `super` 前后各取一次 `getDeltaMovement()`，把「服务器算出的速度」和「被替换掉的原速度」
   存成一个不可变记录 `Knockback(wanted, before)`（`volatile`，一对值始终属于同一次击退）。
2. `tick()` 最前面调 `applyPendingKnockback()`：如果当前速度**正好等于** `before`，说明这次击退确实
   被服务端收回了，就把它补回去；如果速度已经变了（僵尸那种正常生效的击退、重力、水流），
   什么都不做——所以不会重复施加。
3. 只在 `debug: true` 时打两行日志：
   `假人 X 收到击退 强度=0.40 速度 (…)（来源=ENTITY_ATTACK）` /
   `假人 X 补回被服务端收回的击退速度 (…)`。

特意没有去拦 `ClientboundSetEntityMotionPacket`：那个包可能比 tick 晚到，按包内容赋值会把
上一 tick 已经消耗掉的速度再补一遍（击退距离被拉长）。比对 `before` 只认"被收回"这一种情况，
既精确又不会加倍。

### 24.4 验证（Folia 1.21.11 + Paper 1.21.11，`/kicktest attack <攻击者> <目标>`）

`/kicktest attack` 直接调 `Player#attack(Entity)`，就是客户端左键的服务端全部逻辑。

| 版本 | 场景 | 结果 |
|---|---|---|
| 0.6.2（对照） | 玩家打一下 | 血量 20 → 19，坐标 `2.000 → 2.000`（**没动**） |
| 0.6.3 | 玩家打一下 | 血量 20 → 19，坐标 `2.000 → 3.989`，中途 y 抬到 `-59.214`（有起跳分量） |
| 0.6.3 | 连打第二下 | `3.989 → 5.977`，每次稳定 1.99 格，可重复 |
| 0.6.3 | `/damage` 直伤（无回收路径） | `20.5 → 22.488`，与玩家击退完全一致 → **没有加倍** |
| 0.6.3 | 僵尸打 | `22.488 → 20.556`（被推离僵尸），日志只有"收到击退"、没有"补回" |
| 0.6.3 | Paper 端 | `2.000 → 3.989`，同上 |
| 0.6.3 | 刷屏/误踢 | 两个服务端日志里 `INVALID_PLAYER_MOVEMENT` 均为 0 条，无新增踢出 |

日志实证（Folia）：

```
[SunPlayer][debug] 假人 Tgt 受到伤害: ENTITY_ATTACK 1.0（取消=false，模式=SURVIVAL，…）
[SunPlayer][debug] 假人 Bot_Tgt 收到击退 强度=0.40 速度 (0.40, 0.36, 0.00) （来源=ENTITY_ATTACK）
[SunPlayer][debug] 假人 Bot_Tgt 补回被服务端收回的击退速度 (0.40, 0.36, 0.00)
```

## 25. 假人物品栏界面、`use` 打开 GUI 崩溃、NPC 命令的 `@s`（0.7.0）

### 25.1 现象

1. 需要能看/改假人的背包与装备（类似 InvSee），希望 `/sunplayer <名字> invsee` 直接开箱子界面。
2. 假人执行 `use continuous` 走到箱子/工作台前时，服务端报
   `Entity threw exception at ... net.minecraft.server.RunningOnDifferentThreadException`，
   十秒后出现 `假人 X 状态异常（实体已移除=true，在世界里=false，在玩家列表=true），自动修复`。
3. NPC 的 `--command` 里写 `@s`，在只认玩家名、不认选择器的插件上静默失败。

### 25.2 根因

**（2）打开界面会让假人的 tick 抛出异常。** `ActionPack#doUse` 直接调用
`ServerPlayerGameMode#useItemOn`，命中箱子/工作台时内部走 `Player#openMenu`，给这个**没有客户端**的
`ServerPlayer` 挂上一个容器界面。Folia 随后在实体 tick 里检查这个菜单并 `closeContainer`，异常从
`ServerPlayer#tick()` 里冒出来，被 Folia 记成「Entity threw exception」，实体被丢弃、玩家列表条目留下。

**（1）`/sunplayer <名字> invsee` 不能复用假人的容器**：假人自己不能有容器（见上），所以界面必须是
插件自己建的 `Bukkit.createInventory`，只把内容复制进/复制出。

**（3）`@s` 只有原版派发器认识**，插件自己解析参数时它只是一个叫 "@s" 的玩家。

### 25.3 修改

- 新增 `InvseeGui`：6 行箱子界面，`GUI_TO_INV` 把窗口槽位映射到玩家背包
  （0-3 = 头盔/胸甲/护腿/靴子，4 = 副手，9-35 = 背包，36-44 = 快捷栏）；其余格子放灰色玻璃板（带
  PDC 标记），点击/拖拽涉及它们时直接取消，避免把装饰板带走或把物品丢进不同步的格子。
- 同步是**逐槽位差分**：`Session#last` 记录假人当前内容，点击后下一 tick 在查看者线程拍快照，
  再在假人的区域线程上只写「和 `last` 不同」的槽位。界面开着时假人自己捡到的东西不会被整窗覆盖。
- 线程：读/写假人背包在假人所在区域线程；读窗口/开界面在查看者区域线程（`ServerCompat.runOnEntity`）。
- `ActionPack`：`use` 命中 `BlockState#getMenuProvider(...) != null` 的方块时跳过（debug 日志）；
  `attack`/`use` 前用 `Bukkit.isOwnedByCurrentRegion` 判断目标方块/实体是否属于本区域，不是就跳过；
  每次动作结束调用 `closeContainer()` 兜底。
- `FakePlayer#tick` 把「我们自己的那几步」（击退补回、输入、动作、传送确认、世界检查、生成点保护）
  包进 `guard(...)`：异常不再冒到实体 tick 之外；`RunningOnDifferentThreadException` 按名字识别并静默
  （它表示「我已经把调用排到正确的线程，别再在这里做」），其它异常限流打警告。
- `NpcOptions#resolveSelf`：派发前把**独立的** `@s` token 换成点击者玩家名；`@s[...]`、引号/JSON 里的
  `@s` 保持不变。
- 权限：新增 `sunplayer.invsee`（默认所有人），实际仍限「假人主人或 `sunplayer.admin`」。
- 假人被移除时 `InvseeGui#closeFor` 关掉对应窗口；插件关闭时 `closeAll`。

### 25.4 验证

Folia 1.21.11（debug 开）：

| 项目 | 结果 |
|---|---|
| `/sunplayer Inv1 invsee` | 窗口 54 格；假人 39 号槽（头盔）显示在窗口第 0 格，快捷栏 0/1 显示在 36/37 |
| 点击把头盔从窗口 0 移到窗口 9 | `写回 … 槽位 9: 空 -> 1x DIAMOND_HELMET`、`槽位 39: … -> 空`、`同步: 共修改 2 个槽位` |
| 重开窗口 | 第 0 格空、第 9 格是头盔（`getinv` 同样为 `9=1xDIAMOND_HELMET`） |
| 点装饰格（45） | 取消，光标仍为空、装饰格不变 |
| 假人对箱子 `use continuous` | 每 2 tick 一行 `use 跳过：方块 -6, -61, -11（Block{minecraft:chest}）会打开界面`，假人存活 |
| 假人对工作台 `use continuous` | 同上（`Block{minecraft:crafting_table}`） |
| `RunningOnDifferentThreadException` / `Entity threw exception` | 0 条 |

Paper 1.21.11：

| 项目 | 结果 |
|---|---|
| invsee 打开窗口 | `界面类型=CHEST 大小=54`，5 tick 后仍在 |
| 窗口点击 → 假人 | `写回 PA 槽位 9: 空 -> 1x DIAMOND_HELMET`，`getinv` = `9=1xDIAMOND_HELMET` |

NPC `@s`（Folia，用 `/kicktest pos @s` 验证——KickTest 自己解析参数，不认选择器）：

```
[NPC-debug] run NpcT -> kicktest pos ArcBot（原命令: kicktest pos @s）
[KickTest] ArcBot pos=-1.500 -60.000 -2.500 世界=world 生命=20.0 模式=CREATIVE
```

### 25.5 测试环境备注

- 无头客户端在 1.21.11 上发的 `window_click` 会被本测试服忽略（连 window 0 的点击都无效），
  所以窗口点击是用测试插件 `/kicktest invtest <玩家> <窗口槽位> <目标槽位>` 复刻服务端的
  「先 fire `InventoryClickEvent`、再移动物品」两步来验证的。
- 假人 `use` 必须真的**命中**方块：眼睛在脚上 1.62 格，水平射线不会打到脚边的箱子，
  测试时要把方块放在眼睛高度。
- AuthMe 的 limbo / 已经死亡的玩家 `isImmobile()` 为真，`ServerPlayer#tick()` 会在下一 tick 直接把刚
  打开的容器关掉；测试时必须先 `/login` 并确保玩家活着，否则看起来像「界面开了就没了」。

## 26. NPC 点击冷却改成毫秒（`--delay`）（0.7.1）

### 26.1 需求

NPC 点击命令原先只有 `--cooldown <tick>`（默认 10 tick = 500 ms），tick 粒度太粗，也容易被误认为没有冷却。
需要毫秒级、更直观的开关：`--delay <毫秒>`。

### 26.2 修改

- `NpcOptions` 的 `int cooldownTicks` 换成 `long cooldownMillis`；`--cooldown <tick>` 仍按 `×50` 换算，
  `--delay <ms>` 直接用毫秒，两个都写时**以最后一个为准**。
- 新增 `NpcOptions#MIN_COOLDOWN_MILLIS = 50`：一次右键客户端会发两个交互包，50 ms 的下限就是合并这两个包的，
  所以 `--delay 0` 实际是 50 ms（和以前「最小 1 tick」的行为一致）。
- `triggerNpc` 的比较从 `tick × 50_000_000ns` 改成 `effectiveCooldownMillis() × 1_000_000ns`。
- 存档：新写 `npc.cooldown-ms`（同时写一份 `npc.cooldown` 的 tick 数供人阅读），读取时优先 `cooldown-ms`，
  没有就按老文件 `cooldown × 50` 换算——老存档不需要迁移。
- 创建成功的提示里会写明实际冷却：`冷却 3000 ms`。

### 26.3 验证（Folia 1.21.11）

```
/sunplayer Ndelay --npcmode --command kicktest pos @s --delay 3000
```

| 操作 | 结果 |
|---|---|
| 两次点击相隔 311 ms | 只触发 **1** 次 |
| 3.8 s 后再点一次 | 又触发 1 次（整轮 `RESULT triggers = 2 gaps = 3794`，间隔 3.8 s） |
| `--delay abc` | `参数错误: --delay 的值不是数字: abc` |
| `--delay 99999999` | `--delay 必须在 0 到 3600000 之间` |
| `--cooldown 60`（老写法） | 正常解析，等价 `--delay 3000` |
| 存档 | `Ndelay: cooldown-ms: 3000 / cooldown: 60` |

## 27. NPC 不对普通玩家公开（0.7.3）

### 27.1 需求

NPC 是管理员放的装饰。普通玩家不应该在 `/sunplayer ` 的 tab 补全里看到 NPC 名字，也不应该能
`kill` / `remove` / `skin` 掉它们；自己生成的假人不受影响。

### 27.2 行为

| 操作 | 有 `sunplayer.npc`（默认 OP） | 只有 `sunplayer.use` |
|---|---|---|
| tab `/sunplayer ` | 假人 + NPC | **只有假人** |
| tab `/sunplayer <NPC> ` | `kill, remove, skin` | 无任何建议（客户端不显示列表） |
| `/sunplayer <NPC> kill` | 删除 NPC | `删除 NPC 需要管理员权限（sunplayer.npc）。` |
| `/sunplayer <NPC> skin ...` | 换皮肤 | `修改 NPC 需要管理员权限（sunplayer.npc）。` |
| `/sunplayer list` | 含 `[NPC]` 行与 NPC 计数 | 只列假人，NPC 行/计数/说明都不出现 |
| `/sunplayer <NPC> <动作>` | NPC 不支持动作 | 同样提示 |

`sunplayer.npc` 的语义由「只能创建 NPC」扩成「NPC 的全部管理」：创建、tab 列表、删除、换皮肤。

### 27.3 实现

- `FakePlayerManager#isNpc(name)`：在线 NPC，或存档里 `npc.enabled: true` 的条目，都算 NPC。
- `remove(name, permanent, onDone, allowNpc)`：命令层按 `sunplayer.npc` 传入 `allowNpc`。
  为 `false` 时既不进 NPC 分支，也不做「残留 Mannequin 扫描」——否则可以靠猜名字让插件扫到
  未登记的实体再删掉，等于绕过权限。
- tab 第一段用 `visibleNames(sender)` 过滤 NPC；第二段对 NPC 只给管理员 `kill/remove/skin`，
  普通玩家得到空列表（客户端因此不弹出任何建议）。
- `list` 对普通玩家过滤 NPC 行、NPC 计数和 `[NPC]` 说明；存档里没上线的 NPC 也不进「未恢复」名单。
- 生成假人时若同名 NPC 已存在：管理员看到「这个名字是 NPC，先 kill 再生成假人」，普通玩家只看到
  「这个名字已经被占用了」，不透露那是 NPC。
- 名字解析仍然是宽松的（裸名 / `Bot_` 前缀 / 存档旧名都认），但宽松只影响「找不找得到」，
  不绕过上面的权限判断。

### 27.4 验证（Folia 1.21.11，ArcBot 创建 NPC 后 `/deop` 成普通玩家）

| 项目 | 结果 |
|---|---|
| `/sunplayer ` tab | `list`（NPC `TabNpc` 不出现） |
| `/sunplayer TabNpc ` tab | 无建议 |
| `/sunplayer Bot_` tab | `Bot_Zed`（只补假人，不补 NPC） |
| `/sunplayer TabNpc kill` | `删除 NPC 需要管理员权限（sunplayer.npc）。`，NPC 仍在 |
| `/sunplayer TabNpc skin Notch` | `修改 NPC 需要管理员权限（sunplayer.npc）。` |
| `/sunplayer list` | `当前没有在线假人。`（NPC 1 个在线上，但不显示） |
| 普通假人：创建 `Zed` → tab → `kill` | `已生成假人 Zed` / tab 出现 `Zed` / `已删除假人 Zed` |
| 重新 `/op` 后 tab | `list, reload, revive, TabNpc, Zed` |
| 重新 `/op` 后 `/sunplayer TabNpc ` tab | `kill, remove, skin` |
| 重新 `/op` 后 `/sunplayer TabNpc kill` | `已删除 NPC TabNpc` |

Paper 1.21.11：同一套 Bukkit API（`Player#hasPermission` + TabCompleter），行为一致。

## 28. 挖掘（`mine`）与攻击（`attack`）分离（0.8.0）

### 28.1 现象

用户实测：生存模式假人执行 `attack` 时，面前没有实体就变成挖方块，而且**所有方块秒挖，连基岩都能挖**。

### 28.2 根因

`ActionPack#doAttack` 在没有实体目标时直接调用 `ServerPlayerGameMode#destroyBlock(pos)`。
`destroyBlock` 是「方块已经被打碎」之后才该执行的步骤：它不计算硬度、不看工具速度、不检查
`getDestroySpeed == -1` 的不可破坏方块，也不走 START/STOP 的进度累积。所以：

- 硬度不参与 → 一秒一块
- 没有「不可破坏」判断 → 基岩照挖（`destroyBlock` 只拦 GameMasterBlock 和 blockActionRestricted）
- 语义上 `attack` 也不该挖方块

### 28.3 修改

- 新增 `mine` 动作（`once` / `continuous` / `interval <n>`，默认 continuous），是一个逐 tick 的状态机：
  1. `player.pick(4.5, 1.0, false)` 取面前方块，并检查 `mayTouch`（Folia 必须是自己区域的方块）
  2. 计算 `state.getDestroyProgress(player, level, pos)`；`<= 0` 且非创造 → 提示「挖不动」并停止
  3. 需要的 tick 数 = `ceil(1 / 每 tick 进度)`
  4. 发 `handleBlockBreakAction(START_DESTROY_BLOCK)`（客户端按下左键时发的就是它）
  5. 之后每 tick 由 `ServerPlayerGameMode#tick()` 累积进度（它本来就在 `ServerPlayer#tick()` 里运行）
  6. 累计到 1.0 时发 `STOP_DESTROY_BLOCK`，由 `destroyBlock` 正常掉落、扣工具耐久、发 `BlockBreakEvent`
  7. 任何异常路径（方块被保护、事件被取消、超出交互距离、换区域）都会发 `ABORT_DESTROY_BLOCK` 并停止或换目标，
     不会卡在死循环里
- `attack` 不再调用 `destroyBlock`：有实体才 `attack`，否则只挥手并写一条 debug
- 命令层：`mine [once|continuous|interval <n>]`，加入动作表、tab 补全和 usage 说明

### 28.4 验证（Folia 1.21.11，假人 SURVIVAL，`/setblock` + `/execute if block` 判定）

| 条件 | 结果 |
|---|---|
| 钻石镐 + 石头 | 2 秒内变空气 |
| 钻石镐 + 黑曜石 | 3 秒时仍是黑曜石，约 10 秒后变空气（理论 9.4 秒） |
| 空手 + 石头 | 3 秒时仍是石头，约 8 秒后变空气（理论 7.5 秒） |
| 空手 + 基岩 | 方块不变；日志 `挖不动 Bedrock (Block{minecraft:bedrock})：这个方块不可破坏（或当前工具做不出任何进度）` |
| `mine once` + 石头 | 9 秒后变空气；再放一块石头，3 秒后仍是石头（确认已停止） |
| `attack continuous` + 石头 4 秒 | 石头仍在 |
| 服务器日志 | 无 `RunningOnDifferentThreadException` / 实体异常 |

Paper 1.21.11：3 秒时石头仍在，9 秒后变空气；`attack continuous` 4 秒后石头仍在。

## 29. PVP 模式（`pvpmode`，0.9.0）

### 29.1 需求

`/sunplayer <name> pvpmode [1-4]`，**非 OP 可用**：假人进入 PVP 状态后

1. 按手里武器的攻击冷却自动决定出手间隔（不写死 tick）
2. 像真人一样移动、躲闪
3. 有盾牌就举盾格挡
4. 只追**生成它的玩家**（主人），别人一概不打
5. `1 2 3 4` 分别对应 `简单 普通 中等 困难`

### 29.2 结构

新增 `PvpMode`：一个纯粹的每 tick 状态机，由 `ActionPack` 持有，`FakePlayer#tick` 驱动。

- `ActionPack#applyInput()`（`super.tick()` 之前）→ `PvpMode#beforeTick()`：感知主人、转视角、决定举盾、写移动输入
- `ActionPack#tickActions()`（实体 tick 之后）→ `PvpMode#afterTick()`：够得着且蓄满力就砍
- `ActionPack#pvpInput(forward, strafe, jump, sprint)`：把连续值写进 `zza/xxa/jumping` 并同步
  `lastClientInput`。原来的 `move` 动作只有布尔开关，走位/躲闪需要 -1..1 的连续值
- `ActionPack#idle()` 把 `pvp != null` 算作"不空闲"，否则假人会在 PVP 中途被当成待机
- `ActionPack#stop()` 先 `stopPvp()`（放盾、清输入），所以 `/sunplayer <name> stop` 也能关掉 PVP

### 29.3 关键实现

| 需求 | 做法 |
|---|---|
| 武器冷却 | 不自己算间隔：每次检查 `player.getAttackStrengthScale(0.5F) >= 难度阈值`，蓄满才 `player.attack(owner)`，然后 `player.swing(MAIN_HAND)`。`ServerPlayer#swing` 会 `resetAttackStrengthTicker()`，正好等价于客户端每次左键后重新蓄力 |
| 只追主人 | 目标固定为 `ownerId` 对应的在线玩家；不同世界、死亡、旁观、超过 64 格都视为"不在追猎范围"，假人原地等，不会转头打别人 |
| 移动 | 距离 > 3 格时 `zza=1`（远距离且难度 >=2 疾跑），进 3 格内停步；`horizontalCollision` 时跳；前方两格高的墙或 3 格以上落差时不再前进（`canWalkAhead`），避免一直撞墙或掉下去 |
| 走位 | 每 8-18 tick 换一次方向，横移 0.45；躲闪时横移 ±1.0 并稍微后撤 |
| 躲闪 | 挨打（`hurtTime >= 9`）必反应；难度 2 起会看主人抬手（`getAttackStrengthScale >= 0.9` 且视线夹角 <= 25°）按概率提前闪；难度 4 额外跳跃躲 |
| 盾牌 | 副手优先、主手其次，用 `BLOCKS_ATTACKS` 组件判断（自定义盾牌也算）；`startUsingItem` / `stopUsingItem` 举放，砍之前先放盾。1.21.11 的盾有 blockDelay，所以是"主人抬手就提前举"，而不是挨打才举 |
| 难度 | 反应 tick 10/7/5/3，瞄准误差 10°/6°/3°/1.2°，躲闪概率 0.12/0.30/0.50/0.70，格挡概率 0.25/0.50/0.70/0.90，出手蓄力阈值 1.00/0.99/0.97/0.94 |

Folia 相关：

- 读主人位置/蓄力/视线都在假人自己的区域线程里做，包在 try/catch 里；跨区域读失败只是这一次感知跳过
- 攻击前检查 `Bukkit.isOwnedByCurrentRegion(owner)`，主人在别的区域就不打——否则就是跨区域写实体，
  Folia 会抛 `RunningOnDifferentThreadException`
- 视野检查复用 `ActionPack#mayTouch(BlockPos)`（改成包内可见）

命令层：`pvpmode [1-4|off]` / 别名 `pvp`；难度也接受 `easy/normal/medium/hard`、`简单/普通/中等/困难`。
主人校验与 `renew`/`invsee` 一致（主人或 `sunplayer.admin`），但**不要求 OP**。
无主人的假人（控制台 `kicktest spawn` 之类）直接拒绝："不知道追谁"。
tab 补全：`/sunplayer <名字> pvpmode ` 给 `1 2 3 4 off`。

### 29.4 验证

Folia 1.21.11（ArcBot 已被 `deop`，即非 OP）：

| 场景 | 结果 |
|---|---|
| 非 OP 给自己生成的假人开 `pvpmode 4` | 成功，回复"难度 4 困难 / 只追主人 ArcBot / 出手间隔约 12.5 tick（钻石剑）/ 有盾牌" |
| 假人打主人 | ArcBot 20 → 0 血，死亡消息 `ArcBot was slain by Bot_P1`（钻石剑 4 下） |
| 木剑打坏后 | 回复变成"空手 / 约 5.0 tick"，间隔随武器自动变（同时验证工具耐久照常消耗） |
| 主人被传送到 26 格外 | 假人从 z=-4.5 一路追到 z=-29.5，停在离主人约 2 格处横移 |
| 盾牌格挡 | 假人 5 格外举盾：`/damage Bot_P1 5 minecraft:player_attack by ArcBot` 后仍 20.0 血；同样距离关掉 PVP 再来一次 → 20.0 → 15.0 |
| `pvpmode off` / `stop` | 立即停手、放盾，不再追人 |
| 别人的假人 | ArcGuest 执行 → `只有假人的主人（ArcBot）或管理员可以开 PVP 模式。` |
| 控制台生成的假人 | `P2 没有主人记录（可能是控制台生成的），PVP 模式只会追生成它的玩家，所以用不了。` |
| 非法难度 `9` | `难度只能是 1-4（1=简单 2=普通 3=中等 4=困难），不填默认 2 普通` |
| 服务器日志 | 无 PVP 相关异常；旧 NPC 的 orphan 扫描告警与本次改动无关 |

Paper 1.21.11：同一套流程——假人从 (2.5,-60,-5.5) 追到 (9.5,-60,5.3)，ArcBot 20 → 10 血；
主人被传到 21 格外后假人 4 秒内追到 (28.2,-60,7.6)，无异常。

## 30. 难度整体加强：1 = 原 3 档，4 = 内部 8 档（0.9.1）

### 30.1 需求

用户：**"把 1 难度改成类似 3 的难度，4 难度类似 8 的难度"** —— 也就是整体拔高，
1 简单要有原来 3 中等那么强，4 困难要比原来的 4 再高出一大截（相当于原来没有的第 8 档）。

### 30.2 实现

`PvpMode` 里新增一张 8 档的强度表 `GRADES`（`record Grade`，每档 10 个参数），
对外 4 个难度只是取其中一档：

| 对外 | 简单 1 | 普通 2 | 中等 3 | 困难 4 |
|---|---|---|---|---|
| 内部档位 | 3 | 5 | 6 | 8 |

| 档位 | 反应 tick | 瞄准误差 | 躲闪 | 格挡 | 出手蓄力 | 疾跑 | 横移 | 跳跃躲闪 | 拉扯 | 跳劈 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 10 | 10° | 0.12 | 0.25 | 1.00 | 否 | 否 | 否 | 否 | 否 |
| 2 | 8 | 8° | 0.20 | 0.35 | 1.00 | 是 | 否 | 否 | 否 | 否 |
| **3** | **5** | **3°** | **0.50** | **0.70** | **0.97** | 是 | 是 | 否 | 否 | 否 |
| 4 | 4 | 2° | 0.58 | 0.80 | 0.96 | 是 | 是 | 否 | 否 | 否 |
| **5** | **3** | **1.2°** | **0.68** | **0.88** | **0.95** | 是 | 是 | 是 | 是 | 否 |
| **6** | **2** | **0.8°** | **0.78** | **0.94** | **0.95** | 是 | 是 | 是 | 是 | 否 |
| 7 | 1 | 0.6° | 0.86 | 0.98 | 0.95 | 是 | 是 | 是 | 是 | 是 |
| **8** | **1** | **0.4°** | **0.92** | **1.00** | **0.95** | 是 | 是 | 是 | 是 | 是 |

新增两个行为（原来的 4 档都没有）：

1. **拉扯（kite，档位 5+）**：每次砍完把 `kiteTicks` 置 9，这 9 tick 里边横移边后退
   （退到 3.6 格为止），等武器冷却快好再贴回攻击距离。后退同样走 `canWalk(-1)` 检查，
   不会自己退下悬崖。
2. **跳劈暴击（critJump，档位 7+）**：蓄力满、在攻击距离内、站在地面时，先放下盾、
   松开疾跑（原版暴击要求 `!isSprinting`），跳起来，等 `deltaY < 0` 的下落阶段再出手，
   打出 1.5 倍暴击；`critWait` 14 tick 兜底，跳进天花板或提前落地就改成普通攻击。

### 30.3 调试中发现的一个坑：假人的 `fallDistance` 永远是 0

`Player#canCriticalAttack` 要求 `fallDistance > 0`，但 `fallDistance` 对真人是由
`ServerGamePacketListenerImpl#handleMovePlayer` 用客户端上报的移动包喂给
`Entity#doCheckFallDamage` 的；假人没有这些包，`fallDistance` 一直是 0，所以怎么跳都打不出暴击
（实测日志：`dy=-0.445 空中=true 下落=0.000`）。

处理：在 `afterTick` 里确认「空中 + 正在下落」后，如果 `fallDistance <= 0` 就补成 0.3
（相当于这次跳跃客户端本该上报的值），落地时清零。只影响这一处判定，假人依旧不吃摔落伤害
（`checkFallDamage` 本来就不会对假人触发）。

### 30.4 验证

Paper 1.21.11（无 AuthMe，伤害可观测）：

| 场景 | 结果 |
|---|---|
| 难度 1（内部 3 档） | 6 秒内把主人从 20 打到 9；日志 `难度=1 简单（内部 3 档）`，无拉扯/暴击 |
| 难度 4（内部 8 档） | 日志 `难度=4 困难（内部 8 档）`，`拉扯=true`、`暴击=X/3`（计数器）持续增长 |
| 跳劈 | 出手行 `预备=true 空中=true 下落=0.30 疾跑=false 暴击=true`，主人 3~4 下被击杀 |
| 服务器日志 | 无异常 |

Folia 1.21.11：同一套流程，`难度=4 困难（内部 8 档）`、`暴击=…/4` 正常增长，无
`RunningOnDifferentThreadException` / 实体异常；假人追人、走位、举盾与 0.9.0 一致。
（Folia 测试服的 ArcBot 带 AuthMe 的保护效果，血量读数看不出伤害，用暴击计数器与攻击次数验证 AI 逻辑。）

## 31. 全武器、低血量进食、继承主人装备、死亡原地重生（0.10.0）

### 31.1 需求

用户：**"顺便让他低血量会自动吃东西 自动在食物选择 会自动逃跑吃东西啥的；以及会使用弓箭、长矛之类所有武器；
同时 pvp机器人死亡后不会删除 会在1秒后重生在死亡点；让pvp机器人死亡清空自己物品栏 不掉落；
让pvp机器人自动集成主人的所有物品 包括盔甲啥的"**

拆成 5 件事：低血量自动进食（含边吃边逃与食物挑选）、所有武器（近战 / 长矛 / 弓 / 弩 / 三叉戟）、
死亡 1 秒后原地重生、死亡清空不掉落、开 PVP 时继承主人整个物品栏（含盔甲、副手）。

### 31.2 武器识别

`PvpMode` 新增 `enum Weapon { MELEE, SPEAR, BOW, CROSSBOW, TRIDENT }`，每个 tick 按主手物品重算：

| 主手 | 判定 | 行为 |
|---|---|---|
| 有 `PIERCING_WEAPON` 组件 | `SPEAR`（1.21.11 的 7 种矛都是） | 用 `stabAttack` 按长矛自己的攻击距离突刺；只戳主人，不扫旁边的人 |
| `BOW` | `BOW`（背包有箭才用） | 保持 5-14 格，按住 20 tick 蓄满，`releaseUsingItem()` 松手 |
| `CROSSBOW` | `CROSSBOW`（有箭或已上弦） | 按 `CrossbowItem#getChargeDuration` 蓄力，松手时若已上弦就发射 |
| `TRIDENT` | `TRIDENT` | 按住 10 tick 后松手投掷 |
| 其他（剑/斧/重锤/空手） | `MELEE` | 原来的 `Player#attack` 路径，含跳劈暴击 |

箭支检查用 `Player#getProjectile(stack)`：弓弩没箭时自动退回近战，不会举着空弓发呆。
长矛的距离用 `LivingEntity#entityAttackRange().maxRange()`（比默认 3 格远），所以拿长矛会提前出手。

### 31.3 低血量进食

- 触发：血量 < `maxHealth * 0.6`，且不在进食/冷却中。
- 选食物：普通食物优先（面包/熟肉等，按 `nutrition * 2 + saturation * 2` 打分）；
  金苹果只在血量 < 35%（急救）或没有普通食物时才吃；腐肉/蜘蛛眼/毒土豆/河豚最后考虑。
  饥饿值满时只考虑 `canAlwaysEat` 的食物（金苹果）。
- 换手：食物在快捷栏就直接 `setSelectedSlot`；在主背包/副手就把食物和当前选中槽交换，
  并记下原槽位与原来的选中槽，吃完后**换回原武器**（否则会举着苹果打人）。
- 吃：`startUsingItem(MAIN_HAND)`，让原版 `updateUsingItem` 递减并调用 `completeUsingItem`
  （饥饿值、药水效果、金苹果回血都由原版处理）；`isUsingItem()` 结束即视为吃完，
  40 tick 冷却；200 tick 超时兜底。
- 边吃边逃：进食期间主人进 9 格就朝反方向跑（`canWalk(-1)` 防悬崖），带一点横移，
  吃完恢复追击；这期间不举盾、不攻击。

### 31.4 继承主人装备

`PvpMode` 第一个 tick（必须在假人自己的区域线程上写背包）把主人 `PlayerInventory` 的 0-40 槽
复制过来：快捷栏、主背包、盔甲（36-39）、副手（40）和主人当前选中的快捷栏。走 Bukkit 背包
API 而不是 `CraftPlayer#getHandle()`，原因见 §32.5（Folia 的实体线程检查会静默中断复制）。
复制后的背包会存成 `loadout` 快照。

重生时**不再**从主人复制，而是把 `loadout` 快照写回新假人：这样主人刚死、背包被清空时，
重生的假人不会跟着变成空手。想重新同步主人当前装备，再执行一次 `pvpmode` 即可。

### 31.5 死亡与重生

- `CompatListener#onDeath` 先看 `fake.actions().pvpActive()`：
  - 清空 `event.getDrops()`、经验设为 0，并 `inventory.clearContent()` 兜底；
  - 调 `FakePlayerManager#schedulePvpRespawn`，**不**走 `compat.remove-on-death` 的删除逻辑。
- 重生流程：死亡 1 tick 后 `remove(name, false)`（保留存档、释放玩家列表），
  再过 19 tick 在死亡点 `createHere` 一个新假人（同名字、皮肤、主人、游戏模式、到期时间），
  写回 loadout，`actions().pvp(difficulty, false)` 恢复 PVP；总计约 1 秒。
- 为什么不是原版 `respawn`：`PlayerList#respawn` 会 new 一个普通 `ServerPlayer`，
  `FakePlayer` 子类、动作包和 PVP 状态都会丢，只能重新创建实体。
- `remove` 里原本无条件 `findOrphanNpc` 的实体扫描会在全局线程读 Mannequin 而报
  `Thread failed main thread check`；现在只有名字不在在线表时才扫描（顺带去掉每次 kill 的全区实体遍历）。

### 31.6 验证

Folia 1.21.11（ArcBot 非 OP，PVP 假人 Bot_F1，难度 4）：

| 场景 | 结果 |
|---|---|
| 继承装备 | 生成后立刻 `gear`：主手 NETHERITE_SWORD、副手 SHIELD、四件 NETHERITE 盔甲，全部来自 ArcBot |
| 食物挑选 | 血量 8、饥饿 6 → 日志 `低血量进食 食物=Golden Apple 槽位=0 血量=8.0 距离=3.70` |
| 边吃边逃 | 进食期间与主人距离 3.70 → 6.43；日志 `吃完了 Golden Apple，血量=9.0`，随后血量回到 20 |
| 吃完换回武器 | 吃完后 `getinv`：0=NETHERITE_SWORD、4=7xGOLDEN_APPLE，选中槽恢复，继续用剑攻击 |
| 弓 | `setitem 0 BOW` → `武器=BOW`，日志 `远程 武器=BOW 距离=4.40 蓄力=20 已射=1..5`，距离保持约 5 格 |
| 长矛 | `setitem 0 IRON_SPEAR` → `武器=SPEAR`，`已攻击` 从 22 涨到 27，无异常 |
| 死亡清空 | `/kicktest die Bot_F1` 后 `execute if entity @e[type=item]` = `Test failed`（没有掉落物） |
| 1 秒重生 | 死亡后 3 秒内 `list` 仍有 Bot_F1，`pos` 在倒地点附近、血量 20，PVP 自动继续（已攻击计数从 1 重新开始） |
| 重生装备 | 第一次重生时主人恰好刚死（背包空），假人因当时逻辑继承了空背包；改为「重生用 loadout 快照」后重测：剑/盾/盔甲全部回来 |
| 服务器日志 | 无 PVP 异常；`findOrphanNpc` 的 Folia 线程告警已随扫描条件修复消失 |

Paper 1.21.11：同一套武器与进食逻辑（未接 AuthMe，血量可直接观测），无异常。

## 32. 智能换武器、斧头破盾、疾跑击退修复（0.10.0 增补）

### 32.1 需求

用户：**"要让ai会自动切换槽位选择武器；比如远程弓箭近处剑那种，会聪明的使用斧头破盾，反正就是聪明点"**，
另外报告 **"正常打假人有击退，但疾跑时的大击退假人没有击退"**，以及 **"无 OP 似乎假人不会继承宿主装备"**。

### 32.2 自动换武器

`PvpMode` 新增 `enum Choice { FIST, SWORD, AXE, SPEAR, MACE, BOW, CROSSBOW, TRIDENT }`
与 `considerSwitch()`：

| 条件 | 选择 |
|---|---|
| 主人举盾 | **斧头**优先（斧头命中会让盾牌 5 秒内无法再举）；没斧头才用长矛 |
| 主人不举盾、距离 ≥ 6 且有视线 | 弓 → 弩 → 三叉戟（按可用性） |
| 主人不举盾、距离在长矛范围 | 长矛（比剑远，3.6 格开始用，退到 2.8 格才换回剑） |
| 近身 | 剑 → 斧 → 重锤 → 长矛 |
| 弓弩没箭 / 目标在墙后 | 不选远程，自动换近战 |

- **只切快捷栏**：目标武器在 0-8 就直接 `setSelectedSlot`；在 9-35 就与当前选中格**交换**，
  只动这两个槽位，副手盾牌不碰。换下来的旧武器进背包，不会丢。
- **防抖**：换完有 4 tick 冷却；弓在 6 格掏出、4.5 格才收起；长矛 3.6 格进、2.8 格退，
  不会站在阈值上每 tick 来回切。
- **有视线才射箭**：`player.hasLineOfSight(owner)`，主人躲到墙后时不会对着墙放箭。
- 换武器时先 `cancelRanged()`（松弓/取消蓄力），再换主手，避免手里换着东西还在拉弓。

### 32.3 斧头破盾

`sense()` 每个感知周期读 `owner.isBlocking()`（1.21.11 里等价于「正在使用带 `BLOCKS_ATTACKS` 的物品且过了格挡延迟」）。
为 true 时 `chooseWeapon()` 返回 `AXE`，假人举着斧头贴上去；`player.attack(owner)` 走原版
`AxeItem#postHurtEnemy`，命中后调用 `disableBlockingForSeconds(5)`。盾被禁用后 `owner.isBlocking()`
变 false，下一轮就换回剑。弓和长矛都会被盾挡，所以主人举盾时不会浪费箭。

### 32.4 疾跑击退

`Player#attack` 对玩家目标会调用 `causeExtraKnockback(target, 击退强度 + 疾跑 0.5, 受击前速度)`：
里面先 `knockback(...)` 再发 `ClientboundSetEntityMotionPacket` 并把速度**改回受击前**。
一次疾跑命中因此会进入 `FakePlayer#knockback` **两次**：

1. `hurtServer` 的普通击退：`before = v0`、`after = v1`；
2. `causeExtraKnockback` 的额外 0.5：`before = v1`、`after = v2`。

旧代码第二次直接把 pending 覆盖成 `(v2, v1)`，服务端随后恢复的是 `v0`，比对失败 → 不补回。
现在第二次调用发现 `pending.wanted() == before`（即 v1）时，**保留第一次的 v0 作为 before**，
pending 变为 `(v2, v0)`，服务端恢复 v0 后正好匹配，补回 v2。

### 32.5 无 OP 不继承装备

`inheritOwnerLoadout` 原先用 `((CraftPlayer) owner).getHandle()` 取 NMS 背包。Folia 的
`CraftEntity#getHandle()` 会对**主人**做 tick-thread 检查；主人和假人不在同一区域（或读取时线程不对）
就抛异常，被 `catch` 静默吞掉，假人看起来"没继承"。非 OP/OP 本身不参与判断，但区域/线程时机很容易
被误认为权限问题。

改成走 Bukkit 的 `PlayerInventory#getItem/setItem` 0-40 槽（`CraftInventoryPlayer` 直接映射 NMS
背包容器，不做实体线程检查），并 `setHeldItemSlot(owner.getHeldItemSlot())`。读不到时不再静默：
debug 模式下会打 `继承了主人 X 的物品 N 件` 或 `继承...失败: ...`。

### 32.6 验证（Paper 1.21.11，非 OP 的 PaperBot）

| 场景 | 结果 |
|---|---|
| 非 OP 继承 | 主人 0=剑、1=弓、2=64 箭、3=斧、4=矛、39=头盔、40=盾；假人 `getinv` 与 `gear` 完全一致 |
| 远程换弓 | 距离 9.10 → 日志 `换武器 SWORD -> BOW 距离=9.10 主人举盾=false`，随后 `pvp 远程 武器=BOW ... 已射=1..N` |
| 近战换剑/矛 | 距离 3.76 → `SWORD -> SPEAR`；距离 2.65 → `SPEAR -> SWORD`，只在这两个槽位间交换 |
| 主人举盾 | `ShieldBot` 用 `use_item(hand=1)` 举盾 → 日志 `SWORD -> AXE 距离=2.54 主人举盾=true` |
| 普通击退 | 假人 (5.500,-2.500) → (5.209,-4.945)，约 2.46 格；血量 20→13 |
| 疾跑击退 | 假人 (5.500,-2.500) → (4.977,0.974)，约 3.5 格；血量 20→12，明显大于普通击退 |

测试插件新增 `/kicktest sprint <玩家> on|off`、`/kicktest shield <玩家> on|off`、
`/kicktest rot <玩家>`（打印 yaw/pitch/疾跑/举盾），以及 `sethp/food/die/gear/clearinv/arrows`。

## 33. 食物优先级、竹林寻路、重生健壮性（0.10.0 增补）

### 33.1 食物优先级

用户：**"有面包他不吃面包，只是把金苹果啥的吃了"**。原评分给金苹果 +20，导致金苹果永远排第一，
低血量时把急救品当面包吃。

现在 `chooseFoodSlot()` 分三组：

1. **普通食物**（面包/熟肉/胡萝卜…）：`nutrition * 2 + saturation * 2`，最高分优先；
2. **金苹果/附魔金苹果**：只在血量 ≤ 35%（急救）或没有普通食物可吃时才选；
3. **有害食物**（腐肉/蜘蛛眼/毒土豆/河豚）：前面都没有才选。

饥饿值满时普通食物不可吃（`!FoodData#needsFood()`），此时仍会选金苹果（`canAlwaysEat`）。
验证：血量 10、饥饿 6 时吃的是面包（16→15），金苹果数量不变；血量 ≤ 7 才吃金苹果。

### 33.2 竹林/薄方块寻路

用户：**"寻路不聪明，有时候卡在竹林里"**。`canWalk` 原来把「脚和头两格都非空气」一律当墙，
而竹子、草、花、雪层这些方块两格高但碰撞箱很薄，假人因此拒绝前进，站在竹林里不动。

改成 `blocksMovement(level, pos, state)`：

- 空 → 可走；
- 栅栏 / 栅栏门 / 墙 → 仍然是墙（跳不过去，必须绕）；
- 取 `getCollisionShape(...).bounds()`，只有**两个水平方向都 ≥ 0.6 格**才算墙；
  竹子/草/花/雪层/地毯的碰撞箱很窄，视为可穿过；
- 落差 3 格以上、岩浆、火、仙人掌、细雪仍然拒绝。

另外 `move()` 增加「卡住」检测：想靠近却连续 16 tick 水平位移 < 0.05 格时，进入 12 tick 的
`unstuck` 状态（斜着走 + 每 4 tick 跳一次 + 疾跑）；平时撞到两格墙也会改成 0.25 前进 + 侧移 + 跳，
不再把 `forward` 直接清零。远程假人在 5-14 格站定射击不算卡住（只在 `distance > 14` 时检测）。

### 33.3 死亡重生的健壮性

- **区块加载**：死亡点可能在很远、没有玩家加载的区块。`beginPvpRespawn` 先
  `world.getChunkAtAsync(cx, cz).thenRun(...)` 再 `ServerCompat.runAt` 创建实体；失败会每 20-40 tick
  重试最多 3 次，最终失败才移除 `pvpRespawning` 标记并打 `severe` 日志。
- **防重复调度**：`pvpRespawning.add` 用返回值判断，同一假人的第二个死亡事件直接忽略，避免两次
  remove/create 互相踩。
- **不安全死亡点**：`FakePlayer` 每秒记录一次「站在实心地面且不在水/岩浆/细雪」的 `lastSafeSpot`。
  死亡点在世界最低点以下、岩浆或火里时，重生改用最后安全点，避免在虚空里反复刷死。
- 之前测试重现过：把假人 `/tp` 到 3000,-60 的未加载区块外，它掉进虚空死亡；旧逻辑在
  `placeNewPlayer` 之后才 `pvp()`，新实体在那一瞬间死亡会走「按 kill 删除」并把 AuthMe 账号注销。
  现在先保证安全点/区块加载，再创建并立即 `pvp()`，该场景已能正常重生。

### 33.4 验证（Folia 1.21.11）

| 场景 | 结果 |
|---|---|
| 血量 10、饥饿 6、背包 16 面包 + 8 金苹果 | 只吃面包（16→15），金苹果仍 8 |
| 普通死亡 | 3 秒内 `list` 仍有 Bot_F1，`pos` 在倒地点附近、血量 20，装备保留，PVP 继续 |
| 远距离死亡（3000,-60,3000） | 正常重生在 3000 附近，装备保留 |
| 持续靠近的主人 | 不再因竹子/两格墙停住；卡住时会侧移跳跃挣脱 |
| 服务器日志 | 无 `RunningOnDifferentThreadException` / 实体异常 |


## 34. 换武器不再送死：换武器的时间成本（0.10.1）

### 34.1 问题

0.10.0 的换武器只看距离，看起来很近：12 格掏弓、3.6 格换长矛、贴脸换剑。实战里这套会让假人
**在换武器的过程里被打死**，原因是每次换武器都有成本，而旧逻辑完全不算这笔账：

- 拉弓要 20 tick。主人在 6 格开始冲刺，假人还在拉弓，箭没出去人已经被贴脸砍了两刀。
- 换武器那一 tick 到下一 tick 之间没有攻击，本来蓄满的一刀被快捷栏切换吃掉。
- 弓拉到一半距离掉到 4.5 以下，`cancelRanged()` 把 19 tick 的蓄力清零；距离又回到 4.6 时再重新拉，
  在两个阈值之间来回空拉，整场没射出几箭。
- 三叉戟的 4.5 格近战兜底完全绕过了距离门限，实测会出现「贴脸挨打时切三叉戟 → 把三叉戟扔出去 →
  空手继续打」的送死流程。

### 34.2 换武器前先算账

`considerSwitch()` 现在的顺序是「先判断这次换值不值，再付出成本」：

- **脱战优先**：从远程换回近战是紧急动作，无视 `switchCooldown` 立刻执行；其他换法都要等冷却。
- **不丢已经到手的伤害**：两把近战之间换武器要满足 `meleeSwitchWindow()`——要么目标在攻击距离外
  （本来就打不到，换了不亏），要么当前攻击蓄力 < 0.85（这一刀还没好，正好用来换）。蓄满且够得着时
  **先砍这一刀**，下一 tick 蓄力清零了再换。
- **先确认拿得到**：`hasUsable(want)` 在 `abandonRanged()` 之前检查，找不到目标武器就直接放弃这次换，
  不会先把弓取消掉才发现没剑。
- **收弓也要收得值**：`abandonRanged()` 在取消拉弓前先看蓄力，弓 ≥ 60%（12/20）、三叉戟 ≥ 6 tick
  就先 `releaseShot()` 把这一箭射出去再换手；弩必须拉满才能射，拉不满才真的取消。
- **冷却**：普通换 6 tick，从远程脱战 10 tick；不管是哪种，远程都额外进 40 tick 的 `rangedLockout`。

### 34.3 什么时候允许拿弓

`chooseWeapon()` 里远程有两个独立条件：

- `keepRanged`（弓已经在手上）：距离 ≥ `keepRangedDistance()`。这个值随主人接近速度变化：
  `min(9, 4 + 接近速度 * 24)`。主人静止时可以贴到 4 格再收；主人以 0.28 格/tick 冲刺时 9 格就收。
- `takeRanged`（现在要掏弓）：额外要求 `hurtRecently == 0`（最近 40 tick 没挨打）、
  距离 ≥ `safeDrawDistance()`、以及身后有路可退（`canWalk(-1)`）。
  `safeDrawDistance = 攻击距离 + 1.5 + 接近速度 * 拉弓 tick`，夹在 6.5-14 之间——主人冲得越猛，
  要求越远。

`sense()` 每次感知用两次距离差算出 `closingSpeed`（0.35/0.65 平滑），这就是上面两处公式的输入。
主人离开追猎范围时清零，避免旧的「他正在冲我」一直生效。

### 34.4 拉弓途中被贴脸

`tickRanged()` 每 tick 检查 `rushed`（距离 < `keepRangedDistance()`，或 5.5 格内刚挨打）：

- 蓄力已经够 `releaseCharge()` → 直接松手，用半蓄力的箭换一次伤害；
- 不够 → 取消拉弓并进 40 tick `rangedLockout`，下次不再立刻重新拉。

拉弓时的走位也提前了：`move()` 在 `isUsingItem()` 时把保持距离抬高到
`max(6, keepRangedDistance() + 1.5)`，边拉边退，而不是站着拉满。

### 34.5 三叉戟只当近战

三叉戟被移出远程武器集合（`Choice.isRanged()` 不再包含 TRIDENT，`refreshWeapon()` 也不再把三叉戟
认成 `Weapon.TRIDENT`）：它只在近战兜底链里排在剑、斧之后。原因见 34.1 那条实测——扔出去之后假人
直接空手，比不换武器更糟。弓/弩的三叉戟投掷代码保留，但不会被选中。

### 34.6 验证（Paper 1.21.11，`switchtest` / RCON 驱动）

| 场景 | 结果 |
|---|---|
| 主人站在 12.5 格，假人有剑/弓/三叉戟/64 箭 | `SWORD -> BOW` 一次，连续射出 6 箭，期间没有换武器 |
| 主人瞬间 `/tp` 到 3.7 格 | 日志 `BOW -> SWORD 距离=3.72 接近=1.93`，同一 tick 收弓换剑 |
| 贴脸连续被攻击 12 次 | 全程 `武器=MELEE`，没有一次远程切换（`受击` 一直 > 0） |
| 停止挨打、退到 14 格 | `SWORD -> BOW 距离=14.32 接近=0.18 受击=0`，重新掏弓 |
| 三叉戟在快捷栏 | 从未被选中、从未被扔出 |
| 空手假人从 12 格开始追击 | 4.3 格处开始出手，把主人打死（追击/寻路未被本次改动影响） |

## 35. `/pvpbot`：PVP 机器人独立成命令，死亡不再重生（0.11.0）

### 35.1 为什么拆成独立命令

`/sunplayer <name> pvpmode [1-4]` 要求玩家先想一个名字、先生成假人、再开模式，三个动作对「打一场」这件事
全是多余步骤。0.11.0 把它换成一个命令：

```
/pvpbot            难度 1（简单）
/pvpbot <1-4>      指定难度
/pvpbot kill       删除自己创建的全部机器人
```

`pvpmode` / `pvp` 子命令从 `/sunplayer` 移除（tab 补全、`usage`、`DIFFICULTIES` 常量一起删掉）。为了不让老
玩家以为功能丢了，输入旧写法时会回一句「PVP 机器人改用 /pvpbot [1-4] 直接生成」。

### 35.2 名字与编号

- 裸名 `pvpbot_1`、`pvpbot_2`…，套用 `naming.fake-prefix` 后服务端名是 `Bot_pvpbot_1`，和假人完全一致。
- 编号取**现有最大值 +1**：`FakePlayerManager#nextPvpBotIndex()` 扫 `tabNames()`（在线假人 + NPC + 存档）加
  正在创建 / 正在退场的名字，用正则 `(?i)pvpbot_(\d{1,5})` 取最大编号。全部机器人删光后再生成会从 1 重新开始
  （用户要的就是「按已有 pvpbot 往后排」）；只有**刚被删掉**的号会跳过约 5 秒，等实体真正退场。
- 并发生成不会撞号：`reservePvpBotName()` 在异步创建之前先把名字占住（`reservedBotNames`），
  `remove()` 又把名字放进 `retiringNames` 5 秒。没有这一步时同一 tick 连发两次 `/pvpbot` 会各自选中
  `pvpbot_1`，第二个实体用着已经被占用的 profile 登录。
- 生成前再确认一次名字空着（`isOnline` / `isPersisted` / 保留 / 退场中），手改过的存档或残留实体不会撞名。
- 名字总长受 16 字符限制：`Bot_` + `pvpbot_12345` 刚好 12 个裸字符；编号排到放不下时直接提示而不是生成失败。

### 35.3 生命周期：出生、死亡、重启

| 阶段 | 行为 |
|---|---|
| 生成 | `createPvpBotAsync`：普通假人创建流程 → **在机器人自己的区域线程上** `fake.pvpBot(true)` + `ActionPack#pvp(difficulty)` → 用 `snapshot()` 重写存档条目并保存。生成位置、游戏模式、上限检查、玩家位提醒都和 `/sunplayer` 一样 |
| 战斗 | 就是原来的 `PvpMode`（§29-§36 全部保留）：只追主人、多武器、举盾、进食、走位 |
| 死亡 | `PlayerDeathEvent` 里清空掉落、经验、背包，1 tick 后 `remove(name, true)`：实体、`list`、`fake-players.yml` 一起清掉，**不再重生**。判断依据是 `FakePlayer#pvpBot()` 标记，不是「此刻是否在 PVP」（`stop` 之后身上仍有副本，同样不能掉） |
| 重启 | 难度写进存档（`pvp: 1-4`），`spawnRevived` 恢复实体后立即可 `fake.pvpBot(true)` + `pvp(difficulty)`，机器人回来就继续打 |
| 删除 | `/pvpbot kill` 按 owner（先 UUID、后名字）列出该玩家的全部 `pvpbot_N`（在线 + 仅存档），逐个 `remove(..., true)`；没有实体时退回 `forgetPersisted` |
| 隔离 | `/sunplayer` 对机器人直接拒绝（详见 35.4）；`list` 里标成红色 `[机器人]`，tab 补全不出现 |

`/pvpbot` **只接受玩家**（含 `/execute as <玩家> run /pvpbot`）：机器人必须有主人，控制台没有。这条和
`PvpMode` 的「只追主人」是同一个约束。

### 35.4 物品复制与命令隔离

机器人身上的每件东西都是**主人背包的副本**（`PvpMode#inheritOwnerLoadout`）。任何能把副本变回真物品的出口都
是刷物品，所以 0.11.0 把出入口全部封死：

- **`invsee` 拒绝**：`SunPlayerCommand#invsee` 与 `InvseeGui#open` 双层判断 `FakePlayer#pvpBot()`，命令层先给
  「PVP 机器人身上的物品是从你背包复制出来的副本，不能打开物品栏」，GUI 层再兜一次（防止别的插件直接调 `open`）。
- **`drop` / `dropstack` 拒绝**：`action()` 里按同一个标记拦截，否则「丢出去再捡起来」就是无限复制。
- **死亡不掉落**：`CompatListener#onDeath` 用标记清空 `getDrops()`、经验与背包，再删实体。
- **`/sunplayer` 不互通**：机器人只由 `/pvpbot` 创建和 `kill`。`/sunplayer <机器人> <子命令>` 一律回
  「只能用 /pvpbot 管理」，tab 补全不列机器人，`list` 里用 `[机器人]` 区分。唯一例外是
  `sunplayer.admin` 的 `kill` / `remove`——别人留下的机器人总得有人能清掉。

### 35.5 存档格式

`FakePlayerStore.Entry` 增加一个 `int pvp` 字段（0 = 普通假人，1-4 = 机器人难度），写成 `pvp: N`。
`load` 用 `getInt("pvp", 0)`，旧存档没有这个键就是 0，不需要迁移。`withDeadline` / `withSkin` / `migrate`
三处拷贝构造都带上原值，改时限、换皮肤不会把机器人降级成普通假人。

### 35.6 被删掉的旧机制

- `FakePlayerManager#schedulePvpRespawn` 及 `beginPvpRespawn` / `tryPvpRespawn` / `failPvpRespawn` /
  `applyLoadout` / `countItems` / `skinOf` / `pvpRespawning` 整套重生流程删除（§31.4、§31.5、§33.3 因此失效，
  以本节为准）。
- `PvpMode#loadoutCopy()` 删除：没有重生就不需要把 loadout 交出去。
- `CompatListener` 的 `onJoin`（压制重生时的「joined the game」）和 `onQuit` 里的 `isPvpRespawning` 分支删除。
- 普通假人死亡仍走 `compat.remove-on-death`（默认开），行为和 0.10.x 一样。

### 35.7 验证

| 场景 | 预期 |
|---|---|
| `/pvpbot` | 生成 `pvpbot_1`，`list` 显示红色 `[机器人] pvpbot_1` |
| 再执行一次 `/pvpbot 3` | 生成 `pvpbot_2`，难度 3 中等（编号接在最大值后面） |
| `/pvpbot` 时假人已达上限 | 提示 `假人数量已达上限（…）`，不生成 |
| 控制台执行 `/pvpbot` | 提示只能由玩家执行 |
| 同一 tick 连发两次 `/pvpbot` | 两个不同的号（`pvpbot_2`、`pvpbot_3`），不会出现两个 `pvpbot_1` |
| 打死机器人 | 掉落物、经验为 0；1 tick 后 `list` 里消失；`fake-players.yml` 里条目消失 |
| 全部删光后再 `/pvpbot` | 从 `pvpbot_1` 重新开始（刚退场的号会跳过几秒） |
| `/pvpbot kill` | 只删自己的机器人，别人的不动；在线 + 仅存档的都删 |
| 有机器人时重启服务器 | `已恢复 PVP 机器人 … （难度 N …）`，实体回来且继续追主人 |
| `/sunplayer <机器人> invsee / drop / move / stop` | 全部拒绝：「只能用 /pvpbot 管理」 |
| 管理员 `/sunplayer <机器人> kill` | 允许，用于清理别人留下的机器人 |
| `/sunplayer list` / tab 补全 | 列表标 `[机器人]`；tab 里不出现机器人名字 |
| `/sunplayer <名字> pvpmode` | 提示改用 `/pvpbot`，不再有该子命令 |

## 36. 盾牌被斧头破掉后仍然举盾（0.11.0）

### 36.1 问题

主人拿斧头砍中举盾的机器人，机器人只掉了 0 点伤害（盾挡住了），但下一 tick **继续举着盾**：之后每一刀都
被挡，直到主人放弃。日志里 `格挡=true`、`盾破=false` 一直不变。

### 36.2 原因

1.21.11 的破盾已经不是 1.8 那套 `disableShield`，而是数据驱动的：`BlocksAttacks#disable(level, entity,
seconds, stack)` 会给**受害者**的盾牌加一个物品冷却（`Player#getCooldowns().addCooldown(shield, ticks)`，
斧头 = 5 秒 = 100 tick），然后 `stopUsingItem()`。真人客户端看到冷却就不会再发 `use_item`，所以举不起来。

假人没有客户端，`PvpMode#setBlocking` 直接调用 `startUsingItem(OFF_HAND)`，绕过了客户端的冷却检查——于是
服务端刚"破"掉的盾被它自己一遍遍重新举起来。

### 36.3 修复

- `PvpMode#shieldBroken()`：盾牌在手且 `player.getCooldowns().isOnCooldown(盾牌)` 为真 → 视为破盾。
  `shouldBlock()` 直接返回 false，`setBlocking(true)` 再兜一次（破盾可能落在两次调用之间）。
- `FakePlayer#blockUsingItem`（原版「这次伤害被盾挡下」的回调）里读
  `attacker.getSecondsToDisableBlocking()`，有值就调用 `PvpMode#disableShield(seconds)`，把
  `shieldDisabledTicks` 设成 100 并立刻 `setBlocking(false)`。即使某个 fork 不 tick 物品冷却，机器人也照样
  5 秒不举盾。
- 调试行新增 `盾破=`，破盾瞬间另有一条 `pvp 盾牌被破：100 tick 内不能再举盾`。

### 36.4 验证（Paper + Folia 1.21.11）

| 场景 | 结果 |
|---|---|
| 难度 4（格挡率 100%）的主人与机器人贴脸，主人拿铁斧 | 机器人 `格挡=true`，`盾破=false` |
| `kicktest attack` 砍它一刀 | 伤害 0（被挡），随后日志 `盾牌被破：100 tick 内不能再举盾` |
| 破盾后的调试行 | `格挡=false 盾破=true`，持续约 5 秒；之后才恢复举盾 |
| Folia 同样操作 | 同样在 1 tick 内进入 `盾破=true`，无区域线程异常 |


## 37. 物品冷却、举矛、空战与 0.12.0

### 37.1 问题

1. 服务器给 PVP 场地的面包设了 15 秒使用冷却，机器人照吃不误。
2. 机器人拿长矛时只会"左键戳刺"：站着不动、一下一下地戳，从不会举矛。
3. 长矛可以隔墙戳到人：墙后面、石头盒子里照样掉血。
4. 主人用鞘翅飞走后机器人只能在地上抬头看。
5. 难度 2 以上还是太软。

### 37.2 原因

- 所有物品使用都走 `startUsingItem`，绕过了原版 `ServerPlayerGameMode#useItem` 里的
  `player.getCooldowns().isOnCooldown(stack)` 检查。服务端设的冷却（插件 `setCooldown`、数据包的
  `use_cooldown` 组件、破盾冷却）对假人完全无效。
- 1.21.11 的长矛是**动能武器**（`KINETIC_WEAPON` 组件）：真人右键举矛、跑起来，每 tick 沿视线打一条
  2.0-4.5 格（加移动速度延长）的射线，条件是相对速度 ≥ 4.6 格/秒；左键才是 `PIERCING_WEAPON` 的刺击。
  假人只调用了 `stabAttack`，所以永远只有左键那一下。
- `Player#stabAttack` 本身**不做距离/遮挡判定**，调用方负责。原来的 `stabOwner()` 直接对着主人调用，
  于是隔墙也能戳。
- 假人的 `fallDistance` 不会自己积累（服务端只在 `isLocalInstanceAuthoritative()` 时才算摔落），
  所以重锤的 smash 条件也永远不满足；鞘翅的滑翔物理在服务端是完整的（`travelFallFlying`），但假人从
  没被设为 fall-flying，也就没起飞。
- 动能伤害读的是客户端上报的 `knownMovement`（`ServerPlayer#getKnownMovement()`），假人没有移动包，
  永远是 0，即使举起矛也打不出伤害。

### 37.3 修复

**物品冷却**

- `PvpMode#itemOnCooldown(stack)`：`player.getCooldowns().isOnCooldown(stack)`，异常时按"没有冷却"处理。
- 所有使用点都先过这一关：进食（`chooseFoodSlot` 跳过冷却中的食物、`startEating` 再查一次）、
  弓/弩/三叉戟拉弓、长矛举起。盾牌本来就有 `shieldBroken()`。
- `gameMode.useItem`（烟花、风弹）本身带冷却检查，保持原样。

**举矛冲锋**

- `PvpMode#tickSpear()`：拿长矛时，距离 4.6-5.5 格且视线通畅就 `startUsingItem(MAIN_HAND)` 举矛，
  `spearCharging` 期间强制疾跑并前冲；30 tick 或贴身/失去视线/切换武器就放下，6 tick 内不再举。
- 原版 `KineticWeapon#damageEntities` 会自动在 `onUseTick` 里跑，伤害/击退/拖拽全部走原版条件。
- 贴到 2.4 格以内会主动后撤（长矛最小攻击距离 2.0 格，贴脸反而戳不到），保持 2.4-3.6 格的矛距。
- 空战滑翔时举矛窗口放宽到 2-12 格。

**不再穿墙**

- `PvpMode#spearCanReach()`：用 `ProjectileUtil.getHitEntitiesAlong(player, entityAttackRange(),
  e -> e == owner, ClipContext.Block.COLLIDER)` 重放原版射线，方块挡住就不出手。
- `stabOwner()` 先过这道检查再 `stabAttack`。
- `afterTick()` 对所有近战都加了 `hasSight()`：真人客户端只在准星射线命中时才发攻击包，假人没有客户端，
  于是之前会隔着一层石头挥拳。

**空战（0.12.0 新增）**

- `PvpMode#ownerAirborne()`：主人在滑翔、创造飞行、或明显悬空（高 8 格以上且不在地面）。
- `tickAerial()`：
  - 有鞘翅（`LivingEntity.canGlideUsing` 检查胸甲）：地面先给 0.85 的向上冲量起跳，空中
    `tryToStartFallFlying()`，之后每 tick 用 `steerGlide()` 调朝向——低头俯冲、抬头爬升——飞向主人；
    速度低于 0.32 格/tick、距离超过 14 格或撞墙时右键烟花火箭（`gameMode.useItem`，原版会生成挂载火箭）。
  - 离地 3.5 格内强制拉平，滑翔落地不积累摔落伤害。
  - 有长矛且距离 2-12 格、视线通畅：空中架矛（原版动能射线照样生效）。
  - 有风弹、距离 3-22 格、视线通畅：朝主人丢风弹（滑翔中和地面防空都会用）。
- 没有鞘翅时 `dodgeAerial()`：**不起飞**，只在地面左右躲闪、主人俯冲贴近就举盾/侧跳，有风弹就反打。
- 主人落地后如果机器人还在滑翔，会继续平飞滑降，不会直接掉下来。
- `FakePlayer#tick` 里每 tick 把 `setKnownMovement(getDeltaMovement())` 补上，动能武器/弹射物才拿得到
  真实速度（这也是空中架矛能造成伤害的前提）。

**重锤 / 落地**

- 拿重锤且在空中下落时，把 `fallDistance` 至少设成 1.6（原版 smash 要求 > 1.5），落地按原版结算。
- `ensureSafeLanding()` 作为保险：没有滑翔、`fallDistance > 3` 时，脚下 6 格内有地面就垫水桶
  （`useItemOn`），有缓降效果就不管；假人本身很少吃到摔落伤害（没有移动包），这层是给会算摔落的 fork 的。

**难度 2 以上加强**

内部档位：普通 5→6、中等 6→7、困难仍是 8，并整体拔高 6-8 档：

| 档位 | 反应 | 瞄准误差 | 躲闪 | 格挡 | 出手蓄力 |
|---|---|---|---|---|---|
| 6（普通） | 2 tick | 0.45° | 84% | 97% | 0.93 |
| 7（中等） | 1 tick | 0.28° | 92% | 100% | 0.92 |
| 8（困难） | 1 tick | 0.15° | 97% | 100% | 0.90 |

**Tab 补全不再报错（0.11.1）**

- `/sunplayer` 与 `/pvpbot` 的补全器整体包了 `try/catch (Throwable)`：任何异常只记一条带参数的 warning
  并返回空列表，不会再让客户端看到 "An internal error occurred while attempting to tab-complete"。
- 补全前把 `args` 里的 `null` 空洞统一成 `""`，`filter()` 和 `visibleNames()` 也跳过 null 名字。

### 37.4 验证（Paper 1.21.11）

| 场景 | 结果 |
|---|---|
| 面包/金苹果带 `use_cooldown` | 冷却期间完全不进食；换成没有冷却的另一种食物，15 秒内吃了 4 个 |
| 铁矛 + 满背包的机器人 | 日志 `pvp 举矛冲锋 距离=…`，`举矛=` 计数升到 30 再重来，空中也会举矛 |
| 主人关在 3×3×3 石头盒子里（脚下留空气柱） | 6 秒内主人血量不降反升（自然回血）；拆掉盒子后 6 秒掉 6.9 血 |
| 主人用鞘翅升到 12 格高 | 机器人起跳→滑翔→烟花加速（`pvp 空中加速`），追到主人身边；`空战=滑翔` |
| 机器人没有鞘翅、主人在天上 | 机器人留在地面，`空战=防空`，不跟着起飞 |
| 从 20 格高丢下机器人 | 不摔死（服务端本来就不给假人算摔落伤害） |

### 37.5 已知取舍

- 举矛冲锋时，原版动能射线会命中挡在路线上的**其他**实体；左键刺击和普通近战仍只打主人。
- 空战需要机器人自己有鞘翅（通常是生成时从主人身上继承的）和烟花火箭；没有就只做地面防空。


## 38. 鞘翅不穿、风弹重锤、换剑卡住与难度重排（0.12.1-0.12.3）

### 38.1 问题

1. 主人的鞘翅放在背包里（不是胸甲槽）时，机器人不会飞起来，只在地上防空。
2. 机器人有风弹、重锤也不会主动用：重锤只在"剑/斧/三叉戟/长矛都没有"时才被选中。
3. 有时候机器人拿着长矛贴脸却**不会自动换剑**，站着一下都不打。
4. 难度 1、2 太强，3、4 又不够强。
5. 测试中发现：给机器人一把重锤，几秒后重锤会**凭空消失**。

### 38.2 原因

- 原版滑翔判定 `LivingEntity.canGlideUsing(stack, slot)` 只看**装备槽**，背包里的鞘翅永远不生效。
- 重锤在 `chooseWeapon()` 里排在剑/斧/三叉戟/长矛之后；风弹只在空战分支里丢，地面战斗完全不用。
- `meleeSwitchWindow()` 的规则是"武器蓄力 ≥0.85 时不换武器，别把一次必中的攻击换成换物品"。但 1.21.11 的长矛最小
  攻击距离是 2 格，贴脸时 `spearCanReach()` 为 false、`afterTick` 永远不挥矛 → 蓄力一直停在 1.0 → 换武器窗口
  永远打不开，机器人就拿着一把够不着的矛站着。
- `WindChargeItem#use` 消耗的是**主手物品**（`player.getItemInHand(hand)`），不是传进 `gameMode.useItem` 的那个
  stack。之前为了"把风弹丢出去"却拿着重锤，结果每丢一次就消耗一把重锤 —— 这就是重锤消失的原因。

### 38.3 修复

- `PvpMode#equipGlider()`：主人上天而机器人没穿鞘翅时，扫描背包 0-35，把鞘翅换到胸甲槽（原来的胸甲放回原格），
  然后再 `tryToStartFallFlying()`。日志 `pvp equip-elytra slot=N`。
- 重锤 + 风弹连招 `tickMaceCombo()`：
  - 距离 2-7 格、手里有重锤、背包有风弹、冷却结束（难度 3/4 为 45 tick，1/2 为 90 tick）时，
    **先选中风弹**、视角压到 90° 朝脚下丢，爆炸把自己炸上天（`maceJumpTicks = 30`），再把重锤选回来；
  - 空中每 tick 把准星锁在主人身上，下落时 `fallDistance` 至少补到 1.6，走原版重锤 smash；
  - `maceOpportunity()` 让 `chooseWeapon()` 优先选重锤：正在连招、或者已经在主人上方下落、或者风弹可用且在近身窗口。
- `throwWindCharge()` 同样先选中风弹再 `useItem`，不再消耗手里的武器。
- 换武器死锁：`meleeSwitchWindow()` 增加两条"现在这把武器打不到人"的例外——没有视线，或长矛够不着——
  满足任一条就允许换武器，不再等蓄力。
- 难度表扩到 1-10，重排为 **1=2 档、2=4 档、3=9 档、4=10 档**，并新增 9/10 档（反应 1 tick、瞄准 0.08°/0.03°、
  躲闪与格挡 100%、出手蓄力 0.86/0.82、重锤连招冷却 45 tick）。

### 38.4 验证（Paper 1.21.11）

| 场景 | 结果 |
|---|---|
| 主人把鞘翅放在快捷栏（不是胸甲），飞到 13 格高 | 日志 `pvp equip-elytra slot=0`，随后起跳滑翔、`pvp air-boost` 追人 |
| 主人 500 血、机器人只有重锤 + 风弹 | 每约 2 秒一条 `pvp mace-launch dist=…`，主人血量 500 → 245；重锤仍在手上（damage 11），风弹 16 → 9 |
| 主人贴身、机器人拿长矛 + 钻石剑 | 日志 `换武器 SPEAR -> SWORD`，随后正常用剑攻击，不再卡住 |
| 难度 4 的调试行 | `内部 10 档` |
| 难度 1/2 的调试行 | `内部 2 档` / `内部 4 档` |

## 39. 落地后空手、滑翔追过头与 1/2 级再降档（0.12.4）

### 39.1 需求

用户：**"假人似乎在降落以后 只会赤手空拳，不自动切换到剑啥的"**，随后
**"再降低前两极的难度 恢复到再简单一些 差不多类似最开始3级难度"**，补充
**"更像笨机器人 但是会用鞘翅啥的"**。

### 39.2 原因

- **落地后空手**：空战逻辑会临时把风弹选进主手（`selectSlot`）再丢；丢完不还手，
  风弹用完那一刻主手就是**空栈**。空手时 `afterTick` 照常挥拳，而 `considerSwitch()`
  还要求"等这次的挥击冷却窗口"（`meleeSwitchWindow()`：蓄力 < 0.85 才许换），
  空手又打不到东西时这个窗口迟迟不开，于是机器人一直赤手空拳。
- **顺带发现的真 bug**：`FireworkRocketItem#use` / `BucketItem#useOn` 读的是**主手里的物品**，
  不是传进 `gameMode.useItem` 的那个 stack。烟花放在背包里时 `boostGlide()` 其实什么都没放，
  日志 `pvp air-boost` 是假的；水桶同理，还会把主手的武器换成空桶。
- **落地后飞走**：主人落地后 `steerGlide(dive=true)` 用固定 25° 俯冲，把高度全换成速度，
  几十格高时直接冲过主人；冲出 64 格后 `sense()` 判定"不在追猎范围"，`engaged=false`，
  假人保持滑翔姿态一路飞走（实测落到 500 格外），再也不会回来。
- **难度**：0.12.3 的 1/2 级（内部 2/4 档）对用户来说还是太强。

### 39.3 实现

- `restoreSelected(usedSlot, previousSelected)`：烟花、水桶、风弹这类"临时选中再使用"的物品，
  用完立刻把原来的快捷栏槽位（背包槽则把交换换回去）还给主手。
  - `boostGlide()` / `throwWindCharge()` / `ensureSafeLanding()` 全部改成"先选中 → 用主手 → 还原"。
  - 烟花的 `useItem` 传 `getMainHandItem()`，不再骗自己。
- `considerSwitch()`：主手是空手（`Choice.FIST`）时不再受 `switchCooldown` 和
  `meleeSwitchWindow()` 限制——空手没有任何"挥击收益"可等，直接换武器。
- 滑翔落地：主人不再空中时 `steerGlide(false)`（把机头对准主人，而不是固定 25° 俯冲），
  并且不再点烟花；冲出追猎范围但主人仍在线时，`lookupOwner()` 取到主人继续朝人飞，
  主人不在了才压低机头落地。
- 难度表：**1 简单 = 内部 1 档（反应 14 tick、瞄准 16°、躲闪 3%、格挡 6%）、
  2 普通 = 内部 2 档（反应 12 tick、瞄准 12°、躲闪 10%、格挡 20%）**；
  3 中等 = 9 档、4 困难 = 10 档不变。招式（走位、举盾、鞘翅追击、风弹重锤）都保留，
  只是反应和准头更笨。

### 39.4 验证（Paper 1.21.11 + Folia 1.21.11）

| 场景 | 结果 |
|---|---|
| 难度 1 / 2 的调试行 | `内部 1 档` / `内部 2 档` |
| 主人 40 格高滑翔，机器人滑翔追击时的 `SelectedItem` | `minecraft:netherite_sword`（不再是风弹） |
| 主人落地（`kicktest glide off` + `tp` 到地面） | 机器人落在主人旁边 `(7.07, -60, 4.32)`，手里剑，`SelectedItem.damage` 持续增长，主人掉血 |
| 修复前的同一场景 | 机器人滑翔到 512、323，`FallFlying=1b` 一路飞走 |
## 40. 创建假人卡顿、整服变卡与同步 IO（0.12.5）

### 40.1 需求

用户：**"你的插件似乎导致我的服务器很卡 而且我局域网链接的也导致延迟变高
尤其出现在创建机器人啥的 延迟会瞬间飙到600多ms 正常才2ms"**。

### 40.2 定位

在测试服（Paper 1.21.11）上按"冷位置生成"复现并测量：

- 假人一上线，`PlayerList#placeNewPlayer` 立刻给它周围上 PLAYER 凭证。
  落点区块还没生成时，这一批区块**必须在 tick 里现生成**：实测一次 147 ms
  （同一位置第二次就只要 9 ms），机器慢/地形复杂时到几百毫秒完全说得通。
  这就是"创建机器人时延迟瞬间飙高"的来源。
- 每一次创建 / 删除 / 续时 / 换皮肤都在**拥有实体的线程上重写整份
  `fake-players.yml`**（序列化 + 写临时文件 + 原子改名）。`/pvpbot 4` 一次四条，
  慢盘上就是一次几百毫秒的同步 IO。
- `isPvpBot(name)` 对不存在的名字直接 `ConcurrentHashMap.get(null)` → NPE，
  `/sunplayer create Steve` 这种写法会抛异常（用户顺手会这么打）。

### 40.3 实现

- `preloadSpawnArea()`：先在后台用 `World#getChunkAtAsync(..., true)` 把
  `view-distance` 那么大的方块（默认 5x5）加载完，再调度真正的生成。
  失败/超时（10 s）仍然照常生成，不会把假人卡死；`spawn-preload-radius`
  可调（-1 = 跟 `view-distance`，0 = 关闭）。复活（`reviveOne`）走同一条路。
- `requestSave()`：把 14 处 `saveNow()` 改成合并写——最多每秒一次，
  在异步线程上落盘。停机时 `onDisable` 仍然同步写，数据不会丢。
- `createHere()` 记录 `placeNewPlayer` 的耗时，超过 100 ms 打一行
  `假人生成较慢：上线（PlayerJoinEvent / 区块票据）耗时 N ms`，
  把"是我的插件还是别的插件/区块生成"变成日志里能直接读到的数字。
- `view-distance` 允许 1（3x3），并同步进 `config.yml` 注释与 README。
- 修 `isPvpBot` 的 NPE，并接受 `/sunplayer create <名字>` 写法。

### 40.4 验证

| 场景 | 结果 |
|---|---|
| 冷坐标 `/kicktest spawn` 连续 4 个（预加载开） | tick 最大间隔 80 ms（正常 50 ms） |
| 同上，预加载关 | 64 ms，说明本机 join 本身不慢，瓶颈是区块生成 |
| 生成后 `fake-players.yml` | 约 1 秒后由异步线程写出，内容正确 |
| `sunplayer s1 kill` 后再看存档 | 条目同步消失，没有留下半条记录 |
| Folia：`sunplayer f1/f2`（挂 AuthMe/LuckPerms/PacketEvents） | 两个假人稳定存活、位置正常，日志无异常 |
| 旧写法 `/sunplayer create a1` | 不再 NPE，按创建处理 |

### 40.5 已知边界

假人是真的 `ServerPlayer`，因此**它周围的区块会保持加载并参与刷怪**，这部分是原版行为，
插件无法关掉。假人越多、`view-distance` 越大，服务器要 tick 的区块越多；20 个假人散在
20 个地方就是几百个区块的额外负担。`view-distance: 1` 是性价比最高的缓解手段。

## 41. PVP 假人隔空打人、出手节奏忽快忽慢（0.12.6）

### 41.1 需求

用户：**"他的攻击似乎很奇怪 有时候是搁着5格都能打到你 应该是正常的攻击才对 而不是很远能打到
他似乎是攻击两次 然后停1秒 每次攻击的第2次很远都能打到你"**。

### 41.2 定位

两个问题其实是同一个原因：**判断攻击距离用的是缓存值**。

- `PvpMode#sense()` 每 `reactionTicks` tick 才跑一次（1 级 14 tick = 0.7 秒，2 级 12 tick），
  中间所有判断都沿用上次的距离。原版服务端**不校验**近战距离（那是客户端的射线检测，
  服务端信任客户端），所以插件自己的这个门就是唯一的距离限制。
- 于是"命中一次 → 击退把主人推出 1-2 格 → 主人在 0.7 秒里再退"之后，假人手里的距离
  还停在旧值上，第二刀照打：实测日志里缓存 2.31 / 实际 4.5 甚至 5 格的情况就是这样来的。
  "打两下、停 1 秒"也是同一件事：两刀之间不用重新感知，等下一次 `sense()` 发现人已经
  出了范围，再走过去，节奏就变成了两快一慢。
- 另外假人的走位也用这份旧距离，所以它经常"贴上去才发现自己其实在 4 格外"。

### 41.3 实现

- 新增 `inMeleeReach()`：从**眼睛到主人碰撞箱**现算（`AABB#distanceToSqr`，即客户端射线的
  测法），上限 `entityInteractionRange()`（3.0，长矛取其 `AttackRange` 4.5）。
  近战出手、起跳暴击两个门都换成它。
- 新增 `trackPosition()`：两次 `sense()` 之间每 tick 只刷新"主人距离"这一项。位置是物理，
  不是判断；瞄准、举盾、躲闪、换武器仍然受反应时间限制。这样收到击退后它会立刻知道
  人已经远了，不会隔空补刀，也不会站着发呆一秒。
- `Player#attack()` 之后补一次 `resetAttackStrengthTicker()`（`ServerPlayer#swing` 本来也会
  重置，这里显式写一遍，保证换武器/长矛路径也遵守蓄力）。`/sunplayer <名字> attack` 同样补上。
- 调试图加"实测"距离、蓄力/攻速间隔、伤害和 tick 计数，方便以后凭日志定位这类问题。

### 41.4 验证

Paper 1.21.11，客户端假人当靶子，`debug: true` 读日志：

| 场景 | 结果 |
|---|---|
| 2 级、贴身对打 20 秒 | 出手间隔稳定 13 tick（0.65 秒，剑满蓄力），不再忽快忽慢 |
| 同一局的距离 | 实测 1.20-3.35，没有一次超过 3.4（即碰撞箱 3.0 以内） |
| 4 级（起跳暴击） | 间隔同为 13 tick，距离 3.35 以内 |
| 把靶子一直固定在 5 格外（`tp ~5 ~ ~`，每 50 ms 一次） | 12 秒内血量不掉，一下都没打到 |
| 4 级贴身 | 血量 20 → 14 → 9 → 3，四刀一命，节奏正常 |
| Folia 1.21.11（AuthMe 等） | 同样 13 tick 一刀、距离 ≤3.3，日志无异常 |

### 41.5 已知边界

- 3.0 是原版玩家的实体交互距离（眼睛到碰撞箱），所以"最远能打到"仍然是 3 格多一点，
  和真人一致；长矛按原版 4.5 格算。
- 反应时间只影响判断（瞄准/举盾/躲闪/换武器），位置现在每 tick 刷新——这是刻意改的：
  真人永远知道对手站在哪。

## 42. PVP 难度按真人重新标定、机器人跟着主人退出（0.12.7）

### 42.1 需求

用户：**"机器人的攻击距离似乎很远/或者是说他总是会在玩家进入攻击范围内以后瞬间攻击，应该给整体降级
一下反应速度啥的 更像真人
1级机器人 普通玩家 刚练习一个月pvp
2级 入门玩家，练习5个月左右
3级 资深玩家 练习几年
4级 基本上类似外挂但是还有人的反应力
我现在练习了4个月pvp 结果1级瞬间给我秒杀了"**

同一条消息追加：**"顺便加入一个功能，玩家退出，生成的pvp假人也会自动退出"**。

### 42.2 定位

0.12.6 解决了"隔空打人"，但强度表本身离真人还是很远：

- 原来最关键的只有反应时间、瞄准误差、躲闪/格挡概率、蓄力下限。**假人的每一刀都必中**，
  而且按满蓄力的伤害结算。1 级虽然 14 tick 才重新看一次人、误差 16°，可它一旦贴上来，
  一个站着不动的满血目标 4 刀（2 秒左右）就没了——这就是"1 级瞬间秒杀"。
- 缺的三件事恰好是真人最明显的特征：**看到人之后会愣一下才出手**、**会打空**、**经常没蓄满就砍**。
  另外"卡在 3 格边缘就挥刀"（0.12.6 的实时距离只保证了不超过 3.0）也让手感偏"手长"。
- 4 级原本 1 tick 反应、0.03° 误差、100% 举盾躲闪，比外挂还外挂；用户要的是
  "类似外挂但还有人的反应力"。

### 42.3 实现

- **强度表重写成 4 档**（不再用 1-10 内部档映射），每档包含：`reactionTicks`、`attackDelay` +
  `attackDelayJitter`、`aimError`、`missChance`、`minCharge`、`reachMargin`、`dodgeChance`、
  `blockChance`、`swingRecovery`、`sprint/strafe/jumpDodge/kite/critJump`、`maceCooldown`。
- **起手延迟**：新增 `readyTicks`（连续待在攻击范围内的 tick 数）。第一次满足条件时抽
  `windup = attackDelay + rand(jitter)`，`readyTicks <= windup` 期间不出手，但**继续往你身上贴**。
  离开范围超过 `REACQUIRE_GAP_TICKS = 6` 才算重新接近，重新抽一次——所以被击退、绕柱子之后
  它会再"看见"你一次，而不是每一刀都重新发呆。
- **挥空**：每刀按 `missChance` 掷骰（1 级 50%、2 级 35%、3 级 15%、4 级 5%）。挥空照样播挥砍动画、
  照样进蓄力冷却，只是不结算伤害——真人的空刀就是这个观感。
- **不满蓄力**：`swingChargeTarget` 每刀在 `[minCharge, 1.0]` 之间重抽（原来恒为 1.0 或固定值），
  蓄到目标才砍。1 级平均只砍出七成力。
- **砍完发呆**：`attackCooldown = swingRecovery`（1 级 4 tick、2 级 2 tick、3/4 级 1 tick），
  新手砍完会顿一下再补刀。
- **出手距离**：`inMeleeReach()` 改成 `attackReach() - reachMargin`，1 级要贴到 2.5 格内才肯挥，
  走位也用这个更近的距离。视觉上不再"隔着 3 格就抬手"。
- **拉扯不再把自己拉出攻击窗口**：原来砍完退到 3.6 格才停，配上 4 级的 2.97 格出手线，变成
  "退出去 → 走回来 → 再挥"，实测让 4 级比 1 级点得还慢。阈值改成 2.55，退半步就够。
- **难度改名**：1 新手 / 2 入门 / 3 资深 / 4 极限；`parse` 仍兼容旧名（简单/普通/中等/困难、
  easy/normal/medium/hard）。命令帮助与 README 同步。
- **跟着主人退出**（新功能）：`CompatListener#onOwnerQuit` 在 `PlayerQuitEvent` 里取主人名下的
  全部 `/pvpbot`，逐个 `remove(name, permanent = true, null)`——实体、存档、编号一起清，
  不会被 `auto-revive` 拉回来，重启也不会再出现。配置项 `pvp.quit-with-owner: true`，
  关掉恢复旧行为。假人自己下线的事件被跳过，不会反过来"清理"它主人的东西。
- 调试图加 `挥空=`、`起手=`、`进入范围 t=… 起手=… 距离=…`，以后靠日志就能核对反应速度和命中率。

### 42.4 验证

Paper 1.21.11，客户端假人当靶子（站着不动、满血、饱和回血），`debug: true` 读日志：

| 场景 | 结果 |
|---|---|
| 1 级（钻石剑，54 刀样本） | 命中率 54%，平均 12.6 tick 一刀，实测距离 1.21-1.90，起手 9-13 tick |
| 1 级杀一个站着不动的满血靶子 | 约 10 秒（0.12.6 同一场景约 2 秒）；期间靶子还在自动回血 |
| 4 级（钻石剑） | 命中率 94%，12 tick 一刀，每轮第一刀跳劈暴击，实测 2.47-2.82（出手上限 2.97） |
| 4 级杀同一个靶子 | 约 6 秒，5 刀（20→15→10.9→4.9） |
| 进入范围→第一刀 | 每次重新接近都重新抽起手：1 级 9-13 tick，4 级 1-2 tick |
| 挥空 | 日志 `挥空=true` 的刀不结算伤害，但间隔、动作都照常 |
| Folia 1.21.11 | 同样参数生效、距离 ≤2.8、无区域线程异常（只有 AuthMe 的 GeoLite 警告） |
| 主人退出（Paper 与 Folia） | `玩家 ArcBot 退出，已一并移除他的 PVP 机器人: pvpbot_1`，随后 `list`/`sunplayer list` 都为空，重启后也不再出现 |

### 42.5 已知边界

- 挥空是掷骰而不是真的射线检测，所以低级偶尔会出现"看着明明贴脸却空刀"。这是刻意的：
  真人的空刀本来也没有可解释的物理原因。
- 起手延迟只在你**重新进入**攻击范围时抽一次；已经贴脸连续对砍时不再额外加延迟，
  那一段的节奏由蓄力和发呆决定。
- 站着不动的靶子有饱和回血（约 1 点 / 10 tick），所以"击杀时间"只是相对指标，
  真人会走位、会反击，实际体验会比这个数字轻松。
- 难度写进存档，重启会复活——但主人退出会按 `kill` 清掉，两者不冲突（退出优先）。

## 43. NPC 不再占用假人名额（0.12.8）

### 43.1 需求

用户：**"npc 不应该算在假人里，但是现在 npc 算在假人里了"**。追问确认：指的是
SunPlayer 自己 `--npcmode` 生成的 NPC，表现是"上限提示里 NPC 占了假人名额"。

### 43.2 定位

NPC 在 0.3.0 就改成了 `Mannequin` 实体（§18），但**名额计算没有跟着改**：

```java
public int count() { return fakeCount() + npcCount(); }   // ← 旧
```

`count()` 是 `max-fake-players` 的唯一比较对象，于是：

- 3 个装饰性 NPC 就把"假人上限 2"顶满了，玩家看到的却是"假人数量已达上限"，`capacityText()`
  把 NPC 名字也拼进同一行，更加坐实了"NPC 算假人"的印象；
- 反过来，`createNpc()` 也用同一个 `count()` 做检查——**假人满了就连 NPC 都建不了**。
  这条比计数本身更致命：NPC 和假人压根不是同一种东西。

### 43.3 实现

- `FakePlayerManager#count()` 改为 `return fakeCount();`，只数假人。
- `capacityText()` 不再把 NPC 混进名字列表，改成
  `在线假人 2/2，NPC 1 个（不计入上限）：TestB, TestA`。
- `createNpc()` **删掉整段上限检查**：Mannequin 不占玩家位、不会过期，建多少都不影响假人名额。
- `/sunplayer list` 头部改为 `列表 (假人 2/2，NPC 5（不占上限）)`。
- 三处"已达上限"的补充说明（`/sunplayer`、`/pvpbot`、`--npcmode`）统一改成
  "只数假人，不数真人和 NPC"。

### 43.4 验证

Paper 1.21.11，`max-fake-players: 2`，客户端 `/sunplayer` 驱动：

| 场景 | 结果 |
|---|---|
| 先建满 2 个假人 | `列表 (假人 2/2)` |
| 满员后再建 NPC | `已生成 NPC NpcOne …`、`NpcTwo`，**不再被拦** |
| NPC 起来后再建第 3 个假人 | `假人数量已达上限（在线假人 2/2，NPC 1 个（不计入上限）：TestB, TestA）。` |
| 继续加 NPC 到 5 个 | `列表 (假人 2/2，NPC 5（不占上限）)`，假人名额仍是 2/2 |
| 清理 | `sunplayer <名字> kill` 逐个删除，`list` 显示"当前没有在线假人或 NPC" |

### 43.5 已知边界

- **上限仍然可以被瞬间超建**：`createAsync` 是异步的，`fakeCount()` 只统计已经进了 `byName`
  的假人。两条创建命令在同一次异步完成之前发出（实测间隔 1.6 秒还不够宽），两个都会通过检查。
  这不是本次改动引入的，属于独立的并发窗口，0.12.8 没动；要修得在 `createAsync` 前先占位。
- NPC 之间仍然共用名字空间，也不受任何数量限制——建多了是管理员自己的事。

## 44. 假人名字敏感词过滤（0.12.9）

### 44.1 需求

用户：**"总有玩家创建含敏感词的 bot，加上功能 npc 除外。在配置文件有个 .yml 里面存放敏感词，
创建假人如果包含敏感词就会拒绝创建"**。

### 44.2 定位

创建入口只有一个——`SunPlayerCommand#create` 与 `/sunplayer create` 都走它，`checkName`
是可挂载的天然位置。两条容易踩的线：

1. **NPC 走的是另一个方法**（`createNpc`），却调用了同一个 `checkName`。若只在 `checkName` 里
   加过滤，NPC 会一起被拦——和需求正好相反。
2. 直接用 `String.contains` 会被 `F.u.c.k`、`f_u_c_k`、`FUCK` 轻易绕过；而 Minecraft 名字本身
   只允许 `[A-Za-z0-9_]`，所以"去掉所有非字母数字再小写"是既简单又不误伤的归一化。

### 44.3 实现

- 新增 `NameFilter`：读 `plugins/SunPlayer/blocked-names.yml`，字段 `enabled`、`match`、
  `words`（兼容平铺的 `blocked` 列表）。
  - `match: contains`（默认）→ 归一化后"包含"就拒绝；
  - `match: whole` → 归一化后"等于"才拒绝，给词表里有短词（如 `sex` 会误伤 `sussex`）时用。
- 归一化 `normalize()`：只保留字母和数字并转小写，`F.u.c.k` / `f_u_c_k` / `FUCK` → `fuck`；
  另外再拿词对所有 `match` 做一次原始小写比对，支持词表里自带分隔符的写法。
- `checkName` 拆成两层：
  - `checkNameSyntax`（字符 + 长度）——假人和 NPC 共用；
  - `checkName` = 语法 + 敏感词——**只有假人创建走这里**；
  - `createNpc()` 改为只调 `checkNameSyntax`，**NPC 不受词表约束**。
- 拒绝时**不回显命中的词**（玩家就是想在聊天里看到它），改为固定提示
  `这个名字包含敏感词，已拒绝创建，请换一个。`，同时在控制台留一行
  `拒绝创建假人 <名字>（主人 <谁>）：名字命中 blocked-names.yml 的敏感词`，供管理员追溯。
- `blocked-names.yml` 首次启动自动生成（`saveResource`），带中文注释和默认词表（英文脏话 +
  中文辱骂 + 广告类）；`/sunplayer reload` 重新读取，不用重启。
- 文件读坏（YAML 语法错）时**保留上一份词表**并打一行警告，不会因为一个缩进把全服名字放行或全锁死。

### 44.4 验证

Paper 1.21.11，`match: contains`，客户端逐个 `/sunplayer <名字>`：

| 输入 | 结果 |
|---|---|
| `fuck` / `FUCK` | 拒绝（大小写不敏感） |
| `F.u.c.k` / `bot_shit_1` | 拒绝（分隔符绕过失效） |
| `Steve` / `NoRule` | 正常创建 |
| `F.u.c.k`（长度 5 但含点） | 语法层拒绝：`只能用字母、数字、下划线` |
| `/sunplayer fuck --npcmode ...` | **正常创建 NPC**（NPC 排除生效） |
| `/sunplayer shit --npcmode ...` | 正常创建 NPC |
| 改 `match: whole` + `/sunplayer reload` | `fuck` 仍拒绝，`xxfuckxx` 通过 |
| 改回 `contains` + reload | 恢复，6-9 ms 完成 |

### 44.5 已知边界

- 中文词按归一化规则同样处理，但**不做繁简、同音、形近转换**（`傻B`、`傻x` 之类需要自己加词）。
- 只在**创建**时检查，已存在的假人不会因为后来加词而被清掉。
- NPC 完全不检查：需要 `sunplayer.npc`（默认 OP），管理员自己的装饰不该被公共词表挡住；
  如果在意，请自行在命令层面约束。


