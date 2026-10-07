# 源码与私有恢复材料的边界

2026-10-08按所有者要求处理当前源码树中的隐私残留。此改动只用于源码脱敏，不增加功能，不表示ASR、首页或稳定性问题解决。

## 当前源码树

- 个人Mac／Windows路径改为可配置目录或通用占位说明。
- 迁移传输清单只保留文件名、大小和校验；私人Drive文件ID及链接移至私有原件。
- benchmark保留原识别输出和质量结论，只移除个人路径，并明确标记为脱敏元数据副本。
- 实时天气测试不再自动搜索个人笔记库读取密钥，也不打印地点坐标、完整缓存地点身份或可能带URL/凭据的异常消息。
- 历史桌面ASR工具使用 `FAMILY_WEATHER_ASR_WORKDIR`（默认项目内忽略的`.asr_scratch`）；ffmpeg由 `FAMILY_WEATHER_FFMPEG` 指定或使用PATH中的ffmpeg。带真实音频的旧集成测试仅在明确设置 `FAMILY_WEATHER_ASR_FIXTURE_DIR` 时运行；本次未运行。
- 地点配置仍由忽略的 `LocationAuthority.kt` 提供，公开模板无真实坐标。不会删除用户本机配置、模型、媒体或缓存。

检查当前源码可运行：

```sh
python3 tools/migration/check_source_privacy.py
python3 -m unittest discover -s tools/migration/tests -p 'test_source_privacy.py'
```

检查器是有限规则检查，只输出文件位置与类别，不输出匹配原值。它不替代历史、APK或未知凭据格式的审计，也不会联网、运行模型或修改文件。

## 继续私有保留

原文档、原始证据元数据、私有访问索引按原字节留在项目外恢复材料，清单包含原提交及SHA256。完整旧Git历史、分支和标签已保存为校验通过的私有bundle。封存APK也包含原地点常量，仅供所有者恢复运行；不要作为脱敏APK上传。

所有者已授权历史重写及同名重建，最终只使用 `suifracti/family-weather` 一个公开地址。重建会改变GitHub仓库身份；公开历史仅从当前脱敏源码快照开始，不上传旧分支、标签、APK或恢复包。重建先保持私有，检查已知旧隐私blob与commit无法取回后再公开。Windows目录及可能独有的未核成果不动。

GitHub说明历史重写后旧提交仍可能经缓存SHA或其他引用访问，必要时需由GitHub Support清除服务端残留。不能只凭本地清洗成功就切换Public：[GitHub敏感数据移除说明](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository)。
