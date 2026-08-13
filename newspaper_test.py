#!/usr/bin/env python3
"""
Newspaper mod MTLS test client/server.

Usage:
  python newspaper_test.py server --port 8080 --password mypass
  python newspaper_test.py client --host 127.0.0.1 --port 8080 --password mypass

Requirements:
  pip install cryptography
"""

import argparse
import base64
import hashlib
import json
import os
import socket
import ssl
import struct
import sys
import tempfile
from datetime import datetime, timedelta

# ── secp256r1 curve order ──
SECP256R1_N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


# ═══════════════════════════════════════════════════════════════════════════════
# Certificate derivation — matches Java CertificateGenerator exactly
# ═══════════════════════════════════════════════════════════════════════════════

def _sha256(data: bytes) -> bytes:
    return hashlib.sha256(data).digest()


def _derive_scalar(seed: bytes, label: str, n: int) -> int:
    label_bytes = label.encode("utf-8")
    current = seed
    while True:
        h = _sha256(current + label_bytes)
        d = int.from_bytes(h, "big") % n
        current = h
        if d != 0:
            return d


def _derive_keypair(password: str, label: str):
    from cryptography.hazmat.primitives.asymmetric import ec
    seed = _sha256(password.encode("utf-8"))
    d = _derive_scalar(seed, label, SECP256R1_N)
    return ec.derive_private_key(d, ec.SECP256R1())


def _make_ca_cert(private_key):
    from cryptography import x509
    from cryptography.x509.oid import NameOID
    from cryptography.hazmat.primitives import hashes

    name = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, "Newspaper-CA"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Newspaper"),
    ])
    return (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(private_key.public_key())
        .serial_number(1)
        .not_valid_before(datetime.utcnow() - timedelta(minutes=1))
        .not_valid_after(datetime.utcnow() + timedelta(days=3650))
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .add_extension(x509.KeyUsage(
            digital_signature=False, content_commitment=False,
            key_encipherment=False, data_encipherment=False,
            key_agreement=False, key_cert_sign=True, crl_sign=True,
            encipher_only=False, decipher_only=False,
        ), critical=True)
        .sign(private_key, hashes.SHA256())
    )


def _make_end_cert(subject_key, ca_key, ca_cert, cn):
    from cryptography import x509
    from cryptography.x509.oid import NameOID
    from cryptography.hazmat.primitives import hashes

    subject = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, cn),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Newspaper"),
    ])
    return (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(ca_cert.subject)
        .public_key(subject_key.public_key())
        .serial_number(int(datetime.utcnow().timestamp() * 1000))
        .not_valid_before(datetime.utcnow() - timedelta(minutes=1))
        .not_valid_after(datetime.utcnow() + timedelta(days=3650))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.KeyUsage(
            digital_signature=True, content_commitment=False,
            key_encipherment=True, data_encipherment=False,
            key_agreement=False, key_cert_sign=False, crl_sign=False,
            encipher_only=False, decipher_only=False,
        ), critical=True)
        .add_extension(x509.ExtendedKeyUsage([
            x509.ExtendedKeyUsageOID.SERVER_AUTH,
            x509.ExtendedKeyUsageOID.CLIENT_AUTH,
        ]), critical=True)
        .sign(ca_key, hashes.SHA256())
    )


