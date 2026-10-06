# TalkTact 项目交接包（0.8.10 第 3 版时点）

> 生成：2026-10-06 ｜ 来源：本 Agent 的四份文档
> 内容：① 项目总览（新会话先读）② 已知问题清单 ③ 0.8.10 补丁存档 ④ 缓做 7 设计稿
> 用途：单文件自足上下文，可直接导入外部 App（另一个 AI 会话 / 笔记应用）接着干活，不必回到原 Agent。
> **不含任何密钥 / 口令 / token**（本仓库是公开的）。密钥备份口令只在私有文档里。
> 未收录：87k 的「TalkTact 交接文档（0.8.x）」深度明细，需要时另取。

---

# 第一部分 · 项目总览（新会话先读这份）

> 用途：**新会话的第一份**。看完就能接着干，不必去翻那份 87k 的明细。
> 深度细节（每件事的来龙去脉、每个坑怎么踩到的）在「TalkTact 交接文档（0.8.x）」里，需要时按章节跳。
> 各版本完整改动看仓库 `CHANGELOG.md`；可行性分析看仓库 `IMPROVEMENTS.md`。
> 最后更新：2026-10-06（**0.8.8 / 0.8.9 已发正式版 · 0.8.10 第 3 版已出包、① 自检误报已验过 · 当前最大阻塞 = P0「微信报 404、试一试却能用」**）

---

## 0. 30 秒现状

`shibry88-netizen/TalkTact`（LSPosed 微信聊天助手，libxposed Modern API 102，命名空间 `dev.goutou.wingman`）

| 项 | 值 |
| :--- | :--- |
| 模块库上的正式版 | **0.8.9**（vc36，tag `v0.8.9`，模块库 release `36-0.8.9` = Latest；2026-10-06 发布） |
| `main` | `af9af42`（= 0.8.9 正式版 + CHANGELOG 按正式版整理） |
| 开发分支 | `tmp/0.8.10`（本地名 `work/0.8.10`）：**0.8.10 开发中** —— 已 4 提交（缓做 1+3+9 → 代理按请求头选接口 → 缓做 7 → Trace 单测隔离），HEAD = e3531d6，相对 main 15 文件 / +1025 −43。自检直连用真 Key 的补丁只进了一半：LlmClient 那半已进第 3 版，Screens.kt:508 的 second = true 与 404 文案未进 |
| 最新测试包 | `test-0.8.10` **第 3 版**（APK 74540006 B，sha256 6fec576b…536a8，只带 arm64-v8a）—— ① 自检误报已修（用户回「正常了」）；② 决策轨迹 / ③ 主链路回归 待用户实测。P0 的 404 未解（等两条证据） |
| 更早的临时分支 | `tmp/0.8.8` / `tmp/0.8.9`（内容都已在 main 上）、`tmp/0.8.5` —— 按老规矩都留着不删 |

**0.8.10 这一轮做了什么**

- **缓做 1**：一键拉模型列表（`llm/ModelList.kt`）—— 第一套、第二套**各有一个入口**、列表各存各的
- **缓做 3**：归属地开关 + 自定义接口地址（高级设置 → 「网络信息（归属地）」）
- **缓做 9**：ABI 收窄到只留 `arm64-v8a` —— APK **81MB → 71MB**（代价：32 位老设备装不上）
- **第 2 版修 bug**：本地代理转发原本**永远用第一套的地址和 Key**，导致分级模式下第二路被按第一套转发（报「Key 不对」且回显的是第一套那把 Key）。现在靠请求头 `x-talktact-endpoint` 区分，认不出一律当第一套
- **自检补丁（已存档、未提交）**：自检（probe）走的是**直连服务商**，但 Authorization 却按「开没开本地代理」选成了 proxyToken → 一开代理必然 401，而且服务商回显的是 32 位代理 token 的尾号（本次现场是 \*\*\*\*5ttm，不是任何一把 Key）。正解是「直连 + 真 Key」。详见「补丁存档」文档，与缓做 7 同批出第 3 版。

**0.8.8 / 0.8.9 内容（都已随正式版发布、在 main 上）**

签名轮换+CI/文档基建 · ①候选「最推荐+理由」②单条改写 ③提示词前缀缓存 ⑤结构化输出/截断兜底/补正重试/长度复核/诊断导出 zip ⑨AI 生成标识 · 文档三件套 · 设备分级 · 截图回归(Robolectric) · **双模式（直通/模型分级）** · **本地代理** · **会话白名单（拦截 + 手动添加 + 自动拉取候选 + 形状闸 + 长按多选/已忽略 + 独立联系人页）** · 设置页「未保存」红字（外观/高级设置/军师/角色详情四处）· **图片文字离线识别（OCR，ML Kit 内置中文模型）** · 0.8.9 的 OCR 取图收紧 + 生成模式 pill 立刻落盘

**真机已验证过的**：双模式、本地代理路线、白名单整体、**OCR 整条链路（用户回「能读了」→ 取图收紧后回「现在好用了」）**、0.8.9 的四项（取图收紧 / 生成模式立刻生效 / 三处红字 / 角色详情保存后列表刷新）、**0.8.10 第 2 版的第二套接口（拉模型列表 ✓、apiKey2 未留空 ✓）、第 3 版的 ① 自检误报修复**。
**还没验证的**：第 3 版的 ② 决策轨迹 / ③ 主链路回归（还没测）、P0 的 404、设备分级、截图回归。⚠️ **0.8.10 第 2 版自检报 401 属预期内复现**（补丁未进包），不是回归；第 3 版已修好、① 已验过。
⚠️ **0.8.8 换过签名**：从 ≤0.8.7 升级必须卸载重装；0.8.9 起同签名，正常覆盖升级即可。

**⚠️ 当前最大阻塞 · P0**：微信聊天页报「接口地址 404」，而「试一试」粘同一段聊天却能用（不是分级模式、本地代理开着）。已排除「自检修复换错了 Key」（三条硬证据 + Trial 同源反证）。真因二选一：A 模型名不在该套接口（model_not_found）／ B 镜像 prefs 里 model 为空，被 ConfigData.from 兜底成 gpt-4o-mini。差两条证据：① 实际发出的 model 字段名 ② 代理卡「最近一次」那行原文。详见「已知问题清单」第 1 节 P0。

---

## 1. 在等谁（阻塞项，优先处理）

1. **P0 · 微信报 404、试一试却能用（最高优先）** —— 已排除「自检修复换错了 Key」（三条硬证据 + Trial 同源反证）。还差两条证据：①「最近一次调用」里实际发出的 model 字段名；② 代理卡片「最近一次」那行的原文。拿到就能钉死是「模型名不在该套接口」还是「镜像没同步到模型名」。
   - 0.8.10 第 3 版还剩 ②③ 没测（① 自检误报已验过）—— ② 决策轨迹：微信触发卡片 → 诊断页手动抓取 → 有 07-决策轨迹.txt，且不含聊天正文、连续相同合并成 ×N；③ 主链路回归：弹卡片正常 / 候选能填输入框 / 第二套选完模型能生成 / 不自动发送。
   - 第二套接口选完模型能不能正常生成 —— 第 2 版已确认列表能拉 ✓、apiKey2 未留空 ✓，说明 baseUrl2 / apiKey2 本身是对的。
   - **下一轮建议**：做 缓做 6（ViewReader 抽纯函数 + 单测）—— 「取图 / 取会话名」这类坑的根治办法。

2. **缓做清单还剩 4 条**：2（接口形态）/ 6（ViewReader 抽纯函数）/ 8（英文 README）/ 10（OCR 更严白名单）—— 7 已并入第 3 版。用户习惯是「你列出来，我挑一条条来」，直接给带量级和建议顺序的清单最省事。

