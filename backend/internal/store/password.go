package store

import "golang.org/x/crypto/bcrypt"

// HashPassword bcrypt 哈希（身份存储统一入口，seed 与 admin 建号共用）。
func HashPassword(plain string) (string, error) {
	b, err := bcrypt.GenerateFromPassword([]byte(plain), bcrypt.DefaultCost)
	return string(b), err
}

// CheckPassword 校验 bcrypt 哈希。
func CheckPassword(hash, plain string) bool {
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(plain)) == nil
}
