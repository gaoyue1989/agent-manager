// 极简 HTTP 客户端封装（MCP verify / deliver / probe 共用）。
package service

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

var allowLoopbackProbeForTest bool

func checkProbeIP(ip net.IP) error {
	if v4 := ip.To4(); v4 != nil {
		ip = v4
	}
	if ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() {
		return fmt.Errorf("probe target ip %s is not allowed: link-local", ip.String())
	}
	if !allowLoopbackProbeForTest && (ip.IsLoopback() || ip.IsUnspecified()) {
		return fmt.Errorf("probe target ip %s is not allowed: loopback", ip.String())
	}
	return nil
}

func validateProbeURL(rawURL string) error {
	parsed, err := url.Parse(rawURL)
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Hostname() == "" {
		return fmt.Errorf("invalid probe url")
	}
	host := strings.ToLower(strings.TrimSuffix(parsed.Hostname(), "."))
	if !allowLoopbackProbeForTest && (host == "localhost" || strings.HasSuffix(host, ".localhost")) {
		return fmt.Errorf("probe target host %q is not allowed: loopback", host)
	}
	if ip := net.ParseIP(host); ip != nil {
		return checkProbeIP(ip)
	}
	return nil
}

func newProbeHTTPClient(timeoutSec int) *http.Client {
	if timeoutSec <= 0 {
		timeoutSec = 30
	}
	dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(addr)
		if err != nil {
			return nil, err
		}
		lowerHost := strings.ToLower(strings.TrimSuffix(host, "."))
		if !allowLoopbackProbeForTest && (lowerHost == "localhost" || strings.HasSuffix(lowerHost, ".localhost")) {
			return nil, fmt.Errorf("probe target host %q is not allowed: loopback", lowerHost)
		}
		if ip := net.ParseIP(host); ip != nil {
			if err := checkProbeIP(ip); err != nil {
				return nil, err
			}
			return dialer.DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
		}
		ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
		if err != nil {
			return nil, err
		}
		for _, ipAddr := range ips {
			if err := checkProbeIP(ipAddr.IP); err != nil {
				return nil, err
			}
		}
		var lastErr error
		for _, ipAddr := range ips {
			conn, err := dialer.DialContext(ctx, network, net.JoinHostPort(ipAddr.IP.String(), port))
			if err == nil {
				return conn, nil
			}
			lastErr = err
		}
		if lastErr != nil {
			return nil, lastErr
		}
		return nil, fmt.Errorf("no address resolved for %s", host)
	}
	return &http.Client{
		Timeout:   time.Duration(timeoutSec) * time.Second,
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
}

type simpleHTTPClient struct{ c *http.Client }

func httpTimeoutClient(timeoutSec int) simpleHTTPClient {
	return simpleHTTPClient{newProbeHTTPClient(timeoutSec)}
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
