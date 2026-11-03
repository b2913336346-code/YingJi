# 文档索引

文档按用途分目录保存，沿用原文件名和内容。开发手册描述对应版本的计划与约定，实际功能与验收状态需结合当前源码和验收记录判断。

## 需求、设计与测试

- [需求文档](requirements/)：产品需求与应用需求汇总。
- [设计文档](design/)：各版本设计资料。
- [测试与验收方案](testing/)：检测文档、验收基线和测试方案。
- [统一总纲与实施基线](baselines/)：统一规则与实施基线原件。

## 开发手册

V3.1 手册存放在 `development/v3.1/`：

- [基础应用开发手册](development/v3.1/基础影迹开发手册V3.1.md)
- [AI 开发手册](development/v3.1/影迹%20AI%20V3.1%20开发手册.md)
- [模块接口契约与集成验收清单](development/v3.1/模块接口契约与集成验收清单v3.1.md)

历史手册存放在 `archive/`，不与较新版本合并：

- [V1 手册](archive/v1/)：保留原有 `docx/` 和 `md/` 分组。
- [V2 手册](archive/v2/)：原 `docs/ai-v2/`，保留原有格式分组。
- [V2 早期资料](archive/v2-legacy/)：原先嵌套在 `docs/ai-v1/` 内的 V2 手册，单独保留，避免覆盖其他版本。

[历史冻结记录](freeze/2026-10-26-base/README.md) 保留原路径，以保持验收构建配置、历史清单及复核命令的连续性。

## 资源与本地验收资料

- 图标设计源文件放在 [`design/icons/`](../design/icons/)，与应用实际使用的 `app/src/main/res/` 资源分别管理。
- `artifacts/acceptance/`：按既有命名保存阶段验收输出。
- `artifacts/freeze/`：按既有分组保存冻结证据。
- `artifacts/ui/navigation/screenshots/`：导航相关界面截图。
- `artifacts/ui/navigation/layouts/`：对应界面层级 XML。

`artifacts/` 中的原始日志、截图和设备输出仅保留在本机，由 `.gitignore` 排除，新检出的仓库不包含这些文件。历史证据清单中的路径和指纹保留当时记录，不作为本次重新验收的结论。

## 后续文件存放方式

新增资料按用途放入对应目录；开发手册按版本建立子目录，历史版本不覆盖或合并。Word 原稿与 Markdown 手册分别放入 `docx/` 和 `md/`，若某版本仅有 Markdown，可沿用该版本现有结构。保留原始文件，不批量转换格式或删除内容相近的资料。

源码、构建配置、Gradle Wrapper、Room Schema 和历史冻结配置保持现有位置。调整文档位置时同步维护本索引及引用路径。
