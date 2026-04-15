# 🔥 CodeForge

> **锻造每一行代码** — AI 编程助手插件，聚合国内外 16+ 主流大模型，支持 IntelliJ IDEA 和 VSCode

---

## 工程结构

```
codeforge/
├── plugin-idea/        IntelliJ IDEA 插件 (Kotlin + Gradle)
├── plugin-vscode/      VSCode 插件 (TypeScript，Phase 2)
└── codeforge-design.md  完整技术设计文档
```

## 依赖

插件需要配合 [claude-api-proxy](../claude-api-proxy) 后端服务使用。

默认连接地址：`http://localhost:8080`

## 快速开始

### 1. 启动后端服务

```bash
cd ../claude-api-proxy
java -jar target/claude-api-proxy-1.0.0-SNAPSHOT.jar
```

### 2. 开发 / 调试插件

```bash
cd plugin-idea

# 启动 IDE 沙箱（自动下载 IDEA 并运行插件）
./gradlew runIde

# 构建插件 zip
./gradlew buildPlugin
# 输出: build/distributions/codeforge-1.0.0.zip
```

### 3. 安装插件

- IDEA → Settings → Plugins → Install Plugin from Disk
- 选择 `codeforge-1.0.0.zip`

## 功能

| 功能 | 状态 |
|------|------|
| 侧边栏 Chat 面板（JCEF）| ✅ Phase 1 |
| 底部状态栏模型切换 | ✅ Phase 1 |
| 设置页（后端地址配置）| ✅ Phase 1 |
| 右键菜单（解释/优化/审查/生成测试）| ✅ Phase 1 |
| 代码 Apply（写入编辑器）| ✅ Phase 1 |
| 多会话管理 | 🔄 Phase 2 |
| VSCode 插件 | 🔄 Phase 2 |
| Rules 规则系统 | 🔄 Phase 3 |
| MCP Server 支持 | 🔄 Phase 3 |

## 详细设计

见 [forge-code-design.md](../claude-api-proxy/forge-code-design.md)
