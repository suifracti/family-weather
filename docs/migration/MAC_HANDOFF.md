# Windows → Mac 历史接手摘要（脱敏版）

这是2026-10-05交接的公开元数据摘要，2026-10-08移除了个人路径及私有传输标识。原始文档、访问索引和证据按原字节保存在所有者的私有恢复材料中，未删除或改写。此文不证明Windows当前未提交成果与Mac一致。

历史交付基线为 `migration/mac-20261005` 的 `2db4508d3493a44de3d20d730a75aa8235897c76`。当前开发入口及暂停状态见[开发说明](../DEVELOPMENT.md)、[暂停基线](../PAUSED_BASELINE.md)。旧分支、目录和旧APK仅为历史恢复依据，不应整包倒灌。

## 固定依赖与恢复

[required-assets.json](required-assets.json)记录模型、词表、原生AAR及许可证的固定版本、字节数和SHA256；[transfer-downloads.json](transfer-downloads.json)只保留分片与证据包的校验元数据，不再包含私人账户文件ID或下载链接。取得私有文件须通过所有者的恢复材料；不能改成公开分享，也不能补凑其他日期或模型版本。

四个分片合并后的ZIP大小为342175933字节，SHA256为：

```text
4400c771d9be73e2835b53f50bbc540b799d7023fb20790376bf5fac866f98c5
```

```sh
cat required-assets.zip.part01 required-assets.zip.part02 required-assets.zip.part03 required-assets.zip.part04 > required-assets.zip
shasum -a 256 required-assets.zip
python3 tools/migration/restore_assets.py --bundle /path/to/required-assets.zip
```

先核验总SHA，再恢复成员。恢复器会校验成员且不覆盖已有不同文件。也可按清单从固定官方来源恢复；网络可用性并非保证。模型、APK、个人配置、数据库、缓存、原始媒体与敏感日志不进Git。

源码恢复还需本机 `local.properties` 和未入库的 `LocationAuthority.kt`。使用无凭据配置模板及无坐标地点模板；真实地点只由所有者的私有配置恢复，不填0，不用其他城市替代。原机配置继续保留。

## 版本及验收边界

历史sv4制品为 `6.2.2-r6244-sv4`／60210／`com.family.weather.sensevoice`／ARM64 basicDebug，SHA256为 `85cd4e7e9d890761e181bf5479dafe5aa96904f9b355cdc291f769fbe5a208eb`。当前封存包和限制见[暂停基线](../PAUSED_BASELINE.md)，不要用历史版本替代当前候选。

现有Mac记录涵盖依赖恢复、ARM64构建、原生库与最小播放以及定向天气链路；sv4内容质量、首页易用性和TLS／ANR缺口仍在。独立SenseVoice实验不等于生产集成，不引入电脑服务或默认大语言模型。

Windows当前未提交差异暂缓且未核。保留原目录、所有工作树、分支、历史制品和原始证据；历史快照不能替代实时核对。构建使用JDK17、Gradle wrapper和现有Android SDK，保持minSdk23、targetSdk36，具体锁定版本以当前构建文件为准。

## 隐私边界

此摘要和当前源码的脱敏不清除旧Git历史。原私有仓库、完整bundle、原APK及私人恢复材料不能因此直接公开；不得上传到公开附件。历史源码快照清单和证据索引仅说明各自历史字节身份，不代表本次重新执行了构建或内容验收。
