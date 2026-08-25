package service

// ReservedKeySamples 供测试遍历保留键（与 k8s.ReservedEnvKeys 同源）。
func ReservedKeySamples() map[string]string {
	out := map[string]string{}
	for k := range reservedKeys {
		out[k] = "x"
	}
	return out
}
