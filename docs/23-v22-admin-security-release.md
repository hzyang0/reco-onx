# V22：管理面安全、发布门禁与最终工程验收

## 1. 为什么 V21 之后还不能直接上线

V21 已经保证配置重启不丢，但任何能访问端口的人仍可调用接口，把全量流量切回旧链路、开启重度降级、注入下游故障或清空缓存统计。推荐查询属于数据面，发布、降级、熔断和缓存管理属于管理面；管理面的影响更大，必须确认“你是谁、你能做什么、请求有没有被篡改或重复执行”。

V22 完成三个收尾目标：

1. 用 HMAC 签名认证管理请求；
2. 用 RELEASE/OPS 两种服务身份限制权限；
3. 用本地发布脚本与 GitHub Actions 把测试、构建、攻击验证和故障恢复变成可重复门禁。

## 2. 先区分四个安全概念

### 2.1 Authentication：认证

回答“请求是谁发的”。客户端提供 Key ID，服务端据此找到对应密钥，再校验签名。密钥本身不在网络上传输。

### 2.2 Authorization：授权

回答“这个身份能做什么”。本项目的权限由服务端绑定，而不是相信客户端自报：

| 身份 | 允许权限 | 典型接口 |
| --- | --- | --- |
| RELEASE | `CONFIG_WRITE`、`ROLLOUT_WRITE` | 配置发布、灰度切换 |
| OPS | `DEGRADATION_WRITE`、`RESILIENCE_WRITE`、`CACHE_RESET` | 降级、故障管理、缓存统计重置 |

OPS 即使生成了完全正确的签名，调用配置发布也会得到 403。401 表示身份校验失败，403 表示身份已确认但权限不足。

### 2.3 Integrity：完整性

回答“请求途中有没有被改”。签名不只覆盖 URL 路径，还覆盖 HTTP 方法和原始查询串。攻击者把 `newPipelinePercent=5` 改成 `100` 后，服务端算出的签名不同，请求会被拒绝。

### 2.4 Replay protection：防重放

攻击者即使完整复制一次合法请求，也不能无限重复发布。每次请求带当前 Unix 秒时间戳和唯一 nonce：

- 时间戳与服务端相差超过 60 秒：401；
- 同一个 Key ID + nonce 在窗口内再次出现：401；
- nonce 只在签名和权限都通过后登记。

## 3. 签名协议

请求包含四个 Header：

```text
X-Admin-Key-Id: release-bot
X-Admin-Timestamp: 1784510000
X-Admin-Nonce: 86d17f36c3c94253a15cf6adac81d9e2
X-Admin-Signature: <64位十六进制HMAC结果>
```

客户端把以下五部分用换行连接，不能随意排序或重新编码查询参数：

```text
POST
/api/config
expectedVersion=1&newPipelinePercent=5&...
1784510000
86d17f36c3c94253a15cf6adac81d9e2
```

然后计算：

```text
signature = hex(HMAC-SHA256(secret, canonicalRequest))
```

服务端用相同方式重算，并通过 `MessageDigest.isEqual` 做常量时间比较，降低通过比较耗时猜测签名的风险。

配置审计里的 `updatedBy` 在鉴权开启时直接取已认证的 Key ID，不相信调用方自行填写的查询参数，因此发布者不能冒充另一个审计身份。

## 4. 代码地图

### `security/AdminAuthConfig.java`

从环境变量加载两组身份和密钥。密钥少于 16 个字符、配置密钥却缺少 Key ID、两个角色使用重复 Key ID 时直接启动失败。`safeSnapshot()` 只暴露是否开启、Key ID 和时间窗口，永不返回密钥。

### `security/AdminRequestAuthenticator.java`

负责完整认证顺序：必需 Header → nonce 格式 → Key ID → 时间窗口 → HMAC → 权限 → nonce 防重放。它不依赖 HTTP 服务器，因此可用固定时钟进行稳定单测。

### `security/SecuredAdminHttpHandler.java`

它是装饰器：在原 Handler 前拦截 POST，认证成功才调用原业务逻辑。这样配置、灰度、降级、韧性和缓存接口复用同一套安全策略，不会在每个 Handler 复制密码判断。

### 四个管理 Handler

`DegradationHttpHandler`、`ResilienceHttpHandler`、`RolloutHttpHandler`、`FeatureCacheHttpHandler` 现在遵循明确的 HTTP 语义：

- GET 只能读当前状态；
- POST 才能改变状态；
- GET 携带管理参数时返回 405。

这避免浏览器预取、监控探测或随手打开链接意外改变线上状态。

### `compose.yaml`

