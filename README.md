# SunPlayer

这个插件能在服务器里凭空生成一个“玩家”。它会挂机、保持区块加载、会被水冲走、被活塞推动、被打、也会掉东西，行为跟真玩家没什么区别。也可以把它摆成 NPC：玩家点一下，就执行你设置好的命令。

现在还有 pvp 机器人模式。

一个 jar 包，Paper 和 Folia 都能直接跑，没有前置插件。

---

## 特性

- **行为接近真玩家**：会被水冲走、被活塞推、能触发事件、能进计分板、能被 `/ban`
- **挂机不掉线**：假人所在区块一直保持加载，种田、炼药、刷怪机不会停
- **动作齐全**：左键、右键、挥手、丢弃、跳跃、移动、转向、潜行、疾跑、换手、骑乘
- **点击式 NPC**：摆一个假人，玩家点它就触发你指定的命令
- **正版皮肤**：自动使用该名字对应的正版皮肤，取不到就用 Steve
- **不占用同名真人的名额**：假人在服务端使用独立名字，与同名真人完全隔离
- **挂机限时**：可以限制每个假人的最长在线时间，到期自动删除
- **持久化**：重启后自动恢复，不用重新召唤
- **pvp 机器人模式**：命令创建 4 个等级的 pvp 机器人，可以和你对打

---

## 安装

1. 服务器需要 **Paper 或 Folia 1.21.11**，以及 **Java 21**
2. 把 `SunPlayer.jar` 放进 `plugins/` 文件夹
3. 重启服务器

---

## 快速开始

在游戏里执行：

```
/sunplayer Steve
```

一个叫 Steve 的假人就会出现在你脚下，游戏模式跟你当前的一样。

---

## 命令

| 命令 | 说明 |
|---|---|
| `/sunplayer <名字>` | 在你站的位置生成假人 |
| `/sunplayer <名字> <动作>` | 指挥假人做事 |
| `/sunplayer <名字> remove` | 让假人下线（重启后会自动回来） |
| `/sunplayer <名字> kill` | 彻底删除假人（连存档一起） |
| `/sunplayer <名字> renew` | 重置挂机计时 |
| `/sunplayer list` | 查看所有在线假人与 NPC |
| `/sunplayer reload` | 重载配置文件 |
| `/sunplayer revive` | 恢复没有成功上线的假人 |
| `/pvpbot [<等级(1~4)>] [--noely] \| kill` | 创建 pvp 机器人（会继承你的装备）；加 `--noely` 不用鞘翅；`kill` 杀死所有 pvp 机器人 |

命令里只写名字本身就行，不用管前缀。

---

## 动作

| 动作 | 写法 |
|---|---|
| 右键 / 使用 | `use [once\|continuous\|interval <秒>]` |
| 左键 / 攻击 | `attack [once\|continuous\|interval <秒>]` |
| 挥手 | `swing [once\|continuous\|interval <秒>]` |
| 丢弃物品 | `drop [once\|continuous\|interval <秒>] [mainhand\|offhand\|<0-40>]` |
| 丢整组 | `dropstack` |
| 跳跃 | `jump` |
| 移动 | `move <forward\|backward\|left\|right>` |
| 视角 | `look <yaw> <pitch>` |
| 转身 | `turn <left\|right\|back>` |
| 潜行 / 站起 | `sneak` / `unsneak` |
| 疾跑 / 停止疾跑 | `sprint` / `unsprint` |
| 停止所有动作 | `stop` |
| 切换快捷栏 | `hotbar <0-8>` |
| 交换主副手 | `swaphands` |
| 骑乘 / 下坐骑 | `mount` / `dismount` |
| 设置皮肤 | `skin <player_name>` |
示例：

```
/sunplayer Steve
/sunplayer Steve attack continuous
/sunplayer Steve move forward
/sunplayer Steve stop
```

---

## NPC 模式

把假人摆成 NPC，玩家点击时执行命令：

```
/sunplayer <名字> --npcmode --command <命令> [参数...]
```

