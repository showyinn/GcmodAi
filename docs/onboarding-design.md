# GcmodAi 新手教程 + 强引导 —— 调研与设计说明

> 本文只做调研与设计，不含任何 Kotlin 实现代码。所有结论均给出 `文件:行` 级证据。
> 目标功能：首次启动走一遍「登录 → 建/选密钥 → 选分组 → 同步模型 → 选模型 → 发出第一条消息」的强引导；
> 视觉 100% 复用仓库现有组件，禁止引入第三方库或新视觉语言。

---

## 0. 结论速览

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 启动 Activity | `RouteActivity`（LAUNCHER，无独立 MainActivity） | `app/src/main/AndroidManifest.xml:68-79` |
| 起始路由 | **永远**是 `Screen.Chat(id = 随机/上次的 UUID)`，没有任何「首次启动」分支 | `app/src/main/java/com/gcmodai/app/RouteActivity.kt:299-310` |
| 教程完成标记落点 | SharedPreferences `gcmodai.preferences`（不是 `Settings`、不是 `UserAuthStore`） | `app/src/main/java/com/gcmodai/app/ui/hooks/SharedPreferences.kt:72-90` |
| 成熟的既有先例 | `create_new_conversation_on_start` 就是一个纯 SharedPreferences 开关，读在启动路径、写在设置页 | `RouteActivity.kt:300`、`ui/pages/setting/SettingPreferencesGeneralPage.kt:76-79` |
| 关键路径的真实闸门 | 只要有一个 `enabled && models` 非空的 provider，`currentChatModel != null`，发送就不再被拦，**账号/密钥/分组全都不是硬闸门** | `ui/pages/chat/ChatPage.kt:364-368`、`ui/components/ai/ChatInput.kt:159-163` |
| 官方通道如何生效 | 选中密钥 → `applySelectedToken()` 把 key 与模型写进固定 id 的「GcmodAi 官方服务」provider | `data/user/OfficialProviderManager.kt:27-73`、`ui/pages/account/TokenPage.kt:200-211` |

**最核心的一句话**：现在的 App 首次安装后落在空聊天页，用户不登录也能看到一个能输入、按发送却提示「请先选择模型」的界面（`ChatPage.kt:365-368`），而模型列表来自 `Settings.providers`，默认 provider 是内置的 `DEFAULT_PROVIDERS`（`data/datastore/Settings.kt:59`）。所以「强引导」要做的不是加一个新页面，而是**在真实状态机之上加一个不可绕过的闸门 + 一个纯复用组件的引导页**。

---

## 1. 首次启动流程实测

### 1.1 没有 MainActivity

`AndroidManifest.xml` 里带 `LAUNCHER` intent-filter 的只有 `.RouteActivity`：

- `app/src/main/AndroidManifest.xml:68-79`：`.RouteActivity` + `MAIN/LAUNCHER`
- 其他 Activity：`.ui.activity.SafeModeActivity`（`AndroidManifest.xml:63-67`）、`.ui.activity.ShortcutHandlerActivity`（`AndroidManifest.xml:109-122`）
- `app/src/main/java/com/gcmodai/app/ui/activity/` 下只有 `SafeModeActivity.kt` 与 `ShortcutHandlerActivity.kt`，**全仓库不存在 MainActivity**。

所以「MainActivity 入口」在本项目实际就是 **`RouteActivity.onCreate`**：

```
RouteActivity.kt:188  override fun onCreate(savedInstanceState: Bundle?)
RouteActivity.kt:190  enableEdgeToEdge()
RouteActivity.kt:192  if (CrashHandler.hasCrashed(this))  → 启动 SafeModeActivity 并 finish
RouteActivity.kt:197  if (savedInstanceState == null) handleIntent(intent)
RouteActivity.kt:200  setContent { GcmodAiTheme { ... AppRoutes() } }
```

`handleIntent`（`RouteActivity.kt:238-263`）只处理**外部 Intent**（翻译 / 媒体创作 / 分享 / PROCESS_TEXT / 带 `conversationId` 的通知点击），对应分支在 `244-259`。冷启动（`MAIN/LAUNCHER`）走 `else -> mediaCreationDestination(intent) ?: intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let { Screen.Chat(it) }`（`257-258`），两者都为 null 时**什么都不加**。

### 1.2 起始 Screen 的决定逻辑（第 299-310 行）

```kotlin
// RouteActivity.kt:299-310
val startScreen = Screen.Chat(
    id = if (readBooleanPreference("create_new_conversation_on_start", true)) {
        Uuid.random().toString()
    } else {
        readStringPreference("lastConversationId", Uuid.random().toString()) ?: Uuid.random().toString()
    }
)
val backStack = rememberNavBackStack(startScreen)
```

要点：

1. **起始路由恒为 `Screen.Chat`**（`RouteActivity.kt:678-685` 定义的 `@Serializable data class Chat`），没有「首启进引导页」这道分支。
2. 会话 id 来源二选一：
   - `create_new_conversation_on_start = true`（默认）→ 每次启动都是新 UUID；
   - 否则读 `lastConversationId`，该值由 `ChatVM` 的 init 写入（`ui/pages/chat/ChatVM.kt:100-101`：`context.writeStringPreference("lastConversationId", _conversationId.toString())`）。
3. 这个开关的 UI 在 `ui/pages/setting/SettingPreferencesGeneralPage.kt:76-79`，用 `rememberSharedPreferenceBoolean("create_new_conversation_on_start", true)`；`ChatDrawer.kt:409` 也读同一个 key 来兼容旧行为。
4. `navStack` 是 `RouteActivity.kt:169` 的字段，在 `SideEffect` 里挂上（`RouteActivity.kt:311-316`）；Compose 尚未就绪时外部 Intent 进 `pendingIntents`（`RouteActivity.kt:239-243`、`314-315`）。
5. 只有 `backStack.size == 1` 时才用淡入淡出（`RouteActivity.kt:344`），其余是滑动 + 缩放。**新增引导页如果作为起始页，第一个路由也享受这个淡入，视觉效果天然一致。**

### 1.3 「教程已完成」应该持久化在哪

本项目存在**三套互不相通的持久化**，选错会把标记和真实状态割裂：

| 存储 | 文件 | 内容 | 是否适合放教程标记 |
| --- | --- | --- | --- |
| SharedPreferences `gcmodai.preferences` | `ui/hooks/SharedPreferences.kt:72-90` | 零散 UI 开关（`create_new_conversation_on_start`、`lastConversationId`、`model_list_collapsed_providers`） | ✅ **推荐** |
| DataStore `settings`（`Settings` + `SETTINGS_FIELDS`） | `data/datastore/Settings.kt:30-91`、`SettingsFields.kt:76-176`、`PreferencesStore.kt:49` | 全部聊天/模型/UI 设置 | ⚠️ 可用但要动 3 个文件，且「改一下所有界面就重组一遍」的顾虑见 `PreferencesStore.kt:240-243` 注释 |
| DataStore `user_auth` | `data/user/UserAuthStore.kt:23-64` | `UserSession`（登录态 + 选中密钥） | ❌ 退出登录会 `clear()`（`UserAuthStore.kt:50-52`），教程标记会被一起清掉 |