def create_ssl_context(password: str, is_server: bool) -> ssl.SSLContext:
    """Derive mTLS certificates from password and build SSLContext."""
    from cryptography.hazmat.primitives.serialization import (
        Encoding, PrivateFormat, NoEncryption,
    )

    ca_key = _derive_keypair(password, "newspaper-ca")
    if is_server:
        end_key = _derive_keypair(password, "newspaper-server")
        cn = "Newspaper-Server"
    else:
        end_key = _derive_keypair(password, "newspaper-client")
        cn = "Newspaper-Client"

    ca_cert = _make_ca_cert(ca_key)
    end_cert = _make_end_cert(end_key, ca_key, ca_cert, cn)

    cert_pem = end_cert.public_bytes(Encoding.PEM).decode("utf-8")
    ca_pem = ca_cert.public_bytes(Encoding.PEM).decode("utf-8")
    key_pem = end_key.private_bytes(
        Encoding.PEM, PrivateFormat.TraditionalOpenSSL, NoEncryption()
    ).decode("utf-8")

    # Write temp files for load_cert_chain
    cert_file = tempfile.NamedTemporaryFile(
        delete=False, suffix=".pem", mode="w"
    )
    cert_file.write(cert_pem)
    cert_file.write(ca_pem)
    cert_file.close()

    key_file = tempfile.NamedTemporaryFile(
        delete=False, suffix=".pem", mode="w"
    )
    key_file.write(key_pem)
    key_file.close()

    ctx = ssl.SSLContext(
        ssl.PROTOCOL_TLS_SERVER if is_server else ssl.PROTOCOL_TLS_CLIENT
    )
    ctx.load_cert_chain(certfile=cert_file.name, keyfile=key_file.name)
    ctx.load_verify_locations(cadata=ca_pem)

    if is_server:
        ctx.verify_mode = ssl.CERT_REQUIRED
    else:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_REQUIRED

    os.unlink(cert_file.name)
    os.unlink(key_file.name)
    return ctx


# ═══════════════════════════════════════════════════════════════════════════════
# WebSocket frame encoding / decoding
# ═══════════════════════════════════════════════════════════════════════════════

def _build_frame(payload: bytes, opcode: int, masked: bool) -> bytes:
    frame = bytearray()
    frame.append(0x80 | opcode)  # FIN + opcode

    mask_key = os.urandom(4) if masked else None
    length = len(payload)

    mask_bit = 0x80 if masked else 0x00
    if length <= 125:
        frame.append(mask_bit | length)
    elif length <= 65535:
        frame.append(mask_bit | 126)
        frame.extend(struct.pack(">H", length))
    else:
        frame.append(mask_bit | 127)
        frame.extend(struct.pack(">Q", length))

    if masked:
        frame.extend(mask_key)
        masked_payload = bytearray(len(payload))
        for i in range(len(payload)):
            masked_payload[i] = payload[i] ^ mask_key[i % 4]
        frame.extend(masked_payload)
    else:
        frame.extend(payload)

    return bytes(frame)


def send_binary(sock: socket.socket, data: bytes, masked: bool):
    sock.sendall(_build_frame(data, 0x2, masked))


def _recv_exact(sock: socket.socket, n: int):
    buf = bytearray()
    while len(buf) < n:
        try:
            chunk = sock.recv(n - len(buf))
        except (ConnectionError, OSError):
            return None
        if not chunk:
            return None
        buf.extend(chunk)
    return bytes(buf)


def read_frame(sock: socket.socket):
    """Return (opcode, payload) or None on close/error."""
    header = _recv_exact(sock, 2)
    if header is None:
        return None
    b1, b2 = header[0], header[1]
    opcode = b1 & 0x0F
    masked = (b2 & 0x80) != 0
    length = b2 & 0x7F

    if length == 126:
        ext = _recv_exact(sock, 2)
        if ext is None:
            return None
        length = struct.unpack(">H", ext)[0]
    elif length == 127:
        ext = _recv_exact(sock, 8)
        if ext is None:
            return None
        length = struct.unpack(">Q", ext)[0]

    mask_key = None
    if masked:
        mask_key = _recv_exact(sock, 4)
        if mask_key is None:
            return None

    payload = _recv_exact(sock, length)
    if payload is None:
        return None

    if masked and mask_key:
        payload = bytearray(payload)
        for i in range(len(payload)):
            payload[i] ^= mask_key[i % 4]
        payload = bytes(payload)

    return opcode, bytes(payload)


# ═══════════════════════════════════════════════════════════════════════════════
# WebSocket handshake
# ═══════════════════════════════════════════════════════════════════════════════

def client_handshake(sock: socket.socket):
    key = base64.b64encode(os.urandom(16)).decode("utf-8")
    request = (
        "GET / HTTP/1.1\r\n"
        "Host: localhost\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "\r\n"
    )
    sock.sendall(request.encode("utf-8"))

    response = b""
    while b"\r\n\r\n" not in response:
        chunk = sock.recv(4096)
        if not chunk:
            raise ConnectionError("Connection closed during handshake")
        response += chunk

    status_line = response.split(b"\r\n")[0].decode("utf-8")
    if "101" not in status_line:
        raise ConnectionError(f"Handshake failed: {status_line}")


