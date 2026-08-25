package service

import "agent-manager/backend/internal/k8s"

// reservedKeys 引用 k8s 层保留键集合，保持单一来源。
var reservedKeys = k8s.ReservedEnvKeys