---

## 2. 接下来做什么

**手头待做：收 P0 的两条证据 → 钉死 404 真因；顺带把「404 三处文案补丁 + Screens.kt:508 补 second = true + CHANGELOG v0.8.8 那句订正」攒到下一包**。0.8.10 还没发正式版，等第 3 版验完再谈发布。默认节奏：同一版本号迭代就在原分支改、覆盖重出第 N 版；版本号要往上走时才从 `main` 开 `tmp/0.8.11`。

如果第 3 版验完「没问题了」：先问**要不要发正式版 0.8.10**，再开下一轮（建议做 **6（ViewReader 抽纯函数 + 单测）** —— 「取图 / 取会话名」这类坑的根治办法；7 已并入第 3 版）。

**补丁存档**：见本 Agent 的「TalkTact 0.8.10 补丁存档 · 自检直连用真 Key」文档（含症状、代码铁证、完整 diff、出包后要验哪几项、取证环境）。

---

## 3. 缓做（想做，但不急 —— 用户说「等前面的做完再一条条列出来做」）

已完成：**1**（拉模型列表）、**3**（归属地开关/自定义地址）、**4**（生成模式 pill 立刻落盘）、**5**（另两处保存按钮红字）、**9**（体积收窄）、**11**（OCR 取图收紧），以及 7（结构化 ring buffer 日志，已并入第 3 版）。

| # | 事项 | 现状 / 提示 |
| :- | :--- | :--- |
| 2 | **接口形态下拉** | 差异只在**路径 / 请求体 / 鉴权头**三处 → 抽 `ApiShape` 枚举 + `buildRequest` / `parseResponse` 两个纯函数。形态：Chat Completions（默认）/ Responses / Anthropic Messages / 自定义路径。注意名词：**「OpenAI 兼容」≠ 与 Chat Completions 并列**，前者通常就是指后者。 |
| 6 | `ViewReader` 取色/分类抽纯函数 + 单测 | 抽完这片区域的逻辑才能上单测（现在只能真机试）—— 也是「取图/取会话名」这类坑的根治办法 |
| 7 | 结构化 ring buffer 日志 | 把每次「读到什么、判成什么」存环形缓冲，出问题可视化 —— 排查「读不到消息」从猜变成看。**已并入 0.8.10 第 3 版** |
| 8 | 英文 README | 扩大受众，但要长期双语维护 |
| 10 | OCR 的更严白名单 | 现在白名单外的会话也会「认图」（只在本机两进程间走、不外发不落盘）。要连图都不认，得把会话名提前读出来 —— 而会话名依赖解析结果，环形依赖，要做就得重构那段顺序 |

---

## 4. 明确不做（用户拍板过，别再提）

- **自动发送**（风控风险高，且会替用户把话说出去）
- 崩溃收集（ACRA）
- Doze 精确闹钟
- IzzyOnDroid（上架）
- 群聊强支持（微信群聊行结构跨版本差异大，先把单聊做稳）

---

## 5. 新会话第一件事（沙箱会重置）

沙箱**会被重置**（工作目录清空、git 凭据丢失）—— 而且**同一个会话里也可能重置**（0.8.8 / 0.8.10 两轮都遇到）。
第一次动手前：

```bash
# 0) gh 初始是「未登录」：先跑一次 GitHub 工具的命令（github runCommand: gh auth status）让它自动写好凭据
mkdir -p /workspace && cd /workspace && git clone https://github.com/shibry88-netizen/TalkTact.git repo
cd repo
git config user.name shibry88-netizen
git config user.email shibry88-netizen@users.noreply.github.com   # 不设 commit 会失败
git checkout -b work/0.8.10 origin/tmp/0.8.10                     # 本轮：0.8.10 还在迭代（补丁 + 缓做 7），用它
# 版本号往上走时才：git checkout -b work/0.8.11 origin/main
gh auth setup-git                                                 # 不设 push 报 could not read Username
```

- ⚠️ 刚重置完那一下 `gh auth setup-git` 可能报「not logged into any GitHub hosts」—— 先跑一次 GitHub 工具的 `gh auth status`，再重跑 setup-git。
- 沙箱是 **aarch64，本地出不了包**（无 JDK / Android SDK），只能靠 GitHub Actions。
- 本地能做：读改代码、跑 Python（Pillow 齐全）、`git`、`gh`、`curl`（能查 Maven / Google Maven 上的依赖与 AAR 内容）。
- 改完 Kotlin **先跑一遍括号配平脚本**再推（和 `git show HEAD:<file>` 的计数对比，差值必须是 0）。
- ⚠️ **内容还没进 main 的临时分支一律别删**（0.8.0~0.8.2 删过一次，源码最后是从 release 的 `.patch` 捞回来的）。

---

## 6. 出包流程

**测试版**（用户说「有问题」→ 出一包；同一版本号反复覆盖更新，只留最新一个 test-0.8.x）。命名统一：标题 = 「<0.x 版本号> 测试版（第 N 版）」，release tag = 「test-<版本号>」，APK 文件名 = 「TalkTact-<版本号>-test.apk」。

```bash
git add -A && git commit -F /tmp/msg.txt      # 消息含反引号必须走 -F，否则 shell 会破坏它
git push origin work/0.8.10:tmp/0.8.10        # ⚠️ 本地分支名要对上（本轮就是 0.8.10；换版本时把 0.8.10 整体替掉）
gh run watch <runId> -R shibry88-netizen/TalkTact --exit-status   # 等绿（约 1.5~2.5 分钟）
gh run view <runId> --log-failed | grep -E "\te: |error:"        # 挂了先抓编译错误那一行
gh run download <runId> -D /tmp/dist
cp /tmp/dist/apk/app-debug.apk /tmp/dist/TalkTact-0.8.10-test.apk
git diff --binary origin/main..HEAD > /tmp/dist/talktact-0.8.10.patch   # 必须 --binary，基准用 origin/main
gh release upload test-0.8.10 <apk> <patch> --clobber -R shibry88-netizen/TalkTact
gh release edit test-0.8.10 --title "0.8.10 测试版（第 N 版）" --notes-file /tmp/rel.txt --prerelease
# 回读一次线上的 APK 算 sha256，确认和本地一致（防半包）
curl -sL -o /tmp/up.apk https://github.com/shibry88-netizen/TalkTact/releases/download/test-0.8.10/TalkTact-0.8.10-test.apk && sha256sum /tmp/up.apk
```

⚠️ GitHub 工具**一次只能跑一条 gh 命令**（`ID=$(...)` / `sleep 160` 这种会报 unknown command / Gateway Timeout）—— 要 run id 就先单独 `gh run list`；沙箱里的 `lobe-skills runCommand` 是真 shell，可以链式，但 **sleep 别超过 ~50 秒**（网关会超时）。

release 说明的写法：**面向用户**（这版做了什么、请怎么试、要他回什么），附 sha256 + 固定风控提示。

**正式版**（只有用户明确说「可以推送更新 / 发布吧」才走）—— **0.8.8 / 0.8.9 都已按这套走完（2026-10-06）**：

```bash
# ① CHANGELOG 先按正式版整理（新增版本章节、去掉「测试版」字样与临时措辞）
# ② 模块页文案同步：publish/{README,SUMMARY,FAQ,PRIVACY}（没有新功能时 README 可以不动）
git checkout work/0.8.x && git rebase origin/main      # ⚠️ 别直接 merge：main 多提交会被覆盖成旧文案
git checkout main && git merge --ff-only work/0.8.x && git push origin main
git tag v0.8.x && git push origin v0.8.x                # → publish.yml 自动发模块库 + 同步 publish/ 文案
gh release list -R Xposed-Modules-Repo/io.github.shibry88_netizen.talktact --limit 3   # 确认新版是 Latest（刚发布会先显示 Draft，约 2 分钟后转正）
```

