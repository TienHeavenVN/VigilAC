#!/usr/bin/env python3
"""Minimal Source RCON client (stdlib only) used by the CI integration job.

Implements the Source RCON protocol against a vanilla/Paper server with
`enable-rcon=true`. Exits non-zero on auth failure or connection/protocol
error so CI steps fail loudly.
"""
import socket
import struct
import sys

REQUEST_LOGIN = 3
REQUEST_COMMAND = 2
REQUEST_RESPONSE = 0


def send_packet(sock: socket.socket, request_id: int, packet_type: int, payload: str) -> None:
    data = payload.encode("utf-8")
    sock.sendall(struct.pack("<iii", 4 + 4 + len(data) + 2, request_id, packet_type) + data + b"\x00\x00")


def read_packet(sock: socket.socket):
    header = b""
    while len(header) < 4:
        chunk = sock.recv(4 - len(header))
        if not chunk:
            raise EOFError("connection closed while reading packet header")
        header += chunk
    (length,) = struct.unpack("<i", header)
    body = b""
    while len(body) < length:
        chunk = sock.recv(length - len(body))
        if not chunk:
            raise EOFError("connection closed while reading packet body")
        body += chunk
    request_id, packet_type = struct.unpack("<ii", body[:8])
    payload = body[8:-2].decode("utf-8", "replace")
    return request_id, packet_type, payload


def main() -> int:
    if len(sys.argv) < 5:
        print("usage: rcon.py <host> <port> <password> <command...>", file=sys.stderr)
        return 2
    host, port, password = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    command = " ".join(sys.argv[4:])

    try:
        sock = socket.create_connection((host, port), timeout=10)
        sock.settimeout(10)
    except OSError as exc:
        print(f"cannot connect to {host}:{port}: {exc}", file=sys.stderr)
        return 4
    try:
        send_packet(sock, 1, REQUEST_LOGIN, password)
        request_id, _, _ = read_packet(sock)
        if request_id == -1:
            print("RCON authentication failed", file=sys.stderr)
            return 3

        send_packet(sock, 2, REQUEST_COMMAND, command)
        # RCON has no end-of-response marker. Drain packets until the server
        # goes quiet: 3s for the first byte (the server is usually instant on
        # loopback), then a short 0.4s idle grace so multi-packet responses
        # are reassembled without paying a long stall per call.
        sock.settimeout(3.0)
        chunks = []
        first = True
        while True:
            try:
                request_id, packet_type, payload = read_packet(sock)
            except (socket.timeout, TimeoutError, EOFError, OSError):
                break
            if first:
                sock.settimeout(0.4)
                first = False
            if request_id == 2:
                chunks.append(payload)
        sys.stdout.write("".join(chunks))
        sys.stdout.flush()
        return 0
    except (EOFError, OSError) as exc:
        print(f"connection lost: {exc}", file=sys.stderr)
        return 6
    finally:
        sock.close()


if __name__ == "__main__":
    sys.exit(main())
