# Forge Code — LLM Provider 整合开发计划

> **目标**：将 `claude-api-proxy` 的大模型调用核心移植到 `forge-code` 插件中，实现零依赖启动。
> **预计工期**：5 个工作日
> **开始日期**：2026-04-14

---

## 一、整合概述

### 1.1 当前状态

| 模块 | 状态 | 说明 |
|------|------|------|
| JCEF 聊天面板 | ✅ 已完成 | ForgeChatPanel + WebUI |
| 状态栏模型切换 | ✅ 已完成 | ModelStatusBarWidget |
| 右键菜单代码操作 | ✅ 已完成 | CodeActions (解释/优化/审查/测试) |
| 设置页 | ✅ 已完成 | ForgeSettingsConfigurable（当前只有后端地址配置） |
| BackendService | ✅ 已完成 | 通过 HTTP 调外部后端（**需改造**） |
| LLM Provider 层 | ❌ 未移植 | 仍在 claude-api-proxy 工程中 |

### 1.2 目标状态

```
改造前:  插件 → HTTP → claude-api-proxy 后端 → 大模型 API
改造后:  插件（内置 Provider 层）→ 大模型 API
```

### 1.3 改动文件清单

**新增文件（Java，LLM Provider 层）：** 9 个

| 文件 | 来源 | 改动说明 |
|------|------|----------|
| `src/main/java/.../llm/dto/ChatRequest.java` | `OpenAIChatRequest.java` | 改包名，去 Lombok，手写 getter/setter |
| `src/main/java/.../llm/dto/ChatResponse.java` | `OpenAIChatResponse.java` | 改包名，去 Lombok，手写 getter/setter |
| `src/main/java/.../llm/provider/LlmProvider.java` | `LlmProvider.java` | 改包名，改 DTO 引用 |
| `src/main/java/.../llm/provider/AbstractLlmProvider.java` | `AbstractLlmProvider.java` | 改包名，去 Lombok @Slf4j，用 java.util.logging |
| `src/main/java/.../llm/provider/ProviderRegistry.java` | `ProviderRegistry.java` | 改包名，其余原样 |
| `src/main/java/.../llm/provider/impl/GenericOpenAIProvider.java` | `GenericOpenAIProvider.java` | 改包名，去 Lombok |
| `src/main/java/.../llm/provider/impl/DeepSeekProvider.java` | `DeepSeekProvider.java` | 改包名，去 Lombok |
| `src/main/java/.../llm/provider/impl/MiniMaxProvider.java` | `MiniMaxProvider.java` | 改包名，去 Lombok |
| `src/main/java/.../llm/ProviderManager.java` | `LlmProviderFactory.java` | 重写，去 Spring，改配置读取方式 |

> 包路径统一为 `com.forgecode.plugin.llm`

**改造文件（Kotlin，插件层）：** 5 个

| 文件 | 改动说明 |
|------|----------|
| `service/BackendService.kt` → `service/LlmService.kt` | 重写，去 HTTP，改为直调 ProviderManager |
| `settings/ForgeSettings.kt` | 新增 Provider 配置存储字段（activeProvider, activeModel, providerConfigs） |
| `settings/ForgeSettingsConfigurable.kt` | 新增模型提供商配置 UI（API Key、Base URL、测试连通性） |
| `toolwindow/ForgeChatPanel.kt` | 改调用入口：BackendService → LlmService |
| `statusbar/ModelStatusBarWidget.kt` | 改调用入口：BackendService → LlmService |

**不变文件：** 5 个
- `actions/CodeActions.kt` — 无需改动（通过 EditorUtil.sendMessageToChat 间接使用）
- `toolwindow/ForgeToolWindowFactory.kt` — 无需改动
- `toolwindow/ModelChangedListener.kt` — 无需改动
- `util/EditorUtil.kt` — 无需改动
- `resources/webui/*` — 无需改动（JS Bridge 协议不变）

---

## 二、分步开发计划

### Step 1：移植 DTO 层（0.5 天）✅ 已完成

**目标**：将请求/响应数据结构移植到插件中。

**具体任务**：

1. 创建目录 `src/main/java/com/forgecode/plugin/llm/dto/`

2. 移植 `ChatRequest.java`（原 `OpenAIChatRequest.java`）
   - 改包名 `com.forgecode.plugin.llm.dto`
   - 去掉 Lombok `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`
   - 手写所有字段的 getter/setter
   - 保留 Jackson `@JsonProperty` 和 `@JsonIgnoreProperties` 注解（Gson 兼容）
   - 内部类 `ChatMessage`, `ToolCall`, `FunctionCall` 同样处理
   - 字段：model, messages, maxTokens, stream, temperature, topP, stop, tools, toolChoice, frequencyPenalty, presencePenalty

