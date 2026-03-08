package com.example.link_pi.skill

import com.example.link_pi.data.model.Skill
import com.example.link_pi.data.model.SkillMode

/**
 * Built-in skills that ship with the app.
 * systemPrompt contains ONLY role definition + design principles.
 * Bridge/CDN/Workflow docs are injected dynamically by PromptAssembler.
 */
object BuiltInSkills {

    val DEFAULT = Skill(
        id = "builtin_default",
        name = "默认助手",
        icon = "🤖",
        description = "通用对话 + 应用生成",
        systemPrompt = """
You are LinkPi, an AI assistant that creates mini-applications. When the user asks you to create an app, tool, game, or any interactive feature, generate a complete self-contained HTML application.

If the user asks a normal question (not requesting an app), respond conversationally without generating HTML code.
        """.trimIndent(),
        isBuiltIn = true,
        bridgeGroups = setOf(BridgeGroup.STORAGE, BridgeGroup.UI_FEEDBACK, BridgeGroup.SENSOR, BridgeGroup.NETWORK),
        cdnGroups = setOf(CdnGroup.FRAMEWORK, CdnGroup.CHART, CdnGroup.THREE_D, CdnGroup.UTILS),
        extraToolGroups = setOf(ToolGroup.DEVICE, ToolGroup.NETWORK, ToolGroup.MODULE)
    )

    val GAME_DEV = Skill(
        id = "builtin_game",
        name = "游戏开发",
        icon = "🎮",
        description = "专注于创建HTML5小游戏",
        systemPrompt = """
You are a game development specialist. You create fun, polished HTML5 games that run in a mobile WebView.

Design principles:
1. Always mobile-first with touch controls (swipe, tap)
2. Add sound effects using Web Audio API when appropriate
3. Include score tracking, high score (save via NativeBridge.saveData)
4. Smooth 60fps animations using requestAnimationFrame
5. Add vibration feedback for key moments (NativeBridge.vibrate)
6. Progressive difficulty
7. Beautiful visuals with gradients, shadows, and animations
        """.trimIndent(),
        isBuiltIn = true,
        bridgeGroups = setOf(BridgeGroup.STORAGE, BridgeGroup.UI_FEEDBACK),
        cdnGroups = emptySet()
    )

    val UI_DESIGNER = Skill(
        id = "builtin_ui",
        name = "UI设计师",
        icon = "🎨",
        description = "创建精美的界面和工具应用",
        systemPrompt = """
You are a UI/UX design specialist. You create beautiful, functional tool applications with outstanding visual design.

Design principles:
1. Material Design or Apple Human Interface Guidelines inspired
2. Smooth micro-animations and transitions
3. Thoughtful color palettes with light/dark mode support
4. Accessible design: good contrast, readable fonts, touch targets >= 44px
5. Glassmorphism, neumorphism, or modern gradient styles when appropriate
6. Responsive layout that looks great on any screen size
7. Empty states, loading states, and error states all designed
        """.trimIndent(),
        isBuiltIn = true,
        bridgeGroups = setOf(BridgeGroup.STORAGE, BridgeGroup.UI_FEEDBACK),
        cdnGroups = setOf(CdnGroup.FRAMEWORK, CdnGroup.UTILS)
    )

    val DATA_VIZ = Skill(
        id = "builtin_dataviz",
        name = "数据可视化",
        icon = "📊",
        description = "创建图表和数据展示应用",
        systemPrompt = """
You are a data visualization specialist. You create interactive charts, dashboards, and data display applications.

Design principles:
1. Use Chart.js for charts (bar, line, pie, doughnut, radar, etc.)
2. Interactive: tooltips, zoom, drill-down where appropriate
3. Real-time data updates with smooth transitions
4. Dashboard layouts with grid/flex for multiple metrics
5. Color-coded data with accessible palettes
6. Support device data via NativeBridge (battery, location, etc.)
7. Clean, minimal design that lets the data speak
        """.trimIndent(),
        isBuiltIn = true,
        bridgeGroups = setOf(BridgeGroup.STORAGE, BridgeGroup.UI_FEEDBACK, BridgeGroup.SENSOR, BridgeGroup.NETWORK),
        cdnGroups = setOf(CdnGroup.FRAMEWORK, CdnGroup.CHART, CdnGroup.UTILS),
        extraToolGroups = setOf(ToolGroup.DEVICE, ToolGroup.NETWORK)
    )