---

## 7. 坑速查（这些最省时间）

**流程坑（我自己踩过，最贵）**

- ⚠️ **发完正式版** `git checkout main` **之后，新改动必须先** `git checkout -b work/0.8.x` **再动手** —— 0.8.10 那轮我在 main 上直接提交，然后 `git push origin work/0.8.9:tmp/0.8.10` 推的是旧分支，出来的「0.8.10 包」其实是 0.8.9（体积与 sha256 完全一样、patch 0 字节）。发现后修正：`git branch -f work/0.8.10 HEAD` → checkout → `git branch -f main origin/main` → 再 push。
- **推之前对一眼**：`git log --oneline origin/main..HEAD` 非空才说明新内容真的在要推的分支上。
- **同一版本号覆盖更新**时，先 `--clobber` 传附件、再 `gh release edit` 改标题/说明（顺序反了会显示旧附件）。

**代理 / 双模式（0.8.10 第 2 版的血泪）**

- 代理那侧**只拿得到 token**，认不出「这一跳是谁」→ 分级模式的两套接口必须由调用方在请求头里说明（`ProxyProtocol.HEADER_ENDPOINT = x-talktact-endpoint`，只有明确的 `"2"` 才算第二套）。以前写死第一套，症状是「第二套填对了却报 Key 不对，而且回显的是第一套那把 Key」。
- **401/403 的错误正文里那把 Key 是服务商回显的「实际收到的那把」** —— 模块自己从不显示 Key（排查时全仓库 grep 过）。定位「到底谁发的」就看它：回显的是真 Key 尾号 → 是某一套的 Key；回显的是 32 位随机串 → 那是本地代理 token，说明这一跳本该直连却带了代理凭据。
- `riskEndpoint()` 的规矩是**逐格回退**：第二套只填地址、不填 Key 时，会用第一套的 Key 去请求第二套的地址 —— 两家服务商不同就必然 401。
- **自检（probe）是直连的**：它故意不走本地代理 —— 要量的是目标机真实的 DNS / TCP。所以「用哪把 Key」必须跟着「走不走代理」走：直连 → 真 apiKey，走代理 → proxyToken。绑错就会在开代理时把 32 位代理 token 当 Key 发出去，必然 401，且回显 token 尾号（不是任何一把 Key）。30 秒现场验证：高级设置里把本地代理关掉 → 首页「开始自检」立刻变通；再打开 → 又 401。
- **404 别信「地址一般要写到 /v1」**：LlmClient.once()（LlmClient.kt:359）对任何 404 都翻成这句，而且只有 404 没拼服务商 detail（401/403、5xx 都拼了）。先看代理卡「最近一次」那行：转发 404 = 请求到了代理、上游回 404（多为模型名 / model_not_found）；转发（第二套）404 = 踩了 riskEndpoint 逐格回退；转发 200 但微信仍报错 = 这一跳没走代理；那行没更新 = 请求根本没到代理。

**注入侧读视图（第 18~21 版的血泪）**

- **别用** `isShown()` **当「能不能读/画」的判据**：微信把内容塞进 INVISIBLE 的占位控件里是老毛病；
  判断可见性用 `visibility != GONE`；`view.draw(canvas)` 对 INVISIBLE 的控件照样画得出内容。
- **别用「面积占比」当尺寸门槛**：漏判远多于命中。用**短边阈值** + 形状判断就够。
- **取图别把「整行」也收下**（第 21 版已删掉那一档）：整行画下来会把**昵称、时间**一起认成「对方说的话」，比认不出来更糟。
- **只对「可能是图片」的行找图**：`[语音]` / `[表情]` / `[视频]` / `[文件]` / `[位置]` 一律跳过（`isImageLikeAttachment`），否则每个表情包都白认一遍。
- **诊断要能自己说话**：现场里带上「行尺寸 / 遍历了几个视图 / 正文前 10 字 / 最大的 3 个非文字控件（类名 + 尺寸 + visibility）」——用户回一行字就能定位。

**Kotlin / 构建**

- 顶层声明不能插进 class 里（放文件末尾）；单测要用的声明写成 public。
- 加一对存取方法要**成对检查**（写过 `saveProbeMs` 忘写 `probeMs()` → CI Unresolved reference）。
- **有上限的轮询会过期**：页面上的「回传状态」必须靠 `resumedTick()`（`LifecycleEventEffect(ON_RESUME)`）在回到前台时重启一轮。
- 没 JDK：推之前先跑括号配平脚本；Python 批量改代码时 `s.index('\n}\n') + 1` 会落在 `}` 身上，容易多一个 `}`。

**依赖 / AAR（没 JDK 也能查）**

- `androidx.compose.ui.platform.LocalLifecycleOwner` 在 compose-ui **1.7 已不存在** → 用 `androidx.lifecycle.compose.LifecycleEventEffect`。
- ML Kit 的 `InputImage` **不吃 JPEG 字节** → 先 `BitmapFactory` 解得位图；认完 `recycle()`，**超时别回收**。
- 查依赖/类是否存在：直接 curl Google Maven 的 AAR 再 `unzip -l classes.jar`，比跑一轮 CI 快。

**Compose / 设置页**

- `WhiteCard`（白底说明卡）和 `GlassCard`（玻璃卡）刻意分开。
- 「一个保存按钮管整页」的页里，任何要立刻生效的开关都得自己落盘，且只写那几项（别整份 save）—— 现在**立刻落盘**的有：白名单、本地代理、识图、生成模式、归属地；其余跟页底那个「保存」。
- 页面很长时，红字要放**页首和页底两处**（高级设置就是这么做的），否则滚到中间看不见。
- 从二级页改状态，必须回调 `onSaved()` 刷新 App 层的 `ui`。
- 设置这条线四层：`settingsPage` 0 设置 / 1 高级设置 / 2 诊断 / 3 拉取到的联系人。

**注入侧其它**

- **tick 跑在微信主线程上** —— 任何网络 / 模型调用都不能在那儿等（OCR 因此是「后台认、认完再问」）。
- 只用系统 API + Kotlin 标准库；注入侧绝不引用 `ocr/` 与 `ProxyServer`。
- 前台服务通知图标必须单色；本地代理只绑 `127.0.0.1` 且显式绑定。
- `tick()` 里 `if (!decor.hasWindowFocus()) return` 会吞掉一切 —— 手动触发类的东西要放在它**之前**。
- 认页面不靠类名，靠结构特征。微信 8.0.78 实测通过。

---

## 8. 关键标识与地图

| 项 | 值 |
| :--- | :--- |
| 主仓库 / 模块库仓库 | `shibry88-netizen/TalkTact` / `Xposed-Modules-Repo/io.github.shibry88_netizen.talktact` |
| applicationId / namespace | `io.github.shibry88_netizen.talktact` / `dev.goutou.wingman` |
| SDK | minSdk 31 / compileSdk 37 / targetSdk 34 |
| 首页名 | 「WeChat · 聊天助手」 |
| 底部 5 个 tab | 运行状态 / 试一试 / 军师 / 角色 / 设置（四层：设置 → 高级设置 → 诊断 / 拉取到的联系人） |
| APK 体积 | **71MB**（ML Kit 中文离线模型 + 原生库占大头；ABI 只留 `arm64-v8a`） |
| 版本号 | 0.8.10 = vc 37（0.8.9 = vc36，0.8.8 = vc35） |

**代码地图（**`app/src/main/java/dev/goutou/wingman/`**）**

