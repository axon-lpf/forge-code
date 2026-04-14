import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.2"
}

group = "com.forgecode.plugin"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    mavenCentral()
}

dependencies {
    // OkHttp — HTTP 客户端 + SSE 流式支持
    implementation("com.squareup.okhttp3:okhttp:${providers.gradleProperty("okhttpVersion").get()}")
    implementation("com.squareup.okhttp3:okhttp-sse:${providers.gradleProperty("okhttpVersion").get()}")

    // Gson — JSON 序列化
    implementation("com.google.code.gson:gson:${providers.gradleProperty("gsonVersion").get()}")

    // 测试
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("io.mockk:mockk:1.13.8")
}

// IntelliJ 插件配置
intellij {
    // 使用版本号自动下载 IntelliJ Platform SDK
    version.set(providers.gradleProperty("platformVersion").get())
    type.set(providers.gradleProperty("platformType").get())
    // 不依赖额外插件
    plugins.set(listOf())
    downloadSources.set(false)
    updateSinceUntilBuild.set(false)
}

tasks {
    // Java / Kotlin 编译目标版本
    withType<JavaCompile> {
        sourceCompatibility = providers.gradleProperty("javaVersion").get()
        targetCompatibility = providers.gradleProperty("javaVersion").get()
    }
    withType<KotlinCompile> {
        kotlinOptions.jvmTarget = providers.gradleProperty("javaVersion").get()
    }

    // 插件 XML 配置
    patchPluginXml {
        sinceBuild.set("231")        // IDEA 2023.1
        untilBuild.set("")           // 不限制最高版本
        changeNotes.set("""
            <h3>1.0.0</h3>
            <ul>
                <li>🎉 初始版本发布</li>
                <li>✅ 聊天面板（JCEF 内嵌浏览器）</li>
                <li>✅ 底部状态栏模型切换</li>
                <li>✅ 支持 16+ 大模型动态切换</li>
            </ul>
        """.trimIndent())
    }

    // 构建插件 zip
    buildPlugin {
        archiveFileName.set("forge-code-${providers.gradleProperty("pluginVersion").get()}.zip")
    }

    // 测试配置
    test {
        useJUnitPlatform()
    }

    // 运行 IDE 沙箱调试（./gradlew runIde）
    runIde {
        // 可指定本地 IDE 路径，不填则自动下载
        // ideDir.set(file("/path/to/your/idea"))
        jvmArgs("-Xmx2048m")
        // 开启 JCEF 远程调试，在 Chrome 访问 chrome://inspect 即可调试 WebView
        jvmArgs("-Dide.browser.jcef.debug.port=9222")
    }
}