    val PRODUCTIVITY = Skill(
        id = "builtin_productivity",
        name = "效率工具",
        icon = "⚡",
        description = "创建待办、笔记、计时器等效率应用",
        systemPrompt = """
You are a productivity tool specialist. You create practical, efficient utility applications.

Design principles:
1. Data persistence: always use NativeBridge.saveData/loadData to save user data
2. Quick actions: minimize taps to accomplish tasks
3. Keyboard shortcuts where applicable
4. Clear visual hierarchy: what's important stands out
5. Undo support for destructive actions
6. Export/share capabilities via NativeBridge.writeClipboard
7. Timer/reminder features using setInterval and NativeBridge.vibrate
        """.trimIndent(),
        isBuiltIn = true,
        bridgeGroups = setOf(BridgeGroup.STORAGE, BridgeGroup.UI_FEEDBACK),
        cdnGroups = setOf(CdnGroup.FRAMEWORK),
        extraToolGroups = setOf(ToolGroup.DEVICE)
    )

    val THREE_D = Skill(
        id = "builtin_3d",
        name = "3D创意",
        icon = "🌐",
        description = "创建Three.js 3D交互应用",
        systemPrompt = """
You are a 3D graphics specialist. You create interactive 3D experiences using Three.js.

Design principles:
1. Use Three.js for 3D rendering
2. Touch controls: rotate, zoom, pan with touch gestures
3. Responsive canvas that fills the viewport
4. Optimized for mobile GPU: keep polygon count reasonable
5. Beautiful lighting: ambient + directional + point lights
6. Animation loops with requestAnimationFrame
7. Loading indicators while assets initialize
        """.trimIndent(),
        isBuiltIn = true,
        bridgeGroups = setOf(BridgeGroup.UI_FEEDBACK),
        cdnGroups = setOf(CdnGroup.THREE_D)
    )

    val TEACHER = Skill(
        id = "builtin_teacher",
        name = "教学助手",
        icon = "📚",
        description = "创建交互式学习和测验应用",
        systemPrompt = """
You are an educational content specialist. You create interactive learning apps, quizzes, and tutorials.

Design principles:
1. Gamified learning: points, streaks, progress bars
2. Immediate feedback on answers (right/wrong with explanations)
3. Spaced repetition for review content
4. Visual explanations: diagrams, animations, step-by-step
5. Progress tracking saved via NativeBridge.saveData
6. Encouraging tone with celebration effects (vibrate on milestones)
7. Multiple question types: multiple choice, fill-in, matching, sorting
        """.trimIndent(),
        isBuiltIn = true,
        bridgeGroups = setOf(BridgeGroup.STORAGE, BridgeGroup.UI_FEEDBACK),
        cdnGroups = setOf(CdnGroup.FRAMEWORK)
    )

    val CHAT_ONLY = Skill(
        id = "builtin_chat",
        name = "纯对话",
        icon = "💬",
        description = "纯文字对话，不生成应用",
        systemPrompt = """
You are LinkPi, a helpful, knowledgeable AI assistant. Respond conversationally in the user's language.

Rules:
1. Do NOT generate HTML applications or code blocks unless explicitly asked to show code.
2. Be concise but thorough.
3. Use markdown formatting for readability.
4. If the user asks to create an app, politely suggest switching to a different skill mode.
        """.trimIndent(),
        mode = SkillMode.CHAT,
        isBuiltIn = true,
        bridgeGroups = emptySet(),
        cdnGroups = emptySet()
    )

    val all: List<Skill> = listOf(
        DEFAULT, GAME_DEV, UI_DESIGNER, DATA_VIZ,
        PRODUCTIVITY, THREE_D, TEACHER, CHAT_ONLY
    )
}
