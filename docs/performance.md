# 性能压测

## 召回串行与并行对照

`RECALL_FANOUT_PARALLELISM` 可以把相同的三路召回分别配置为单线程串行和三线程并行。运行：

```powershell
./scripts/run-recall-benchmark.ps1 -Requests 300 -WarmupRequests 30
```

2026-08-06 在本机 Docker MySQL 8.4 上得到以下结果：

| 模式 | 请求数 | 平均耗时 | P50 | P95 | 最大耗时 | 吞吐量 | 错误 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 串行召回（1 线程） | 300 | 6.17ms | 5.57ms | 8.91ms | 39.29ms | 160.88 req/s | 0 |
| 并行召回（3 线程） | 300 | 4.32ms | 4.14ms | 5.49ms | 8.23ms | 229.36 req/s | 0 |

本次 P95 降低 38.38%，吞吐量提高约 42.56%。这是开发机上的可复现实验，不应包装成生产容量结论；机器、数据库缓存和后台负载都会改变结果。

## 并发压测

仓库提供 `scripts/load-test.k6.js`。安装 k6 后运行：

```powershell
$env:BASE_URL="http://localhost:18081"
$env:VUS="20"
$env:DURATION="30s"
k6 run ./scripts/load-test.k6.js
```

阈值要求失败率低于 1%，P95 低于当前请求总预算 500ms。压测前后同时观察 `/metrics/prometheus` 中的请求耗时、召回状态和 HikariCP 活跃/等待连接数。