| 参数 | 说明 | 默认 |
|---|---|---|
| `--npcmode` | 开启 NPC 模式（必需） | — |
| `--command <命令,命令>` | 点击时执行的命令，逗号分隔 | 无 |
| `--left` / `--right` / `--both` | 哪种点击触发 | `--both` |
| `--look` | 始终面向最近的玩家 | 关 |
| `--look-range <格>` | 面向距离 | 8 |
| `--cooldown <tick>` | 同一玩家的点击冷却 | 10 |
| `--as-console` | 用控制台权限执行命令 | 关 |
| `--delay` | 更精确的冷却控制，单位 ms | / |

示例：

```
/sunplayer Merchant --npcmode --command tell @s 欢迎光临,title @s title {"text":"商店"} --look
/sunplayer Guard --npcmode --left --command effect give @s minecraft:slowness 5 1
```

命令里的 `@s` 指向点击者本人，坐标和朝向也是他的。

NPC 不会移动、不会受伤、不占玩家位。参数在创建后无法修改，要改就先 `kill` 再重建。

---

## PvP 机器人

```
/pvpbot             生成 1 级机器人
/pvpbot 3           生成 3 级机器人
/pvpbot 2 --noely   生成 2 级机器人，不用鞘翅
/pvpbot kill        删除你的所有机器人
```

机器人会复制你当前的背包（包括盔甲），只追你一个人，死亡不掉落、不再重生。你退出服务器时它们跟着下线，重新 `/pvpbot` 即可。

### `--noely`：不用鞘翅

默认情况下机器人会继承你背包里的鞘翅，你飞在天上时它也会起飞追你。加上 `--noely` 之后：

- 不继承你背包里任何位置的鞘翅（不只是胸甲槽）
- 不会自己把鞘翅穿到胸甲槽
- 被人手动塞了鞘翅也会立刻脱掉
- 你飞在天上时，它改为在地面横向躲闪，并用风弹把你打下来

`--noely` 可以和等级以任意顺序写，也可以不写等级：`/pvpbot --noely` 就是 1 级不用鞘翅。

这个状态会存进存档，重启后机器人还是不用鞘翅。

---

## 挂机限时

在 `config.yml` 里限制假人的最长挂机时间：

```yaml
auto-remove:
  minutes: 120        # 最多挂 2 小时，0 = 关闭
  warn-minutes: 1     # 删除前 1 分钟提醒主人
```

- 每个假人独立计时，重启后继续计时
- 快到期时会私聊创建者，并附带可点击的 `renew` 续时链接
- 到期后假人被彻底删除，重启也不会复活
- NPC 不受这个限制

---

## 权限

| 权限 | 默认 | 作用 |
|---|---|---|
| `sunplayer.use` | 所有人 | 生成假人、发送动作、`remove`、`kill`、`renew`、`list` |
| `sunplayer.npc` | 仅 OP | 使用 NPC 模式及全部 NPC 参数 |
| `sunplayer.admin` | 仅 OP | 以上全部，并可给别人的假人续时、重载配置 |

---

## 配置

```yaml
max-fake-players: 20   # 全服假人上限
view-distance: 2       # 假人视距（区块）

naming:
  fake-prefix: "Bot_"  # 假人服务端名字前缀

auto-remove:
  minutes: 0           # 假人最多挂几分钟，0 = 关闭
  warn-minutes: 1      # 删除前几分钟提醒主人

compat:
  prevent-plugin-kicks: true   # 防止其他插件误踢假人
  authme-force-login: true     # 安装 AuthMe 时自动免登录
  remove-on-death: true        # 假人被打死后自动删除
```

改完配置执行 `/sunplayer reload` 即可生效，在线假人不受影响。

---

## 实用小贴士

- 假人占玩家位，NPC 不占。`max-fake-players` 建议按「服务器总人数 − 预期真人数」来设
- `view-distance` 建议设为 2，能明显降低内存占用
- 关闭正版验证（`online-mode=false`）的服务器也能正常使用
- 假人不加载玩家背包与成就数据，每次生成都是干净的新玩家

---

## 常见问题