3. 移植 `ChatResponse.java`（原 `OpenAIChatResponse.java`）
   - 同样处理
   - 内部类 `Choice`, `Message`, `Delta`, `ToolCall`, `FunctionCall`, `Usage`

**验证**：编译通过即可。

**文件清单**：
```
新增: src/main/java/com/forgecode/plugin/llm/dto/ChatRequest.java
新增: src/main/java/com/forgecode/plugin/llm/dto/ChatResponse.java
```

---

### Step 2：移植 Provider 核心层（1 天）✅ 已完成

**目标**：移植 LlmProvider 接口、AbstractLlmProvider 基类、ProviderRegistry 注册表。

**具体任务**：

1. 创建目录 `src/main/java/com/forgecode/plugin/llm/provider/`

2. 移植 `LlmProvider.java`（接口）
   - 改包名
   - 将 `OpenAIChatRequest` → `ChatRequest`
   - 将 `OpenAIChatResponse` → `ChatResponse`
   - 其余原样

3. 移植 `AbstractLlmProvider.java`（基类，约 191 行）
   - 改包名
   - 去掉 Lombok `@Slf4j`
   - 添加 `private static final Logger LOG = Logger.getLogger(AbstractLlmProvider.class.getName());`
   - 将所有 `log.xxx(...)` 替换为 `LOG.xxx(...)`
     - `log.info(...)` → `LOG.info(...)`
     - `log.debug(...)` → `LOG.fine(...)`
     - `log.trace(...)` → `LOG.finest(...)`
     - `log.error(...)` → `LOG.severe(...)`
     - `log.warn(...)` → `LOG.warning(...)`
   - 注意：java.util.logging 不支持 `{}` 占位符，需改为 `String.format()` 或字符串拼接
   - 将 DTO 引用改为新包名
   - OkHttp 调用部分**完全原样**保留

4. 移植 `ProviderRegistry.java`（注册表，约 287 行）
   - 改包名即可
   - 这是**纯静态数据**，无外部依赖
   - 16 个 ProviderMeta record 定义原样保留

**验证**：编译通过，ProviderRegistry.get("deepseek") 能返回元信息。

**文件清单**：
```
新增: src/main/java/com/forgecode/plugin/llm/provider/LlmProvider.java
新增: src/main/java/com/forgecode/plugin/llm/provider/AbstractLlmProvider.java
新增: src/main/java/com/forgecode/plugin/llm/provider/ProviderRegistry.java
```

---

### Step 3：移植 Provider 实现类（1 天）✅ 已完成

**目标**：移植 3 个 Provider 实现类 + ProviderManager 工厂。

**具体任务**：

1. 创建目录 `src/main/java/com/forgecode/plugin/llm/provider/impl/`

2. 移植 `GenericOpenAIProvider.java`（约 127 行）
   - 改包名，去 Lombok @Slf4j
   - 改 DTO 引用
   - `fromMeta()` 和 `custom()` 静态工厂方法原样保留
   - 注意：构造函数中 `ObjectMapper` 替换为 `Gson`（或保持用 Jackson ObjectMapper，后续统一决定）

3. 移植 `DeepSeekProvider.java`（约 105 行）
   - 改包名，去 Lombok @Slf4j
   - 改 DTO 引用
   - `adjustRequest()` 中 deepseek-reasoner 特殊逻辑原样保留
   - `normalizeImagePartsForDeepSeek()` 图片降级逻辑原样保留

4. 移植 `MiniMaxProvider.java`（约 217 行）
   - 改包名，去 Lombok @Slf4j
   - 改 DTO 引用
   - `adjustRequest()` 中图片上传逻辑原样保留
   - `uploadBase64Image()` 文件上传逻辑原样保留
   - `normalizeRequestForMiniMax()` 请求清洗逻辑原样保留

5. 新建 `ProviderManager.java`（原 `LlmProviderFactory.java`，约 254 行）
   - 改包名 `com.forgecode.plugin.llm`
   - **去掉 Spring 依赖**：`@Component`, `@RequiredArgsConstructor`, `@PostConstruct`
   - **去掉 ConfigStore 依赖**：改为接收 `Map<String, ProviderConfig>` 参数
   - 新增内部类 `ProviderConfig`（简化版，公开字段，无 Lombok）
   - `init(Map<String, ProviderConfig> configs, String activeProvider, String activeModel)` 方法
   - `reloadProvider(String name, ProviderConfig config)` 方法签名调整
   - `createProvider()` 和 `createSpecialProvider()` 逻辑原样保留
   - 注意：`ObjectMapper` 依赖需处理（或替换为 Gson）

