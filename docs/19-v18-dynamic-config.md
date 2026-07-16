# V18：动态配置中心、灰度发布与最后正确值

V17 已经能滚动发布程序，但“把新链路从 5% 调到 20%”如果每次都改环境变量、重新发 Pod，响应太慢且容易误操作。V18 把经常变化的策略做成独立的动态配置。

## 1. 六个角色如何协作

```text
发布人员 --POST(带期望版本)--> config-center --保存新版本/审计记录
                                      ^
                                      | 每 500ms 拉取
                                   gateway
                                      |
                         原子更新 RolloutManager + DegradationManager
```

配置中心并不直接“推”进业务线程。每个网关实例独立轮询，拿到完整快照并校验后一次性更新本地状态。推荐请求只读内存中的不可变快照，不在主链路访问配置中心，因此配置中心变慢不会拖慢推荐请求。

## 2. 为什么配置必须有版本

假设小王和小李同时读取版本 7：小王提交 10% 灰度后变成版本 8；小李仍基于旧页面提交 20%。如果服务器无条件覆盖，小王的修改会悄悄丢失。

V18 要求更新携带 `expectedVersion`：

```text
expectedVersion == currentVersion -> 接受，版本加一
expectedVersion != currentVersion -> HTTP 409 Conflict，要求重新读取
```

这叫**乐观锁**。它假设冲突不常见，不长期锁住数据，但提交时必须比较版本。

## 3. 一份配置快照包含什么

- `newPipelinePercent`：进入新链路的稳定用户桶比例；
- `shadowPercent`：后台影子双跑比例，不影响用户返回；
- `degradationLevel`：`NONE/LIGHT/HEAVY`；
- `version`、`updatedBy`、`updatedAt`：发布序号和审计信息。

比例必须在 0～100，降级枚举必须合法，操作者不能为空。任何字段非法时整份快照拒绝，不能只应用一半。

## 4. 稳定灰度为什么不是随机数

规则是 `userId % 100 < newPipelinePercent`。5% 灰度时，桶 0～4 永远走 NEW，桶 5～99 走 LEGACY。同一用户重复请求不会一会儿新一会儿旧，便于比较指标、定位问题和回滚。

## 5. 配置中心宕机怎么办

网关保存最后一次通过完整校验的配置（Last Known Good，最后正确值）：

- 拉取失败只增加错误计数，不覆盖本地配置；
- 超过 3 秒没成功拉取，`/runtime-config` 标为 `STALE`；
- 推荐主链路仍按最后正确值服务；
- 配置中心恢复后轮询自动恢复。

注意：`STALE` 是治理告警，不等于推荐服务必须停止。宁可暂时使用已知正确的 5%，也不能因配置系统故障把流量随机切回 100%。

## 6. API 动手练习

查询：

```powershell
Invoke-RestMethod http://localhost:18888/api/config
```

基于版本 1 发布 5% 灰度：

```powershell
Invoke-RestMethod 'http://localhost:18888/api/config?expectedVersion=1&newPipelinePercent=5&shadowPercent=20&degradationLevel=LIGHT&updatedBy=hzyang' -Method Post
```

观察网关实际生效值：

```powershell
Invoke-RestMethod http://localhost:18093/runtime-config
```

查看最近 20 条审计记录：

```powershell
Invoke-RestMethod http://localhost:18888/api/config/history
```

## 7. 一键验收

```powershell
.\scripts\run-dynamic-config-demo.ps1
```

它会执行 48 项测试并启动六个容器，真实验证：版本 1→2→3→4、0% 回滚、100% shadow、5% 稳定桶、LIGHT 降级、旧版本 409、非法比例 400、暂停配置中心网络后的 `STALE + Last Known Good`、恢复后 100% 全量和审计历史。

## 8. 面试表达

> 我把灰度比例、影子流量和降级级别抽成不可变版本快照。配置中心用 expectedVersion 做乐观锁，避免多人发布丢失更新，并保留操作者与时间审计。网关每 500ms 拉取并先完整校验，再原子更新两个运行时管理器；请求线程只读内存，不依赖配置中心。配置中心宕机时状态转为 STALE，但继续使用 Last Known Good。验收覆盖了 409 冲突、400 参数校验、稳定用户桶、停服容灾和自动恢复。

生产环境还应补充持久化数据库、RBAC、审批流、签名、跨机房一致性和变更回滚；本版配置中心使用内存存储，所以验收用 `pause/unpause` 模拟网络中断并保留进程状态。若容器被真正重建，服务端会回到初始版本，而网关会拒绝低版本覆盖 Last Known Good。这个行为安全但无法继续发布，正说明生产环境的版本与审计记录必须持久化。本版重点是把核心正确性语义做成可运行项目，而不是用内存服务冒充完整商业配置平台。
