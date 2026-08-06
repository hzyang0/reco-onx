# 生产化边界

当前版本是可部署、可观测、具备真实 MySQL 数据闭环的工程原型，不等同于完整生产推荐平台。

已经实现：Flyway 迁移、HikariCP 连接池、数据库 readiness、进程 liveness、请求总超时、召回超时与部分降级、线程池优雅关闭、Prometheus 文本指标、曝光和显式行为反馈、来源专属数据表、Testcontainers 测试和可复现压测。

接入真实业务前仍需要由外部基础设施提供：

- 网关鉴权、TLS、限流、WAF、用户身份和后台权限；
- Kafka 等行为消息流、实时计算、特征库和离线数仓；
- 独立召回服务、向量/搜索索引、模型训练和模型推理服务；
- 广告预算扣减、频控、定向、审核和归因；
- Prometheus/Grafana/OpenTelemetry 后端、SLO 告警和日志采集；
- 多副本部署、配置中心、Secret 管理、灰度、回滚、容灾和隐私合规。

这些能力不能通过在单仓库中增加几个模拟类来等价替代。当前代码通过 `RecallService`、`OnlineFeatureService`、`MixRankService` 和 `JdbcDataRepository` 保留了替换边界，真实落地时应在边界外接入对应平台。
