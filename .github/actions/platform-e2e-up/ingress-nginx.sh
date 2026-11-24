#!/usr/bin/env bash
set -euo pipefail
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.10.1/deploy/static/provider/kind/deploy.yaml
kubectl -n ingress-nginx wait --for=condition=ready pod \
  -l app.kubernetes.io/component=controller --timeout=240s
# provider 清单默认 hostPort 80/443；仓库脚本统一用 localhost:30080
kubectl -n ingress-nginx patch svc ingress-nginx-controller --type merge -p '{"spec":{"type":"NodePort","ports":[{"name":"http","port":80,"targetPort":"http","nodePort":30080},{"name":"https","port":443,"targetPort":"https","nodePort":30443}]}}'