**确切结论：复用 SharedPreferences，新增两个 key。**

- 读取 API（已有，直接用）：`Context.readBooleanPreference(key, defaultValue)` `ui/hooks/SharedPreferences.kt:88-90`
- 写入 API（已有，直接用）：`Context.writeBooleanPreference(key, value)` `ui/hooks/SharedPreferences.kt:82-86`
- Compose 响应式读取（已有）：`rememberSharedPreferenceBoolean(key, defaultValue)` `ui/hooks/SharedPreferences.kt:45-70`
- 底层文件名：`gcmodai.preferences`（`SharedPreferences.kt:25`、`73`、`79`、`83`、`89`）

为什么**不**放进 `Settings`：`Settings` 的每个字段都要在 `SETTINGS_FIELDS` 里登记（`SettingsFields.kt:76-176`），并且要同步改 `MISSING_KEY_DEFAULTS`（`SettingsFields.kt:183-187`）、迁移与备份（`data/sync/BackupManager.kt:52`、`177-200`）。而既有 `create_new_conversation_on_start` 这种同级别 UI 开关就没进 `Settings`，**教程标记与它同级，应当同样放 SharedPreferences**，改动面最小且不触碰备份/迁移链路。

`DefaultSettings.kt` 的实际情况（避免误解）：该文件里并没有 `Settings` 的默认值，只有 `DEFAULT_ASSISTANT_ID`（`DefaultSettings.kt:10`）、`DEFAULT_ASSISTANTS`（`11-36`）、`DEFAULT_SYSTEM_TTS_ID`（`38`）、`DEFAULT_TTS_PROVIDERS`（`39-51`）、`DEFAULT_MODE_INJECTIONS`（`55`）。
`Settings` 的默认值在 **`data/datastore/Settings.kt:30-91`**（`data class Settings(` 在 `:30`，各字段默认值 `:32-85`），key 缺失时的默认值在 `SettingsFields.kt:183-187`。**本方案不修改这几个文件。**

---

## 2. 关键路径拆解：从「刚装好 App」到「成功发出一条消息」

真实链路（含状态与判定条件）：

### 步骤 0：冷启动落地页

- 路由：`Screen.Chat(id)`（`RouteActivity.kt:299-310`、entry 在 `RouteActivity.kt:359-369`）
- 入口：`RouteActivity.onCreate` → `AppRoutes()`（`RouteActivity.kt:188-224`、`280-310`）
- 完成判定：**无需判定**，这是落点。

### 步骤 1：登录 GcmodAi 账号

- 路由：`Screen.Login`（`RouteActivity.kt:738-739`；entry `RouteActivity.kt:508-510`）
- 注册：`Screen.Register`（`RouteActivity.kt:742-743`；entry `RouteActivity.kt:512-514`）
- 入口（三条）：
  1. 抽屉账号行：`ui/pages/chat/ChatDrawer.kt:272-280`（`if (account == null) Screen.Login else Screen.Account`）
  2. 设置页账号项：`ui/pages/setting/SettingPage.kt:179-184`（同款三元）
  3. 密钥页未登录自动跳：`ui/pages/account/TokenPage.kt:98-104`
- 页面实现：`ui/pages/account/LoginPage.kt:70-269`；提交在 `:95-103`，成功后 `popBackStack()`（`:85-91`）并 `officialProviderManager.syncModelsAndApply()`（`:88`）
- **完成判定**：`UserRepository.sessionFlow.value != null`，即 `UserSession.user.id > 0`。
  证据：`data/user/UserRepository.kt:25-26`（`sessionFlow: StateFlow<UserSession?>`，`null` 表示未登录）、`:36-57`（`login()` 成功后 `store.save(session)`）、`data/user/UserModels.kt:76-84`（`UserSession`）、`UserAuthStore.kt:35-48`（落盘 / 读回）。
- 完成后 UI 侧的即时效应：`LoginPage.kt:85-91` 的 `LaunchedEffect(session?.user?.id)`。

### 步骤 2：创建 / 选择 API 密钥

- 路由：`Screen.Tokens`（`RouteActivity.kt:745-747`；entry `RouteActivity.kt:516-518`）
- 入口：`ui/pages/account/AccountPage.kt:349-354`（`item(onClick = { navController.navigate(Screen.Tokens) })`，标题「API 密钥」）
- 页面实现：`ui/pages/account/TokenPage.kt:78-368`
- 建密钥：`TokenPage.kt:123-125`（顶栏「新建」）与 `:189-191`（空态按钮）打开 `CreateTokenDialog`（`:370-475`）；落到 `repository.createToken(...)`（`UserRepository.kt:266-296`，创建后 `_tokens.value = page.items`，已刷新列表）
- 选密钥：`TokenPage.kt:200-211` 的 `item(onClick = ...)` → `repository.selectToken(token.id)` → 成功后 `officialProviderManager.applySelectedToken()`
- **完成判定**：`session.selectedTokenId != 0L` **且** `session.selectedTokenKey` 非空。
  证据：`UserRepository.kt:327-353`（`selectToken` 里 `fullKey.isNullOrBlank()` 直接抛「获取密钥内容失败」，成功后 `store.save(updated)` 写入 `selectedTokenId/selectedTokenKey/selectedTokenGroup/selectedTokenName`）；字段定义 `UserModels.kt:83-90`；判定用 `UserRepository.kt:356-359` 的 `selectedApiKey(session)`（`selectedTokenKey` 为空返回 null）。
- 删除时的回退（强引导必须处理）：`UserRepository.kt:299-321`，删掉当前选中的密钥会把四个字段清空（`:306-310`）。

### 步骤 3：选分组

- 位置：**没有独立路由**，分组在两个地方操作，都在 `Screen.Tokens` 内：
  1. 创建密钥时在对话框里选：`TokenPage.kt:400-418`（`FilterChip`，含 `跟随账号` 与各分组 `×倍率`）
  2. 创建后更换：`TokenPage.kt:257-259`（行内「分组」按钮）→ `ChangeGroupDialog`（`TokenPage.kt:481-550`）→ `repository.changeTokenGroup(...)`
- 数据来源：`UserRepository.groups`（`UserRepository.kt:175-177`，`StateFlow<Map<String, GroupInfo>>`），由 `refreshTokens()` 里的 `api.availableGroups(...)` 填充（`UserRepository.kt:191`），接口是 `/api/user/self/groups`（`data/user/UserApi.kt:322-324`）
- **完成判定**：`groups` 非空（能选）；「已选分组」判定为 `session.selectedTokenGroup` 非空，或按业务口径接受空串（= 跟随账号分组，见 `TokenPage.kt:243`「跟随用户分组」与 `:501` 的 `"" to GroupInfo(desc = "跟随账号分组", ratio = 1.0)`）。
- **设计取舍（重要）**：空串在业务上是**合法**取值（跟随账号），因此「选分组」这一步**不能**用 `selectedTokenGroup != ""` 当硬条件，否则永远卡住。建议该步判定为「`groups` 已成功拉取过」+「用户点过一次分组选项（含「跟随账号」）」，用一次性标记 `onboarding_group_confirmed` 记录点击行为，而不是用字符串非空判断。理由链：`UserRepository.kt:191`（`runCatching` 吞异常，失败时 `groups` 保持旧值，可能为空）、`UserRepository.kt:207-215`（`refreshGroups()` 失败也静默返回旧值）。

