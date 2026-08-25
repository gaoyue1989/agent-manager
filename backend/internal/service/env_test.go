package service

import "testing"

func TestValidateEnvOK(t *testing.T) {
	env := map[string]string{"LOG_LEVEL": "info", "_PRIVATE": "x", "MAX_TOKENS2": "8192"}
	if err := ValidateEnv(env); err != nil {
		t.Fatalf("valid env rejected: %v", err)
	}
	if err := ValidateEnv(nil); err != nil {
		t.Fatalf("nil env should pass: %v", err)
	}
}

func TestValidateEnvReserved(t *testing.T) {
	for k, v := range ReservedKeySamples() {
		err := ValidateEnv(map[string]string{k: v})
		if err == nil {
			t.Errorf("reserved key %s must be rejected", k)
		}
	}
}

func TestValidateEnvBadKey(t *testing.T) {
	for _, k := range []string{"1ABC", "A B", "a-b", "", "中文KEY"} {
		if err := ValidateEnv(map[string]string{k: "v"}); err == nil {
			t.Errorf("bad key %q must be rejected", k)
		}
	}
}

func TestValidateEnvLimits(t *testing.T) {
	big := make([]byte, MaxEnvValueBytes+1)
	env := map[string]string{"BIG": string(big)}
	if err := ValidateEnv(env); err == nil {
		t.Fatal("oversized value must be rejected")
	}
	many := map[string]string{}
	for i := 0; i <= MaxEnvKeys; i++ {
		many[string(rune('A'+i%26))+string(rune('A'+i/26))+string(rune('0'+i%10))] = "v"
	}
	if len(many) < MaxEnvKeys {
		t.Fatal("test setup wrong")
	}
	if err := ValidateEnv(many); err == nil {
		t.Fatal("too many keys must be rejected")
	}
}