**重启后假人没回来？**
先执行 `/sunplayer list`，底部会列出没有恢复的假人名字，再执行 `/sunplayer revive` 重试。

**假人会跟同名真人冲突吗？**
不会。假人在服务端有独立名字，与同名真人完全隔离。

---

---

## SunPlayer

**Fake player plugin, supports Paper / Folia 1.21.11**

Summons a "player" out of thin air on your server. It will sit AFK, keep chunks loaded, get pushed around, take hits, and drop items, just like a real player. You can also set it up as an NPC: a player clicks it once, and it runs whatever command you configured.

There is also a PvP bot mode now.

One jar, runs directly on both Paper and Folia, no dependencies.

---

## Features

- **Behaves like a real player**: gets swept away by water, pushed by pistons, fires events, appears on scoreboards, and can be `/ban`ned
- **No AFK disconnect**: the chunks the fake player is in stay loaded, so farms, brewing stands, and mob grinders keep running
- **Full set of actions**: left click, right click, swing, drop, jump, move, turn, sneak, sprint, swap hands, mount
- **Clickable NPC**: place a fake player and have it run your command when a player clicks it
- **Official skins**: automatically uses the official skin for that name, falls back to Steve if unavailable
- **No name conflicts**: the fake player uses its own separate server-side name, fully isolated from real players with the same name
- **AFK time limit**: cap how long each fake player can stay online, auto-removed when it expires
- **Persistence**: restores automatically after restart, no need to summon again
- **PvP bot mode**: create one of 4 tiers of PvP bots via command and fight them

---

## Installation

1. Server needs **Paper or Folia 1.21.11** and **Java 21**
2. Drop `SunPlayer.jar` into the `plugins/` folder
3. Restart the server

---

## Quick Start

Run this in game:

```
/sunplayer Steve
```

A fake player named Steve spawns at your feet, using your current game mode.

---

## Commands

| Command | Description |
|---|---|
| `/sunplayer <name>` | Spawn a fake player at your position |
| `/sunplayer <name> <action>` | Command the fake player to do something |
| `/sunplayer <name> remove` | Take the fake player offline (it comes back after restart) |
| `/sunplayer <name> kill` | Permanently delete the fake player (including its save data) |
| `/sunplayer <name> renew` | Reset the AFK timer |
| `/sunplayer list` | List all online fake players and NPCs |
| `/sunplayer reload` | Reload the config file |
| `/sunplayer revive` | Recover fake players that failed to come online |
| `/pvpbot [<tier(1~4)>] [--noely] \| kill` | Create a PvP bot (inherits your gear); `--noely` disables the elytra; `kill` removes all PvP bots |

Just write the plain name in commands, you don't need to worry about prefixes.

---

## Actions

| Action | Syntax |
|---|---|
| Right click / use | `use [once\|continuous\|interval <seconds>]` |
| Left click / attack | `attack [once\|continuous\|interval <seconds>]` |
| Swing arm | `swing [once\|continuous\|interval <seconds>]` |
| Drop item | `drop [once\|continuous\|interval <seconds>] [mainhand\|offhand\|<0-40>]` |
| Drop stack | `dropstack` |
| Jump | `jump` |
| Move | `move <forward\|backward\|left\|right>` |
| Look | `look <yaw> <pitch>` |
| Turn | `turn <left\|right\|back>` |
| Sneak / stand up | `sneak` / `unsneak` |
| Sprint / stop sprinting | `sprint` / `unsprint` |
| Stop all actions | `stop` |
| Switch hotbar slot | `hotbar <0-8>` |
| Swap main/off hand | `swaphands` |
| Mount / dismount | `mount` / `dismount` |
| SetSkin | `skin <player_name>` |

Examples:

```
/sunplayer Steve
/sunplayer Steve attack continuous
/sunplayer Steve move forward
/sunplayer Steve stop
```

---

## NPC Mode

Turn a fake player into an NPC that runs a command when clicked:

```
/sunplayer <name> --npcmode --command <command> [arguments...]
```