### 步骤 4：官方通道同步模型

- 触发点（三个，全部是既有代码，不需要新写）：
  1. `TokenPage.kt:200-211`：选中密钥成功后 `officialProviderManager.applySelectedToken()`
  2. `LoginPage.kt:85-91`：登录成功后 `officialProviderManager.syncModelsAndApply()`
  3. `AccountPage.kt:91-98`：进入账号页 `LaunchedEffect(user?.id)` → `refreshProfile()` + `syncModelsAndApply()`
- 同步实现：`data/user/OfficialProviderManager.kt:76-79`（`syncModelsAndApply` = `refreshTokens()` + `applySelectedToken()`）、`:41-73`（`applySelectedToken` 把 `models.map { Model(modelId=..., type=CHAT) }` 写进固定 id 的官方 provider）

  ```kotlin
  // OfficialProviderManager.kt:48-72（节选）
  settingsStore.update { settings ->
      val updated = ProviderSetting.OpenAI(
          id = officialProviderId,                 // :27 固定 UUID
          enabled = apiKey != null,                // :63 ← 没有 key 就禁用
          name = OFFICIAL_PROVIDER_NAME,           // :82 "GcmodAi 官方服务"
          models = modelList,
          apiKey = apiKey ?: existing?.apiKey.orEmpty(),
          baseUrl = officialBaseUrl,               // :30 https://gcmod.online/v1
          builtIn = false,
      )
      val others = settings.providers.filterNot { it.id == officialProviderId }
      settings.copy(providers = others + updated)  // :71
  }
  ```
- **完成判定**：`UserRepository.models.value.isNotEmpty()`（`UserRepository.kt:179-181`，由 `:192` 的 `api.userModels(...)` 填充，接口 `/api/user/models` 见 `UserApi.kt:344-346`）**且** 官方 provider 的 `models` 非空。
  证据：`OfficialProviderManager.kt:44-47` 明确写了「没有拉过模型列表就直接用上次缓存的供应商模型」，所以只看 `repository.models` 不够，还要看 provider 侧（`:50-52` 的 `existing?.models ?: emptyList()`）。
- 注意 `applySelectedToken()` 的 `enabled = apiKey != null`（`:63`）：**没选中密钥时官方 provider 是 disabled 的**，此时 `ModelListState.filteredProviders` 会把它过滤掉（`ui/components/ai/ModelList.kt:122-125` 里 `provider.enabled && ...`），模型列表里看不到官方模型 —— 这正是「有密钥才能选到官方模型」这条链的物理原因。

### 步骤 5：选模型

- 入口（聊天页内，无需跳页）：`ui/pages/chat/ChatPage.kt:397`（`onUpdateChatModel = vm::setChatModel`）→ 传给 `ChatInput`（`ChatPage.kt:345-402`）
- 组件链：`ChatInput` 里 `rememberModelListState(modelId = chatModel?.id, providers = settings.providers, type = CHAT)`（`ui/components/ai/ChatInput.kt:159-163`）→ 左下角 `ModelSelectorButton(onlyIcon = true)`（`ChatInput.kt:291-295`）→ `ModelListSheet`（`ChatInput.kt:367-370`）→ 选择回调 `vm::setChatModel`
- 落地：`ui/pages/chat/ChatVM.kt:194-198` → `chatService.updateChatAssistant(...) { it.copy(chatModelId = model.id) }`，即写到**会话/助手级**的 `chatModelId`
- **完成判定**：`ChatVM.currentChatModel.value != null`。
  证据：`ChatVM.kt:126-129`（`currentChatModel` 来自 `settings.getChatModelOf(conversation)`）、`data/model/ConversationConfig.kt:112-120`（`getChatModelOf` 用 `findModelById(...)`，找不到就返回 null）、`data/datastore/SettingsExt.kt:18-34`（`findModelById` 在所有 provider 的 models 里查 UUID）、`ui/components/ai/ModelList.kt:119-120`（`currentModel` 同样是 `providers.findModelById(modelId)`）。
- 补充入口（设置页，非聊天页）：`Screen.SettingModels`（`RouteActivity.kt:795-796`；entry `:496-498`）→ `ui/pages/setting/SettingModelPage.kt:128-132` 的 `rememberModelListState(modelId = settings.chatModelId, ...)`，写回在 `SettingModelPage.kt:307`。

### 步骤 6：发消息（终点）

- 入口：`ChatPage.kt:364-368`

  ```kotlin
  onSendClick = {
      if (currentChatModel == null) {
          toaster.show("请先选择模型", type = ToastType.Error)   // ChatPage.kt:365-368
          return@ChatInput
      }
      ...
      vm.handleMessageSend(inputState.getContents())            // ChatPage.kt:375
  ```
- 实际发送：`ChatVM.kt:235-240` → `chatService.sendMessage(_conversationId, content, answer)`
- **「真正发出去」的完成判定**：不要在 UI 层猜测。最稳的两条既有信号：
  1. **会话落库即视为开始**：`ConversationSession.flush()` 在「从未落库 + 标题为空 + `messageNodes` 为空」时不写库，一旦有内容就写（`service/ConversationSession.kt:114-129`，判定在 `:120-122`）；落库走 `ChatService` 的 `prepareWrite()`（`service/ChatService.kt:126-143`，注释 `:128`「会话落库即视为开始」）。→ 可用 `Conversation.messageNodes.isNotEmpty()` 作为「发出过消息」的判定，信号来源 `ChatVM.kt:67`（`conversation: StateFlow<Conversation>`）与 `data/model/Conversation.kt:51-54`（`currentMessages`）。
  2. **助手真的回了**：`ChatVM.kt:150`（`generationDoneFlow: SharedFlow<Uuid>`）—— 若要区分「发出去了」与「成功收到回复」，用这个；错误则进 `ChatVM.kt:132` 的 `errors`（`ChatService.kt:159-169` 的 `addError`）。

- 另一个终点（可选）：充值 / 兑换。路由 `Screen.Topup`（`RouteActivity.kt:750-751`；entry `:520-522`），入口 `AccountPage.kt:355-360`；页面 `ui/pages/account/TopupPage.kt:72-389`；接口 `UserRepository.kt:362-375`（兑换码 `/api/user/topup`）、`:383-396`（在线支付 `/api/user/pay`），`UserApi.kt:384-387`、`:413-415`。**这一步不是「能发消息」的必要条件**，只作为额度不足时的补救入口。

### 2.7 关键路径的「隐藏捷径」与它带来的强引导难点

必须写清楚，否则实现者会误以为加了闸门就够：

