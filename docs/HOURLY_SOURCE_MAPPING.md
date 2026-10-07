# 逐小时来源核对（2026-10-03）

默认请求地点由本机私有工作点配置提供，本文不包含其经纬度。模型的提供机构所在地不等于覆盖地区，当前指定的均为覆盖中国的全球模型。节目联播不是逐小时预报来源。

|界面来源|实际接口和上游|可用值及边界|
|---|---|---|
|德国 ICON|Open-Meteo `/v1/forecast`，`icon_global`，DWD ICON 全球模式；概率来自 ICON-EPS|逐小时降水量、天气码、概率；不是只覆盖德国的 ICON-D2|
|欧洲 IFS|同一 Open-Meteo 接口，`ecmwf_ifs025`，ECMWF IFS 全球模式及 IFS 集合概率|降水量、天气码、概率；不是仅欧洲地区接口|
|美国 GFS|同一接口，`gfs_global`，NOAA GFS 全球模式及 GEFS 概率|降水量、天气码、概率；中国地点不用美国境内 HRRR|
|中国气象局 GRAPES|同一接口，`cma_grapes_global`，CMA GRAPES 全球模式|降水量、天气码；本次概率全部 null。原生3小时模式由网关输出小时值，不冒称本地每小时观测|
|日本 GSM|同一接口，`jma_gsm`，JMA 全球模式|降水量、天气码；本次概率全部 null|
|组合优选|同一接口，`best_match`|具体模式未在响应中披露；与已知物理模式重叠时不新增独立意见|
|和风天气|配置存在才使用专属域名 `/weather/v1/hourly/{纬度}/{经度}`，当前不再自动降级到旧 `/v7/weather/24h` 城市接口|官方说明全球覆盖；模型上游不透明，不计作独立物理模式。本轮不读取或新增凭证|
|小米天气缓存|现有 `china` 主天气源使用 `weatherapi.market.xiaomi.com` 或 `weatherapi.intl.xiaomi.com` 的 `/wtr-v3/weather/all`；只借用相同工作点已有缓存|署名含北京天气/彩云等，但逐小时字段具体上游未披露。不得冒称直接彩云或北京局 API；缓存无小时 mm/PoP，不新增请求或独立模式|

一次现有接口实读：上述 Open-Meteo 请求 HTTP 200，共72个时间点。CMA 的降水量/天气码各72个有效值，概率0/72；日本同样；IFS、GFS、ICON和组合优选三个字段各72/72。这个证据只证明当次地点/时段可读，不承诺中国普通网络稳定性或预测准确率；无需重复请求确认。

修正：首页展示有降水量或天气记录的来源，缺概率显示“未给概率”；null不变成0或无雨。Open-Meteo 在时间戳 t 的降水量和概率描述前一小时，统一归入 `t-1小时..t` 后再按本地日期筛选，午夜00:00归入前日23–24点；天气码是区间结束时刻的现象。旧小时缓存版本失效，防止继续复用错位时段。已知同一物理模式跨网关只计一次；未知综合来源不增加独立模式数。`>`与`>=`概率口径不混合。

官方依据：

- [Open-Meteo 全部模式与变量定义](https://open-meteo.com/en/docs)：区分模型机构与全球/地区覆盖；降水量和概率是 preceding hour，天气码是 instant。
- [CMA 全球模式](https://open-meteo.com/en/docs/cma-api)、[德国 ICON 全球/欧洲/D2 范围](https://open-meteo.com/en/docs/dwd-api)、[ECMWF](https://open-meteo.com/en/docs/ecmwf-api)、[GFS 与美国境内 HRRR](https://open-meteo.com/en/docs/gfs-api)。
- [Open-Meteo 官方路由源码](https://github.com/open-meteo/open-meteo/blob/main/Sources/App/Controllers/ForecastapiController.swift)：IFS025接 IFS 概率读取器；GFS接 GEFS；ICON Global接 ICON-EPS；CMA按独立确定性模式提供。路由源码不等于响应已披露某一期具体模型运行编号。
- [Open-Meteo 使用条款](https://open-meteo.com/en/terms)：个人非商业用途免 API key，免费接口限额，数据按 CC BY 4.0 提供。界面保留可点击 Open-Meteo 和许可署名；没有服务可用性保证。
- [和风逐小时覆盖与接口](https://dev.qweather.com/en/docs/api/weather/weather-hourly-forecast/)：全球覆盖，需自己的认证凭证；本轮不增加付费或凭证服务。
- [彩云小时接口](https://docs.caiyunapp.com/weather-api/v2/v2.6/3-hourly.html)、[官方认证说明](https://docs.caiyunapp.com/weather-api/auth.html)：直接 API 需要授权凭证，不能把小米内用网关的署名当公开免凭证授权。本轮不新接彩云。

小米当前网关未找到面向第三方的逐小时公开接口许可与按字段的独立模式证明。因此只准确描述已有缓存，不把内用接口可达当新增使用授权。
