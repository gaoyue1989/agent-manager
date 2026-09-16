# 文件支持设计方案（文档解析 + 图片多模态识别）

## 1. 背景与目标

### 1.1 背景

当前 agent-framework 的文件能力仅覆盖**纯文本**：

- Harness 内置 `FilesystemTool`（read_file / write_file / edit_file / grep_files / glob_files / list_files）只能按 UTF-8 读文本，docx/pptx/xlsx/pdf 等二进制格式读取后为乱码
- `ShellExecuteTool`（shell_execute）仅在沙箱模式（`SANDBOX_ENABLED=true`）可用，可借助沙箱内 python 处理文档，但非沙箱模式无任何解析能力
- 图片多模态：消息模型（DataBlock/ImageBlock）与事件流已支持，但缺少输入侧验证与图片读取工具，视觉模型接入未落地

参考 agentscope-java 官方方案（`agentscope-extensions-rag-simple` 的 Reader 体系：`WordReader`(POI) / `TikaReader` / `PDFReader`(PDFBox) / `ImageReader`），本框架需要补齐：

1. **文档处理读取**：word（docx）、ppt（pptx）、excel（xlsx）、csv、pdf、txt/md
2. **图片多模态识别**：对话中直接传图识别 + Agent 工具读取工作区图片识别

### 1.2 目标

1. 提供 Java 侧文档解析工具集（不依赖沙箱，两种模式均可用）
2. 文档内容统一转为 Markdown/文本注入 LLM 上下文
3. 支持 A2A 消息携带图片（DataPart）直接多模态识别
4. 支持 Agent 工具读取工作区图片调用视觉模型描述

---

## 2. 现状分析

### 2.1 已有能力

| 能力 | 现状 | 位置 |
|------|------|------|
| 文本文件读写 | ✅ read_file/write_file/edit_file（Harness 内置，带 baseDir 校验） | HarnessAgent 自动注册 |
| Shell 执行 | ✅ shell_execute（仅沙箱模式） | HarnessAgent 自动注册 |
| 沙箱（python3） | ✅ OpenSandbox（`opensandbox/code-interpreter:v1.1.0`，含 python3+pip） | OpenSandboxFilesystemSpec |
| DataBlock 消息模型 | ✅ 输出侧已处理 DataBlock 事件（流式转 base64） | AgentRuntimeService:264 / StreamController:87 |
| OpenAI 兼容多模态 | ✅ OpenAI formatter 支持 `image_url` content part | agentscope-extensions-model-openai |
| 自定义 @Tool 注册 | ✅ BusinessTools 模式（@Tool 注解 → customTools Bean） | BusinessTools.java |

### 2.2 缺口

| 缺口 | 说明 |
|------|------|
| 二进制文档解析 | docx/pptx/xlsx/pdf 无 Java 侧解析工具，非沙箱模式完全不可用 |
| csv 结构化读取 | 无专用工具（官方 agentscope-java 也无内置，标准做法是转 Markdown 表格） |
| 图片识别工具 | 无读取图片 → 视觉模型 的工具 |
| 输入侧图片验证 | A2A DataPart → Msg 链路未验证（依赖 SDK 转换 + LLM 供应商视觉支持） |

---

## 3. 方案设计

### 3.1 总体架构

```
用户上传/对话
   │
   ▼
A2A message/send（parts: DataPart 图片）或 Chat Channel
   │
   ▼
HarnessAgent（工具调用）
   ├── FileParsingTools（新增，Java 侧，两种模式均可用）
   │     parse_document_file / parse_csv_file / describe_image
   ├── FilesystemTool（已有，纯文本）
   └── ShellExecuteTool（已有，沙箱模式，python 处理）
   │
   ▼
LLM（OpenAI 兼容：qwen-vl / glm-4v / gpt-4o 等视觉模型）
```

- **文档解析优先走 Java 侧工具**（不依赖沙箱，行为一致、可测试）
- **沙箱模式下的 python 处理作为兜底**（复杂图表、需要计算的场景）

### 3.2 FileParsingTools 工具设计

新增 `tool/FileParsingTools.java`（沿用 BusinessTools 的 @Tool 注解模式），注册为 Spring Bean 加入 `customTools`。

#### 3.2.1 parse_document_file — 通用文档解析

```
parse_document_file(file_path, max_chars?, include_images?)
```

按扩展名自动路由解析器，输出统一 Markdown 文本：