- 默认就存在**内置 provider**：`Settings.providers: List<ProviderSetting> = DEFAULT_PROVIDERS`（`data/datastore/Settings.kt:59`），且 `normalized()` 每次都会把内置 provider 补回来（`SettingsNormalization.kt:8-24`，`:9-14` 是「缺失就补」）。
- 因此 `currentChatModel` 可能**在用户完全没登录时就不为 null**，发送闸门（`ChatPage.kt:365`）就不拦了。
- 结论：**强引导的闸门必须自己定义「本 App 的可用状态」，而不能只依赖 `currentChatModel != null`。** 本项目的准确定义应当是「官方 provider 可用」，即 `OfficialProviderManager.currentProvider(settings)` 非空且 `enabled == true` 且 `models` 非空（`OfficialProviderManager.kt:33-35`、`:61-69`）。这是整个强引导方案的判定基石。

---

## 3. 强引导方案

### 3.1 设计原则

1. **不新增视觉语言**：整页只用 `CardGroup` / `formItem` / `item` / `switchItem` / `CardGroupIcon` / `CardGroupValue` / `ConnectedChoiceButtons` / `ConnectedChoiceLabel` / `Scaffold` + `LargeFlexibleTopAppBar` + `BackButton`（全部已在 `ui/components/ui/` 与 `ui/components/nav/`）。
2. **闸门放在真实状态之上**，而不是自造一套「教程进度」。教程状态 = 真实账号状态 + 少量「已确认」标记。
3. **可绕过项一律不做成阻塞**：能跳过的是「解释性页面」，不能跳过的只有「没有它就没法发消息」的四件事（登录、密钥、分组确认、模型）。
4. **退出/重开不丢进度**：判定全部来自持久化状态（`UserSession` + SharedPreferences），不依赖内存。

### 3.2 闸门（Gate）定义

新增一个纯函数式的判定（无副作用、无 IO），命名为 `OnboardingState.evaluate(...)`，依据以下**已有** StateFlow：

| 输入 | 来源 |
| --- | --- |
| `session: UserSession?` | `UserRepository.kt:25-26` |
| `models: List<String>` | `UserRepository.kt:179-181` |
| `groups: Map<String, GroupInfo>` | `UserRepository.kt:175-177` |
| `settings: Settings` | `SettingsStore.settingsFlow`（`PreferencesStore.kt:212`） |
| `official = settings.providers.firstOrNull { it.id == OfficialProviderManager().officialProviderId }` | `OfficialProviderManager.kt:27`、`:33-35` |

判定顺序（短路）：

```
1. session == null                                            → Step.Login      (阻塞)
2. session.selectedTokenId == 0L || selectedTokenKey.isBlank() → Step.Token     (阻塞)
3. groups.isNotEmpty() == false                               → Step.Sync      (阻塞，需先同步)
   || onboarding_group_confirmed != true                      → Step.Group     (阻塞)
4. official == null || !official.enabled || official.models.isEmpty() → Step.Sync (阻塞)
5. currentChatModel == null                                   → Step.Model     (阻塞)
6. 全部通过                                                    → 放行
```

第 5 步的 `currentChatModel` 取法照抄聊天页：`settings.getChatModelOf(conversation)`（`ConversationConfig.kt:112-120`），但引导页拿不到会话对象时，用 `settings.findModelById(settings.chatModelId)`（`SettingsExt.kt:18-22`）等价替代 —— 因为未落库的新会话就是走这一条（`ConversationConfig.kt:113` 的 `?: chatModelId`）。

### 3.3 四种真实状态下分别显示什么

这一节是实现的「文案表」，每一种状态都由 `evaluate` 的一个分支驱动。

#### 状态 A：未登录（`session == null`）

- 标题：`新手引导 · 1/4`
- `CardGroup(title = { Text("第一步：登录 GcmodAi 账号") })`
  - `formItem(headlineContent = { Text("为什么要登录") }, leadingContent = { CardGroupIcon(HugeIcons.UserCircle) })` 内放 `Text`：说明「模型调用走 gcmod.online 网关，需要账号额度」
  - `item(onClick = { navigate(Screen.Login) }, headlineContent = { Text("登录账号") }, supportingContent = { Text("使用 gcmod.online 账号，登录后可查看额度") }, leadingContent = { CardGroupIcon(HugeIcons.Key01) })`
  - `item(onClick = { navigate(Screen.Register) }, headlineContent = { Text("还没有账号？使用 QQ 邮箱注册") }, leadingContent = { CardGroupIcon(HugeIcons.UserCircle) })`
- 文案来源可直接照抄 `LoginPage.kt:146-155`（「登录 GcmodAi」「使用 gcmod.online 账号直接登录」）与 `SettingPage.kt:196-203`（未登录态支持文案），保持语感一致。
- 「下一步」按钮 `enabled = false`，`supportingContent` 提示「完成登录后自动继续」。

#### 状态 B：已登录、无密钥（`selectedTokenId == 0L` 或 `selectedTokenKey` 空）

- 标题：`新手引导 · 2/4`；`CardGroupValue(text = session.user.username)` 显示在 `CardGroup` 标题行右侧（复用 `AccountPage.kt:283` 的用法，或直接在 `item.trailingContent` 里用 `CardGroupValue`）
- 关键动作：`item(onClick = { navigate(Screen.Tokens) }, headlineContent = { Text("创建 / 选择 API 密钥") }, supportingContent = { Text("创建后自动选中，模型调用真实计费") }, leadingContent = { CardGroupIcon(HugeIcons.Key01) })`
- 若 `groups.isEmpty()`，额外显示一条 `Surface(color = secondaryContainer)` 提示：「正在同步分组…」，并给一个重试 `item(onClick = { repository.refreshTokens() })`，措辞照抄 `TokenPage.kt:151-162` 的说明条样式（`Surface(shape = MaterialTheme.shapes.large)`）。
- 完成后自动进入状态 C（因为 `TokenPage` 选中后就会 `refreshTokens` + `applySelectedToken`）。

#### 状态 C：有密钥、无分组 / 未确认分组（`groups.isEmpty()` 或 `onboarding_group_confirmed != true`）

- 标题：`新手引导 · 3/4`
- `groups.isEmpty()` 时：
  - `CardGroup(title = { Text("同步模型与分组") })` 内 `formItem { Row { CircularProgressIndicator(18.dp); Text("正在从官网同步…") } }`（照抄 `TopupPage.kt:169-178` 的加载行写法）
  - 失败/空时用 `item(onClick = { scope.launch { repository.refreshTokens() } }, headlineContent = { Text("重新同步") })`
