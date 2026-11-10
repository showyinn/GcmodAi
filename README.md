# GcmodAi

> **官网：https://gcmod.online/** ｜ **下载：http://38.76.219.151/** ｜ **源码镜像：http://38.76.219.151/opensource/**

一个把「AI 写代码」做成闭环的 Android 客户端：
AI 不只会给你一段代码，它会**把文件写进真实的工作区**，然后回你一个**能打开的网址**；
有服务器或 Windows 电脑的，还能让 AI **直接在上面动手**。

基于 [rikkahub](https://github.com/rikkahub/rikkahub) 二次开发，**AGPL-3.0**。

<p>
  <img alt="android" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84">
  <img alt="kotlin" src="https://img.shields.io/badge/Kotlin-Compose-7F52FF">
  <img alt="license" src="https://img.shields.io/badge/License-AGPL--3.0-blue">
  <img alt="version" src="https://img.shields.io/badge/Version-3.9.5-orange">
</p>

> [!IMPORTANT]
> **未登录 GitHub 的访客访问本仓库会得到 404。** 这不是仓库被删，而是发布账号被 GitHub 账号级 shadow flag：
> 已登录该账号能看到，匿名访问仓库、raw 文件、个人主页全部 404（新建空公开仓库同样 404，可排除内容原因）。
> **所有源码与安装包请优先从镜像站获取：[http://38.76.219.151/opensource/](http://38.76.219.151/opensource/)**，
> 该地址匿名可访问，含 `GcmodAi-source-3.9.5.zip`、`LICENSE`、Windows 助手。
> 申诉恢复后本仓库链接才会对所有人生效。

---

## 目录

- [和上游的关系](#和上游的关系)
- [核心差异](#核心差异)
- [功能清单（按真实模块）](#功能清单按真实模块)
- [工具全表（32 个）](#工具全表32-个)
- [三种 AI 工作区](#三种-ai-工作区)
- [Windows 电脑助手](#windows-电脑助手)
- [页面地图（45 个页面）](#页面地图45-个页面)
- [管理后台 36 项配置](#管理后台-36-项配置)
- [构建](#构建)
- [发布与更新流程](#发布与更新流程)
- [安全设计](#安全设计)
- [常见问题](#常见问题)
- [版本历史](#版本历史)
- [许可与致谢](#许可与致谢)

---

## 和上游的关系

| 项 | 内容 |
|---|---|
| 上游 | [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) |
| 上游许可证 | AGPL-3.0 |
| 本项目许可证 | **AGPL-3.0**（沿用，符合协议） |
| 沿用上游能力 | 多助手 / 会话分支 / Message Transformer / MCP / Skills / Web UI / 终端模拟 / 搜索 / 语音 / 文档解析 |
| 本项目新增 | 账号体系、云工作区、代码产物托管、SSH 远程建站、电脑控制、Windows 助手、每日签到、账单明细、网页管理后台、App 内更新、多语言文案 |

---

## 核心差异

| | 普通聊天客户端 | GcmodAi |
|---|---|---|
| AI 写出代码后 | 你自己保存成文件 | **直接写进工作区** |
| 怎么看到效果 | 自己找工具打开 | **给一个网址，点开就是网站** |
| 能不能操作电脑 | 不能 | **能**：SSH 整机、Windows 助手 |
| 花了多少钱 | 不知道 | **账单明细**：模型 + token + 金额 |
| 每天有奖励 | 无 | **每日签到**领额度（与网站互通） |

---

## 功能清单（按真实模块）

### 一、账号（登录后才能进主界面）

- **注册**：仅支持 **QQ 邮箱**（`@qq.com`），需邮箱验证码
- **登录**：用户名 + 密码；token 自动续期，续期失败回登录页
- **额度**：实时余额（美元）、已用额度、请求次数
- **密钥（Tokens）**：多 API Key 管理，可切换、查看所属分组
- **分组**：显示可用模型与费率倍率
- **充值（Topup）**：跳转官网充值
- **每日签到（Checkin）**：与网站共用同一份签到记录 —— **网站签过 App 签不了，反之亦然**；额度随机
- **账单明细（Billing）**：逐笔列模型、token、扣费金额；可按"只看消费/全部"筛选
- 昵称编辑、退出登录

### 二、对话

- 会话列表、按时间分组、置顶、重命名、删除
- **历史（History）**、**收藏（Favorite）**、**全文搜索（MessageSearch）**
- 消息树分支：同节点保留多个候选回复并切换
- 重新生成、编辑重发、复制、引用、分享
- 上下文管理、token 用量显示、流式输出、可中断、失败重试
- 推理模型：思考过程折叠展示
- **AI 翻译（Translator）** 独立入口
- 输入增强：快捷短语、`/` 指令、`@` 引用

### 三、助手（Assistant）

- 多助手，独立配置：系统提示词、模型、温度、上下文大小、开场白
- 提示词注入：模式注入、世界书、快捷消息绑定
- 正则转换：输入/输出替换
- 记忆（Memory）开关与整理
- **助手级工具开关**（见下节，12 个开关）
- 助手级 MCP 服务器绑定、工作区绑定
- 助手列表、详情、统计（Stats）

### 四、模型与渠道

- provider 类型：OpenAI 兼容 / Anthropic / Google / DeepSeek / 自定义
- 多 provider 并行，每个可配多个模型
- 模型能力标记：**工具调用（TOOL）**、推理、视觉等 —— 没有 TOOL 标记的模型不会拿到工具
- 余额查询：内置模板 + 自定义 `quotaQuery`（URL / Headers / 结果路径）
- 默认模型、翻译模型、标题模型、记忆模型分别配置
- 代理：HTTP / SOCKS5，含认证
- 模型收藏、快速切换

### 五、工具系统（12 个开关 → 5 组共 32 个工具）

「助手设置 → 本地工具」里的开关：

| 开关 | SerialName | 说明 |
|---|---|---|
| 官方工具集 | `gcmodai_official` | 8 个纯计算工具，零网络零隐私 |
| 服务器工作区 | `workspace_server` | 云端 100MB，6 个 `ws_*` 工具 |
| SSH 远程服务器 | `ssh_remote` | 6 个 `ssh_*` 工具 |
| 电脑控制 | `pc_control` | SSH 整机，6 个 `pc_*` + 6 个 `wpc_*` 工具 |
| JavaScript 引擎 | `javascript_engine` | 本地执行 JS 片段 |
| 时间信息 | `time_info` | 当前时间/时区 |
| 剪贴板 | `clipboard` | 读写剪贴板 |
| TTS 朗读 | `tts` | 让 AI 朗读 |
| 询问用户 | `ask_user` | 中途向用户确认选项 |
| 屏幕使用时间 | `screen_time` | 读取使用统计 |
| 日历 | `calendar` | 查询/创建日程 |
| 图表显示 | `chart_display` | 渲染图表 |

另有非本地来源：**MCP**（远程工具，前缀 `mcp__<server>__<tool>`）、**Skills**（Agent Skills，可从 GitHub 导入 `SKILL.md`）、**外部搜索**（Exa / Tavily / Zhipu / Bing / Brave / SearXNG）。

### 六、云工作区（云端 100MB）

- 每人 **100MB** 独立空间，账号绑定，登录即用
- **闲置 24 小时自动清空**：期间任何读写/访问都会续期
- 页面：配额进度条、文件树、在线编辑、部署、删除、闲置倒计时
- 部署后**公网可访问**（含手机移动数据），链接可分享
- 危险代码静态扫描：拦截 webshell / 反弹 shell / 挖矿 / 钓鱼表单

### 七、代码产物托管（Files）

较早的一版托管：每人 100MB、保留 24 小时、支持上传任意允许类型（源码/图片/字体）。
适合"让 AI 写完把文件发过来看看"，不要求建站。

### 八、SSH 远程服务器

- 新建/编辑 SSH 工作区：主机、端口、用户名、密码或私钥、工作目录
- 首次连接确认**主机指纹**，指纹变化拒绝连接
- 支持多台服务器；凭据用 Android Keystore 加密
- 配置页内置 **8 节教程**（字段含义、查 IP、端口确认、用户名、密码与私钥、工作目录、开启 SSH、8 种报错对照），并支持**一键填入示例**

### 九、电脑控制（SSH 整机）

比工作区更放开：**任意绝对路径**、**任意目录执行命令**、系统信息。
适合装环境、配服务、跑脚本。破坏性命令统一拦截。

### 十、语音 / 媒体 / 文档

- **TTS**：系统 TTS 与多 provider，语速音色可调
- **ASR**：语音输入
- **媒体生成（ImageGen / MediaCreationSessions）**
- **文档解析**：PDF / DOCX / PPTX / EPUB
- OCR（图片转文字）
- 聊天附件管理、占用统计与清理

### 十一、终端与本地工作区

- 内嵌终端（termux terminal-emulator 派生）
- 本地工作区：PRoot Rootfs 沙箱，AI 可执行命令
- 文件编辑器、上传/下载、目录浏览
- 写文件类工具可要求**用户审批**

### 十二、Web 界面

- App 内启动内嵌 Ktor 服务器，浏览器访问同一套界面
- 可配端口、是否仅本机、访问密码

### 十三、App 内更新

- 双来源：管理后台 `latest_version` + `updates.json`，取较高版本
- **App 内下载**：带进度条、显示已下载/总大小/速度、可取消
- 下载完自动拉起安装器
- 支持强制更新；失败可"用浏览器打开下载页"

### 十四、界面

- **Material 3 Expressive**：分段 `CardGroup`、大圆角、tonal 容器
- 动态取色、多套主题（`SettingTheme`）、字体与背景
- 多语言：中 / 英 / 日 / 韩 / 德 / 俄 / 阿 / 繁
- 日志（Log）、统计（Stats）、调试页（Debug）

---

## 工具全表（32 个）

### 官方工具集 `gcmodai_official`（8）

| 工具 | 作用 |
|---|---|
| `calc_expression` | 精确数学求值（sqrt/abs/round/pow/log/sin…） |
| `text_process` | 字数统计、大小写、trim、替换、截断 |
| `date_calc` | 日期加减、区间差、格式化 |
| `hash_text` | MD5 / SHA-1 / SHA-256 / SHA-512 |
| `json_format` | JSON 格式化 / 压缩 / 校验 |
| `unit_convert` | 长度/重量/温度/存储换算 |
| `url_codec` | URL 编解码 |
| `base64_codec` | Base64 编解码 |

### 云工作区 `workspace_server`（6）

| 工具 | 作用 |
|---|---|
| `ws_list_files` | 列目录 |
| `ws_read_file` | 读文件 |
| `ws_write_file` | 写/覆盖文件 |
| `ws_delete_file` | 删除文件或目录 |
| `ws_deploy_site` | 部署并返回公网地址 |
| `ws_info` | 配额用量 |

### SSH `ssh_remote`（6）

`ssh_list_servers`、`ssh_list_files`、`ssh_read_file`、`ssh_write_file`、`ssh_run_command`、`ssh_deploy_site`

### 电脑控制 `pc_control` — SSH 整机（6）

| 工具 | 作用 |
|---|---|
| `pc_list_computers` | 列出可控制电脑 |
| `pc_system_info` | 系统/内核/用户/磁盘/内存 |
| `pc_exec` | 任意目录执行命令 |
| `pc_list_dir` | 列任意绝对路径 |
| `pc_read_file` | 读任意文件 |
| `pc_write_file` | 写任意文件 |

### 电脑控制 `pc_control` — Windows 助手（6）

| 工具 | 作用 |
|---|---|
| `wpc_list_computers` | 列出已连接电脑 |
| `wpc_info` | 主机名/系统/用户/所有 IP/盘符 |
| `wpc_exec` | 执行 cmd 命令 |
| `wpc_ls` | 列目录 |
| `wpc_read` | 读文件 |
| `wpc_write` | 写文件 |

---

## 三种 AI 工作区

聊天页抽屉 →「AI 工作区」，三选一：

| 选择 | 适合 | 说明 |
|---|---|---|
| **我的工作区** | 所有人 | 云端 100MB，登录即用，闲置 24h 清空 |
| **SSH 远程服务器** | 有自己机器 | 可**多选**多台；容量看你磁盘 |
| **不使用工作区** | 只聊天 | AI 不碰文件 |

选了 SSH 后还能额外勾选**本地工作区**（仅本机，供本地文件工具）。

---

## Windows 电脑助手

不想碰 SSH 的话走这条：电脑双击一个 exe，手机就能控制它。

1. 下载 `GcmodAi-Agent.zip` → **解压**
2. 双击 **`启动助手.bat`**
3. 窗口里出现：
   ```
   地址： http://192.168.1.10:8787
   口令： a1b2c3d4e5f6a7b8
   ```
4. 抄进 App：设置 → 工作区与建站 → Windows 电脑 → 右下角 +（可点"测试"验证）

**为什么用 bat**：Windows SmartScreen 会拦"从网上下载、无数字签名"的 exe。启动器先 `Unblock-File` 去掉"来自网络"标记，再放行防火墙，最后启动 exe，因此不弹拦截框。

**助手接口**：`GET /ping`（免口令）、`GET /info`、`GET /ls?path=`、`GET /read?path=`、`POST /exec`、`POST /write`。
**助手安全**：随机 16 位口令；破坏性命令 403；仅监听本机；关窗即断开。

---

## 页面地图（45 个页面）

| 分组 | 页面 |
|---|---|
| 启动/账号 | `Onboarding`、`Login`、`Register`、`Account`、`Tokens`、`Topup`、`Billing`、`Checkin` |
| 对话 | `History`、`Favorite`、`Translator`、`MessageSearch`、`Stats` |
| 助手 | `Assistant`、`Prompts`、`QuickMessages`、`Skills`、`Extensions` |
| 工作区/托管 | `MyWorkspace`、`WorkspaceHub`、`Files`、`Workspaces`、`SettingFiles`、`SettingWeb` |
| 电脑控制 | `PcAgents` |
| 设置 | `Setting`、`SettingProvider`、`SettingModels`、`SettingSearch`、`SettingSpeech`、`SettingMedia`、`SettingMcp`、`SettingTheme`、`SettingAbout`、`SettingPreferences*`（4 个） |
| 其他 | `ImageGen`、`MediaCreationSessions`、`Backup`、`Log`、`Debug` |

---

## 管理后台 36 项配置

访问 `http://38.76.219.151/admin`，管理员密码登录（role ≥ 10）。
配置定义在服务端，页面按 schema **动态渲染**，新增配置项无需改前端。

### 公告（3）
`announcement_enabled`、`announcement_title`、`announcement`

### 官方群与客服（7）
`official_group_name`、`official_group_number`、`official_group_desc`、`official_group_url`、`official_group_qr`、`support_contact`、`support_email`

### 版本与更新（4）
`latest_version`、`download_url`、`force_update`、`update_check_enabled`

### App 全局设置（5）
`app_name`、`app_slogan`、`welcome_message`、`startup_tip`、`about_text`

### 功能开关（6）
`checkin_enabled_hint`、`billing_enabled`、`files_hosting_enabled`、`tools_enabled`、`onboarding_enabled`、`register_enabled`

### 链接配置（4）
`doc_url`、`terms_url`、`privacy_url`、`register_url`

### 额度与计费提示（4）
`quota_per_usd`（默认 500000）、`checkin_min_hint`、`checkin_max_hint`、`register_bonus_hint`

### 代码托管限额（3）
`files_quota_mb`（100）、`files_retention_hours`（24）、`files_max_file_mb`（32）

**接口**：`GET /api/config/public`（公开只读）、`GET /api/config/all`（管理员）、`POST /api/config/set`（管理员，带审计）、`GET /api/config/audit`。

---

## 构建

环境：**JDK 21** + Android SDK。

```bash
./gradlew assembleGithubRelease   # Release APK → app/build/outputs/apk/github/release/
./gradlew assembleGithubDebug     # Debug
./gradlew test                    # 单元测试
./gradlew spotlessApply           # 代码格式
```

### 助手 exe

```bash
cd agent
pip install pyinstaller
python -m PyInstaller --noconfirm --onefile --console --name GcmodAi-Agent gcmodai_agent.py
python package_agent.py           # 打包成 zip（exe + 启动器 + 说明）
```

---

## 发布与更新流程

> 下面命令里的 `deploy/` 是维护者本地的发布脚本（含服务端地址与部署路径），**未随源码开源**；
> App 本体构建只需要 JDK 21 + Android SDK，与这些脚本无关。

更新链路是**双来源**：管理后台 `latest_version` + `updates.json`，App 取较高者。

```bash
# 1) 改版本号：app/build.gradle.kts 的 versionCode / versionName
# 2) 构建
./gradlew assembleGithubRelease
# 3) 暂存产物（重命名 + sha256）
python deploy/stage_v330.py
# 4) 生成 updates.json（以磁盘真实文件为准）
python deploy/gen_updates.py 3.9.5 deploy/changelog.txt
# 5) 上传下载站
python deploy/deploy_v2.py
# 6) 生成控制台卡片数据（合并历史）
python deploy/gen_app_info.py
# 7) 同步到下载站 + 推到控制台所在机器
python deploy/publish_app_info.py
python deploy/deploy_app_card.py
```

> ⚠️ `updates.json` 必须用脚本生成，**不要手改版本号**。
> 曾经手改导致"声称新版本、链接指向旧包"，用户点更新就 404。

### 控制台令牌页的 App 卡片

`https://gcmod.online/console/token` 顶部有一张「GcmodAi 客户端」卡片：品牌图标、版本胶囊、「最新」标记、
参数 chips、三个 ABI 的下载按钮、SHA256 一键复制、可上下滑动的更新日志时间线。
它**不是**改 SPA 源码，而是在页面里注入一个独立脚本：

| 件 | 位置 | 作用 |
|---|---|---|
| `gcmodai-app-download.js` | `/custom-ui/gcmodai-app-download.js` | 只在 `/console/token` 路由渲染卡片 |
| `app-info.json` | 站点根（同源） | 版本 / 下载地址 / SHA256 / 历史日志 |
| 一行 `<script defer>` | `smai-parity-root/smai-spa/index.html` 的 `</head>` 前 | 加载上面那个脚本 |

**安装包必须走本站 HTTPS**。`38.76.219.151` 只有 HTTP，而控制台是 HTTPS，
在 HTTPS 页面上点 `http://` 直链会被浏览器判为**不安全下载并直接拦截**
（Chrome / Edge：「无法安全下载 …apk」）。因此加了同源反代：

```
https://gcmod.online/download/<apk>   →   http://38.76.219.151/<apk>
```

配套两件事：`.apk` 在下载站声明为 `application/vnd.android.package-archive`；
反代关闭 `proxy_buffering` 并保留 `Range`，断点续传正常（206）。实测公网完整下载
19,120,042 字节、SHA256 与页面标注一致、约 2.2 MB/s。

设计约束（保证「不影响原有 UI」）：

- 只在 `location.pathname` 命中 `/console/token` 时插入，其它路由不产生任何 DOM；
- **按钮直接复用站点自己的按钮类**：卡片根节点带 `token-page` 类，于是
  `.token-page .token-action-button [.token-action-button--primary]` 这套作用域规则与
  `--token-*` 变量一并生效 —— 与页面上的「添加」按钮同一套高度 36px、圆角 8px、字号 12px、图标 14px；
- 强调色全部取站点令牌（该站 `--primary: 0 0% 9%` 是黑白主题，所以卡片也是黑/白，不掺品牌色）；
- **整张卡片可收起**：右上角「收起 / 展开」按钮，整行标题也可点；收起后只剩一行，状态写 `localStorage`；
- 卡片 `position: relative` 常驻文档流，**不悬浮、不固定、不遮挡**下面的令牌表格；
- **更新日志不截断**：每个版本是独立可折叠条目，默认只展开最新版；日志区限高
  `min(56vh, 420px)` 独立滚动，常显细滚动条；底部提示随内容滚动（不再是 `sticky`，不会压住最后一行）；
  展开时把内层 `scrollTop` 归零（否则浏览器焦点滚动会把内容顶到中间，看起来像被截）；
- 数据优先读同源 `app-info.json?t=<时间戳>`（绕开 CDN 边缘缓存），失败用脚本内置兜底值，**永不阻塞页面**；
- 被 React 重新渲染带走时由 `MutationObserver` 自动补回；
- 改动前自动备份 `index.html.bak-appcard-<时间戳>`，回滚只需还原该文件。

本地回归（无需登录线上站，用 Edge headless 跑真实 DOM；harness 直接引入站点真实 CSS，截图即所见）：

```bash
python deploy/fetch_site_css.py   # 抓站点 CSS 到 harness，保证还原度
python deploy/test_app_card.py    # 有表格 / 无表格 / 非令牌页 / 折叠交互 四个场景
python deploy/shot_app_card.py    # 出亮色 / 暗色 / 日志展开 / 折叠 四张截图
```

---

## 安全设计

| 场景 | 措施 |
|---|---|
| 云工作区隔离 | 每用户独立目录（`sha256(用户名)[:16]`）；`realpath` 前缀校验；拒绝 `..`、绝对路径、符号链接、空字节 |
| 危险代码扫描 | 拦截 webshell / 反弹 shell / 挖矿 / 钓鱼表单；扩展名白名单；二进制魔数检测 |
| 部署页面隔离 | HTML 下发带 `Content-Security-Policy: sandbox`，读不到主站 cookie |
| 配额 | 100MB 总量 / 32MB 单文件 / 2000 文件，写入前检查 |
| SSH 主机验证 | 首次确认指纹，变化即拒绝（防中间人） |
| 远程命令 | `ShellGuard` 统一拦截不可回滚命令，SSH 与助手同一套规则 |
| 助手鉴权 | 随机口令 + Bearer 校验，仅监听本机 |
| 账号校验 | 服务端本地查库（`users.access_token`），不依赖公网网关 |
| 凭据存储 | Android Keystore 加密，DB 只存密文 |
| 配置解析 | 容错序列化，坏数据不会拖垮整份设置 |
| 管理后台 | 密码会话（30 天）/ access token 双通道；role ≥ 10；操作审计 |

---

## 常见问题

**AI 说「无法访问或修改工作区文件」**
该助手没开工作区工具。去「助手设置 → 本地工具」打开，或在聊天页「AI 工作区」选位置。

**工作区提示未登录但已登录**
3.7.0+ 已修：token 自动续期 + 服务端本地查库校验。

**部署的网站打不开**
确认选了「我的工作区」、`ws_deploy_site` 成功；闲置 24 小时会被清空。

**Windows 助手双击没反应**
用 `启动助手.bat`，并确认已解压。窗口会打印 `[1/3][2/3][3/3]` 状态。

**手机连不上助手**
同一 Wi-Fi；首次运行点"允许访问网络"；手动放行：
```
netsh advfirewall firewall add rule name="GcmodAi Agent" dir=in action=allow protocol=TCP localport=8787
```

**SSH 连不上**
配置页 8 节教程 + 8 种报错对照，照着查。

---

## 版本历史

| 版本 | 主要内容 |
|---|---|
| 3.9.5 | 关于页源码入口重排：镜像置顶、LICENSE 走公开直链；GitHub 入口标注需登录 |
| 3.9.4 | App 关于页改指新仓库；新增源码镜像入口 |
| 3.9.3 | 开源发布；SSH 页删除蓝牙/探测 UI，教程 8 节 + 一键示例 |
| 3.9.1–3.9.2 | Windows 助手 zip 分发；启动器修复 SmartScreen 与脚本卡死 |
| 3.9.0 | Windows 助手 exe + 6 个 `wpc_*` 工具 |
| 3.8.x | 工作区弹层滚动修复；AI 工作区三选一 + 多选服务器 |
| 3.7.x | token 续期修复；设置页入口合并为「工作区与建站」 |
| 3.6.x | 云工作区 100MB、危险代码扫描、24 小时保留、SSH 远程建站 |
| 3.5.x | 代码产物托管、App 内更新、账单明细 |
| 3.4.x | 每日签到、强制登录门禁、网页管理后台 |

---

## 许可与致谢

- 本项目以 **AGPL-3.0** 发布，见 [LICENSE](LICENSE)
- 原始项目：[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)，感谢原作者
- 依赖：Kotlin、Jetpack Compose、Koin、OkHttp、kotlinx.serialization、JSch、Ktor、Room、DataStore

> 二次分发请遵守 AGPL-3.0，保留上游与本项目版权，并提供完整源代码。

---

**官网 [https://gcmod.online/](https://gcmod.online/)** ｜ **下载 [http://38.76.219.151/](http://38.76.219.151/)** ｜ **源码镜像 [http://38.76.219.151/opensource/](http://38.76.219.151/opensource/)**