```plain
config/{Config,Roles,Backup,RemoteSync,DiagExport}.kt   配置读写 / 角色 / 备份 / 镜像到框架 / 诊断包
   Config.kt: saveWhitelist / saveOcr / saveGraded / saveGeo / saveModelList(second) = 只写那几项、立刻落盘
ocr/Ocr.kt        App 进程的 ML Kit 封装（位图输入 + sha256 LRU 结果缓存）
ocr/OcrText.kt    识别结果的拼行 / 截断（纯函数）
llm/{LlmClient,Prompt,Json,Suggestion,Graded,StyleSkill,RemoteSkill,NetInfo,ModelList}.kt
   ModelList.kt: modelsUrl / parseModels / looksNonChat 纯函数 + fetch(GET /models)
   LlmClient: second 标记（第二套接口）→ 走代理时带 x-talktact-endpoint 头
proxy/{ProxyProtocol,ProxyServer,ProxyService,ProxyState}.kt
   ProxyProtocol: endpointOf / endpointHeader（决定代理用哪套接口）
ui/{Ui,Screens,GlassQuality}.kt
   Screens.kt: WhiteCard · NoticeBanner · PickRow/PickBar · resumedTick · AdvancedScreen · ChatCandidatesScreen · DiagScreen
wechat/{Overlay,ViewReader,ChatParser,Snapshot,TextCapture,Sensitive,Chrome,ConvNames,ImageOcr}.kt
   ViewReader.scanImage：大叶子 → 大容器 → 任意叶子（排除头像/方形小图、短边 ≥ 48dp），只排除 GONE
   Snapshot: isImageLikeAttachment（只有 [图片]/[照片]/通用占位才去找图）
```

**关键常量**：`SELF_ROLE_KEY="__self__"`、`ROLE_MAX_MSGS=120`、`REPLY_TOO_LONG_CHARS=60`、`CONV_PULL_MIN_MS=15_000`、
`OCR_MAX_SIDE=1280`、`OCR_MAX_BYTES=3_000_000`、`OCR_MAX_CHARS=300`、`ATTACHMENT_TEXT="[图片/表情/语音]"`、
`ProxyProtocol.HEADER_ENDPOINT="x-talktact-endpoint"`。

---

## 9. 安全与签名（要点）

- 旧密钥曾连口令一起提交在公开仓库（`4f7b58e`）→ **已作废并轮换**（0.8.8 起生效）。新密钥只在仓库 Secrets：
  `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEYSTORE_ALIAS`（= `talktact`），另有 `MODULE_REPO_TOKEN`。
- 证书指纹（SHA-256 无分隔大写）：`F8D1C2B43AE5EBD3169493937A7DF7AD2C23C59197E29E999E7FCBA626807AD5`
  （workflow 里当断言硬编码；换钥匙要同步改 `signing/README.md`、`build.gradle.kts` 注释、workflow、交接文档）。
- 备份：`signing/key.p12.enc`（AES-256-CBC + PBKDF2 600000）存在仓库里，**口令不在仓库、也不在这份文档**——
  在「TalkTact 交接文档（0.8.x）」第九节。还原顺序：先 `KEYSTORE_BASE64`，为空才回退加密包 + `KEYSTORE_PASSPHRASE`。
- **换钥匙 = 用户必须卸载重装**（0.8.8 就是这种情况）。0.8.9 起同签名，正常覆盖升级即可。

---

# 第二部分 · 已知问题清单

> **用途**：和第一部分（项目总览）**配合读** —— 总览管「全局 / 流程 / 坑速查」，这一份只管**「还有什么没落地」**：等实测的、等证据的、已定位未修的、设计如此不算 bug 的、已经证实不必再查的、环境硬限制、以及要订正的文档欠账。
> **最后更新**：2026-10-06（已对远端做过现场核对，结果见第 0 节；第 3 版已出包、① 已验过。当前最大阻塞 = 第 1 节 P0）

---

## 0. 现场核对过的真实状态（权威，以本节为准）

| 项 | 值 |
| :--- | :--- |
| `main` | `af9af42`（0.8.9 正式版；模块库 Latest = `36-0.8.9`） |
| 开发分支 | `tmp/0.8.10`，HEAD = `e3531d6`，相对 main **4 提交 / 15 文件 / +1025 −43** |
| 4 个提交 | `3bf6d0c` 缓做 1+3+9 ｜ `85415ff` 代理按请求头选接口 + 第二套拉列表 ｜ `8c24c32` 缓做 7 决策轨迹 ｜ `e3531d6` Trace 单测隔离 |
| 最新测试包 | `test-0.8.10` = **「0.8.10 测试版（第 3 版）」**，prerelease，createdAt 2026-10-06T05:42:15Z |
| APK | `TalkTact-0.8.10-test.apk`，74540006 字节，sha256 `6fec576b45e5497bed83a26a259734b2456cce05395be2ccdb1ff1a8af9536a8`（线上回读一致） |
| patch | `talktact-0.8.10.patch`，83710 字节，sha256 `58d9cf738027f31aa1201b569f79a2491fc4d56d24653faa6d863f84ec961975`（线上回读一致） |
| 补丁进了第 3 版多少 | **一半**（现场 grep 确认）：`LlmClient.kt:70` `authHeader(proxyRoute)`、`:271` `post(..., direct = true)`、`:333` `proxyRoute = !direct && viaProxy()` **已进**；`Screens.kt:508` 的 `second = true` 与 404 文案 **均未进** |
| versionCode | 0.8.10 = 37 |

---

## 1. 还没闭环

### P0 · 微信界面报 404，「试一试」页同一份配置却能用 ⬅ **当前最大阻塞**

**症状**：微信聊天页里触发分析 / 生成 → 报 `接口地址 404`（附文案「地址一般要写到 /v1…」）；退到「试一试」页粘同一段聊天 → 正常。

**用户已答复的三问**（2026-10-06）

1. **不是**分级模式（＝直通模式，只有一套接口）
2. 本地代理**开着**
3. 「试一试」**可用**；微信界面**不可用**

**已排除的怀疑**：「自检修复时误把微信用的 api/key 换成了本地代理 key」→ **不成立**，三条硬证据：

1. `RemoteSync.kt` 本分支**零改动** —— 它是唯一把配置镜像给注入进程的文件
2. `LlmClient.kt` 本分支只动了 4 处（新增 `second` / `authHeader(proxyRoute)` / `probe()` 改直连 / 401·403 加「这是第二套接口」文案），**都不碰** `apiKey` / `baseUrl` **的来源**
3. 微信侧那份 `api_key` 是**空字符串**，这是设计（代理开时 `RemoteSync` 故意推空，注入侧改用 proxyToken，真 Key 由 App 进程代理补）

**更强的反证**：`TrialScreen` 用的是 `store.load()`（**同一份 App 配置**）；代理开时它和微信走**同一台代理、同一份 cfg**（`LlmClient(cfg)` → `viaProxy()` → `127.0.0.1/proxy/chat/completions` → `ProxyServer.forward(cfg.baseUrl, cfg.apiKey, …)`）。Trial 通 ⇒「地址 ✅ + 真 Key ✅ + 代理链 ✅」三者全对，所以「微信那份被换成代理 key」逻辑上不可能同时成立。

**分水岭 —— 看「本地代理」卡片里的「最近一次」那行**（不用重装、不用抓包）：

| 「最近一次」显示 | 含义 | 下一步 |
| :--- | :--- | :--- |
| `转发 404 · xxms` | 请求**到了**代理，上游回 404（地址已被 Trial 证明是对的） | 基本就是**模型名**不在那一套接口上（`model_not_found`） |
| `转发（第二套）404` | 这一跳带了 `x-talktact-endpoint: 2` | 第二套混合填写，踩了 `riskEndpoint()` 逐格回退 |
| `转发 200` 但微信仍报错 | 请求没走这条代理 | 查注入侧是否还带旧 token / 端口 |
| **那行没更新** | 微信的请求**根本没到代理** | 往注入侧链路查（不是服务商的问题） |

