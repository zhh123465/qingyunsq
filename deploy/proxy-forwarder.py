#!/usr/bin/env python3
"""
Docker 容器 → 宿主 mihomo 代理的 TCP 转发。

mihomo 只绑了 127.0.0.1:7890（loopback），容器无法直连；本脚本监听 0.0.0.0:7891
把所有连接转发到 127.0.0.1:7890，容器通过 host.docker.internal:7891 走代理。
"""
import socket
import threading
import sys

LISTEN_HOST = "0.0.0.0"
LISTEN_PORT = 7891
UPSTREAM_HOST = "127.0.0.1"
UPSTREAM_PORT = 7890


def pipe(src, dst):
    try:
        while True:
            buf = src.recv(65536)
            if not buf:
                break
            dst.sendall(buf)
    except OSError:
        pass
    finally:
        try:
            src.shutdown(socket.SHUT_RD)
        except OSError:
            pass
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(client):
    try:
        upstream = socket.create_connection((UPSTREAM_HOST, UPSTREAM_PORT), timeout=15)
    except OSError as e:
        print(f"upstream fail: {e}", flush=True)
        client.close()
        return
    threading.Thread(target=pipe, args=(client, upstream), daemon=True).start()
    threading.Thread(target=pipe, args=(upstream, client), daemon=True).start()


def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((LISTEN_HOST, LISTEN_PORT))
    server.listen(128)
    print(f"proxy-forwarder listening {LISTEN_HOST}:{LISTEN_PORT} → {UPSTREAM_HOST}:{UPSTREAM_PORT}",
          flush=True)
    while True:
        client, addr = server.accept()
        threading.Thread(target=handle, args=(client,), daemon=True).start()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
