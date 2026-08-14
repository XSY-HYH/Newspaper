"""
Newspaper Minecraft mod remote operation SDK.

Pure API encapsulation: mTLS authentication, WebSocket transport,
basic operations, and chunked file transfer.

Usage as library:
    from newspaper import NewspaperSDK

    # Client mode
    sdk = NewspaperSDK(password="mypass", host="127.0.0.1", port=8080)
    sdk.connect()
    info = sdk.server_info()
    sdk.upload_file("local.txt", "/remote/path/file.txt")
    sdk.close()

    # Server mode
    sdk = NewspaperSDK(password="mypass", is_server=True, host="0.0.0.0", port=8080)
    sdk.serve()

Requirements:
    pip install cryptography
"""

import base64
import hashlib
import json
import os
import socket
import ssl
import struct
import subprocess
import tempfile
import threading
import uuid
from datetime import datetime, timedelta

# ── Constants ──

SECP256R1_N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
DEFAULT_CHUNK_SIZE = 65536


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


def _create_ssl_context(password: str, is_server: bool) -> ssl.SSLContext:
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

    cert_file = tempfile.NamedTemporaryFile(delete=False, suffix=".pem", mode="w")
    cert_file.write(cert_pem)
    cert_file.write(ca_pem)
    cert_file.close()

    key_file = tempfile.NamedTemporaryFile(delete=False, suffix=".pem", mode="w")
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
    frame.append(0x80 | opcode)

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


def _send_binary(sock: socket.socket, data: bytes, masked: bool):
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


def _read_frame(sock: socket.socket):
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

def _client_handshake(sock: socket.socket):
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


def _server_handshake(sock: socket.socket) -> bool:
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
# NewspaperSDK
# ═══════════════════════════════════════════════════════════════════════════════

