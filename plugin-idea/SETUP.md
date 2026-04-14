# 环境搭建指南 & 踩坑记录

## 📋 环境要求

| 工具 | 版本 | 说明 |
|------|------|------|
| IntelliJ IDEA | 2023.1+ | 推荐 Community Edition |
| JDK | 17 | 编译目标版本 |
| Gradle | 8.5 | 由 Wrapper 自动管理 |

---

## 🚀 首次搭建步骤

### 1. 克隆项目后，先检查 `gradle-wrapper.jar` 是否存在

```
plugin-idea/
└── gradle/
    └── wrapper/
        ├── gradle-wrapper.jar        ← ⚠️ 必须存在！
        └── gradle-wrapper.properties
```

> ⚠️ **注意**：`gradle-wrapper.jar` 通常不会被 Git 提交（被 `.gitignore` 忽略），
> **换电脑后需要手动补充此文件**，否则 Gradle 无法启动，IDEA 会一直卡在 `Build model...`

**获取 `gradle-wrapper.jar` 的方法：**

方法一：从本机其他 Gradle 项目复制（推荐，无需联网）
```powershell
# 搜索本机已有的 jar
Get-ChildItem -Path "D:\" -Recurse -Filter "gradle-wrapper.jar" -ErrorAction SilentlyContinue | Select-Object -First 5 FullName

# 复制到本项目
Copy-Item "D:\其他项目\gradle\wrapper\gradle-wrapper.jar" `
          -Destination "D:\workspace\forge-code\plugin-idea\gradle\wrapper\gradle-wrapper.jar"
```

方法二：从 Gradle 官方 GitHub 下载
```
https://github.com/gradle/gradle/blob/master/gradle/wrapper/gradle-wrapper.jar
```

### 2. 配置本地 IDEA 路径（避免从网络下载 SDK）

编辑 `build.gradle.kts`，将 `localPath` 改为你本机的 IDEA 安装目录：

```kotlin
intellij {
    // ✅ 使用本地安装的 IDEA，无需从网络下载（填安装根目录，不是 bin 目录）
    localPath.set("D:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2023.1.2")
    type.set("IC")
    downloadSources.set(false)
}
```

> 💡 **为什么要这样做？**
> `org.jetbrains.intellij` 插件默认会去 JetBrains 服务器下载整个 IDEA SDK（约 400MB+），
> 国内访问极慢，改用本地路径可**完全跳过网络下载**，秒级完成。

### 3. 验证 Gradle Wrapper 是否正常

```powershell
cd D:\workspace\forge-code\plugin-idea
.\gradlew.bat --version
```

看到 `Gradle 8.5` 输出即为正常。

### 4. 在 IDEA 中 Sync

点击右侧 **🐘 Gradle 图标 → Reload All Gradle Projects**，等待同步完成。

---

## 🐢 Gradle 下载加速配置

已配置以下国内镜像，无需额外操作：

| 配置文件 | 镜像内容 |
|----------|----------|
| `settings.gradle.kts` | 插件仓库 + 依赖仓库 → 阿里云镜像 |
| `build.gradle.kts` | 项目依赖 → 阿里云镜像 |
| `gradle-wrapper.properties` | Gradle 发行版 → 腾讯云镜像 |

> ⚠️ 注意：JetBrains 自己的 SDK 仓库（`cache-redirector.jetbrains.com`）**无法**走阿里云镜像，
> 这也是为什么必须用 `localPath` 指向本地 IDEA 的原因。

---

## 🔥 常见问题

### ❌ IDEA 一直卡在 `Gradle: Build model...`

**原因排查顺序：**

1. **`gradle-wrapper.jar` 缺失** → 最常见原因，见上方步骤 1
2. **未配置 `localPath`** → Gradle 在尝试从 JetBrains 服务器下载 SDK，网络超时
3. **Gradle 发行版未下载** → 检查 `gradle-wrapper.properties` 中的 `distributionUrl` 是否可访问

**快速诊断命令：**
```powershell
# 在项目目录执行，看报什么错
cd D:\workspace\forge-code\plugin-idea
.\gradlew.bat --version
```

常见错误信息对应原因：
```
ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain  → gradle-wrapper.jar 缺失
```

---

### ❌ 依赖下载很慢

检查 `settings.gradle.kts` 和 `build.gradle.kts` 中是否有阿里云镜像配置，参考当前文件内容。

---

## 📁 关键文件说明

```
plugin-idea/
├── build.gradle.kts              # 构建配置（含 localPath、镜像配置）
├── gradle.properties             # Gradle 性能参数（daemon、cache、parallel）
├── settings.gradle.kts           # 插件仓库镜像配置
└── gradle/wrapper/
    ├── gradle-wrapper.jar        # ⚠️ 换电脑必须手动补充
    └── gradle-wrapper.properties # Gradle 版本 + 下载镜像地址
```
