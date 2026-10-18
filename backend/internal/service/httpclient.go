// 极简 HTTP 客户端封装（MCP verify / deliver / probe 共用）。
package service

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"time"
)

type simpleHTTPClient struct{ c *http.Client }

func httpTimeoutClient(timeoutSec int) simpleHTTPClient {
	if timeoutSec <= 0 {
		timeoutSec = 30
	}
	return simpleHTTPClient{&http.Client{Timeout: time.Duration(timeoutSec) * time.Second}}
}

// Post 发送请求并返回响应体与响应头（不校验状态码——调用方按语义处理）。
func (s simpleHTTPClient) Post(url string, body []byte, headers [][2]string) ([]byte, [][2]string, error) {
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return nil, nil, err
	}
	for _, h := range headers {
		req.Header.Set(h[0], h[1])
	}
	resp, err := s.c.Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return data, nil, fmt.Errorf("HTTP %d: %s", resp.StatusCode, truncateBytes(data, 200))
	}
	hs := make([][2]string, 0)
	for k := range resp.Header {
		hs = append(hs, [2]string{k, resp.Header.Get(k)})
	}
	return data, hs, nil
}