| 扩展名 | 解析器 | 技术 | 输出 |
|--------|--------|------|------|
| .docx | Word 解析 | Apache POI (poi-ooxml) | 段落文本 + 表格(Markdown) + 图片(Base64，受 include_images/max_images 限制) |
| .pptx | Tika 兜底 | Apache Tika | 幻灯片文本（含表格文本） |
| .xlsx | Excel 解析 | Apache POI | 每个 sheet 转 Markdown 表格 |
| .pdf | PDF 解析 | Apache PDFBox | 文本（分页输出） |
| .csv | CSV 解析 | 纯 Java（RFC4180 解析） | Markdown 表格（自动识别分隔符） |
| .txt/.md | 直接读取 | Java NIO | 原样返回 |

设计要点：

1. **路径安全**：复用 `FileToolUtils.validatePath` 同款逻辑，工具构造时传入工作区 baseDir（`WorkspaceInitializer` 产物路径），拒绝越权访问
2. **大小保护**：`max_chars`（默认 `FILE_PARSE_MAX_CHARS`）截断，超长输出尾部追加 `...（已截断，共 N 字符，可加 max_chars 参数或分段读取）`
3. **解析失败兜底**：返回错误信息而非抛异常（ToolResultBlock.error 风格，参考 ReadFileTool 实现），并提示可尝试沙箱 python 方案
4. **docx 图片提取**：`XWPFParagraph.getEmbeddedPictures()` → Base64 + mediaType（参考官方 WordReader 的 `extractImageData` / `getMediaTypeFromPictureType`），数量上限 `FILE_PARSE_MAX_IMAGES`
5. **表格统一转 Markdown**：`| a | b |` + `---` 分隔行（参考官方 `tableToMarkdown`）

#### 3.2.2 parse_csv_file — CSV 专用

```
parse_csv_file(file_path, delimiter?, max_rows?, max_chars?)
```

- 纯 Java 实现，无第三方依赖
- 自动探测分隔符（`,` / `\t` / `;`）
- `max_rows` 限制行数（默认 200 行），头部 N 行 + 尾部 M 行 + 省略号提示
- 输出 Markdown 表格

#### 3.2.3 describe_image — 图片多模态识别工具

```
describe_image(image_path, prompt?)
```

1. 读取工作区图片文件（jpg/png/gif/webp/bmp/tiff），限制大小（`FILE_IMAGE_MAX_MB`，默认 5MB，防超大 base64）
2. 构造 `UserMessage(TextBlock(prompt), ImageBlock(Base64Source))`（参考官方 VisionExample）
3. 调用视觉模型（`VISION_MODEL_ID`，默认复用主模型）返回描述
4. 工具内部复用 `OpenAIChatModel` 实例（仅该模型支持视觉时可用；主模型非视觉模型时用 `VISION_MODEL_ID` 单独创建）

#### 3.2.4 工具说明示例

```java
@Tool(
    name = "parse_document_file",
    description = "Parse binary/text document files (docx/pptx/xlsx/pdf/csv/txt/md) "
        + "and return content as Markdown. Use this to read Word/PPT/Excel/CSV/PDF files.")
public String parseDocumentFile(
        @ToolParam(name = "file_path", description = "Path of the document file") String filePath,
        @ToolParam(name = "max_chars", description = "Max characters to return", required = false) Integer maxChars,
        @ToolParam(name = "include_images", description = "Extract embedded images (docx/pdf)", required = false) Boolean includeImages)
```

### 3.3 图片多模态：输入侧链路

A2A 消息携带图片（官方 SDK 支持）：

```
A2A message.parts = [ DataPart(data: base64, mimeType: image/png), TextPart ]
    → SDK PartParserRouter 转换 → Msg(ImageBlock/DataBlock)
    → HarnessAgent.stream → LLM（OpenAI formatter 转 image_url）
```

| 环节 | 状态 | 动作 |
|------|------|------|
| A2A DataPart → Msg | SDK 内置转换 | 无需改动，验证即可 |
| OpenAI formatter 图片 | ✅ 支持 image_url | 无需改动 |
| 视觉模型 | 依赖 LLM 供应商 | 需 `LLM_MODEL_ID` 或 `VISION_MODEL_ID` 配置为视觉模型（qwen-vl-max / glm-4v-plus / gpt-4o 等） |
| 非视觉模型降级 | — | 识别图片的请求会失败 → 报错信息提示配置 VISION_MODEL_ID |

**结论**：输入侧多模态基本零代码，核心是配置视觉模型 + 提供 `describe_image` 工具（工作区图片场景）。

### 3.4 配置项（AgentManagerProperties 新增）

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `FILE_TOOLS_ENABLED` | `true` | 是否注册 FileParsingTools（false 时工具不可见，兼容旧行为） |
| `FILE_PARSE_MAX_CHARS` | `20000` | 单文件解析最大字符数 |
| `FILE_PARSE_MAX_ROWS` | `200` | CSV 最大行数 |
| `FILE_PARSE_MAX_IMAGES` | `5` | docx/pdf 提取图片数量上限 |
| `FILE_IMAGE_MAX_MB` | `5` | describe_image 图片大小上限 |
| `VISION_MODEL_ID` | （空=主模型） | 图片识别专用模型 ID |

