package main

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestMCPAuthentication(t *testing.T) {
	gin.SetMode(gin.TestMode)
	for _, tc := range []struct {
		name          string
		token         string
		authorization string
		status        int
		calls         int
	}{
		{"missing bearer", "test-token", "", http.StatusUnauthorized, 0},
		{"wrong bearer", "test-token", "Bearer other-token", http.StatusUnauthorized, 0},
		{"valid bearer", "test-token", "Bearer test-token", http.StatusNoContent, 1},
		{"optional authentication", "", "", http.StatusNoContent, 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			router := gin.New()
			registerMCP(router, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				w.WriteHeader(http.StatusNoContent)
			}), tc.token)
			req := httptest.NewRequest(http.MethodPost, "/mcp", nil)
			req.Header.Set("Authorization", tc.authorization)
			resp := httptest.NewRecorder()
			router.ServeHTTP(resp, req)
			if resp.Code != tc.status || calls != tc.calls {
				t.Fatalf("status=%d calls=%d; want status=%d calls=%d", resp.Code, calls, tc.status, tc.calls)
			}
		})
	}
}
