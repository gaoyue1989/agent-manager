#!/usr/bin/env python3
"""Mock LLM server: 返回 streaming SSE 格式（对齐 OpenAI streaming），用于测量框架开销"""
import json, time
from http.server import HTTPServer, BaseHTTPRequestHandler

class MockHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = json.loads(self.rfile.read(length)) if length else {}
        messages = body.get('messages', [])
        user_msg = ''
        for m in messages:
            if m.get('role') == 'user':
                user_msg = m.get('content', '')

        # 回复逻辑
        reply = '你好！我是 Mock Agent，立即回复无需等待。'
        if '1+1' in user_msg:
            reply = '2'
        elif '2+2' in user_msg:
            reply = '4'

        is_stream = body.get('stream', False)
        model = body.get('model', 'mock-model')

        self.send_response(200)
        if is_stream:
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Cache-Control', 'no-cache')
            self.send_header('Connection', 'keep-alive')
            self.end_headers()
            # 逐字流式输出
            for char in reply:
                chunk = {
                    'id': 'mock-001',
                    'object': 'chat.completion.chunk',
                    'choices': [{'delta': {'content': char}, 'index': 0, 'finish_reason': None}],
                    'model': model
                }
                self.wfile.write(f'data: {json.dumps(chunk)}\n\n'.encode())
                self.wfile.flush()
            # 结束标记
            done = {
                'id': 'mock-001',
                'object': 'chat.completion.chunk',
                'choices': [{'delta': {}, 'index': 0, 'finish_reason': 'stop'}],
                'usage': {'prompt_tokens': 10, 'completion_tokens': len(reply), 'total_tokens': 10 + len(reply)},
                'model': model
            }
            self.wfile.write(f'data: {json.dumps(done)}\n\n'.encode())
            self.wfile.write(b'data: [DONE]\n\n')
            self.wfile.flush()
        else:
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            resp = {
                'id': 'mock-001',
                'object': 'chat.completion',
                'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': reply}, 'finish_reason': 'stop'}],
                'usage': {'prompt_tokens': 10, 'completion_tokens': len(reply), 'total_tokens': 10 + len(reply)},
                'model': model
            }
            self.wfile.write(json.dumps(resp).encode())

    def log_message(self, format, *args):
        print(f'[MockLLM] {args[0]}')

if __name__ == '__main__':
    server = HTTPServer(('127.0.0.1', 19999), MockHandler)
    print('Mock LLM (streaming) on :19999')
    server.serve_forever()