Gateway 和 Config Center 都接收相同的身份配置。Compose 文件只引用环境变量，不写生产密钥。最终验收脚本设置的密钥是一次性本地演示值，真实部署应来自 Kubernetes Secret、Vault 或云密钥管理服务。

### `.github/workflows/ci.yml`

每次推送 main 或创建 PR 时，GitHub Actions 使用 Java 17 执行 `mvn clean verify`，然后构建生产 Docker 镜像。任何编译、测试或镜像构建失败都会阻断绿色门禁。

## 5. 为什么不用“一个管理员密码”

如果所有人共享一个密码，就无法贯彻最小权限，也难以判断哪类自动化执行了操作。V22 至少把发布与运维拆开：发布机器人不能注入故障，运维机器人不能改线上灰度。

HMAC 适合这个无外部身份服务的教学项目：实现小、签名可验证、密钥不直接传输。但它仍有边界：

- 多副本各自保存 nonce，跨副本重放仍需 Redis/数据库共享状态；
- 密钥轮换需要同时支持新旧 Key ID；
- 没有人员登录、审批流和细粒度资源权限；
- 必须配合 HTTPS，否则请求内容虽不能伪造，仍可能被旁观者看见并在窗口内尝试重放。

生产系统通常会使用 API Gateway、mTLS、OIDC/JWT、IAM 与审计平台。面试时主动讲清边界，能体现你不是把教学实现包装成万能安全方案。

## 6. 65 项测试证明什么

V21 的 58 项测试全部保留，V22 新增 7 项认证测试：

- 合法 RELEASE 请求通过；
- 缺签名返回 401；
- 查询参数被篡改返回 401；
- 过期时间戳返回 401；
- nonce 重放返回 401；
- OPS 越权发布返回 403；
- 未配置密钥时保留本地兼容模式。

最后一项只用于旧版本脚本和单进程学习。V22 Compose 验收明确配置密钥并断言鉴权已开启，避免“测试环境忘记开安全开关却误以为通过”。

## 7. 十阶段最终验收

执行：

```powershell
.\scripts\run-v22-final-acceptance.ps1
```

脚本真实执行以下门禁：

1. `mvn clean verify`，65 项测试全绿；
2. 校验 Compose、构建 V22 镜像、启动 Gateway、三路 gRPC、Trace Collector、Config Center 和 Redis；
3. 验证无签名、伪造签名、过期签名和重复 nonce 都被拒绝；
4. 验证 RELEASE/OPS 各自成功、越权失败、带副作用 GET 返回 405；
5. 重启 Config Center，确认已认证发布的 V2 和两条历史恢复；
6. 停止 Redis，推荐主链路继续返回，再启动 Redis；
7. 停止 live，召回从 25 条降为 goods + ad 的 17 条；
8. 启动 live，召回恢复 25 条；
9. 检查 Gateway、Config Center 健康、鉴权开启、文件持久化存在；
10. 输出 `V22 FINAL ACCEPTANCE PASSED` 并清理容器和数据卷。

本机最终实测结果：65 项测试无失败，认证/授权/防篡改/防重放全部通过，配置重启恢复，Redis 故障绕过，live 故障过程为 25 → 17 → 25。

## 8. 面试回答模板

> 项目最后我重点补了管理面安全与发布门禁。推荐查询是数据面，配置、灰度、降级和故障注入是管理面。我统一用 Handler 装饰器保护所有写操作，使用 HMAC-SHA256 对方法、路径、原始查询串、时间戳和 nonce 签名；服务端限制时间窗口并缓存 nonce 防重放。角色权限由服务端 Key ID 绑定，RELEASE 只能发布，OPS 只能做运维，越权返回 403。所有状态变更统一改为 POST，GET 只读。最后把 65 项测试和镜像构建放进 GitHub Actions，并用一键脚本真实验证攻击请求、配置中心重启、Redis 宕机和直播召回宕机恢复。

## 9. 从 V1 到 V22，工程闭环是什么

项目不再只是“能返回推荐结果”的 Demo，而具备了一条可解释的工程演进线：

```text
业务串行代码
 -> 类型安全上下文与统一 Item
 -> 算子框架与 DAG
 -> 并行执行和韧性治理
 -> 影子流量、灰度和协议收敛
 -> gRPC 多进程、容器、监控、Kubernetes
 -> 动态配置、Redis 缓存、容量 SLO
 -> 持久化配置、管理面安全、CI 与最终故障验收
```

真正值得在面试里表达的不是“用了多少名词”，而是每一版解决了前一版暴露出的具体问题，并且每个结论都有测试或真实故障实验作为证据。
