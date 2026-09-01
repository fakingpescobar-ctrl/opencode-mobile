"""IPv4-first HTTP CONNECT proxy.

opencode (bun) on Android fails to connect to https://opencode.ai/zen/go/v1
because DNS returns IPv6 (Cloudflare) addresses first and the device has no
IPv6 route — bun does no IPv4 fallback ("Cannot connect to API").

This proxy accepts CONNECT tunnels over IPv4 only (AF_INET lookup), so the
bun binary force-resolves opencode.ai to 172.65.90.x and TLS stays end-to-end.
"""

import logging
import socket
import sys
import threading

LISTEN_PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 3128


def pipe(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except Exception:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except Exception:
            pass


def handle(conn):
    try:
        conn.settimeout(30)
        req = b""
        while b"\r\n\r\n" not in req:
            chunk = conn.recv(4096)
            if not chunk:
                return
            req += chunk
            if len(req) > 1_000_000:
                return
        first = req.split(b"\r\n", 1)[0].decode("latin1", "replace").strip()
        parts = first.split()
        if len(parts) < 2:
            return
        method, target = parts[0].upper(), parts[1]

        if method == "CONNECT":
            host, sep, port = target.rpartition(":")
            if not sep:
                host, port = target, "443"
            port = int(port)
            infos = socket.getaddrinfo(host, port, socket.AF_INET, socket.SOCK_STREAM)
            up = socket.socket(infos[0][0], infos[0][1])
            up.settimeout(30)
            up.connect(infos[0][4])
            logging.info("CONNECT %s:%d -> %s", host, port, infos[0][4])
            conn.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")
            conn.settimeout(None)
            up.settimeout(None)
            t1 = threading.Thread(target=pipe, args=(conn, up), daemon=True)
            t2 = threading.Thread(target=pipe, args=(up, conn), daemon=True)
            t1.start()
            t2.start()
            t1.join()
            t2.join()
        else:
            conn.sendall(b"HTTP/1.1 502 Only CONNECT supported\r\n\r\n")
    except Exception as e:
        logging.error("ERR %s | %s", first, e)
    finally:
        try:
            conn.close()
        except Exception:
            pass


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", datefmt="%H:%M:%S", stream=sys.stdout)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", LISTEN_PORT))
    srv.listen(64)
    logging.info("IPv4-only CONNECT proxy listening on :%d", LISTEN_PORT)
    while True:
        c, _ = srv.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()


if __name__ == "__main__":
    main()