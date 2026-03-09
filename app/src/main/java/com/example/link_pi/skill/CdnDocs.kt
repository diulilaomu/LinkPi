package com.example.link_pi.skill

/**
 * Grouped CDN library documentation.
 * Only relevant groups are injected based on the active Skill.
 */
object CdnDocs {

    private val FRAMEWORK = """
- Vue 3: https://cdn.bootcdn.net/ajax/libs/vue/3.3.4/vue.global.prod.min.js
- Vue 2: https://cdn.bootcdn.net/ajax/libs/vue/2.7.14/vue.min.js
- React: https://cdn.bootcdn.net/ajax/libs/react/18.2.0/umd/react.production.min.js
- ReactDOM: https://cdn.bootcdn.net/ajax/libs/react-dom/18.2.0/umd/react-dom.production.min.js
""".trimIndent()

    private val CHART = """
- Chart.js: https://cdn.bootcdn.net/ajax/libs/Chart.js/4.4.0/chart.umd.min.js
""".trimIndent()

    private val THREE_D = """
- Three.js: https://cdn.bootcdn.net/ajax/libs/three.js/r128/three.min.js
""".trimIndent()

    private val UTILS = """
- Axios: https://cdn.bootcdn.net/ajax/libs/axios/1.6.0/axios.min.js
- Animate.css: https://cdn.bootcdn.net/ajax/libs/animate.css/4.1.1/animate.min.css
""".trimIndent()

    private val GROUP_MAP = mapOf(
        CdnGroup.FRAMEWORK to FRAMEWORK,
        CdnGroup.CHART to CHART,
        CdnGroup.THREE_D to THREE_D,
        CdnGroup.UTILS to UTILS
    )

    /**
     * Build CDN documentation for the given groups.
     * Returns empty string if groups is empty.
     */
    fun build(groups: Set<CdnGroup>): String {
        if (groups.isEmpty()) return ""
        val libs = groups.mapNotNull { GROUP_MAP[it] }.joinToString("\n")
        return """
### CDN 库（国内可访问——不要使用 unpkg/jsdelivr）

$libs
始终为 CDN 脚本添加 onerror 回退：<script src="cdn_url" onerror="document.title='CDN加载失败:'+this.src"></script>
使用 Vue 时添加 v-cloak 和 [v-cloak]{display:none}，防止初始化前显示模板语法。
""".trimIndent()
    }
}