def server_handshake(sock: socket.socket) -> bool:
    headers = b""
    while b"\r\n\r\n" not in headers:
        chunk = sock.recv(4096)
        if not chunk:
            return False
        headers += chunk

    key = None
    for line in headers.decode("utf-8").split("\r\n"):
        if line.lower().startswith("sec-websocket-key:"):
            key = line.split(":", 1)[1].strip()
            break

    if not key:
        return False

    accept_key = base64.b64encode(
        hashlib.sha1((key + WS_GUID).encode("utf-8")).digest()
    ).decode("utf-8")

    response = (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept_key}\r\n"
        "\r\n"
    )
    sock.sendall(response.encode("utf-8"))
    return True


# ═══════════════════════════════════════════════════════════════════════════════
# JSON send / receive
# ═══════════════════════════════════════════════════════════════════════════════

def send_json(sock: socket.socket, msg: dict, masked: bool = True):
    data = json.dumps(msg, ensure_ascii=False).encode("utf-8")
    send_binary(sock, data, masked)


def recv_json(sock: socket.socket):
    result = read_frame(sock)
    if result is None:
        return None
    opcode, payload = result
    if opcode == 0x8:  # Close frame
        return None
    try:
        return json.loads(payload.decode("utf-8"))
    except json.JSONDecodeError:
        print(f"[raw] {payload.decode('utf-8', errors='replace')}")
        return None


# ═══════════════════════════════════════════════════════════════════════════════
# Interactive console
# ═══════════════════════════════════════════════════════════════════════════════

HELP_TEXT = """\
Available commands:
  server_info                    Get server information
  online_players                 List online players
  command <cmd>                  Execute Minecraft console command
  shell <cmd>                    Execute system shell command
  shutdown                       Shutdown Minecraft server
  config_reload                  Reload configuration
  config_modify <key> <value>    Modify config (e.g. port 9090)
  console_broadcast <msg>        Broadcast message to console
  file_upload <local> <remote>   Upload file
  file_download <remote> <local> Download file
  raw <json>                     Send raw JSON message
  help                           Show this help
  quit                           Exit\
"""


def parse_command(line: str):
    """Parse user input, return (msg_dict, local_save_path) or None."""
    parts = line.strip().split(None, 1)
    if not parts:
        return None

    cmd = parts[0].lower()
    arg = parts[1] if len(parts) > 1 else ""

    if cmd == "server_info":
        return {"type": "server_info", "data": {}}, None
    if cmd == "online_players":
        return {"type": "online_players", "data": {}}, None
    if cmd == "command":
        if not arg:
            print("Usage: command <cmd>")
            return None
        return {"type": "command", "data": {"command": arg}}, None
    if cmd == "shell":
        if not arg:
            print("Usage: shell <cmd>")
            return None
        return {"type": "shell_command", "data": {"command": arg}}, None
    if cmd == "shutdown":
        return {"type": "shutdown", "data": {}}, None
    if cmd == "config_reload":
        return {"type": "config_reload", "data": {}}, None
    if cmd == "config_modify":
        kv = arg.split(None, 1)
        if len(kv) != 2:
            print("Usage: config_modify <key> <value>")
            return None
        return {"type": "config_modify", "data": {kv[0]: kv[1]}}, None
    if cmd == "console_broadcast":
        if not arg:
            print("Usage: console_broadcast <msg>")
            return None
        return {"type": "console_broadcast", "data": {"message": arg}}, None
    if cmd == "file_upload":
        args = arg.split()
        if len(args) != 2:
            print("Usage: file_upload <local_path> <remote_path>")
            return None
        try:
            with open(args[0], "rb") as f:
                content = base64.b64encode(f.read()).decode("utf-8")
            return {"type": "file_upload", "data": {
                "path": args[1], "content": content,
            }}, None
        except Exception as e:
            print(f"Error reading file: {e}")
            return None
    if cmd == "file_download":
        args = arg.split()
        if len(args) != 2:
            print("Usage: file_download <remote_path> <local_path>")
            return None
        return {"type": "file_download", "data": {"path": args[0]}}, args[1]
    if cmd == "raw":
        try:
            return json.loads(arg), None
        except json.JSONDecodeError:
            print("Invalid JSON")
            return None
    if cmd == "help":
        print(HELP_TEXT)
        return None
    if cmd == "quit":
        return "quit", None

    print(f"Unknown command: {cmd}. Type 'help' for available commands.")
    return None


