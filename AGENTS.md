# AGENTS.md

## 身份

本项目是 **A11yPilot** — 一个 Android AI 手机代理应用。通过无障碍 API 读取屏幕，使用 LLM（Claude/OpenAI/第三方）驱动手机操作，同时支持作为 MCP 服务器暴露工具给外部客户端。

**专业定位**：Android 原生应用开发，Kotlin + Jetpack Compose，涉及无障碍服务、MCP 协议、LLM API 集成。

## 职责

1. **维护应用核心功能**：无障碍服务、屏幕序列化、工具执行器、Agent 循环
2. **维护 API 集成**：Anthropic 官方/第三方、OpenAI 兼容接口
3. **维护 MCP 服务器**：工具暴露、JSON-RPC 协议、Bearer 认证
4. **维护 UI 界面**：Jetpack Compose 设置页、主界面、悬浮窗
5. **维护多语言支持**：中英文国际化

### 边界 / 禁止

- **禁止在本地编译部署**：只通过 GitHub Actions CI/CD 部署
- **禁止删除现有文件或代码**：修改需保留原代码备份
- **禁止无关修改**：只执行开发者明确指令
- **禁止对话框给代码**：直接操作代码文件
- **禁止未要求的构建/环境检查**
- **单文件不超过 500 行**，单函数不超过 40 行，禁止超过 5 层嵌套

## 语言规范

- **主语言**：Kotlin
- **UI 框架**：Jetpack Compose
- **响应语言**：简体中文

### MUST

- Kotlin 代码风格：4 空格缩进，无通配符导入
- Composable 函数使用 `stringResource()` 获取字符串，禁止硬编码
- `stringResource()` 必须在 `@Composable` 上下文中调用，不能在 `remember` 块内
- API 密钥等敏感信息存储在 `EncryptedSharedPreferences`
- 新增工具必须同步更新 `ToolExecutor`、`Prompts.anthropicTools()`、`AgentEngine.dispatch()`、`JsonRpc.toolsCall()`

### SHOULD

- 使用 `data class` 表示数据模型
- 使用 `sealed class/interface` 表示状态枚举
- 网络请求使用 `OkHttpClient`，注意复用避免线程泄漏
- JSON 解析使用 `kotlinx.serialization`

### 优先使用库

| 库 | 用途 |
|---|---|
| Jetpack Compose | UI 框架 |
| OkHttp | HTTP 客户端 |
| kotlinx.serialization | JSON 序列化 |
| Ktor | MCP 服务器 |
| DataStore Preferences | 设置存储 |
| EncryptedSharedPreferences | 敏感信息存储 |

## 工具列表

| 类型 | 名称 | 用途 |
|------|------|------|
| 内置工具 | `dump_screen` | 读取当前屏幕无障碍树 |
| 内置工具 | `screenshot` | 截取屏幕截图 |
| 内置工具 | `click` | 点击指定节点 |
| 内置工具 | `set_text` | 设置文本输入 |
| 内置工具 | `scroll` | 滚动操作 |
| 内置工具 | `tap` | 坐标点击 |
| 内置工具 | `swipe` | 滑动手势 |
| 内置工具 | `global` | 系统导航（返回/主页/最近任务） |
| 内置工具 | `launch_app` | 启动应用 |
| 内置工具 | `wait` | 等待屏幕更新 |
| 内置工具 | `done` | 终止 Agent 循环 |

## MCP

A11yPilot 同时作为 MCP **客户端**和**服务端**：

| 角色 | 说明 |
|------|------|
| MCP 服务端 | 暴露手机操作工具给外部 AI 客户端（Claude Desktop、Claude Code 等） |
| MCP 客户端 | 连接外部 MCP 服务器，发现并调用其工具 |

**服务端配置**：设置页开启 MCP 服务器，配置端口（默认 8765），Bearer 令牌自动生成。

**客户端配置**：设置页填写 MCP 服务器 JSON，格式：`[{"url":"http://host:port/mcp","token":"..."}]`

## Skill

无项目级 Skill。

## 工作流程

1. **任务理解**：确认需求范围、关联文件
2. **代码扫描**：读取相关源码，理解现有实现
3. **代码实现**：按语言规范编写，保持单函数 ≤40 行
4. **提交推送**：通过 Git 提交，由 GitHub Actions CI 验证
5. **上下文更新**：重要变更写入 `ai*context.md`

## 部署规则

**只通过 GitHub Actions 部署，禁止本地编译。**

CI 流程：
1. Push 到 `main` 分支触发 `.github/workflows/ci.yml`
2. 执行 `./gradlew assembleDebug` 编译
3. 执行 `./gradlew lintDebug` 代码检查
4. 通过后自动上传 APK artifact
