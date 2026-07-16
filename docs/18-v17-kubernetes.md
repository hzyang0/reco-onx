# V17：把五个容器真正部署到 Kubernetes

V16 的 Docker Compose 像“在一台机器上按清单启动进程”；V17 的 Kubernetes（K8s）则像一个值班平台：你声明希望有几个实例、怎样判断健康、需要多少资源，控制器会持续把现实状态拉回期望状态。

## 1. 先建立最小心智模型

请求经过的对象是：

```text
浏览器 -> NodePort Service -> gateway Pod
                            -> goods Service -> goods Pod
                            -> live Service  -> live Pod
                            -> ad Service    -> ad Pod
                            -> trace-collector Service -> collector Pod
```

- **Container**：一个可运行的应用镜像。
- **Pod**：K8s 最小调度单位，本项目每个 Pod 放一个容器。
- **Deployment**：声明 Pod 模板和副本数；Pod 消失后会补建，还负责滚动更新。
- **Service**：给一组会变化的 Pod 提供稳定 DNS 和虚拟 IP。网关永远调用 `live:19002`，不关心 live Pod 的真实 IP。
- **EndpointSlice**：Service 当前真正可转发到的 Pod 地址集合。
- **Namespace**：资源的逻辑隔离空间，本项目统一在 `mini-reco`。
- **ConfigMap**：把非敏感配置从镜像中拆出；密码、Token 应使用 Secret 或外部密钥系统。

## 2. 为什么需要三种探针

探针不是重复配置，它们回答三个不同问题：

| 探针 | 问题 | 失败后的动作 |
|---|---|---|
| startupProbe | 应用启动完了吗 | 启动期间暂不执行另外两种探针 |
| readinessProbe | 现在可以接流量吗 | 从 Service EndpointSlice 摘除，不重启 |
| livenessProbe | 进程是否已经卡死 | 重启容器 |

网关使用 `/health` HTTP 探针，三路召回使用 Kubernetes 原生 gRPC 探针，并明确检查对应的标准 gRPC Health service。只有端口打开不等于业务服务已经可用。

## 3. 声明式部署到底做了什么

`deploy/k8s/base` 是环境无关的基础清单，`overlays/local` 只增加本机 kind 所需的 NodePort。Kustomize 会在不复制整份 YAML 的情况下组合二者：

```powershell
kubectl kustomize deploy/k8s/overlays/local
kubectl apply -k deploy/k8s/overlays/local
```

这叫**声明式**：提交“最终应该是什么”，而不是手工写一串启动命令。Deployment 控制器不断比较期望与现实并执行调谐（reconcile）。

## 4. 上线配置如何降低风险

- `requests` 是调度时承诺的最低资源，`limits` 是容器上限。
- `RollingUpdate + maxUnavailable: 0 + maxSurge: 1` 表示先创建新 Pod，就绪后再下掉旧 Pod。
- `preStop` 和 15 秒优雅终止窗口给 Endpoint 更新及在途请求留时间。
- PDB 的 `minAvailable: 1` 限制节点维护等**自愿驱逐**，但它不会阻止 Deployment 更新、节点宕机或人为删除。
- HPA 根据 CPU 目标在 1～3 个网关副本间伸缩。kind 默认没有 metrics-server，所以本地看到 `cpu: <unknown>` 是基础设施缺少指标源，不是清单失效；生产集群必须安装 metrics-server。

`deploy/k8s/observability/service-monitors.yaml` 是给已安装 Prometheus Operator 的生产环境使用的可选清单。本地没有相应 CRD，因此不把它加入默认 Kustomize，避免假装“应用成功但监控对象无法创建”。

## 5. 容器安全配置读法

本项目强制非 root UID 65532、禁止提权、删除 Linux capabilities、使用 RuntimeDefault seccomp，并把根文件系统设为只读。Java 仍可能需要 `/tmp`，因此单独挂载有大小上限的内存卷。这里体现的是最小权限，而不是依赖镜像默认值。

## 6. 一键做真实验收

```powershell
.\scripts\run-kubernetes-demo.ps1
```

脚本不是只检查 YAML，而会依次：

1. 执行完整 Maven 测试和打包；
2. 下载并校验固定版本的 kind（若本机没有）；
3. 构建 V17 镜像并创建一次性 Kubernetes 集群；
4. 部署 5 个 Deployment，检查 Pod、探针、安全上下文、资源和 EndpointSlice；
5. 验证健康状态三路召回共 25 条；
6. 把 live Deployment 缩容到 0，验证 goods + ad 共 17 条且 live 为 `FALLBACK`；
7. 恢复 live，验证重新回到 25 条；
8. 对 gateway 执行真实滚动重启，重启后再次验证 25 条；
9. 检查 HPA、PDB、ConfigMap，最后删除临时集群。

想保留环境观察：

```powershell
.\scripts\run-kubernetes-demo.ps1 -KeepCluster
kubectl -n mini-reco get pods,svc,hpa,pdb
kubectl -n mini-reco logs deployment/gateway -f
```

清理：

```powershell
.\target\tools\kind.exe delete cluster --name mini-reco-v17
```

## 7. 面试时如何讲

可以用“问题—设计—验证”三段式：

> Compose 已经解决单机多进程编排，但缺少生产所需的自愈、服务发现、滚动升级与弹性声明。我把网关、三路召回和链路收集器拆为五个 Deployment，通过 Service DNS 解耦 Pod IP；HTTP/gRPC 三类探针控制流量与重启，资源请求限制、HPA、PDB 和滚动策略约束可用性。验收不是看资源 Running，而是真实缩容 live，观察推荐从 25 条降级为 17 条，再恢复至 25 条，并滚动重启网关验证服务恢复。

常见追问：

- **Service 和 Deployment 有什么区别？** 前者解决稳定访问，后者管理 Pod 生命周期与版本。
- **readiness 失败为何不重启？** 应用可能只是暂时不能接流量，先摘流比立即重启更安全。
- **为什么固定 kind 节点镜像？** 可复现性。宿主机使用 cgroup v1 时，未经验证的新版本节点可能无法启动 kubelet；固定已验收的 K8s 1.31.2 避免开发环境漂移。
- **PDB 能保证永不宕机吗？** 不能，只约束支持 eviction API 的自愿驱逐。
- **HPA 为何本地 unknown？** HPA 控制器存在，但 kind 未安装提供 CPU 指标的 metrics-server。

## 8. 你应该亲手完成的练习

1. 把 gateway 副本改为 2，观察 Service EndpointSlice 中出现两个地址。
2. 把 readiness 路径故意改错，观察 Pod `Running` 但 `READY 0/1`，Service 不再转发。
3. 修改镜像标签并执行 `kubectl rollout status`，再用 `kubectl rollout undo` 回滚。
4. 给 goods 设置过小内存并观察重启原因；练习后恢复配置。

能解释每一步“控制器观察到什么、采取什么动作”，才算真正掌握，而不只是会背命令。