- `groups` 非空时用 `ConnectedChoiceButtons` 让用户点一下即完成「确认」：
  - `formItem(headlineContent = { Text("选择分组") }, supportingContent = { Text("分组决定计费倍率与可调模型，随时可换") })` 内放 `ConnectedChoiceButtons(options = 分组名列表, selected = current, onSelect = { ... writeBooleanPreference("onboarding_group_confirmed", true) })`，标签用 `ConnectedChoiceLabel(text = "name ×ratio", selected = ...)`
  - 选项集合 = `listOf("") + groups.keys`（与 `TokenPage.kt:500-503` 的候选构造一致），`""` 的标签为「跟随账号」（`TokenPage.kt:506`）
  - 选中后异步调 `repository.changeTokenGroup(token, group)`（`UserRepository.kt:218-237`）—— **不阻塞 UI**，失败用 `Surface(errorContainer)` 报错，样式照抄 `TokenPage.kt:285-300`
- 这一步**只有 2~4 个选项**，正是 `ConnectedChoiceButtons` 的设计用途（`ConnectedChoiceButtons.kt:39` 注释：「选项不多（两到四个）时用来代替下拉框」）。

#### 状态 D：有分组、官方通道无模型 / 未选模型

- 标题：`新手引导 · 4/4`
- 模型未同步（`official.models.isEmpty()`）：
  - `item(onClick = { scope.launch { officialProviderManager.syncModelsAndApply() } }, headlineContent = { Text("同步官方模型") }, supportingContent = { Text("从 gcmod.online 拉取当前分组可用模型") }, leadingContent = { CardGroupIcon(HugeIcons.Brain02) })`
- 模型已同步但未选：
  - `formItem(headlineContent = { Text("选择聊天模型") })` 内放与「模型设置页」同款的模型选择行。
    最省事的复用方式：调用 `ModelSelector`（`ui/components/ai/ModelList.kt:167-195`），它是**公开的 Composable**（`ui/components/ai/ModelList.kt:167-195`，无 `internal` 修饰），内部就是 `ModelSelectorButton` + `ModelListSheet`，并会自动带出底部弹层（`ModelList.kt:339-366`）。回调 `onSelect = { officialProviderManager.applySelectedToken(); settingsStore.update { s -> s.copy(chatModelId = it.id) } }`。
  - 或者照抄设置页的做法：`CardGroupScope.modelItem(...)` 是 `SettingModelPage.kt:328-350` 的 **private** 扩展函数，不能跨文件复用；等价写法是自己写一个 `formItem { ModelField(model = ..., onClick = { state.open() }) }`（`ModelField` 是 `internal`，在 `ModelList.kt:269`）＋ `ModelListSheet(state = state, onSelect = ...)`（`ModelList.kt:320-366`，`public`）。**推荐直接复用 `ModelSelector`，改动最小。**
- 全部满足后显示完成态：
  - `CardGroup(title = { Text("全部就绪") })` + `item(headlineContent = { Text("开始聊天") }, trailingContent = { CardGroupValue(text = "→") })`，`onClick` 写 `onboarding_completed = true` 并 `clearAndNavigate(Screen.Chat(Uuid.random()))`（`ui/context/NavContext.kt:28-31` 的 `clearAndNavigate`，语义与 `utils/ChatUtil.kt:21-28` 的 `navigateToChatPage` 一致）。

### 3.4 「不能绕过」的落地手段

强引导的刚性来自三个层面，缺一不可：

1. **入口层**：起始路由不再无条件 `Screen.Chat`。未完成时必须进引导页/引导路由。
2. **路由层**：在 `RouteActivity` 里对「已经被引导劫持」的期间限制返回。既有 `onBack` 是 `{ if (backStack.size > 1) backStack.removeLastOrNull() }`（`RouteActivity.kt:342`），引导期间若只保留一个引导路由（`size == 1`），返回键就自然无效（`NavContext.kt:33-35` 的 `popBackStack` 同样要求 `size > 1`）。
3. **UI 层**：引导页的「下一步」用 `enabled` 控制，而不是把按钮藏起来 —— 保留可点感的破坏（灰按钮 + supportingContent 说明原因），符合 M3 Expressive 的一贯做法（参考 `LoginPage.kt:93` 的 `canSubmit`、`TokenPage.kt:119/123` 的 `enabled = !busy && session != null`）。

**注意不要过度封锁**：设置页、聊天页本身不应该被破坏。本方案建议**只在「首次未完成」时把引导设为起始页**，而不是全局拦截导航。理由是既有页面大量依赖设置页作为唯一出口（如 `SettingPage.kt:161` 的 `ProviderConfigWarningCard` 跳 provider 页），全局拦截会制造死路。

---

## 4. 状态机

### 4.1 步骤列表与完成条件

| # | 步骤 | 阻塞 | 完成条件（判定表达式） | 证据 |
| --- | --- | --- | --- | --- |
| 0 | `Intro` 说明页 | 否（可跳过） | `onboarding_intro_seen == true` | 新 key |
| 1 | `Login` | 是 | `sessionFlow.value != null` | `UserRepository.kt:25-26`、`:36-57` |
| 2 | `Token` | 是 | `selectedTokenId != 0L && selectedTokenKey.isNotBlank()` | `UserModels.kt:83-86`、`UserRepository.kt:327-353` |
| 3 | `Group` | 是 | `groups.isNotEmpty() && onboarding_group_confirmed == true` | `UserRepository.kt:175-177`、`:191` |
| 4 | `Sync` | 是 | `official != null && official.enabled && official.models.isNotEmpty()` | `OfficialProviderManager.kt:33-35`、`:41-73` |
| 5 | `Model` | 是 | `settings.findModelById(settings.chatModelId) != null` | `SettingsExt.kt:18-22`、《ConversationConfig.kt:112-120》 |
| 6 | `Done` | — | `onboarding_completed == true`（或直接放行，不落标记） | 新 key |

`official` 的取法：`settings.providers.firstOrNull { it.id == officialProviderId } as? ProviderSetting.OpenAI`，直接照抄 `OfficialProviderManager.kt:33-35` 的 `currentProvider(settings)`（可把该函数从 `private` 语义上提升为可复用，或让引导 VM 直接注入 `OfficialProviderManager` 并调用它 —— 它已经是 `public fun`，`OfficialProviderManager.kt:33`）。

### 4.2 持久化时机

| 标记 | key | 写入时机 | 写入 API |
| --- | --- | --- | --- |
| 已看过说明页 | `onboarding_intro_seen: Boolean` | 用户点击「开始设置」/「跳过」时 | `writeBooleanPreference`（`SharedPreferences.kt:82-86`） |
| 分组已确认 | `onboarding_group_confirmed: Boolean` | `ConnectedChoiceButtons` 的 `onSelect` 回调内 | 同上 |
| 教程已完成 | `onboarding_completed: Boolean` | 状态机进入 `Done` 且用户点「开始聊天」时 | 同上 |

**必须由真实状态推导的（不落标记）**：步骤 1/2/4/5 全部来自 `UserRepository` 与 `Settings` 的既有 StateFlow。这样做的好处是「退出登录后引导自动回到第 1 步」，无需任何同步逻辑。

> 反向提醒：**不要**把 `onboarding_completed` 放进 `UserSession`，因为 `logout()` 会 `store.clear()`（`UserRepository.kt:59-61` → `UserAuthStore.kt:50-52`），会导致「重登后被要求重看教程」。

### 4.3 跳过与重试语义

