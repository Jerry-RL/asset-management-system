# Kubernetes 部署清单

| 文件 | 说明 |
|------|------|
| [namespace.yaml](./namespace.yaml) | 命名空间 `ams` |
| [configmap.yaml](./configmap.yaml) | 非敏感环境变量 |
| [secret.example.yaml](./secret.example.yaml) | Secret 模板（复制后填入真实值） |
| [deployment.yaml](./deployment.yaml) | API Deployment（含探活与资源限制） |
| [service.yaml](./service.yaml) | ClusterIP Service |
| [ingress.yaml](./ingress.yaml) | Ingress + TLS |

## 部署步骤

```bash
# 1. 准备 Secret（勿提交 secret.yaml）
cp deploy/k8s/secret.example.yaml deploy/k8s/secret.yaml
# 编辑 secret.yaml 填入 DATABASE_URL、JWT_* 等

# 2. 应用清单
kubectl apply -f deploy/k8s/namespace.yaml
kubectl apply -f deploy/k8s/configmap.yaml
kubectl apply -f deploy/k8s/secret.yaml
kubectl apply -f deploy/k8s/deployment.yaml
kubectl apply -f deploy/k8s/service.yaml
kubectl apply -f deploy/k8s/ingress.yaml

# 3. 验证
kubectl -n ams rollout status deployment/ams-api
kubectl -n ams get pods,svc,ingress
curl -k https://api.example.com/api/v1/health
```

PostgreSQL / Redis 建议使用云托管或独立 StatefulSet，本清单仅包含 API 层。

详见 [部署手册](../../docs/deployment/部署手册.md)。