**最可能的真因**（二选一，就差两条证据）

- **A. 模型名不在那套接口上** —— 404 且上游 `model_not_found`
- **B. 镜像没同步到模型名** —— 微信侧读的是**镜像 prefs**，而 `ConfigData.from` 有静默兜底：
  `model = p.getString(Keys.MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }`
  即镜像里 `model` 空 → 悄悄回退成 `gpt-4o-mini` → 该服务商没这个模型 → 404，且这个 404 会被那句万能话盖住

**还差的两条证据**（拿到就能钉死是 A 还是 B）

1. 「最近一次调用」里**实际发出去的** `model` **字段名**是什么
2. 代理卡片「最近一次」那行的**原文**

**⚠️ 别信那句「地址一般要写到 /v1」**：`LlmClient.once()`（现场核对仍在 `LlmClient.kt:359`）对**任何** 404 都翻成这句话，而且**只有 404 没拼服务商 detail**（401/403、5xx 都拼了）。它是在猜，不代表真因。

### P1 · 0.8.10 第 3 版没验完（① 已过）

- ✅ **① 自检误报修复**（本版主修）：代理关 → 自检通；代理开 → 自检通、不报 401。**用户已回「正常了」**
- ⏳ **② 决策轨迹（缓做 7）**：微信聊天里触发卡片 → 诊断页手动抓取 → 确认有 `07-决策轨迹.txt`；三个检查点：看懂「为什么没弹卡片」/ 连续相同合并成 `×N` / **不含聊天正文**；等 ≥20 秒看自动回传。**还没测**
- ⏳ **③ 主链路回归**：弹卡片正常、候选能填输入框、第二套选完模型能生成、**不自动发送**。**还没测**

### P2 · 一直没真机验证过的历史项

- **设备分级**（`GlassQuality.decideGlassQuality`，高 / 低 / 自动三档）—— 只在 CI 单测里过，没在真机上核对过效果（低档该「不模糊 / 不折射 / 不推扫光相位」，自动档在低内存机或 API 33 以下的表现）
- **截图回归**（Robolectric + Roborazzi）—— CI 只把 PNG 当 artifacts 传，**没人肉眼看过**；即「界面被画崩了」这类回归目前没有闸门

---

## 2. 已定位、未修

1. **404 文案误导 + 不拼服务商 detail**（现场核对仍在 `LlmClient.kt:359`）。建议 3 处小补丁（**待拍板**）：① 404 也拼服务商 detail；② body 是 `model_not_found` 一类 → 直说「这个模型名在那一套接口上没有」；③ 404 也标明是哪一套（像 401 那样）
2. **第二套自检漏传** `second = true`（现场核对：`Screens.kt:508` 仍是 `LlmClient(conf.riskEndpoint()).probe()`）。第 3 版补丁只修了「直连用真 Key」那一半 —— 因为 `direct = true` 时凭据已强制走真 Key，所以这条**现在只影响报错文案**（第二套自检失败时不会带「这是第二套接口」那句），**不影响凭据正确性**；对照：`TrialScreen(575)` 那条已经传了 `second = true`
3. **模型名静默兜底**：`ConfigData.from` 里 `ifBlank { DEFAULT_MODEL }` —— 镜像 / 配置里模型名为空就悄悄用 `gpt-4o-mini`，不报错、不可见（也是 P0 真因 B 的机制）
4. `riskEndpoint()` **逐格回退**：第二套只填地址、不填 Key → 会拿第一套的 Key 去请求第二套的地址 → 两家服务商不同就**必然 401**。设计如此，但极易被当成 bug（以后报「Key 不对」先看这条）
5. **高级设置页两种保存行为混在一页**：白名单 / 本地代理 / 识图 / 生成模式 / 归属地 = **点一下立刻落盘**；接口地址 / 上下文条数 / 温度 = **草稿，要按页底那个保存**。已加「未保存」红字缓解，机制本身没统一
6. **注入侧大文件、几乎没有单测覆盖**：`Overlay.kt` 已 1349 行（折叠按钮 + 卡片 + 采集 + 注入 + tick 主循环都在这一个文件），`Screens.kt` 同样巨大。取图 / 取会话名 / 是否弹卡片这些逻辑**只能在真机上试**，出问题只能靠用户回一行现场字 —— 这也是缓做 6 要抽纯函数的动因（累积隐患，非 bug）
7. **APK 里的诊断包 / 日志没有自动回收**：导出诊断包、`Trace` ring buffer 都是手动触发 / 有上限（ring buffer 本身 CAP 80 条是上限保护的），但**没有「存太久自动清」的策略**（低风险，记录在案）
8. **保存链路是「整份覆盖」，子页面改完必须回调 onSaved()**：整份 `ConfigStore.save(load().copy(...))` 会把**别的卡片还没保存的草稿一起定死**；子页面（如「拉取到的联系人」二级页）改完若不回调 `onSaved()` 刷新 App 层的 `ui`，回上一页看到的是旧名单，那边再点一次「保存」还会把旧值整份写回去。已按处修（新增 `saveWhitelist()` 这类「只写自己那几项、立刻落盘」的写法），但**没有统一的存储层，全靠每处自觉**（累积隐患，非 bug）
9. **诊断 / 状态类信息靠「有上限轮询」，会过期**：注入侧广播回传的状态（识图结果、会话名候选、上次拉取）在页面上是 `produceState` + `repeat(200)` 每 1.5s ≈ **5 分钟就停**；「打开看一眼 → 去微信里试 → 切回来」这种典型用法靠 `resumedTick()`（`LifecycleEventEffect(ON_RESUME)`）当重启键救回来了（0.8.8 第 19 版修）。**残余**：页面一直停在前台不动超过 5 分钟后，新的回传不会再自动出现，得切走再切回（低风险，记录在案）

---

## 3. 设计如此 / 已知限制（不是 bug，别当 bug 去查）

| 现象 | 说明 |
| :--- | :--- |
| 微信侧 `api_key` 是空的 | 设计：代理开时故意推空，注入侧只用 proxyToken，真 Key 只在 App 进程 |
| 自检**故意直连**、不走本地代理 | 它要量真实目标机的 DNS / TCP；走回环就变成量 `127.0.0.1`，数字没意义 |
| 「Key 不对」里回显的那把 Key | 是**服务商回显的「实际收到的那把」**，模块自己从不显示 Key；回显 32 位随机串 = 那是代理 token |
| 白名单外的会话**也会认图**（OCR） | 图只在本机两进程间走、不外发不落盘；要「连图都不认」得把会话名提前读出来，而会话名依赖解析结果 —— 环形依赖，要做得重构 |
| 认不出会话名 → 按「没勾」拦 | fail-closed，故意的 |
| 拉会话名：**个人资料页不是来源** | 只从「打开的聊天」或「首页 / 通讯录列表」读（资料页整页是一行行资料，会冒充列表） |
| 合并角色的记录时间 | 是**模块「看到」它的时间**，不是微信里那条消息的真实时间 |
| 只实测微信 **8.0.78** | 适配表只写实测通过的版本 |
| 32 位设备装不上 | ABI 只留 `arm64-v8a`（缓做 9，省约 10MB） |
| APK ≈71MB | ML Kit 中文离线模型 + 原生库占大头 |
| 用户自己存过的提示词不会被内置 skill 覆盖 | 内置 skill 更新后，要到「军师」页重新点一下内置 skill 才生效 |
| 注入侧 tick 跑在微信主线程 | 任何网络 / 模型调用都不能在那儿等（OCR 因此是「后台认、认完再问」） |
| OCR 认不出 / 没开 / 没代理 / 失败 | 一律回落成 `[图片/表情/语音]` 占位，行为跟没有 OCR 时完全一样；失败单独**冷却 60s** 且不进缓存（否则「代理没起来」会变成每轮都重试，卡片永远卡在「正在认图…」） |

