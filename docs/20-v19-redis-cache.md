# V19：Redis 特征缓存与缓存三大问题

推荐准备阶段每次都要查用户特征。真实系统的特征服务可能访问数据库或远程存储；热门用户反复请求时，完全重复查询既慢又浪费容量。V19 在 `UserFeatureService` 前加入真实 Redis cache-aside（旁路缓存）。

## 1. 一次请求怎样读缓存

```text
请求 -> GET Redis
          | 命中 -> 反序列化 -> 返回
          | 未命中 -> single-flight -> 查源服务 -> SETEX Redis -> 返回
          | Redis异常 -> single-flight -> 查源服务 -> 返回（推荐不中断）
```

Cache-aside 的关键是业务代码而非 Redis 自动完成：先读缓存，miss 再读源，成功后回填。Redis 不是事实来源，缓存丢失可以重建。

## 2. 为什么用连接池

V19 使用 Jedis `RedisClient`。它从 7.2 起内置连接池；多个请求复用 TCP 连接，避免每次握手。连接池解决连接复用，不代表 Redis 永不失败，所以业务仍必须捕获连接异常并回源。连接方式和池配置可对照 [Redis 官方 Jedis 文档](https://redis.io/docs/latest/develop/clients/jedis/connect/)。

## 3. 缓存的三个经典风险

### 穿透：反复查询不存在的数据

攻击或错误请求不断查不存在的 userId，缓存永远 miss，源服务每次被打。V19 对 null 写入 `__NULL__` 哨兵，TTL 仅 10 秒。短 TTL 既保护源服务，又避免“用户刚创建但长时间仍被视为不存在”。

### 击穿：一个热点 key 过期

100 个并发请求同时发现热门 key 过期，可能同时访问源服务。V19 用进程内 single-flight：第一个线程负责加载，其余线程等待同一个 `CompletableFuture`，最终只产生一次源调用。

### 雪崩：大量 key 同时过期

若所有 key TTL 都是整齐的 60 秒，它们可能同一刻失效。V19 使用 60 秒基础 TTL 加 0～15 秒确定性抖动，把过期压力摊开。确定性意味着同一 userId 的 TTL 规律可复现。

## 4. Redis 宕机时为什么要 fail-open

用户特征有可用的源服务，因此 Redis 故障时继续回源比让推荐整体失败更合理：

- GET 异常：记录 `error`，进入 single-flight 回源；
- 回填 SETEX 异常：记录错误，但不丢弃已经拿到的特征；
- Redis 恢复：后续 miss 自动重新回填，无需重启网关。

这叫 fail-open。若缓存的是鉴权黑名单等安全数据，可能必须 fail-closed，不能机械套用。

## 5. 指标怎样证明它有效

`/feature-cache` 暴露：hits、misses、errors、originLoads、singleFlightJoins、writes 和 hitRate。`/metrics/prometheus` 同时输出带 `result=hit/miss/error/write_error` 标签的标准计数器。

命中率高不一定就好：还要同时观察源服务 QPS、缓存 P95、错误率、内存淘汰和数据新鲜度。

## 6. 一键真实验收

```powershell
.\scripts\run-redis-cache-demo.ps1
```

脚本执行 52 项测试并启动真实 `redis:7-alpine`：

1. 清空 Redis，第一次请求严格产生 1 miss、1 origin load、1 write；
2. 第二次相同用户严格产生 Redis hit，源调用不增加；
3. 用 `redis-cli GET/TTL` 检查真实值和抖动过期时间；
4. 停止 Redis，验证推荐仍返回 10 条且 error/origin 指标增长；
5. 重启 Redis，验证自动重新写入并再次命中；
6. 验证 Prometheus 文本包含 hit 和 error 时序指标。

## 7. 面试表达

> 我给准备阶段的用户特征查询增加 Redis cache-aside。key 带 `v1` 命名空间便于结构升级，正常值使用 60～75 秒抖动 TTL，空值用 10 秒哨兵防穿透；同 key 并发 miss 通过 CompletableFuture single-flight 合并，防止热点击穿。Redis GET/SET 异常都 fail-open 回源，主链路继续服务，并记录 hit、miss、error、origin、join、write 指标。真实验收会停止 Redis，证明不是只测 happy path。

生产环境还要按数据一致性要求选择删除/更新策略，配置 Redis Cluster/Sentinel、TLS/ACL、连接池上限、命令超时和容量告警。