| Parameter | Description | Default |
|---|---|---|
| `--npcmode` | Enable NPC mode (required) | — |
| `--command <cmd,cmd>` | Commands to run on click, comma-separated | none |
| `--left` / `--right` / `--both` | Which click triggers it | `--both` |
| `--look` | Always face the nearest player | off |
| `--look-range <blocks>` | Facing distance | 8 |
| `--cooldown <ticks>` | Per-player click cooldown | 10 |
| `--as-console` | Run the command with console permission | off |
| `--delay` | Finer cooldown control, in ms | / |

Examples:

```
/sunplayer Merchant --npcmode --command tell @s Welcome,title @s title {"text":"Shop"} --look
/sunplayer Guard --npcmode --left --command effect give @s minecraft:slowness 5 1
```

`@s` in the command refers to the player who clicked, and coordinates and direction are theirs too.

NPCs don't move, don't take damage, and don't occupy a player slot. Parameters can't be edited after creation; to change them, `kill` and recreate.

---

## PvP Bots

```
/pvpbot             spawn a tier 1 bot
/pvpbot 3           spawn a tier 3 bot
/pvpbot 2 --noely   tier 2, no elytra
/pvpbot kill        remove all of your bots
```

A bot copies your current inventory (armour included), chases only you, drops nothing on death, and never respawns. Bots leave when you disconnect; run `/pvpbot` again to get a new one.

### `--noely`: no elytra

By default a bot inherits whatever elytra you carry and will take off to chase you in the air. With `--noely` it:

- does not inherit an elytra from any inventory slot (not just the chest slot)
- never moves one onto its own chest slot
- drops an elytra immediately if anything puts one on it
- dodges sideways on the ground and knocks you out of the sky with a wind charge instead of chasing

`--noely` works before or after the tier, and with no tier: `/pvpbot --noely` is a tier 1 bot without an elytra.

The flag is saved with the bot, so it still refuses the elytra after a restart.

---

## AFK Time Limit

Cap the maximum AFK time of fake players in `config.yml`:

```yaml
auto-remove:
  minutes: 120        # max 2 hours AFK, 0 = disabled
  warn-minutes: 1     # warn the owner 1 minute before removal
```

- Each fake player is timed separately, and the timer continues after restart
- When close to expiry, the owner gets a private message with a clickable `renew` link
- Once expired, the fake player is permanently deleted and won't come back after restart
- NPCs are not affected by this limit

---

## Permissions

| Permission | Default | Purpose |
|---|---|---|
| `sunplayer.use` | Everyone | Spawn fake players, send actions, `remove`, `kill`, `renew`, `list` |
| `sunplayer.npc` | OP only | Use NPC mode and all NPC parameters |
| `sunplayer.admin` | OP only | Everything above, plus renewing others' fake players and reloading config |

---

## Config

```yaml
max-fake-players: 20   # global fake player limit
view-distance: 2       # fake player view distance (chunks)

naming:
  fake-prefix: "Bot_"  # fake player server-side name prefix

auto-remove:
  minutes: 0           # max AFK minutes per fake player, 0 = disabled
  warn-minutes: 1      # warn the owner this many minutes before removal

compat:
  prevent-plugin-kicks: true   # prevent other plugins from kicking fake players by mistake
  authme-force-login: true     # auto login bypass when AuthMe is installed
  remove-on-death: true        # auto delete fake player when killed
```

Run `/sunplayer reload` after editing the config; online fake players are unaffected.

---

## Tips

- Fake players occupy a player slot, NPCs don't. Set `max-fake-players` to roughly "total server slots − expected real players"
- Setting `view-distance` to 2 noticeably reduces memory usage
- Servers with online mode disabled (`online-mode=false`) work fine too
- Fake players don't load player inventory or advancement data, so each spawn is a clean new player

---

## FAQ

**The fake player didn't come back after restart?**
Run `/sunplayer list`; unrecovered fake player names are listed at the bottom. Then run `/sunplayer revive` to retry.

**Will a fake player conflict with a real player of the same name?**
No. The fake player has its own separate server-side name and is fully isolated from real players with the same name.