class NewspaperSDK:
    """
    Newspaper mod remote operation SDK.

    Encapsulates mTLS authentication, WebSocket transport, basic operations,
    and chunked file transfer. Supports both client and server modes.

    Client mode:
        sdk = NewspaperSDK(password="mypass", host="127.0.0.1", port=8080)
        sdk.connect()
        info = sdk.server_info()
        sdk.close()

    Server mode:
        sdk = NewspaperSDK(password="mypass", is_server=True, host="0.0.0.0", port=8080)
        sdk.serve()  # blocking, handles incoming requests
    """

    def __init__(self, password: str, is_server: bool = False,
                 host: str = "127.0.0.1", port: int = 8080,
                 chunk_size: int = DEFAULT_CHUNK_SIZE):
        self.password = password
        self.is_server = is_server
        self.host = host
        self.port = port
        self.chunk_size = chunk_size
        self.sock: socket.socket | None = None
        self.ssl_ctx: ssl.SSLContext | None = None
        self.masked = not is_server
        self._transfers: dict[str, dict] = {}
        self._running = False

    # ── Connection ─────────────────────────────────────────────────────────

    def connect(self):
        """Client mode: establish mTLS + WebSocket connection to the server."""
        self.ssl_ctx = _create_ssl_context(self.password, is_server=False)
        raw_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        raw_sock.settimeout(10)
        self.sock = self.ssl_ctx.wrap_socket(raw_sock, server_hostname=self.host)
        self.sock.connect((self.host, self.port))
        self.sock.settimeout(None)
        _client_handshake(self.sock)

    def serve(self, max_clients: int = 5):
        """
        Server mode: listen, accept clients and handle requests.
        Blocking call. Set self.stop() to gracefully shut down.
        """
        self._running = True
        self.ssl_ctx = _create_ssl_context(self.password, is_server=True)

        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((self.host, self.port))
        srv.listen(max_clients)
        srv.settimeout(1.0)

        while self._running:
            try:
                try:
                    raw_sock, addr = srv.accept()
                except socket.timeout:
                    continue
                except OSError:
                    if not self._running:
                        break
                    continue

                try:
                    self._handle_client(raw_sock)
                except Exception:
                    pass
                finally:
                    try:
                        raw_sock.close()
                    except Exception:
                        pass
            except KeyboardInterrupt:
                self._running = False
                break

        srv.close()

    def stop(self):
        """Signal the server loop to stop."""
        self._running = False

    def _handle_client(self, raw_sock: socket.socket):
        """Wrap a raw socket in TLS, perform WebSocket handshake, serve requests."""
        self.sock = self.ssl_ctx.wrap_socket(raw_sock, server_side=True)
        if not _server_handshake(self.sock):
            return
        self._serve_loop()

    def _serve_loop(self):
        """Main request-handling loop for server mode."""
        while self._running:
            msg = self.recv_json()
            if msg is None:
                break
            response = self.handle_request(msg)
            if response is not None:
                self.send_json(response)
            if msg.get("type") == "shutdown":
                break

    def close(self):
        """Close the current connection."""
        if self.sock:
            try:
                self.sock.sendall(_build_frame(b"", 0x8, self.masked))
            except Exception:
                pass
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None

    # ── Low-level JSON transport ───────────────────────────────────────────

    def send_json(self, msg: dict):
        """Send a JSON message as a WebSocket binary frame."""
        data = json.dumps(msg, ensure_ascii=False).encode("utf-8")
        _send_binary(self.sock, data, self.masked)

    def recv_json(self):
        """Receive and parse a JSON frame. Returns dict or None on close."""
        result = _read_frame(self.sock)
        if result is None:
            return None
        opcode, payload = result
        if opcode == 0x8:
            return None
        return json.loads(payload.decode("utf-8"))

    def request(self, msg: dict):
        """Send a request and wait for the response."""
        self.send_json(msg)
        return self.recv_json()

    # ── Basic operations (client side) ─────────────────────────────────────

    def server_info(self):
        return self.request({"type": "server_info", "data": {}})

    def online_players(self):
        return self.request({"type": "online_players", "data": {}})

    def command(self, cmd: str):
        return self.request({"type": "command", "data": {"command": cmd}})

    def shell_command(self, cmd: str, encoding: str | None = None,
                      timeout: int = 30):
        data = {"command": cmd, "timeout": timeout}
        if encoding:
            data["encoding"] = encoding
        return self.request({"type": "shell_command", "data": data})

    def shutdown(self):
        return self.request({"type": "shutdown", "data": {}})

    def config_reload(self):
        return self.request({"type": "config_reload", "data": {}})

    def config_modify(self, key: str, value: str):
        return self.request({"type": "config_modify", "data": {key: value}})

    def console_broadcast(self, message: str):
        return self.request({"type": "console_broadcast", "data": {"message": message}})

    def poll_chat_messages(self):
        return self.request({"type": "chat_message", "data": {}})

    def poll_player_join(self):
        return self.request({"type": "player_join", "data": {}})

    def poll_player_quit(self):
        return self.request({"type": "player_quit", "data": {}})

    # ── Chunked file transfer (client side) ────────────────────────────────

    def upload_file(self, local_path: str, remote_path: str,
                    chunk_size: int | None = None) -> dict:
        """
        Upload a local file to the remote server via chunked transfer.

        Protocol: start -> meta -> chunks -> end (with SHA-256 verification).
        """
        chunk_size = chunk_size or self.chunk_size
        transfer_id = str(uuid.uuid4())

        with open(local_path, "rb") as f:
            content = f.read()
        total_size = len(content)
        file_hash = hashlib.sha256(content).hexdigest()
        total_chunks = (total_size + chunk_size - 1) // chunk_size if total_size > 0 else 0

        # 1. start
        resp = self.request({"type": "file_transfer_start", "data": {
            "transfer_id": transfer_id,
            "direction": "upload",
            "filename": remote_path,
        }})
        if not self._is_ok(resp):
            return resp

        # 2. meta
        resp = self.request({"type": "file_transfer_meta", "data": {
            "transfer_id": transfer_id,
            "total_size": total_size,
            "chunk_size": chunk_size,
            "total_chunks": total_chunks,
            "hash": file_hash,
        }})
        if not self._is_ok(resp):
            return resp

        # 3. chunks
        for i in range(total_chunks):
            chunk = content[i * chunk_size:(i + 1) * chunk_size]
            resp = self.request({"type": "file_transfer_chunk", "data": {
                "transfer_id": transfer_id,
                "chunk_index": i,
                "data": base64.b64encode(chunk).decode("utf-8"),
            }})
            if not self._is_ok(resp):
                return resp

        # 4. end
        return self.request({"type": "file_transfer_end", "data": {
            "transfer_id": transfer_id,
            "hash": file_hash,
        }})

    def download_file(self, remote_path: str, local_path: str) -> dict:
        """
        Download a remote file to local via chunked transfer.

        Protocol: start (returns meta) -> chunks -> end (with SHA-256 verification).
        """
        transfer_id = str(uuid.uuid4())

        # 1. start (server returns file meta)
        resp = self.request({"type": "file_transfer_start", "data": {
            "transfer_id": transfer_id,
            "direction": "download",
            "filename": remote_path,
        }})
        if not self._is_ok(resp):
            return resp

        total_chunks = resp.get("total_chunks", 0)
        expected_hash = resp.get("hash", "")

        # 2. chunks
        chunks = []
        for i in range(total_chunks):
            resp = self.request({"type": "file_transfer_chunk", "data": {
                "transfer_id": transfer_id,
                "chunk_index": i,
            }})
            if not self._is_ok(resp):
                return resp
            chunks.append(base64.b64decode(resp["data"]))

        content = b"".join(chunks)
        actual_hash = hashlib.sha256(content).hexdigest()

        # 3. end
        resp = self.request({"type": "file_transfer_end", "data": {
            "transfer_id": transfer_id,
            "hash": actual_hash,
        }})

        # Verify hash
        if expected_hash and actual_hash != expected_hash:
            return {"status": "error",
                    "message": f"Hash mismatch: expected {expected_hash}, got {actual_hash}"}

        # Save file
        with open(local_path, "wb") as f:
            f.write(content)
        return resp

    # ── Request handling (server side) ─────────────────────────────────────

    def handle_request(self, msg: dict) -> dict | None:
        """
        Dispatch an incoming request to the appropriate handler.
        Override this method or the individual _h_* methods to customize server behavior.
        """
        msg_type = msg.get("type", "")
        data = msg.get("data", {})

        handler = {
            "server_info": self._h_server_info,
            "online_players": self._h_online_players,
            "command": self._h_command,
            "shell_command": self._h_shell_command,
            "shutdown": self._h_shutdown,
            "config_reload": self._h_config_reload,
            "config_modify": self._h_config_modify,
            "console_broadcast": self._h_console_broadcast,
            "chat_message": self._h_poll_empty,
            "player_join": self._h_poll_empty,
            "player_quit": self._h_poll_empty,
            "file_transfer_start": self._handle_file_transfer_start,
            "file_transfer_meta": self._handle_file_transfer_meta,
            "file_transfer_chunk": self._handle_file_transfer_chunk,
            "file_transfer_end": self._handle_file_transfer_end,
        }.get(msg_type)

        if handler is None:
            return {"status": "error", "message": f"Unknown type: {msg_type}"}

        try:
            return handler(data)
        except Exception as e:
            return {"status": "error", "message": str(e)}

    # Default server-side handlers (override to customize)

    def _h_server_info(self, data):
        return {"status": "ok", "info": {
            "motd": "Newspaper Test Server",
            "version": "2.0.0",
            "tps": 20.0,
        }}

    def _h_online_players(self, data):
        return {"status": "ok", "players": []}

    def _h_command(self, data):
        return {"status": "ok", "result": f"Executed: {data.get('command', '')}"}

    def _h_shell_command(self, data):
        cmd = data.get("command", "")
        encoding = data.get("encoding", "utf-8")
        timeout = data.get("timeout", 30)
        try:
            result = subprocess.run(
                cmd, shell=True, capture_output=True, timeout=timeout
            )
            stdout = result.stdout.decode(encoding, errors="replace")
            stderr = result.stderr.decode(encoding, errors="replace")
            return {
                "status": "ok",
                "stdout": stdout,
                "stderr": stderr,
                "returncode": result.returncode,
            }
        except subprocess.TimeoutExpired:
            return {"status": "error", "message": f"Command timed out ({timeout}s)"}
        except Exception as e:
            return {"status": "error", "message": str(e)}

    def _h_shutdown(self, data):
        return {"status": "ok", "message": "Server shutting down"}

    def _h_config_reload(self, data):
        return {"status": "ok", "message": "Configuration reloaded"}

    def _h_config_modify(self, data):
        return {"status": "ok", "message": "Configuration modified", "changes": data}

    def _h_console_broadcast(self, data):
        return {"status": "ok", "message": f"Broadcast: {data.get('message', '')}"}

    def _h_poll_empty(self, data):
        return {"status": "ok", "messages": []}

    # File transfer handlers (server side)

    def _handle_file_transfer_start(self, data: dict) -> dict:
        transfer_id = data["transfer_id"]
        direction = data["direction"]
        filename = data["filename"]

        if direction == "upload":
            self._transfers[transfer_id] = {
                "direction": "upload",
                "filename": filename,
                "chunks": {},
                "total_chunks": None,
                "hash": None,
            }
            return {"status": "ok", "transfer_id": transfer_id}

        if direction == "download":
            try:
                with open(filename, "rb") as f:
                    content = f.read()
            except Exception as e:
                return {"status": "error", "message": str(e)}

            total_size = len(content)
            file_hash = hashlib.sha256(content).hexdigest()
            total_chunks = (total_size + self.chunk_size - 1) // self.chunk_size if total_size > 0 else 0

            self._transfers[transfer_id] = {
                "direction": "download",
                "filename": filename,
                "content": content,
                "total_chunks": total_chunks,
                "hash": file_hash,
            }
            return {
                "status": "ok",
                "transfer_id": transfer_id,
                "total_size": total_size,
                "chunk_size": self.chunk_size,
                "total_chunks": total_chunks,
                "hash": file_hash,
            }

        return {"status": "error", "message": f"Unknown direction: {direction}"}

    def _handle_file_transfer_meta(self, data: dict) -> dict:
        transfer_id = data["transfer_id"]
        transfer = self._transfers.get(transfer_id)
        if transfer is None or transfer["direction"] != "upload":
            return {"status": "error", "message": "Unknown transfer_id"}

        transfer["total_chunks"] = data["total_chunks"]
        transfer["hash"] = data["hash"]
        transfer["chunk_size"] = data.get("chunk_size", self.chunk_size)
        return {"status": "ok", "transfer_id": transfer_id}

    def _handle_file_transfer_chunk(self, data: dict) -> dict:
        transfer_id = data["transfer_id"]
        chunk_index = data["chunk_index"]
        transfer = self._transfers.get(transfer_id)
        if transfer is None:
            return {"status": "error", "message": "Unknown transfer_id"}

        if transfer["direction"] == "upload":
            chunk_data = base64.b64decode(data["data"])
            transfer["chunks"][chunk_index] = chunk_data
            received = len(transfer["chunks"])
            return {
                "status": "ok",
                "transfer_id": transfer_id,
                "chunk_index": chunk_index,
                "received": received,
            }

        if transfer["direction"] == "download":
            content = transfer["content"]
            cs = self.chunk_size
            start = chunk_index * cs
            chunk = content[start:start + cs]
            is_last = chunk_index == transfer["total_chunks"] - 1
            return {
                "status": "ok",
                "transfer_id": transfer_id,
                "chunk_index": chunk_index,
                "data": base64.b64encode(chunk).decode("utf-8"),
                "is_last": is_last,
            }

        return {"status": "error", "message": "Invalid transfer direction"}

    def _handle_file_transfer_end(self, data: dict) -> dict:
        transfer_id = data["transfer_id"]
        transfer = self._transfers.pop(transfer_id, None)
        if transfer is None:
            return {"status": "error", "message": "Unknown transfer_id"}

        if transfer["direction"] == "upload":
            total_chunks = transfer["total_chunks"] or 0
            chunks = transfer["chunks"]
            content = b"".join(chunks[i] for i in range(total_chunks))
            actual_hash = hashlib.sha256(content).hexdigest()
            expected_hash = transfer["hash"] or ""

            if expected_hash and actual_hash != expected_hash:
                return {
                    "status": "error",
                    "message": f"Hash mismatch: expected {expected_hash}, got {actual_hash}",
                }

            filename = transfer["filename"]
            os.makedirs(os.path.dirname(filename) or ".", exist_ok=True)
            with open(filename, "wb") as f:
                f.write(content)

            return {
                "status": "ok",
                "message": "File uploaded successfully",
                "path": filename,
                "size": len(content),
            }

        if transfer["direction"] == "download":
            return {"status": "ok", "message": "Transfer completed"}

        return {"status": "error", "message": "Invalid transfer direction"}

    # ── Utils ──────────────────────────────────────────────────────────────

    @staticmethod
    def _is_ok(resp) -> bool:
        return resp is not None and resp.get("status") == "ok"