- **跳过（Skip）**：只允许在步骤 0（说明页）。理由：步骤 1~5 都是「没有它发不出消息」的硬条件，允许跳过等于破坏强引导的定义。
- **重试（Retry）**：步骤 3/4 涉及网络（`refreshTokens` 里 `runCatching` 吞异常，`UserRepository.kt:191-192`），必须提供显式重试入口。重试动作统一为：
  - 分组/模型：`repository.refreshTokens()`
  - 官方通道落盘：`officialProviderManager.syncModelsAndApply()`（`OfficialProviderManager.kt:76-79`）
  - 并且两者在 `busy` 为真时要禁用按钮（`UserRepository.kt:34`、`:186`、`:220`、`:253`、`:269` 都有 `_busy` 保护，`busy` 已暴露为 `StateFlow<Boolean>`）
- **回退（Back）**：引导页内不提供「上一步」——因为每一步的真实状态都是由外部页面完成的，回退只会造成状态与 UI 不一致。用户在 `Login`/`Tokens` 页用 `BackButton`（`ui/components/nav/BackButton.kt:16-31`）返回后，引导页会**自动**重新求值并停在同一位置（幂等）。

### 4.4 教程状态与真实账号状态的联动

| 事件 | 引导状态变化 | 机制 |
| --- | --- | --- |
| 用户在引导第 1 步完成登录 | 自动进入第 2 步 | `sessionFlow` 变更触发重组 |
| 用户中途退出登录（`AccountPage.kt:374-386` → `repository.logout()` → `UserAuthStore.clear()`） | **回到第 1 步** | `sessionFlow` 变 `null`；`selectedTokenId/Key` 随会话一起消失（`UserAuthStore.kt:50-52` 连 key 一起 `remove(SESSION_KEY)`） |
| 用户删掉当前选中的密钥（`UserRepository.kt:299-321`） | **回到第 2 步** | `:306-310` 清空 `selectedTokenId/Key/Group/Name` |
| 官方 provider 被别的路径改坏（如用户在设置里禁用/删 provider） | **回到第 4 步** | `OfficialProviderManager.kt:63` 的 `enabled = apiKey != null`；用户在设置里禁用/删 provider 会直接改 `settings.providers`（`SettingProviderPage.kt:111-115`） |
| 用户换了模型但官方 provider 仍有模型 | 停在已就绪态 | `Model` 步只看「有没有选中任意可解析的模型」 |

**联动实现建议**：不要写「事件监听 + 手动回退」。整个引导页只做一件事 —— **每次 `session`/`settings`/`groups`/`models` 变化时重新跑一遍 `evaluate()`，用它返回的步骤号渲染**。因为 `evaluate()` 的输入全是 StateFlow，退出登录这类事件会自动反映出来，天然幂等且不会出现状态漂移。

### 4.5 与启动落点的关系（关键改造点）

现在 `startScreen` 是无条件 `Screen.Chat`（`RouteActivity.kt:299-310`）。改造后：

```
未完成（!onboarding_completed 且 evaluate() != Done）  → startScreen = Screen.Onboarding
已完成                                                → 保持现状：Screen.Chat(...)
```

**重要约束**：`readBooleanPreference` / `readStringPreference` 是 `Context` 扩展（`SharedPreferences.kt:78-80`、`:88-90`），在 `AppRoutes()` 里可以直接用（现在就在用，见 `RouteActivity.kt:300-306`），但它是**同步读**且**不响应式**。`onboarding_completed` 只在「用户点开始聊天」时写一次，此后不会再变，因此同步读完全够用；如果需要响应式（例如用户从设置页重置教程），只需在 `RouteActivity` 里补一个 `rememberSharedPreferenceBoolean("onboarding_completed", false)`（`SharedPreferences.kt:45-70`）。

另外：**`conversationIntent` 与外部 Intent 路径要保留原样**。`RouteActivity.kt:159-163` 的 `conversationIntent` 带 `EXTRA_CONVERSATION_ID`，`handleIntent`（`:238-263`）会把它压到栈顶。若此时引导未完成，会出现「引导页 + 聊天页」两层栈 —— 这是**可接受**的（用户从通知点回来时本来就应该看到那条会话）。建议把引导拦截限定在「冷启动无外部 Intent」的分支，即给 `startScreen` 加条件，而不是改 `handleIntent`。

---

## 5. 视觉实现约束（全部复用现有组件）

### 5.1 必须使用的组件与用法依据

| 组件 | 定义位置 | 引导页用法 |
| --- | --- | --- |
| `MaterialExpressiveTheme` | 由 `GcmodAiTheme` 包装，`ui/theme/Theme.kt:107-112` | 无需改动；引导页天然在此主题内（`RouteActivity.kt:200-222` 的 `setContent { GcmodAiTheme { ... } }`） |
| `CardGroup` | `ui/components/ui/CardGroup.kt:323-355` | 每组步骤一个 `CardGroup`，`title` 用 `Text("第 N 步：…")`；标题自动走 `titleSmallEmphasized` + primary 色（`CardGroup.kt:334-340`） |
| `CardGroupScope.item` | `CardGroup.kt:71-81`、`:97-119` | 可点击的步骤行 / 跳转入口 |
| `CardGroupScope.formItem` | `CardGroup.kt:84-91`、`:121-142` | 承载说明文字、加载指示、`ConnectedChoiceButtons`、模型选择行 |
| `CardGroupScope.switchItem` | `CardGroup.kt:146-165` | 若需要「以后不再显示」这类开关 |
| `CardGroupIcon` | `CardGroup.kt:176-203` | 每个 item 的 `leadingContent`，配 `HugeIcons.*`（与 `AccountPage.kt:48-55`、`TokenPage.kt:50-55` 同一套图标源） |
| `CardGroupValue` | `CardGroup.kt:207-225` | 状态徽标（如「已完成」「使用中」，用法同 `AccountPage.kt:242`、`TokenPage.kt:234`） |
| `ConnectedChoiceButtons` | `ui/components/ui/ConnectedChoiceButtons.kt:41-62` | 步骤 3 选分组（2~4 个选项，正是其设计场景，见 `:39` 注释） |
| `ConnectedChoiceLabel` | `ConnectedChoiceButtons.kt:134-162` | 上面的按钮文案，自动 11~14sp 自适应（`:158`） |
| `ConnectedMultiChoiceButtons` | `ConnectedChoiceButtons.kt:66-87` | 备选；本引导流程没有多选场景，除非要做「常用模型收藏」式的多选，否则**不要硬用** |
| `Scaffold` + `LargeFlexibleTopAppBar` + `CustomColors.topBarColors` | 参考 `SettingPreferencesGeneralPage.kt:54-69`、`SettingPage.kt:137-151` | 引导页外壳，保证顶栏配色与其余页面完全一致 |
| `BackButton` | `ui/components/nav/BackButton.kt:16-31` | 仅在允许返回的步骤显示；`navigationIcon` 位置 |
| `Surface(color = errorContainer/secondaryContainer)` 提示条 | 参考 `TokenPage.kt:151-162`（说明条，`secondaryContainer`）、`TokenPage.kt:285-300`（错误条，`errorContainer`）、`LoginPage.kt:215-228` | 引导页的错误与说明提示，**不要自造 Alert 卡片** |
| `Button`（大圆角填充按钮） | 参考 `LoginPage.kt:230-247`（`MaterialTheme.shapes.large` + `height(52.dp)`） | 「下一步 / 开始聊天」主按钮 |
| `CircularProgressIndicator` | 参考 `TopupPage.kt:175`、`LoginPage.kt:239-243` | 同步中态 |
| `ModelSelector` / `ModelListSheet` / `ModelField` | `ui/components/ai/ModelList.kt:167-195`、`:320-366`、`:269-317` | 步骤 4 选模型，直接复用聊天页同款弹层 |
| `ModalBottomSheet`（由 `ModelListSheet` 内部提供） | `ModelList.kt:339-366` | 不建议自己写弹层 |
| `AlertDialog` | 参考 `AccountPage.kt:404-426`、`TokenPage.kt:344-367` | 只用于确认类交互（如「退出教程」） |