### 3.5 依赖变更（pom.xml）

```xml
<!-- 文档解析 -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.2.5</version>
</dependency>
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>3.0.7</version>
</dependency>
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>3.3.0</version>
</dependency>
```

> 版本与 agentscope-java dependencies-bom 对齐（pdfbox 3.0.7 / tika 3.3.0 / poi 由 bom 管理为 5.2.x）。
> 不引入 `tika-parsers-standard-package`（体积大 ~50MB）：pptx 文本提取优先用 POI 的 `XSLFSlideShow`（poi-ooxml 自带），xlsx 用 POI `XSSFWorkbook`，Tika 仅作未知格式兜底（tika-core + 手写 MIME 探测即可，甚至可不引入，见 3.6 备选）。

### 3.6 备选方案对比

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| **A. Java 侧工具（POI+PDFBox）** | 不依赖沙箱/网络、可单测、两种模式一致 | 镜像体积 +~15MB | ✅ 推荐主方案 |
| B. 沙箱 python（python-docx/python-pptx/pandas） | 生态全、支持复杂图表 | 仅沙箱模式可用；依赖 SANDBOX_ENABLED | 作为 A 的兜底/增强 |
| C. 仅 tika-parsers 全量解析 | 一个依赖全格式 | 体积大、错误信息差 | ❌ 不采用 |

---

## 4. 实施步骤

| Phase | 内容 | 涉及文件 |
|-------|------|---------|
| P1 | pom 增加 POI/PDFBox 依赖；FileParsingTools 骨架 + txt/md/csv 解析 | pom.xml、tool/FileParsingTools.java、AgentManagerProperties |
| P2 | docx 解析（段落 + 表格 Markdown + 图片 Base64） | FileParsingTools（复用官方 WordReader 逻辑） |
| P3 | xlsx（POI XSSF）+ pdf（PDFBox）解析 | FileParsingTools |
| P4 | describe_image 视觉工具（OpenAIChatModel 二次实例化） | FileParsingTools、AgentScopeConfig |
| P5 | 注册 customTools + FILE_TOOLS_ENABLED 开关 + 工具过滤联动 | AgentScopeConfig |
| P6 | 测试（见 §5）+ 系统提示词补充文件工具使用说明 | 测试目录、OAF 模板 |

---

## 5. 测试计划

| 测试类型 | 用例 | 位置 |
|---------|------|------|
| 单元测试 | csv/txt/md 解析（分隔符探测、max_rows 截断） | FileParsingToolsTest |
| 单元测试 | docx 解析 fixture（段落/表格/图片），图片数量上限 | FileParsingToolsTest |
| 单元测试 | 路径穿越防护（`../`、绝对路径越权） | FileParsingToolsTest |
| 单元测试 | 超长文件截断提示 | FileParsingToolsTest |
| 单元测试 | 不支持格式 / 文件不存在 / 解析失败的错误返回 | FileParsingToolsTest |
| 集成测试 | customTools 注册 + /tools 展示 parse_document_file | AgentScopeConfigTest 扩展 |
| 集成测试 | A2A message/send 携带 DataPart 图片 → 消息链路转换 | A2AServerConfigTest 扩展 |
| 手动验证 | 真实视觉模型 describe_image / 对话传图识别 | e2e |

---

## 6. 风险与注意事项

1. **沙箱模式路径差异**：沙箱模式下工作区文件在沙箱容器内，Java 侧工具读不到。处理：FileParsingTools 检测到沙箱文件系统时返回提示"沙箱模式请使用 shell_execute + python"，或通过 WorkspaceSyncService 拉取文件（二期可选）
2. **镜像体积**：poi-ooxml + pdfbox 约 +15MB，可接受；不引入 tika-parsers 全量包
3. **视觉模型依赖供应商**：LLM 供应商不支持图片（如纯文本模型）时，多模态请求失败需给出明确错误提示（区分"模型不支持"与"请求失败"）
4. **大文件保护**：所有解析路径必须经过 max_chars / max_rows / 图片大小限制，防止 OOM 与上下文爆炸
5. **安全**：路径穿越防护必须覆盖所有 file_path 参数；describe_image 仅允许工作区内路径
6. **工具可见性**：FILE_TOOLS_ENABLED=false 时不注册工具，与现有 tools.json deny 机制兼容（deny 优先）
