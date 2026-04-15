package com.codeforge.plugin.idea.util

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

/**
 * T22：@Codebase 代码库语义检索增强
 *
 * 优先使用 IntelliJ PSI API 进行类名/方法名模糊匹配；
 * PSI 不可用时自动降级为文件内容 grep。
 *
 * 对外暴露三个能力：
 *  1. searchByName      — 类名/方法名/文件名模糊匹配（供 Agent searchCode 和 @ 引用下拉使用）
 *  2. findImplementations — 查找接口的所有实现类（PSI 专属，降级返回空列表）
 *  3. findUsages        — 查找类/方法被引用的位置（PSI 专属，降级 grep 方式）
 */
object CodebaseSearcher {

    private val log = logger<CodebaseSearcher>()

    // ==================== 数据类 ====================

    /** 单条搜索结果 */
    data class SearchResult(
        val filePath: String,       // 相对项目根的路径
        val lineNumber: Int,        // 行号（1-based），-1 表示整文件级别
        val displayText: String,    // 展示给用户的单行文字
        val kind: Kind = Kind.CODE  // 结果类型
    ) {
        enum class Kind { CLASS, METHOD, FIELD, FILE, CODE }
    }

    // ==================== 公开 API ====================

    /**
     * 类名/方法名/文件名模糊匹配搜索
     *
     * 优先调用 PSI，降级为逐行 grep。
     * @param query    搜索词（支持部分匹配，不区分大小写）
     * @param limit    最大返回数量，默认 30
     */
    fun searchByName(project: Project, query: String, limit: Int = 30): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        return try {
            val psiResults = searchByNameViaPsi(project, query, limit)
            if (psiResults.isNotEmpty()) psiResults
            else searchByNameViaGrep(project, query, limit)
        } catch (e: Exception) {
            log.warn("searchByName PSI 失败，降级 grep: ${e.message}")
            searchByNameViaGrep(project, query, limit)
        }
    }

    /**
     * 查找接口的所有实现类（仅 PSI 支持，降级返回空列表）
     * @param interfaceName 接口名（全限定名或简单名均可）
     */
    fun findImplementations(project: Project, interfaceName: String): List<SearchResult> {
        return try {
            findImplementationsViaPsi(project, interfaceName)
        } catch (e: Exception) {
            log.warn("findImplementations 失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 查找类/方法的使用位置
     * @param className  类名
     * @param methodName 方法名（可为空，表示只搜类引用）
     */
    fun findUsages(project: Project, className: String, methodName: String? = null): List<SearchResult> {
        return try {
            val psiResults = findUsagesViaPsi(project, className, methodName)
            if (psiResults.isNotEmpty()) psiResults
            else findUsagesViaGrep(project, className, methodName)
        } catch (e: Exception) {
            log.warn("findUsages PSI 失败，降级 grep: ${e.message}")
            findUsagesViaGrep(project, className, methodName)
        }
    }

    // ==================== PSI 实现（反射隔离，兼容无 PSI 环境）====================

    /**
     * 通过 PSI AllClassesSearch / FilenameIndex 实现模糊匹配
     * 使用反射调用，避免在精简 IDE 环境下 NoClassDefFoundError
     */
    private fun searchByNameViaPsi(project: Project, query: String, limit: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val lowerQuery = query.lowercase()

        // 1. 通过 FilenameIndex 查找文件名匹配
        try {
            val filenameIndexClass = Class.forName("com.intellij.psi.search.FilenameIndex")
            val projectScopeClass = Class.forName("com.intellij.psi.search.ProjectScope")

            val getAllFilenames = filenameIndexClass.getMethod("getAllFilenames", Project::class.java)
            @Suppress("UNCHECKED_CAST")
            val allFilenames = getAllFilenames.invoke(null, project) as? Collection<String> ?: emptyList()

            val allScope = projectScopeClass.getMethod("getAllScope", Project::class.java).invoke(null, project)
            val getFilesByName = filenameIndexClass.getMethod(
                "getFilesByName", Project::class.java, String::class.java,
                Class.forName("com.intellij.psi.search.GlobalSearchScope")
            )

            for (filename in allFilenames) {
                if (results.size >= limit) break
                if (!filename.lowercase().contains(lowerQuery)) continue
                @Suppress("UNCHECKED_CAST")
                val files = getFilesByName.invoke(null, project, filename, allScope) as? Collection<*> ?: continue
                for (vf in files) {
                    if (results.size >= limit) break
                    val pathMethod = vf!!.javaClass.getMethod("getPath")
                    val path = pathMethod.invoke(vf) as? String ?: continue
                    if (isExcludedPath(path)) continue
                    val projectBase = project.basePath ?: ""
                    val rel = path.removePrefix(projectBase).trimStart('/', '\\')
                    results.add(SearchResult(rel, -1, "📄 $rel", SearchResult.Kind.FILE))
                }
            }
        } catch (e: Exception) {
            log.debug("FilenameIndex 反射失败: ${e.message}")
        }

        // 2. 通过 JavaPsiFacade / PsiShortNamesCache 查找类名匹配
        try {
            val shortNamesCacheClass = Class.forName("com.intellij.psi.search.PsiShortNamesCache")
            val getInstance = shortNamesCacheClass.getMethod("getInstance", Project::class.java)
            val cache = getInstance.invoke(null, project)

            val getAllClassNames = shortNamesCacheClass.getMethod("getAllClassNames")
            @Suppress("UNCHECKED_CAST")
            val allClassNames = getAllClassNames.invoke(cache) as? Array<String> ?: emptyArray()

            val projectScopeClass = Class.forName("com.intellij.psi.search.ProjectScope")
            val projectScope = projectScopeClass.getMethod("getProjectScope", Project::class.java).invoke(null, project)
            val getClassesByName = shortNamesCacheClass.getMethod(
                "getClassesByName", String::class.java,
                Class.forName("com.intellij.psi.search.GlobalSearchScope")
            )

            for (className in allClassNames) {
                if (results.size >= limit) break
                if (!className.lowercase().contains(lowerQuery)) continue
                @Suppress("UNCHECKED_CAST")
                val classes = getClassesByName.invoke(cache, className, projectScope) as? Array<*> ?: continue
                for (psiClass in classes) {
                    if (results.size >= limit) break
                    val containingFile = psiClass!!.javaClass.getMethod("getContainingFile").invoke(psiClass)
                    val vFile = containingFile?.javaClass?.getMethod("getVirtualFile")?.invoke(containingFile)
                    val path = vFile?.javaClass?.getMethod("getPath")?.invoke(vFile) as? String ?: continue
                    if (isExcludedPath(path)) continue
                    val projectBase = project.basePath ?: ""
                    val rel = path.removePrefix(projectBase).trimStart('/', '\\')
                    val qualName = try {
                        psiClass.javaClass.getMethod("getQualifiedName").invoke(psiClass) as? String ?: className
                    } catch (_: Exception) { className }
                    results.add(SearchResult(rel, -1, "🔷 $qualName", SearchResult.Kind.CLASS))
                }
            }
        } catch (e: Exception) {
            log.debug("PsiShortNamesCache 反射失败: ${e.message}")
        }

        return results
    }

    /**
     * 查找接口实现类 — 通过 ClassInheritorsSearch 反射调用
     */
    private fun findImplementationsViaPsi(project: Project, interfaceName: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            val shortNamesCacheClass = Class.forName("com.intellij.psi.search.PsiShortNamesCache")
            val getInstance = shortNamesCacheClass.getMethod("getInstance", Project::class.java)
            val cache = getInstance.invoke(null, project)

            val projectScopeClass = Class.forName("com.intellij.psi.search.ProjectScope")
            val allScope = projectScopeClass.getMethod("getAllScope", Project::class.java).invoke(null, project)
            val getClassesByName = shortNamesCacheClass.getMethod(
                "getClassesByName", String::class.java,
                Class.forName("com.intellij.psi.search.GlobalSearchScope")
            )

            @Suppress("UNCHECKED_CAST")
            val interfaces = getClassesByName.invoke(cache, interfaceName, allScope) as? Array<*> ?: return emptyList()
            val targetInterface = interfaces.firstOrNull() ?: return emptyList()

            // ClassInheritorsSearch.search(psiClass, scope, true)
            val searchClass = Class.forName("com.intellij.psi.search.searches.ClassInheritorsSearch")
            val searchMethod = searchClass.getMethod("search", Class.forName("com.intellij.psi.PsiClass"))
            val query = searchMethod.invoke(null, targetInterface)
            val findAll = query.javaClass.getMethod("findAll")
            @Suppress("UNCHECKED_CAST")
            val inheritors = findAll.invoke(query) as? Collection<*> ?: return emptyList()

            val projectBase = project.basePath ?: ""
            for (psiClass in inheritors) {
                if (results.size >= 20) break
                val containingFile = psiClass!!.javaClass.getMethod("getContainingFile").invoke(psiClass)
                val vFile = containingFile?.javaClass?.getMethod("getVirtualFile")?.invoke(containingFile)
                val path = vFile?.javaClass?.getMethod("getPath")?.invoke(vFile) as? String ?: continue
                if (isExcludedPath(path)) continue
                val rel = path.removePrefix(projectBase).trimStart('/', '\\')
                val qualName = try {
                    psiClass.javaClass.getMethod("getQualifiedName").invoke(psiClass) as? String ?: ""
                } catch (_: Exception) { "" }
                results.add(SearchResult(rel, -1, "🔶 $qualName implements $interfaceName", SearchResult.Kind.CLASS))
            }
        } catch (e: Exception) {
            log.debug("findImplementationsViaPsi 失败: ${e.message}")
        }
        return results
    }

    /**
     * PSI 查找类/方法的使用位置（ReferencesSearch）
     */
    private fun findUsagesViaPsi(project: Project, className: String, methodName: String?): List<SearchResult> {
        // PSI ReferencesSearch 实现较复杂，此处返回空列表，交由 grep 降级处理
        return emptyList()
    }

    // ==================== Grep 降级实现 ====================

    /**
     * 文件内容逐行 grep 搜索（降级方案）
     */
    private fun searchByNameViaGrep(project: Project, query: String, limit: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val root = java.io.File(project.basePath ?: return emptyList())
        val lowerQuery = query.lowercase()

        // 1. 文件名匹配
        for (file in root.walkTopDown()) {
            if (results.size >= limit / 2) break
            if (!file.isFile || isExcludedPath(file.path)) continue
            if (file.name.lowercase().contains(lowerQuery)) {
                val rel = file.path.removePrefix(root.path).trimStart('/', '\\')
                results.add(SearchResult(rel, -1, "📄 $rel", SearchResult.Kind.FILE))
            }
        }

        // 2. 代码内容匹配（class/fun/def/func 声明行）
        val declPatterns = listOf(
            Regex("\\b(class|interface|object|enum)\\s+\\w*${Regex.escape(query)}\\w*", RegexOption.IGNORE_CASE),
            Regex("\\b(fun|def|func|function|method|void|public|private|protected)\\s+\\w*${Regex.escape(query)}\\w*", RegexOption.IGNORE_CASE)
        )
        val codeExts = setOf("kt", "java", "py", "go", "ts", "js", "swift", "cs", "cpp", "c", "rb", "rs")

        outer@ for (file in root.walkTopDown()) {
            if (results.size >= limit) break
            if (!file.isFile || isExcludedPath(file.path)) continue
            if (file.extension !in codeExts) continue
            try {
                val rel = file.path.removePrefix(root.path).trimStart('/', '\\')
                for ((idx, line) in file.readLines(Charsets.UTF_8).withIndex()) {
                    if (results.size >= limit) break@outer
                    for (pat in declPatterns) {
                        if (pat.containsMatchIn(line)) {
                            val kind = if (line.contains("class", ignoreCase = true) ||
                                line.contains("interface", ignoreCase = true))
                                SearchResult.Kind.CLASS else SearchResult.Kind.METHOD
                            results.add(SearchResult(rel, idx + 1,
                                if (kind == SearchResult.Kind.CLASS) "🔷 ${line.trim().take(80)}"
                                else "🔹 ${line.trim().take(80)}", kind))
                            break
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return results.take(limit)
    }

    /**
     * grep 方式查找类/方法引用
     */
    private fun findUsagesViaGrep(project: Project, className: String, methodName: String?): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val keyword = if (methodName != null) "$className.$methodName" else className
        val root = java.io.File(project.basePath ?: return emptyList())
        val codeExts = setOf("kt", "java", "py", "go", "ts", "js", "swift", "cs", "cpp", "c", "rb", "rs")

        outer@ for (file in root.walkTopDown()) {
            if (results.size >= 30) break
            if (!file.isFile || isExcludedPath(file.path)) continue
            if (file.extension !in codeExts) continue
            try {
                val rel = file.path.removePrefix(root.path).trimStart('/', '\\')
                for ((idx, line) in file.readLines(Charsets.UTF_8).withIndex()) {
                    if (results.size >= 30) break@outer
                    if (line.contains(keyword, ignoreCase = true)) {
                        results.add(SearchResult(rel, idx + 1, "$rel:${idx + 1}: ${line.trim().take(100)}"))
                    }
                }
            } catch (_: Exception) {}
        }
        return results
    }

    // ==================== 工具方法 ====================

    private fun isExcludedPath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.contains("/.git/") ||
               normalized.contains("/build/") ||
               normalized.contains("/target/") ||
               normalized.contains("/.gradle/") ||
               normalized.contains("/node_modules/") ||
               normalized.contains("/.idea/") ||
               normalized.contains("/out/")
    }

    /**
     * 将搜索结果列表格式化为供 LLM 阅读的文本
     */
    fun formatResults(results: List<SearchResult>): String {
        if (results.isEmpty()) return "未找到匹配结果"
        return results.joinToString("\n") { r ->
            val loc = if (r.lineNumber > 0) ":${r.lineNumber}" else ""
            "${r.filePath}$loc  ${r.displayText}"
        }
    }
}