### 5.2 应该模仿的既有页面

1. **`ui/pages/account/TokenPage.kt`** —— 最接近「引导 + 状态 + 空态 + 加载」的完整样板：
   - 未登录占位与自动跳转：`:132-143`
   - 空态（图标 + 标题 + 说明 + 主按钮）：`:165-194`
   - 说明条：`:150-163`
   - 错误条：`:285-300`
   - 顶部操作（刷新 / 新建）：`:113-127`
2. **`ui/pages/account/AccountPage.kt`** —— 分组信息的展示范式：
   - `CardGroup(title = { Text("额度") })` + `formItem` + `CardGroupValue`：`:236-276`
   - 头像圆底 + 大标题的「品牌区」：`:188-233`（引导页首屏可直接照抄这个结构）
   - `CardGroup { item(onClick = …) }` 的跳转列表：`:347-388`
3. **`ui/pages/setting/SettingProviderPage.kt`** —— **不建议**作为视觉模仿对象：它用的是 `Card` + `RoundedCornerShape` + `TextField` 的旧写法（`SettingProviderPage.kt:23-44` 的 import、`:111-115` 的 reorderable 用法），与 AGENTS.md 要求的 `CardGroup` 分段组不一致（`AGENTS.md:17-19`）。**只参考它的数据流（`vm.settings` / `vm.updateSettings`，`:103-115`），不参考它的视觉。**
4. **`ui/pages/setting/SettingPreferencesGeneralPage.kt`** —— `switchItem` / `formItem` + `ConnectedChoiceButtons` 的规范用法：`:75-94`、`:130-147`。
5. **`ui/pages/account/LoginPage.kt`** —— 品牌区（`:126-156`）、`CardGroup` 表单（`:158-213`）、错误条（`:215-228`）、主按钮（`:230-247`）。

### 5.3 明确禁止

- ❌ 引入任何第三方 UI/引导库（如 `com.github.technolifestyle:...`、appintro 之类）。
- ❌ 自建「高亮 + 蒙层气泡」的 spotlight 引导。理由：仓库内没有任何现成 mask/highlight 组件（`ui/components/ui/` 全量清单见 §5.1，无相关文件），自建必然引入新视觉语言，与需求冲突。
- ❌ 新增颜色、圆角常量、字体。一律用 `MaterialTheme.colorScheme.*`、`MaterialTheme.shapes.*`、`CustomColors.topBarColors`（`ui/theme/`）与 `MaterialTheme.typography.*Emphasized` 变体。
- ❌ 自造列表项样式。所有行必须是 `CardGroup { item(...) }` 或 `CardGroup { formItem(...) }`。

### 5.4 值得一提的可复用「花活」（可选，不强制）

若希望首屏更有表现力又不引入新语言，可复用：
- `ui/components/ui/AppLogo.kt:30-39` 的 `rememberAppLogoLayers()` + `Modifier.drawAppLogo(layers)`，用法见 `SettingAboutPage.kt:90`、`:142-166`（配合 `MaterialShapes` 做形状变形，`:150`）。
- `EmojiBurstHost`（`SettingAboutPage.kt:110-116`）—— 完成态庆祝效果。**属于可选增强，若不想引入额外依赖则该功能本身就在仓库内，没有新依赖风险。**

---

## 6. 改动文件清单与并行拆分

### 6.1 新增文件

| # | 路径 | 内容 | 负责人 |
| --- | --- | --- | --- |
| N1 | `app/src/main/java/com/gcmodai/app/ui/pages/onboarding/OnboardingState.kt` | 步骤枚举 `OnboardingStep`、`OnboardingPrefs`（key 常量 + 读写封装）、纯函数 `evaluate(...)` | A |
| N2 | `app/src/main/java/com/gcmodai/app/ui/pages/onboarding/OnboardingVM.kt` | `ViewModel`，注入 `UserRepository` / `SettingsStore` / `OfficialProviderManager`，暴露 `state: StateFlow<OnboardingUiState>` + `refresh()` / `confirmGroup()` / `selectModel()` | A |
| N3 | `app/src/main/java/com/gcmodai/app/ui/pages/onboarding/OnboardingPage.kt` | 引导页 Composable，按 `step` 渲染 §3.3 四种状态 | B |
| N4 | `app/src/main/java/com/gcmodai/app/ui/pages/onboarding/OnboardingStepCard.kt` | 复用的 `CardGroup` 片段（步骤行 / 加载行 / 错误条 / 分组选择块） | B |
| N5 | `app/src/main/java/com/gcmodai/app/ui/pages/onboarding/OnboardingGate.kt` | 被 `RouteActivity` 调用的启动判定（`shouldShowOnboarding(context, ...)`） | A |

> 实现自由：若想减少文件数，可把 N4 合并进 N3；N1 与 N5 可以合并。但**不要把 N1/N2（逻辑）与 N3/N4（视觉）合到同一文件**，那会破坏下面的并行拆分。

### 6.2 需要修改的既有文件（精确到行）

| # | 文件 | 位置 | 改动 |
| --- | --- | --- | --- |
| M1 | `app/src/main/java/com/gcmodai/app/RouteActivity.kt` | `:299-310`（`startScreen` 计算） | 加分支：`if (OnboardingGate.shouldShowOnboarding(this)) Screen.Onboarding else Screen.Chat(...)` |
| M2 | 同上 | `:678-873`（`sealed interface Screen`） | 新增 `@Serializable data object Onboarding : Screen`（紧邻 `:730-751` 的 Account/Login/Tokens/Topup 注释块，便于分组） |
| M3 | 同上 | `:358-624`（`entryProvider`） | 新增 `entry<Screen.Onboarding> { OnboardingPage() }` |
| M4 | 同上 | `:113-118`（import 区） | 加 `import com.gcmodai.app.ui.pages.onboarding.OnboardingPage`（按现有分组插到 account 与 setting 之间，见 `:114-118`） |
| M5 | `app/src/main/res/values/strings.xml` | 文件末尾（当前 1792 行） | 新增引导文案。按 AGENTS.md `AGENTS.md:105`，页面级字符串用页面前缀：`onboarding_page_*` |
| M6 | `app/src/main/res/values-zh-rCN/strings.xml`（若存在） | 同上 | 中文文案（仓库文案风格见 `LoginPage.kt:146-155` 直接硬编码中文，也可先只写中文硬编码，按 `AGENTS.md:107`「用户未明确要求本地化时优先不本地化」） |
| M7 | `app/src/main/java/com/gcmodai/app/data/user/OfficialProviderManager.kt` | `:33-35`（`currentProvider`） | 无需改动（已是 public）。**仅在**引导 VM 需要直接读取时确认调用即可；如确需暴露 `officialProviderId` 给 VM，它已在 `:27` 为 public |