---

## 4. 已经验证过的事实（新会话**别再重复排查**）

| 事实 | 依据 |
| :--- | :--- |
| 微信进程**能**连 `http://127.0.0.1:端口` 的**明文**请求 | OCR 整条链路（第 18~20 版 → 0.8.9 用户回「现在好用了」）走的就是这条回环代理；原以为微信网络策略会拦，**没拦** |
| 模块**从不显示**任何 Key | 全仓库 grep 过 `takeLast / substring / sk-`；报错里那把 Key 一定来自服务商回显 |
| 第二套的 `baseUrl2 / apiKey2` 本身填对了 | 第 2 版实测：第二套模型列表能拉 ✓、`apiKey2` 未留空 ✓ |
| 白名单 fail-closed 已生效 | 认不出会话名按「没勾」拦；形状闸 + 长按多选 / 已忽略都已被真机确认「白名单功能正常了」 |
| 0.8.10 第 3 版「自检不再误报 401」已修 | 用户已回「正常了」 |
| 「自检修复时改错了微信用的 Key」**不成立** | 见第 1 节 P0 的三条硬证据 + `TrialScreen` 反证 |
| OCR 取图逻辑**已够好用**，别再无实例大改 | 用户明确定调「现在好用了」；除非再给实例，否则别回头动取图 |
| `Overlay.tick()` 的 `hasWindowFocus()` 早退会吞掉手动触发 | 已知坑，手动抓取类逻辑必须放在它**之前**（0.8.8 第 15 版踩过） |
| 微信 8.0.78 的正文大量是**自绘控件**，图片不一定在 `ImageView` 里 | 取图必须两档：`ImageView` + `BitmapDrawable` 直接取原图；其他「大控件」只能 `view.draw()` 画进位图（自绘的只能这么取，**且必须在主线程**）。读视图一律用 `visibility != GONE`，**别用 isShown()**（微信把内容塞进 INVISIBLE 控件是老毛病） |
| ML Kit 的 `InputImage` 不吃 JPEG 字节 | 它只有 Bitmap / NV21 / YV12 / YUV_420_888，**根本没有 IMAGE_FORMAT_JPEG 这个常量**（CI 报 Unresolved reference 才知道）→ 必须先 `BitmapFactory` 解成位图再 `InputImage.fromBitmap`；识别完回收位图，**但超时别回收**（任务可能还在读它），交 GC |

---

## 5. 环境与工具链的硬限制（不是 bug，是工作条件）

| 限制 | 影响 / 应对 |
| :--- | :--- |
| 沙箱是 **aarch64，无 JDK / Android SDK** | **本地出不了包**，`assembleDebug` 只能靠 GitHub Actions；本地只能读改代码、跑 Python(Pillow)、`git` / `gh` / `curl` |
| 沙箱**会重置**（且同一会话中途也可能重置，0.8.8 / 0.8.10 都遇到） | 重置后要重 clone、重设 `git config user.name / user.email`、重跑 `gh auth setup-git` |
| GitHub 工具**一次只能跑一条 gh 命令** | `ID=$(...)` / `sleep 160` 会报 unknown command / Gateway Timeout；要 run id 就单独 `gh run list` |
| `lobe-skills runCommand` 是真 shell，可链式，但 **sleep 别超 ~50 秒** | 网关会超时 |
| **没有真机自动化** | 所有「读屏 / 弹卡片 / 注入」类改动都只能靠用户手测 + 回一行现场字 |
| 内容没进 main 的**临时分支一律别删** | 0.8.0~0.8.2 删过一次，源码最后只能从 release 的 `.patch` 捞回来 |
| Kotlin 改完**先跑括号配平脚本** | 无 JDK 本地编不了；Python 批量改代码时切片 off-by-one 会多出一个 `}`（被抓到过一次） |

---

## 6. 剩余未做（缓做清单）

已完成：**1** 拉模型列表 · **3** 归属地开关 / 自定义地址 · **4** 生成模式立刻落盘 · **5** 另两处保存红字 · **7** 决策轨迹 ring buffer · **9** ABI 收窄 · **11** OCR 取图收紧

| # | 事项 | 量级 / 提示 |
| :- | :--- | :--- |
| 2 | 接口形态下拉（Chat Completions / Responses / Anthropic Messages / 自定义路径） | 中；抽 `ApiShape` 枚举 + `buildRequest` / `parseResponse` 两个纯函数 |
| 6 | `ViewReader` 取色 / 分类抽纯函数 + 单测 | 中；**下轮建议做这条**（「取图 / 取会话名」这类坑的根治办法，见第 2 节 #6） |
| 8 | 英文 README | 小；但要长期双语维护 |
| 10 | OCR 更严白名单（白名单外连图都不认） | 大；环形依赖，要重构「先读会话名」那段顺序 |

---

## 7. 明确不做（用户拍板过，别再提）

自动发送 · 崩溃收集（ACRA） · Doze 精确闹钟 · IzzyOnDroid 上架 · 群聊强支持

---

## 8. 文档欠账

| 文档 | 现状 | 要做什么 |
| :--- | :--- | :--- |
| 「TalkTact 总览」 | 已于 2026-10-06 订正：第 3 版已出、① 已验、P0 404 写进第 1 节阻塞项，并在第 7 节补了 404 分水岭速查 | 已完成 |
| 「TalkTact 0.8.10 补丁存档」 | 已于 2026-10-06 订正：正文头现在写明「**补丁只进了一半**」—— 第 3 版带上了 LlmClient 那半，`Screens.kt:508` 的 second = true 与 404 文案未进 | 已完成（2026-10-06） |
| 「TalkTact 交接文档（0.8.x）」 | 自述「现状以总览为准」，但正文快照停在 `main=32c620f` / `tmp/0.8.5` 时期 | 只在需要改它时顺手订正；它是历史明细，不必强求同步 |
| CHANGELOG（仓库里） | v0.8.8 那句「第二套接口不参与首页接口自检」**与代码不符**（`56df21c` 之后已不成立）；且 **0.8.10 条目还没写** | 等用户拍板发正式版那一刻，连同 0.8.10 条目一起改 |

---

## 9. 新会话接手清单

### 9.1 接手命令（沙箱重置后照抄）

```bash
gh auth status                                   # 未登录就先跑一次 GitHub 工具的命令，让它写好凭据
mkdir -p /workspace && cd /workspace && git clone https://github.com/shibry88-netizen/TalkTact.git repo && cd repo
git config user.name shibry88-netizen
git config user.email shibry88-netizen@users.noreply.github.com
git checkout -b work/0.8.10 origin/tmp/0.8.10    # 本轮还在 0.8.10 迭代
gh auth setup-git
git log --oneline origin/main..HEAD              # 应为 4 条（e3531d6 / 8c24c32 / 85415ff / 3bf6d0c）
```

### 9.2 下一步（建议顺序）

1. **先收 P0 的两条证据**（实际发出去的 `model` 名 + 代理卡片「最近一次」那行原文）→ 一次钉死是「模型名不在该接口」还是「镜像没同步到模型名」
2. **等 ②③ 实测**（决策轨迹 / 主链路回归）
3. 顺手做 **2.1 的三处 404 文案补丁** + **2.2 的** `Screens.kt:508` **补** `second = true` + **CHANGELOG v0.8.8 那句订正** → 攒到下一包一起出
4. 若 ②③ 都过 → 问用户**要不要发正式版 0.8.10**（走第一部分的正式版流程；别忘了先 `rebase origin/main`）
5. 下一轮功能：建议 **缓做 6**（`ViewReader` 抽纯函数 + 单测）
6. 订正「补丁存档」的现状段落（见第 8 节）—— **已于 2026-10-06 完成**