**JSON 序列化决策**：

forge-code 已引入 Gson（build.gradle.kts 中有 `com.google.code.gson:gson`），但 claude-api-proxy 用的是 Jackson ObjectMapper。有两个选择：
- **方案 A**：保持用 Jackson ObjectMapper（需额外引入 jackson-databind 依赖）
- **方案 B**：全部改为 Gson（AbstractLlmProvider 中 ObjectMapper 需替换）

> **推荐方案 A**：保持 Jackson，减少改动。在 build.gradle.kts 中添加 jackson-databind 依赖。
> 如果选方案 B，则需改 AbstractLlmProvider 和所有 Provider 的序列化代码。

**验证**：编译通过，能手动创建 DeepSeekProvider 实例。

**文件清单**：
```
新增: src/main/java/com/forgecode/plugin/llm/provider/impl/GenericOpenAIProvider.java
新增: src/main/java/com/forgecode/plugin/llm/provider/impl/DeepSeekProvider.java
新增: src/main/java/com/forgecode/plugin/llm/provider/impl/MiniMaxProvider.java
新增: src/main/java/com/forgecode/plugin/llm/ProviderManager.java
可能修改: build.gradle.kts（添加 jackson-databind 依赖）
```

---

### Step 4：改造 ForgeSettings 和 LlmService（1 天）✅ 已完成

**目标**：改造配置存储和服务层，让插件能直接调用 Provider。

**具体任务**：

#### 4.1 改造 ForgeSettings.kt

当前 State 字段：
```kotlin
data class State(
    var backendUrl: String = "http://localhost:8080",
    var connectTimeout: Int = 30,
    var readTimeout: Int = 120
)
```

改造后 State 字段：
```kotlin
data class State(
    // 模型配置（新增）
    var activeProvider: String = "",
    var activeModel: String = "",
    var providerConfigs: String = "{}",   // JSON 格式存储各 Provider 配置

    // 通用设置（保留）
    var connectTimeout: Int = 30,
    var readTimeout: Int = 120,

    // 界面设置（保留）
    var theme: String = "auto",
    var language: String = "zh",
    var defaultMode: String = "vibe"
)
```

新增方法：
```kotlin
/** 将 providerConfigs JSON 转为 Map<String, ProviderManager.ProviderConfig> */
fun getProviderConfigMap(): Map<String, ProviderManager.ProviderConfig>

/** 保存单个 Provider 配置到 JSON */
fun setProviderConfig(name: String, config: ProviderManager.ProviderConfig)

/** 删除单个 Provider 配置 */
fun removeProviderConfig(name: String)
```

去掉字段：`backendUrl`（不再需要后端地址）

#### 4.2 新建 LlmService.kt（替代 BackendService.kt）

```kotlin
@Service(Service.Level.APP)
class LlmService {

    private val providerManager = ProviderManager()
    private var initialized = false

    companion object {
        fun getInstance(): LlmService =
            ApplicationManager.getApplication().getService(LlmService::class.java)
    }

    /** 初始化 Provider 层 */
    fun ensureInitialized() {
        if (initialized) return
        val settings = ForgeSettings.getInstance()
        providerManager.init(
            settings.getProviderConfigMap(),
            settings.state.activeProvider,
            settings.state.activeModel
        )
        initialized = true
    }

    /** 获取当前激活信息 */
    fun getActiveInfo(): ActiveInfo

    /** 获取所有 Provider 列表 */
    fun getProviderList(): List<ProviderInfo>

    /** 切换模型 */
    fun switchModel(provider: String, model: String)

    /** 流式对话 */
    fun chatStream(
        messages: List<Map<String, Any>>,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Exception) -> Unit
    )

    /** 测试 Provider 连通性 */
    fun testProvider(providerName: String): TestResult

    /** 重新加载 Provider（配置变更后调用） */
    fun reloadProvider(name: String, config: ProviderManager.ProviderConfig)
}
```

#### 4.3 注册 LlmService

在 `plugin.xml` 中注册：
```xml
<applicationService
    serviceImplementation="com.forgecode.plugin.idea.service.LlmService"/>
```

或直接在类上使用 `@Service` 注解（IntelliJ 2023.1+ 支持）。

**验证**：编译通过，LlmService.getInstance() 能获取单例。