**不需要改动**：`Settings.kt`、`SettingsFields.kt`、`PreferencesStore.kt`、`DefaultSettings.kt`、`UserAuthStore.kt`、`UserRepository.kt`、`ChatPage.kt`、`ChatVM.kt`、`ChatInput.kt`、`TokenPage.kt`、`LoginPage.kt`。

> 这是本方案最重要的取舍：**所有判定都读既有的 `StateFlow`，不新增持久化字段到 `Settings`**，因此不触碰那个一改动就牵连备份/迁移/规整三处的链路（`SettingsFields.kt:76-204`、`SettingsNormalization.kt:4-93`、`data/sync/BackupManager.kt:177-200`）。

### 6.3 可并行、写范围不重叠的拆分

三条并行线，**写范围两两不相交**：

**A 线 —— 逻辑与状态机（纯 Kotlin，无 Compose）**
- 写范围：`app/src/main/java/com/gcmodai/app/ui/pages/onboarding/OnboardingState.kt`、`OnboardingVM.kt`、`OnboardingGate.kt`
- 交付：`evaluate()` 六分支正确；四态（未登录 / 无密钥 / 无分组 / 无模型）各有单测；`OnboardingGate.shouldShowOnboarding` 能在「已完成」时返回 false
- 阻塞依赖：无（可立即开工）
- 验收：新增 `app/src/test/java/com/gcmodai/app/ui/pages/onboarding/OnboardingStateTest.kt` 覆盖 6 个分支

**B 线 —— 视觉与页面（纯 Compose）**
- 写范围：`app/src/main/java/com/gcmodai/app/ui/pages/onboarding/OnboardingPage.kt`、`OnboardingStepCard.kt`
- 交付：`OnboardingPage(uiState: OnboardingUiState, onAction: (OnboardingAction) -> Unit)` 这样**纯展示、无业务**的签名（便于 A/B 并行：B 先按签名写 UI，A 填 VM）
- 阻塞依赖：仅依赖 A 线**定义的接口签名**（枚举名、Action 名），不依赖实现。建议 A 线先 10 分钟内把 `OnboardingState.kt` 的公开类型落盘，B 线即可开工
- 验收：四个状态的 Preview（照 `CardGroup.kt:357-395` 的 `@Preview` 写法）

**C 线 —— 接线与资源（RouteActivity + strings）**
- 写范围：`app/src/main/java/com/gcmodai/app/RouteActivity.kt`（仅 `:113-118`、`:299-310`、`:358-624`、`:678-873` 四处）、`app/src/main/res/values/strings.xml`
- 阻塞依赖：**必须等 A 线的 `OnboardingGate` 签名与 B 线的 `OnboardingPage` 签名冻结**（否则类型对不上）
- 验收：`./gradlew assembleGithubDebug`（`AGENTS.md:24`）通过；冷启动（清数据）落引导页；完成一次全流程后重启不再进引导页

**收敛顺序**：A 线先落类型 → B/C 并行 → Lead 跑 `./gradlew assembleGithubDebug` + `./gradlew test` + `./gradlew lint`（`AGENTS.md:24-28`）并审最终 diff。

**冲突提示（必须显式协调）**：C 线的 `RouteActivity.kt` 是**唯一**的既有文件改动点，且三处改动彼此相距量级很大（import 区 / `startScreen` / `entryProvider` / `Screen` 定义），如果由多人同时改同一个文件会撞车。**规定：`RouteActivity.kt` 只由 C 线一人改，A/B 线不得触碰。**

### 6.4 潜在风险清单

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 引导页与聊天页同时入栈 | 外部 Intent（`EXTRA_CONVERSATION_ID`、分享、翻译）会把目的地压到引导页之上（`RouteActivity.kt:244-263`） | 引导拦截只作用于「无外部 Intent 的冷启动」，见 §4.5 |
| `groups` 拉取失败被静默吞掉 | `UserRepository.kt:191-192`、`:207-215` 全是 `runCatching` | 步骤 3 必须提供显式重试，见 §4.3 |
| 空字符串分组的语义 | `""` = 跟随账号分组，是合法值（`TokenPage.kt:243`、`:501`） | 不用字符串非空判定，用 `onboarding_group_confirmed` 标记点击行为，见 §2 步骤 3 |
| `busy` 单例冲突 | `UserRepository._busy` 是全仓库共享的单飞标记（`UserRepository.kt:33-34`），引导页与其它页面同时发起请求会互斥 | 引导页所有网络按钮统一 `enabled = !busy` |
| 模型列表为空但 `refreshTokens` 成功 | `models` 与 `groups` 是两条独立 `runCatching`（`UserRepository.kt:191-192`），可能一个成功一个失败 | 步骤 4 的判定基于 **provider 侧** 的 `models`，而不是 `repository.models`（见 §2 步骤 4） |
| 备份恢复后状态错乱 | 恢复会把 `Settings` 整份写回（`data/sync/BackupManager.kt:142-144`），官方 provider 可能被带回来 | `evaluate()` 幂等，恢复后进入对应步骤而不是报错 |

---

## 7. 实现者速查（按此顺序做即可）

1. 建 `OnboardingState.kt`：定义 `enum class OnboardingStep { Intro, Login, Token, Group, Sync, Model, Done }`，以及 `data class OnboardingUiState(val step: OnboardingStep, val session: UserSession?, val groups: Map<String, GroupInfo>, val officialModels: List<String>, val busy: Boolean, val error: String?)`。
2. 写 `evaluate()`（§3.2 的六分支短路），输入全部来自 `UserRepository` 三个 StateFlow + `Settings`，参考实现里的字段位置：`UserRepository.kt:25-26 / 175-181`、`OfficialProviderManager.kt:33-35`。
3. 建 `OnboardingVM`：`combine(sessionFlow, groups, models, settingsFlow)` → `state`。
4. 建 `OnboardingPage`：`Scaffold` + `LargeFlexibleTopAppBar`（照 `SettingPreferencesGeneralPage.kt:54-69`），内容按 `step` 分发到 §3.3 的四个分支。
5. 改 `RouteActivity`：加 `Screen.Onboarding`、`entry<Screen.Onboarding>`、`startScreen` 分支、import。
6. 补字符串、跑 `./gradlew assembleGithubDebug`。