---

出包 / 发布流程、坑速查、代码地图、关键常量：见本文件第一部分。

---

# 第三部分 · 0.8.10 补丁存档 · 自检直连用真 Key

> **状态（2026-10-06 订正）：补丁只进了一半 —— 第 3 版已出包，现场 grep 确认：LlmClient 那一半已进**（`authHeader(proxyRoute)` / `post(..., direct = true)` / `proxyRoute = !direct && viaProxy()`）；**`Screens.kt:508` 的 `second = true` 与 404 文案均未进** —— 这两条仍挂在第二部分第 2 节（第 1、2 条）。
>
> （以下是当时原始存档记录，保留备查）用户 2026-10-06 拍板：**不改第 2 版测试包**，这份补丁**攒着**，等**缓做 7（ring buffer 结构化日志）**改完，**同一版一起出**，出 `test-0.8.10` 第 3 版。事实是第 3 版只带上了前半（见上一段）。

---

## 0. 一句话

开着**本地代理**时，「接口自检」走的是**直连服务商**，但凭据判断却跟着 `viaProxy()` 走 → 把**代理 token** 当 API Key 发出去 → **必然 HTTP 401**（服务商回显的那把 Key 尾号就是随机 proxyToken）。**自检的正解是「直连 + 真 Key」**，不是改走代理。

---

## 1. 症状（真机复现）

- 真机第 2 版：**第二套接口能拉出模型列表**（说明 `baseUrl2` + `apiKey2` 本身没问题），但**自检仍然报 401**：

```plain
Key 不对，或这个 Key 没有该模型的权限：Authentication Fails,
Your api key: ****5ttm is invalid (request_id. 328ed4bf-...)
```

- 关键旁证 ①：这段报错里**没有**「（**这是第二套接口**：…）」那句 → 说明 `second` 没传（提示只在 `second=true` 时拼）。
- 关键旁证 ②：`****5ttm` 是随机 32 位 **proxyToken** 的尾号，**不是** `apiKey2`。
- 关键旁证 ③：第二套「从服务端拉取模型列表」能成功 → 走的是 `ModelList.fetch()`，它用**真 Key**（`"Bearer " + apiKey`）直连 → 两套配置都没填错，坏的只有自检这一跳的凭据选择。
- 30 秒免打包实测：高级设置 → **本地代理关掉** → 首页点「开始自检」→ 预期**变通**；再打开 → 又报 401。

**与** `apiKey2` **是否留空无关。** 与 `main` 也无关：锅来自 `19a562e feat(proxy): 本地代理模式`（属 **v0.8.8**），它把 `authHeader()` 改成代理感知却没管自检 → 只要开本地代理，**从 0.8.8 起一直有此症状，第一套/第二套都中**。

---

## 2. 代码铁证（分支 `tmp/0.8.10` = `85415ff`，即第 2 版源码）

```kotlin
// LlmClient.kt:64-65 —— 凭据判断只看「走不走代理」
private fun authHeader(): String =
    "Bearer " + if (viaProxy()) cfg.proxyToken else cfg.apiKey

// LlmClient.kt:237-239 —— probe() 的出站地址是直连（没走 outboundUrl()）
fun probe(): ProbeResult {
    if (cfg.apiKey.isBlank()) throw LlmException("还没填 API Key", "到「设置 → 高级设置」里填地址和 Key")
    val url = endpoint(cfg.baseUrl)          // ← 直连服务商，刻意如此
    ...
    val root = parseResponse(post(url, payload))   // → post() → once()

// LlmClient.kt:318-322 —— once() 按 viaProxy() 决定 Authorization
conn.setRequestProperty("Authorization", authHeader())
if (viaProxy()) {
    conn.setRequestProperty(ProxyProtocol.HEADER_ENDPOINT, ProxyProtocol.endpointHeader(second))
}

// Screens.kt:508 —— 第二套自检漏传 second
val r2 = withContext(Dispatchers.IO) { LlmClient(conf.riskEndpoint()).probe() }
```

三行拼起来 = 直连服务商 + 发 proxyToken = 401。

**为什么自检必须保持直连**（别改成走代理）：

- 自检要量的是**真实目标机**的 DNS 解析 / TCP 握手耗时；走回环就变成量 `127.0.0.1`，数字没意义。
- 自检页要显示的「目标服务器 / IP」也得是**真地址**。
- 「代理通不通」由代理卡片那行「微信侧最近一次走的是：本地代理 ✅」负责，不该让自检代劳。

---

## 3. 补丁（完整）

### 3.1 `app/src/main/java/dev/goutou/wingman/llm/LlmClient.kt`

把「用哪把凭据」与「走不走代理」绑成**同一个判断**：

```kotlin
/** 出站凭据：代理模式给 token（App 会用真 Key 去调服务商），否则就是 API Key 本身。 */
private fun authHeader(proxyRoute: Boolean): String =
    "Bearer " + if (proxyRoute) cfg.proxyToken else cfg.apiKey

/** 失败重试一次（只对超时/5xx/429 这种「可能只是碰巧」的错误）。 */
private fun post(url: String, payload: String, direct: Boolean = false): String {
    var last: LlmException? = null
    for (attempt in 0..1) {
        try {
            return once(url, payload, direct)
        } catch (e: LlmException) {
            ...
        }
    }
    throw last ?: LlmException("请求失败", null)
}

private fun once(url: String, payload: String, direct: Boolean = false): String {
    val conn = ...
    try {
        ...
        val proxyRoute = !direct && viaProxy()      // 直连时不带代理凭据
        conn.setRequestProperty("Authorization", authHeader(proxyRoute))
        // 代理那侧认不出「这一跳是谁」，得靠这个头告诉它用哪套接口（只对代理有意义）
        if (proxyRoute) {
            conn.setRequestProperty(ProxyProtocol.HEADER_ENDPOINT, ProxyProtocol.endpointHeader(second))
        }
        ...
    }
}

// probe()：明确「自检是直连的」
val root = parseResponse(post(url, payload, direct = true))
```

### 3.2 `app/src/main/java/dev/goutou/wingman/ui/Screens.kt:508`

让第二套自检失败时，提示里带上「这是第二套接口」（与 `:604-605` 生成链路的写法对齐）：

```kotlin
val r2 = withContext(Dispatchers.IO) { LlmClient(conf.riskEndpoint(), second = true).probe() }
```

**覆盖范围**：全仓库只有两个 `probe()` 调用点 —— `Screens.kt:493`（第一套，`LlmClient(conf).probe()`）与 `:508`（第二套）。
`direct = true` 写进 `probe()` 内部后**两处自动都走直连真 Key**；`second = true` 只补第二套那处。

### 3.3 随补丁一起的小项

1. `requireReady()` **复核**：现在代理模式下只看 `proxyToken` 非空、非代理模式只看 `cfg.apiKey` —— 就自检这条而言是**对的**，不用改，但改完 `authHeader` 后顺手对一眼，别留下「两套判断标准」。
2. `CHANGELOG.md` **v0.8.8 那段订正**：里面「第二套接口不参与首页接口自检」的说法，在 `56df21c` 之后已与代码不符 → 补 0.8.10 条目时**顺手改掉**。

---

## 4. 出包后要验的（第 3 版）

1. **本地代理 ON + 模型分级**：风险那一路自检**不再报 Key 不对**；若仍报，看 ①`apiKey2` 是否留空 ②错误里有没有「这是第二套接口」句 ③转发记录有没有「（第二套）」标记，否则要**原始错误原文**。
2. 本地代理 **OFF**：自检照常可用（回归）。
3. 第二套「从服务端拉取模型列表（第二套）」→ 选完能正常生成。
4. 生成 / 单条改写 / 军师 / 角色 / 图片文字识别主链路没被带坏。