def interactive_console(sock: socket.socket, masked: bool):
    print(HELP_TEXT)
    print()

    while True:
        try:
            line = input("newspaper> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nExiting...")
            break

        if not line:
            continue

        result = parse_command(line)
        if result is None:
            continue

        msg, save_path = result
        if msg == "quit":
            break

        try:
            send_json(sock, msg, masked=masked)
            response = recv_json(sock)

            if response is None:
                print("Connection closed by peer")
                break

            if save_path and response.get("status") == "ok":
                content = response.get("content", "")
                try:
                    with open(save_path, "wb") as f:
                        f.write(base64.b64decode(content))
                    print(f"File saved to: {save_path}")
                except Exception as e:
                    print(f"Error saving file: {e}")
            else:
                print(json.dumps(response, indent=2, ensure_ascii=False))
        except ConnectionError as e:
            print(f"Connection error: {e}")
            break


# ═══════════════════════════════════════════════════════════════════════════════
# Server mode
# ═══════════════════════════════════════════════════════════════════════════════

def run_server(host: str, port: int, password: str):
    ctx = create_ssl_context(password, is_server=True)

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((host, port))
    srv.listen(5)

    print(f"[Server] Listening on {host}:{port} (mTLS)")

    while True:
        try:
            raw_sock, addr = srv.accept()
            print(f"[Server] Connection from {addr}")

            ssl_sock = ctx.wrap_socket(raw_sock, server_side=True)
            peer = ssl_sock.getpeercert()
            cn = "?"
            if peer:
                for rdn in peer.get("subject", ()):
                    for k, v in rdn:
                        if k == "commonName":
                            cn = v
            print(f"[Server] TLS established, peer CN={cn}")

            if not server_handshake(ssl_sock):
                print("[Server] WebSocket handshake failed")
                ssl_sock.close()
                continue

            print("[Server] WebSocket handshake completed")
            interactive_console(ssl_sock, masked=False)

            ssl_sock.close()
            print("[Server] Client disconnected")
        except KeyboardInterrupt:
            print("\n[Server] Shutting down...")
            break
        except Exception as e:
            print(f"[Server] Error: {e}")

    srv.close()


# ═══════════════════════════════════════════════════════════════════════════════
# Client mode
# ═══════════════════════════════════════════════════════════════════════════════

def run_client(host: str, port: int, password: str):
    ctx = create_ssl_context(password, is_server=False)

    raw_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    raw_sock.settimeout(10)

    print(f"[Client] Connecting to {host}:{port} (mTLS)...")

    try:
        ssl_sock = ctx.wrap_socket(raw_sock, server_hostname=host)
        ssl_sock.connect((host, port))
    except Exception as e:
        print(f"[Client] Connection failed: {e}")
        raw_sock.close()
        return

    ssl_sock.settimeout(None)
    print("[Client] TLS established")

    try:
        client_handshake(ssl_sock)
        print("[Client] WebSocket handshake completed")
        print()
        interactive_console(ssl_sock, masked=True)
    except Exception as e:
        print(f"[Client] Error: {e}")
    finally:
        ssl_sock.close()


# ═══════════════════════════════════════════════════════════════════════════════
# Entry point
# ═══════════════════════════════════════════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(
        description="Newspaper MTLS test client/server"
    )
    parser.add_argument("mode", choices=["server", "client"], help="Run mode")
    parser.add_argument(
        "--host", default="127.0.0.1",
        help="Host to bind/connect (default: 127.0.0.1)",
    )
    parser.add_argument(
        "--port", type=int, default=8080,
        help="Port (default: 8080)",
    )
    parser.add_argument(
        "--password", required=True,
        help="Password for certificate derivation",
    )
    args = parser.parse_args()

    if args.mode == "server":
        run_server(args.host, args.port, args.password)
    else:
        run_client(args.host, args.port, args.password)


if __name__ == "__main__":
    main()
