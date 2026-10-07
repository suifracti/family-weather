# 开发与恢复入口

**状态：暂停开发，保留未来继续（2026-10-07）。** 当前封存基线收录果园和CMA／温度／天气证据缓存成果；源码公开整理不等于发布安装包或通过整体验收，对应包和未完项见[暂停基线](PAUSED_BASELINE.md)。下面命令用于以后恢复，不表示本次要继续构建、补验或自动运行。

唯一源码仓库为 `suifracti/family-weather`，正式开发主线是 `main`。固定 Mac 工作目录为 `<workspace>/family-weather`，使用 `main` 并跟踪 `origin/main`，不再新增长期整合／备份分支。逐项采用依据见 [31项整合决策](migration/MAINLINE_INTEGRATION.md)。

构建前先配置本机Git忽略的 `domain/src/main/kotlin/org/breezyweather/domain/multisource/location/LocationAuthority.kt`；无坐标模板为同目录 `LocationAuthority.kt.example`。不能把真实坐标重新提交到Git。原机配置保留，独立恢复由所有者私有材料补入；缺配置不填0或其他城市。

构建使用现有 JDK 17、Gradle wrapper 与本地 Android SDK。保留 minSdk 23、targetSdk 36、compileSdk 37 与 Build Tools 36。依赖升级必须遵守 API23 安装兼容；不使用 overrideLibrary 绕过检查。Vico 使用 2.5.2，Media3 保持生产已验配置。

```sh
python3 tools/migration/restore_assets.py
./gradlew :app:assembleBasicDebug --console=plain
```

私有分片的固定大小和 SHA 见 [交接](migration/MAC_HANDOFF.md) 与 [依赖清单](migration/required-assets.json)。现有不同文件不覆盖。`tools/migration/download_assets.py` 是 CI 从清单内官方固定版本恢复的入口，先验证压缩归档及每个文件 SHA，不选择替代日期／版本；缺失或不匹配直接失败。

main 的 CI 配置可构建 basicDebug 并运行模型标识兼容检查；PR 同样面向 main。CI 不使用上游品牌参数、不上传 APK／模型，不创建标签或发布。固定官方依赖首次下载约270 MB（恢复后约342 MB），只在临时 runner 构建使用；Gradle cache 不包含工程中的模型资产。上游锁帖、过期 issue 和标签评论工作流限定上游仓库，不作用于私有问题记录。

生产手机使用 SenseVoice int8、结构化摘要和明确失败后的 Vosk 回退，不需要电脑服务或默认大语言模型。当前内容质量仍未通过；完整准备、模型推理及旧故障矩阵不随本轮整合而重验，见 [字幕链路当前说明](ANDROID_LOCAL_EPISODE_ASR.md)。

## 留档及分支处理

清理的三个旧 dist 生成文件与 `subtitle-hosting/subtitles/` 字节完全相同，字幕唯一归档、原始证据和有效测试继续保留。无生产调用的旧 LocalGenerativeSummary 与历史长说明已迁到项目外私有目录 `<private-retention>/retained-mainline`，其 manifest.json 记录路径、原字节 SHA 和源提交；同样可由 Git 历史恢复。不删除许可证、模型来源、个人设置、缓存、设计原稿或手动资料。

Windows 当前主目录、UI 工作树、迁移目录及独立实验目录仍无法实时访问。58份历史快照与六文件 UI 记录不证明当前没有未提交成果；这些目录和关联分支不删除。主线整合仅声明本次实际核清的 Git 增量。旧分支删除须按项目外具体清单另行确认，通过一次 bundle 和清单恢复，不新建长期备份分支。

公开源码时保留现有工作流文件，但关闭仓库Actions，避免上传源码触发自动构建、维护或发布。此状态不影响本机构建；以后重新启用须单独确认。完整未清洗历史只在项目外私有恢复材料中保存，不再推回远端。