---

## 5. 取证环境备忘

```bash
mkdir -p /workspace && cd /workspace && rm -rf repo
git clone -q https://github.com/shibry88-netizen/TalkTact.git repo && cd repo
git fetch -q origin tmp/0.8.10 && git checkout -q FETCH_HEAD
# 文件位置
#   app/src/main/java/dev/goutou/wingman/llm/LlmClient.kt
#   app/src/main/java/dev/goutou/wingman/ui/Screens.kt
#   app/src/main/java/dev/goutou/wingman/proxy/ProxyProtocol.kt
```

- 分支：`main` = `af9af42`（0.8.9 正式版）；`tmp/0.8.10` = `85415ff`（`3bf6d0c` 缓做 1+3+9 / `85415ff` 修代理按请求头选接口）。
- 关键常量：`ProxyProtocol.HEADER_ENDPOINT = "x-talktact-endpoint"`、`ENDPOINT_FIRST="1"`、`ENDPOINT_SECOND="2"`。
- `Config.riskEndpoint()`：三字段全空 → `this`；否则 `copy` **逐格回退**（填了的覆盖、留空的回落第一套）。

---

# 第四部分 · 缓做 7 设计稿（决策轨迹 ring buffer）

> 本部分是当时的设计稿；缓做 7 已实现，并随 `test-0.8.10` 第 3 版出包（2026-10-06）。结论已回写进第一部分与第二部分。

## 目标（用户原话）

> 把每次「读到什么、判成什么」存成环形缓冲，出问题时可视化 —— 排查「读不到消息」会从猜变成看。

## 关键约束（注入侧环境逼出来的）

1. **注入侧写不了文件** → 轨迹只存内存，跨进程靠广播（Heartbeat）。
2. **tick 900ms 一次，不能把广播淹了** → 连续相同记录合并计数（`×N`），内容变了才留痕。
3. **tick 跑在微信主线程** → 记录必须极便宜（`ArrayList` + 截断字符串）。
4. **广播/Binder 有大小上限** → 容量 80 条 × 单条 200 字封顶（约 16KB）。
5. **纯 Kotlin**（只用标准库）→ 可在 JVM 单测里直接验。

## 定位：与现有诊断的分工

|  | 决策轨迹（新） | 界面结构诊断（已有） |
| :--- | :--- | :--- |
| 记什么 | **判定与时间线**：读到几条、停在哪一步、为什么 | **快照与细节**：View 树、控件尺寸、正文前 10 字 |
| 频率 | 每个早退分支都记，连续去重 | 30s 限流 + 手动 |
| 含聊天内容 | **否**（只记条数/形状/会话名） | 是（最后 3 条、行内文本） |
| 用途 | 「为什么没弹卡片」 | 「读到的到底是什么」 |

**刻意不含消息正文** —— 轨迹会随心跳回传、常驻 App 本地，不该比诊断包更容易泄露聊天内容。

## 埋点清单（tick 的每个早退分支）

| 分支 | tag | 文本要点 |
| :--- | :--- | :--- |
| 手动抓取 | 抓取 | App 触发 |
| 拉取会话 | 会话 | 扫到几个列表/几行/认出几个名字 |
| 无窗口焦点 | 焦点 | 不在前台 |
| 找不到输入框 | 输入 | 有候选（类名+尺寸）/ 完全没有 |
| 读配置失败 / 主开关关 | 配置 / 开关 |  |
| 没找到消息列表 | 列表 | 连续 N 轮 / 第 1 轮再等等 |
| 指纹未变 | 跳过 | 这一屏没变 |
| 正在认图 | 识图 | 后台认字中 |
| 解析出 N 条 | 读到 | 条数 + 最后一条方向（**不含正文**） |
| 解析出 0 条 | 空 | 一行都没解析出来 |
| 白名单拦住 | 白名单 | 认不出名字 / 不在名单 |
| 全是附件 | 全图 | 多半选错列表 |
| 最后一条是我发的 | 方向 | 收起卡片 |
| busy | 忙 | 上一轮还在跑 |
| 命中缓存 | 缓存 | 3 分钟内问过 |
| 命中敏感词 | 敏感 | 命中哪些词（先不发模型） |
| 最短间隔内 | 间隔 | 还剩几秒 |
| 真正发起 | 调用 | 条数·会话·路线 |
| 自愈重绑 | 自愈 | 学了几个控件类 |
| 调用结束 | 结果 | 成功（几条候选/风险/告警）或失败原因 |

## 回传策略（关键）

- **手动**：点「抓取微信界面」→ `dumpDiagnosis(manual=true)` → 一定带轨迹。
- **自动**：ticker 每轮 `finally` 里调 `maybeSendTrace()` —— 轨迹内容有变且距上次 ≥20s 才发一次。
  → 用户「在微信里复现 → 切回 App」就能看到，不必记得点按钮；正常聊天几乎不发。

## 改动文件

1. **新增** `wechat/Trace.kt` —— 环形缓冲本体（纯 Kotlin）
2. **改** `wechat/Overlay.kt` —— 埋点 + `maybeSendTrace()` + ticker finally
3. **改** `HeartbeatReceiver.kt` —— `trace` 字段 + 落盘
4. **改** `config/Config.kt` —— `Keys.TRACE` / `TRACE_AT` + `ConfigStore.trace()/traceAt()`
5. **改** `ui/Screens.kt` —— 诊断页「决策轨迹」卡 + 诊断包 `07-决策轨迹.txt`
6. **新增** `app/src/test/java/dev/goutou/wingman/TraceTest.kt`

## 交付清单（全部已完成）

- ✅ Trace.kt —— 环形缓冲本体（纯 Kotlin，CAP 80 / 单条 200 字 / 回传最小间隔 20s）
- ✅ Overlay 埋点 22 处 + maybeSendTrace() + ticker finally
- ✅ 回传链路（Heartbeat.send 加 trace 参数 / HeartbeatReceiver 落盘 / Keys.TRACE、TRACE_AT / ConfigStore.trace()、traceAt()）
- ✅ 诊断页「决策轨迹」卡 + 诊断包 `07-决策轨迹.txt` + `DiagExport.readme()` 说明行
- ✅ 单测 TraceTest.kt（9 项）；DiagExportTest 同步更新
- ✅ 括号配平 + 出 test-0.8.10 第 3 版（2026-10-06，APK sha256 `6fec576b…`）
- ⚠️ 出包时 CI 挂过一次（149 项里 1 挂）：Trace 是单例 object，全局累加的 `seq` 没被 `resetForTest()` 归零，前一个用例的序号泄漏 → 「渲染是新在上」的 #0/#1 断言变成看执行顺序碰运气。已修：`resetForTest()` 一并 `seq = 0`；生产 `clear()` 仍刻意不回绕（序号要能看出挤掉了几轮）。

---

# 附 · 本文件的来源与边界

- 本文件由四个来源合成，**内容是 2026-10-06 的快照**：①「TalkTact 总览（新会话先读这份）」②「TalkTact 已知问题清单」③「TalkTact 0.8.10 补丁存档」④「缓做 7 设计稿」。
- **未收录**：87k 的「TalkTact 交接文档（0.8.x）」深度明细（每件事的来龙去脉、每个坑怎么踩到的）。需要时另取。
- **不含任何密钥 / 口令 / token** —— 本仓库是公开的。签名密钥备份口令只在私有文档里。
- 各版本完整改动看仓库 `CHANGELOG.md`；可行性分析看仓库 `IMPROVEMENTS.md`。
