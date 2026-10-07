# 31项最终采用决策

基于实际代码/有效入口/库manifest/实际请求与定向检查，不以合并祖先关系证明成果覆盖。起点a76cd9ac，最新远端main d587328ae9755c8801f8cbc252a16ead7ebaea5a未新增提交。
共有21项已采用或部分采用、10项不采用；没有“保留待以后”的悬置结论。部分采用均明确列出拒绝部分。

| 提交 | 成果 | 最终决策与功能影响／依据 |
|---|---|---|
| `17764a06c36d` | New cycle | **不采用**：上游6.2.3周期、模板版本和技术说明；当前家庭品牌6.2.2-sv4/60210已专门定义，不能用版本周期覆盖。保留历史提交，不拿上游发布日志证明私有功能。；不采用周期/发布身份 |
| `413b6f8424c4` | Vico2弃用API | **已采用**：起点BreezyBar/Line/Ephemeris及Nowcast仍用旧consumeMoveEvents=true；实际缓存2.5.2 AAR已提供markerController.showOnPress/rememberShowOnPress。行为目标原本等价，但源码清理未包含；无需升级Vico3。；本轮已采用原补丁；仍消费移动事件 |
| `5e36cd77bfa3` | 排序恢复默认入口 | **采用**：新增主动恢复普通卡片/两种趋势默认设置，不修改DEFAULT_CARD_DISPLAY；家庭首页两张固定卡不依赖该列表。 |
| `5d79ca456b56` | 暂停BMKG告警 | **采用并适配**：旧JSON接口无凭据401，当前没有合法token；本接入不可用，官方CAP合法可达但schema不同未集成。标注不可用，天气预报保留；界面/通知/组件/共享输出屏蔽该缓存，数据库不清空。 |
| `35af5a6b9155` | 移除Android6支持 | **不采用**：用户明确保留Android6/min23全部兼容路径。 |
| `02a9cbd5678f` | Android6移除日志 | **不采用**：只改CHANGELOG，记录Android6移除；当前MIN23仍保留，日志不能先于实际选择。；不采用不实日志 |
| `385c6a4ff66e` | Open-Meteo模型ID与选项 | **采用并适配**：官方页面52项均对应新ID，5项新全球模型小请求HTTP200返回独立字段；11等价旧ID兼容，GraphCast/未知值提示重选并阻止请求，不换名返回替代数据；小时响应兼容保持真实来源和时段。 |
| `6adf82c2d98a` | 来源UI及隐私链接 | **部分采用**：采用SelectableSource与有效字段访问；不采用上游被注释入口对应的不可达隐私dialog/trailing hook，保留API23 Calendar注解。 |
| `2a0ff428c7f5` | 首页主题说明 | **已采用**：当前主题代码已有日夜/黑太阳；文档仍旧表和设置位置说明。main文案无条件称默认Always dark，与SDK<29实际默认不符。；本轮采用并适配Android10+/旧版本默认说明；无设置变化 |
| `464db825656b` | 清理旧版本参考 | **已采用**：HOMEPAGE/UPDATES仍有限定旧版本号；当前RefreshHelper已具备国家码/时区修正及更新回退，删除旧版本限定是准确文档维护。；本轮采用原补丁 |
| `3737a9cae106` | 贡献文档排版 | **已采用**：当前两列表缺空行、既有AI声明未加粗；纯排版，未新增权限流程，不影响应用。；本轮采用原补丁 |
| `697f5fd35cfd` | 第一次依赖维护 | **部分采用**：采用aboutlib/compose-lint/immutable/XML及最终Spotless等适用版本；Material3alpha28、Navigation2.10.1实际AAR最低24，min23明确拒绝；中间值不采用。 |
| `6245edfb8bc7` | 小时阵风显示 | **采用**：小时阵风副柱/pill/TalkBack，保留12/24dp与字体倍率，新pill指标同倍率，预留变化requestLayout。 |
| `bdb92a15503e` | 日阵风显示 | **采用**：日阵风与图表范围/高度，保持wind.speed有效性判断及阈值覆盖；两侧动态预留和回收布局更新。 |
| `6cf55e5a8894` | 紧凑图表顶边距 | **已采用**：当前PolylineAndHistogram固定24dp，无hasTopPolylineLabel。补丁因旧类无gust字段而整份上下文不匹配，三项本质变化与gust无关。；本轮适配：普通12dp，日温度/体感顶标签保留24dp，并配合字体倍率 |
| `f85261dd0f1f` | targetSdk37 | **不采用**：用户明确保留target36/BuildTools36，不提高安装/平台要求。 |
| `ebd7cd2c6e8e` | 图表字体缩放 | **已采用**：DailyTrendItem已有fontScaleToApply，但host/两个chart数值标注仍固定dp；main还改不存在的secondary gust字段。已有字段可拆，不必先加阵风。；本轮适配现有字段；文本与相应边距同倍率，无gust字段 |
| `ad7d3afa5936` | 详情图表效果与性能 | **部分采用**：继续保持a76的12个数据效果键；不采用未完整列主题/provider键的额外formatter记忆化，不以性能优化覆盖已验缓存更新。 |
| `d40dab2bdd95` | 补充花粉映射 | **采用**：补五项实际域字段花粉来源映射，不修改既有ID、数据库、来源选择。 |
| `6119f7bf2224` | Vico3 WIP迁移 | **不采用**：当前锁Vico2.5.2，生产图表已可用；main为3.x WIP，改views依赖/接口/图标方案，不能按所有分支合并引入。；不采用此迁移链；保留历史，不删除 |
| `4f69db987c58` | Vico3图标补修 | **不采用**：当前2.x仍走SpannableString/ImageSpan顶轴图标，无3.x迁移造成的故障；main持久IconCartesianMarker仅补前一WIP。；不把依赖3.x的补修倒灌2.x；不宣称旧2.x图标运行案例已重验 |
| `ce601a127311` | Vico3弃用API清理 | **不采用**：实际2.5.2只含core lineSeries/columnSeries，无3.x lineModel/columnModel；直接改导入会破坏当前编译。；不采用不适用API；与413b6的2.x清理区别处理 |
| `b83d79cbc4d5` | 花粉等级对外共享 | **不采用**：新datasharing628de实测manifest min24，与用户min23冲突；不使用overrideLibrary；provider字段和库成对不采用。夹带AGP9.4单独采用维护。 |
| `43e5839b9da5` | release action维护 | **已采用**：当前pin为gh-release3.0.2；main3.0.3是独有维护，原tag/repo条件保持，不会因本地编辑触发发布。；本轮采用最终固定SHA；云CI结果单独记录 |
| `5f03d2d8d6d8` | setup-java6.0.0中间步 | **不采用**：当前5.7.0，main后来201c32为6.0.1；此项不是已包含，但中间目标已被替代。；跳过中间值，直接采用201c32最终pin |
| `f20eb23e04ba` | Spotless8.10.2 | **采用**：Spotless最终8.10.2，跳过中间版本；不全仓格式重写。 |
| `809110774c4a` | Gadgetbridge新增导出字段 | **采用**：Gadgetbridge日/小时pressure、cloudCover及小时dewPoint可空字段，mbar/percent/Kelvin单位；intent/发送选择不改。 |
| `b5228113d3bd` | Gadgetbridge发布日志 | **不采用**：不采用上游6.2.3发布日志原补丁；Gadgetbridge实际采用结果准确写入家庭未发布开发说明，不冒充已发布版本。 |
| `d7471164c66c` | 第二次依赖维护 | **部分采用**：BOM09/Kotlin2.4.20/Kotest6.2.5/SQLiteAndroid2.7.1采用；Material3alpha28和Navigation2.10.1因实测min24不采用。 |
| `201c32d67f48` | setup-java6.0.1最终维护 | **已采用**：起点两个workflow仍5.7.0。runner为ubuntu24.04，保留Temurin及java-version-file，最终pin适用。；本轮采用最终pin，旧中间步跳过；云CI结果单独记录 |
| `d587328ae975` | KSP2.3.12 | **采用**：KSP2.3.12随Kotlin2.4.20实际生成与编译验证。 |

## 本轮核验与边界

实际 ARM64 basicDebug 构建、21项模型标识／来源映射／告警缓存／隔离协议单测，以及实际组件的日夜小时阵风绘制与复用、恢复默认适配器、花粉映射、Gadgetbridge导出单位和新增资源加载通过。此前a76的阈值覆盖、日风速判断和详情缓存刷新证据继续沿用；没有把启动等同交互通过。

Windows主目录、elder-ui工作树、mac-migration目录及独立实验目录未实时核清；58个历史路径不是当前状态证明。已将含固定基线、逐树status、staged/unstaged二进制diff、未跟踪源码和逐文件原字节SHA的要求交原主对话；这些目录和关联分支不删除。不用这个缺口阻塞已核清Git代码收敛。

当前sv4 / 60210内容质量未通过；完整准备、实际模型推理和旧全量矩阵未验。保留真实节目身份、共享任务、安全取消、模型／媒体／处理链缓存隔离与Vosk回退，不合入独立大模型实验。