**文件清单**：
```
修改: src/main/kotlin/.../settings/ForgeSettings.kt
新增: src/main/kotlin/.../service/LlmService.kt
删除: src/main/kotlin/.../service/BackendService.kt（或保留待下一步替换）
修改: src/main/resources/META-INF/plugin.xml（注册 LlmService）
```

---

### Step 5：改造 UI 层 + 设置页 + 集成测试（1.5 天）✅ 已完成

**目标**：让所有 UI 组件使用 LlmService，改造设置页支持 Provider 配置。

**具体任务**：

#### 5.1 改造 ForgeChatPanel.kt

将所有 `BackendService` 调用替换为 `LlmService`：

- `handleSendMessage()` 中：
  ```kotlin
  // 旧: BackendService.getInstance().chat(request, onToken, onDone, onError)
  // 新:
  LlmService.getInstance().chatStream(messages, onToken, onDone, onError)
  ```

- `handleSwitchModel()` 中：
  ```kotlin
  // 旧: BackendService.getInstance().switchModel(provider, model)
  // 新:
  LlmService.getInstance().switchModel(provider, model)
  ```

- `handleGetModels()` 中：
  ```kotlin
  // 旧: BackendService.getInstance().getModels()
  // 新:
  val providers = LlmService.getInstance().getProviderList()
  ```

#### 5.2 改造 ModelStatusBarWidget.kt

- `getActiveModel()` 改为调用 `LlmService.getInstance().getActiveInfo()`
- `showModelSwitcher()` 改为调用 `LlmService.getInstance().getProviderList()`
- 模型切换后调用 `LlmService.getInstance().switchModel()`

#### 5.3 改造 ForgeSettingsConfigurable.kt

新增模型提供商配置区域：

```
┌─────────────────────────────────────────────────────┐
│  模型提供商配置                                       │
│                                                       │
│  提供商列表（JBTable）:                               │
│  ┌──────────┬───────────────┬─────────┬───────────┐  │
│  │ 提供商    │ API Key       │ 状态    │ 操作      │  │
│  ├──────────┼───────────────┼─────────┼───────────┤  │
│  │ DeepSeek │ sk-****1234   │ ✅ 可用  │ [编辑]    │  │
│  │ Qwen     │ (未配置)      │ ⚠️ 未配置│ [配置]    │  │
│  │ ...      │ ...           │ ...     │ ...       │  │
│  └──────────┴───────────────┴─────────┴───────────┘  │
│                                                       │
│  点击 [配置] 弹出 DialogWrapper:                      │
│  ┌─────────────────────────────────┐                  │
│  │ API Key:  [________________]    │                  │
│  │ Base URL: [________________]    │  (可选)          │
│  │ 模型:     [_________ ▼    ]    │                  │
│  │                                 │                  │
│  │      [测试] [保存] [取消]       │                  │
│  └─────────────────────────────────┘                  │
└─────────────────────────────────────────────────────┘
```

实现要点：
- 使用 IntelliJ UI DSL (`panel { }`) 或手动 `JPanel` + `JBTable`
- Provider 列表数据从 `ProviderRegistry.getAll()` 获取
- 配置编辑弹窗使用 `DialogWrapper`
- 保存时调用 `ForgeSettings.setProviderConfig()` + `LlmService.reloadProvider()`
- 测试连通性调用 `LlmService.testProvider()`

#### 5.4 删除旧 BackendService

确认所有调用方已迁移后，删除 `BackendService.kt`。

#### 5.5 集成测试

手动测试清单：

- [ ] 插件启动，无报错
- [ ] 打开设置页，能看到所有 16 个提供商
- [ ] 配置 DeepSeek API Key，保存
- [ ] 点击测试连通性，返回成功
- [ ] 关闭设置页，底部状态栏显示 "DeepSeek · deepseek-chat"
- [ ] 点击状态栏，弹出模型选择列表
- [ ] 切换到其他模型，状态栏更新
- [ ] 打开聊天面板，发送消息，收到流式回复
- [ ] 选中代码，右键 → 解释代码，聊天面板显示结果
- [ ] 重启 IDE，配置仍然保留（持久化验证）
- [ ] 未配置 API Key 时发送消息，显示友好错误提示

**文件清单**：
```
修改: src/main/kotlin/.../toolwindow/ForgeChatPanel.kt
修改: src/main/kotlin/.../statusbar/ModelStatusBarWidget.kt
修改: src/main/kotlin/.../settings/ForgeSettingsConfigurable.kt
删除: src/main/kotlin/.../service/BackendService.kt
```

---

## 三、依赖变更

### 3.1 build.gradle.kts 变更

