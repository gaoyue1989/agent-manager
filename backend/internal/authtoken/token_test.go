package authtoken

import (
	"strings"
	"testing"
	"time"
)

func TestSignParseRoundtrip(t *testing.T) {
	tok := Issue("s3cret", 42, "zhangsan", "user")
	claims, err := Parse("s3cret", tok)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if claims.UID != 42 || claims.Sub != "zhangsan" || claims.Role != "user" {
		t.Fatalf("claims: %+v", claims)
	}
	if claims.Exp <= time.Now().Unix() {
		t.Fatal("exp should be in future")
	}
}

func TestParseRejectsTamperedAndExpired(t *testing.T) {
	tok := Issue("s3cret", 1, "u", "user")
	if _, err := Parse("other-secret", tok); err == nil {
		t.Fatal("wrong secret should fail")
	}
	parts := strings.Split(tok, ".")
	parts[1] = strings.TrimSuffix(parts[1], "=") + "x"
	if _, err := Parse("s3cret", strings.Join(parts, ".")); err == nil {
		t.Fatal("tampered payload should fail")
	}
	expired := Sign("s3cret", Claims{Sub: "u", UID: 1, Role: "user",
		IssuedAt: time.Now().Add(-2 * time.Hour).Unix(), Exp: time.Now().Add(-time.Hour).Unix()})
	if _, err := Parse("s3cret", expired); err == nil {
		t.Fatal("expired token should fail")
	}
	if _, err := Parse("s3cret", "not-a-jwt"); err == nil {
		t.Fatal("malformed token should fail")
	}
}