需要确认或添加的依赖：

```kotlin
dependencies {
    // OkHttp — 已有，用于 HTTP 调用和 SSE 流式
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Gson — 已有
    implementation("com.google.code.gson:gson:2.10.1")

    // Jackson — 需新增（Provider 层 JSON 序列化）
    // 或者将 Provider 层改为用 Gson（见 Step 3 决策点）
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
}
```

### 3.2 plugin.xml 变更

```xml
<!-- 注册 LlmService（如果不用 @Service 注解） -->
<extensions defaultExtensionNs="com.intellij">
    <applicationService
        serviceImplementation="com.forgecode.plugin.idea.service.LlmService"/>
</extensions>
```

---

## 四、风险与决策点

### 4.1 JSON 序列化库选择

| 方案 | 优点 | 缺点 |
|------|------|------|
| **A: 保持 Jackson** | 移植改动最小，DTO 的 @JsonProperty 注解可保留 | 增加 jackson-databind 依赖包大小 |
| **B: 全部改 Gson** | 无额外依赖（已有 Gson） | 需改 AbstractLlmProvider 的序列化代码，DTO 的 @JsonProperty 失效 |

> **建议**：选方案 A（保持 Jackson），移植更快。IntelliJ Platform 自带 Jackson，不会增加实际体积。

### 4.2 日志框架

| 方案 | 说明 |
|------|------|
| **A: java.util.logging** | 无依赖，但格式化不如 SLF4J 方便 |
| **B: IntelliJ Logger** | `com.intellij.openapi.diagnostic.Logger`，IDE 内显示更友好 |

> **建议**：选方案 B，用 `com.intellij.openapi.diagnostic.Logger`，日志会出现在 IDE 的 idea.log 中。

### 4.3 API Key 安全

当前阶段用 `PersistentStateComponent` 明文存储（XML 文件），后续 Phase 2 再迁移到 `PasswordSafe`。
风险等级：低（本地文件，操作系统用户权限保护）。

### 4.4 线程安全

`LlmService.chatStream()` 会在后台线程执行 HTTP 请求，UI 回调需在 EDT 执行：
```kotlin
ApplicationManager.getApplication().invokeLater {
    // 更新 JCEF 页面
}
```

---

## 五、时间线总览

```
Day 1 (4/14)  ────────────────────────────────────────────
  上午: Step 1 — 移植 DTO 层 (ChatRequest, ChatResponse)
  下午: Step 2 — 移植 Provider 核心层 (LlmProvider, AbstractLlmProvider, ProviderRegistry)

Day 2 (4/15)  ────────────────────────────────────────────
  全天: Step 3 — 移植 Provider 实现类 + ProviderManager
        (GenericOpenAI, DeepSeek, MiniMax, ProviderManager)
        处理 JSON 序列化决策

Day 3 (4/16)  ────────────────────────────────────────────
  上午: Step 4.1 — 改造 ForgeSettings（新增 Provider 配置字段）
  下午: Step 4.2 — 新建 LlmService（替代 BackendService）

Day 4 (4/17)  ────────────────────────────────────────────
  上午: Step 5.1 — 改造 ForgeChatPanel（调用 LlmService）
        Step 5.2 — 改造 ModelStatusBarWidget（调用 LlmService）
  下午: Step 5.3 — 改造 ForgeSettingsConfigurable（Provider 配置 UI）

Day 5 (4/18)  ────────────────────────────────────────────
  上午: Step 5.3 续 — 完善设置页（编辑弹窗、测试连通性）
  下午: Step 5.4 — 删除 BackendService
        Step 5.5 — 集成测试 + Bug 修复
```

---

## 六、验收标准

### 必须达成（P0）

- [ ] 编译通过，`./gradlew buildPlugin` 成功
- [ ] 插件安装后，无需启动外部后端服务
- [ ] 设置页可配置至少一个 Provider 的 API Key
- [ ] 测试连通性功能正常
- [ ] 聊天面板可流式对话（使用配置的 Provider）
- [ ] 状态栏可切换模型
- [ ] 重启 IDE 后配置持久化
- [ ] 未配置 API Key 时有友好错误提示

### 最好达成（P1）

- [ ] 设置页展示全部 16 个内置提供商
- [ ] API Key 脱敏显示（`sk-****1234`）
- [ ] 多个 Provider 同时配置，可自由切换
- [ ] 右键菜单代码操作正常工作

### 后续计划（P2，不在本次范围）

- 自定义提供商支持
- API Key 加密存储（PasswordSafe）
- 多会话管理
- 代码 Apply（Diff 视图）
