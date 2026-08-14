// ═══════════════════════════════════════════════════════════════════════════════
// Newspaper Node.js SDK — mTLS + WebSocket remote operation for Minecraft mod
//
// Zero external dependencies — uses only Node.js built-in modules.
// Certificate derivation is deterministic and cross-compatible with the
// Java/Python/C# implementations (secp256r1 + SHA-256).
// ═══════════════════════════════════════════════════════════════════════════════

import crypto from 'node:crypto';
import tls from 'node:tls';
import fs from 'node:fs';
import path from 'node:path';

// ── Constants ──────────────────────────────────────────────────────────────────

const SECP256R1_N =
  0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551n;

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const DEFAULT_CHUNK_SIZE = 65536;
const VALIDITY_MS = 3650 * 24 * 60 * 60 * 1000;
const DEFAULT_TIMEOUT = 30000;

// ═══════════════════════════════════════════════════════════════════════════════
// ASN.1 DER encoder — minimal, enough for X.509 certificates
// ═══════════════════════════════════════════════════════════════════════════════

function derLength(len) {
  if (len < 0x80) return Buffer.from([len]);
  if (len < 0x100) return Buffer.from([0x81, len]);
  if (len < 0x10000) {
    const b = Buffer.alloc(3);
    b[0] = 0x82;
    b.writeUInt16BE(len, 1);
    return b;
  }
  if (len < 0x1000000) {
    const b = Buffer.alloc(4);
    b[0] = 0x83;
    b.writeUIntBE(len, 1, 3);
    return b;
  }
  const b = Buffer.alloc(5);
  b[0] = 0x84;
  b.writeUInt32BE(len, 1);
  return b;
}

function derTag(tag, content) {
  return Buffer.concat([Buffer.from([tag]), derLength(content.length), content]);
}

function derSequence(content) {
  return derTag(0x30, content);
}

function derSet(content) {
  return derTag(0x31, content);
}

function derInteger(value) {
  let buf;
  if (typeof value === 'bigint') {
    if (value === 0n) {
      buf = Buffer.alloc(1, 0);
    } else {
      let hex = value.toString(16);
      if (hex.length % 2 !== 0) hex = '0' + hex;
      buf = Buffer.from(hex, 'hex');
    }
  } else if (Buffer.isBuffer(value)) {
    buf = Buffer.from(value);
  } else {
    return derInteger(BigInt(value));
  }
  if (buf[0] & 0x80) buf = Buffer.concat([Buffer.from([0x00]), buf]);
  return derTag(0x02, buf);
}

function encodeOID(oidStr) {
  const parts = oidStr.split('.').map(Number);
  const bytes = [40 * parts[0] + parts[1]];
  for (let i = 2; i < parts.length; i++) {
    let n = parts[i];
    if (n < 128) {
      bytes.push(n);
    } else {
      const temp = [n & 0x7f];
      n >>>= 7;
      while (n > 0) {
        temp.push((n & 0x7f) | 0x80);
        n >>>= 7;
      }
      temp.reverse();
      bytes.push(...temp);
    }
  }
  return Buffer.from(bytes);
}

function derOID(oidStr) {
  return derTag(0x06, encodeOID(oidStr));
}

function derBitString(data, unusedBits = 0) {
  return derTag(0x03, Buffer.concat([Buffer.from([unusedBits]), data]));
}

function derOctetString(data) {
  return derTag(0x04, data);
}

function derBoolean(value) {
  return derTag(0x01, Buffer.from([value ? 0xff : 0x00]));
}

function derUtf8String(str) {
  return derTag(0x0c, Buffer.from(str, 'utf-8'));
}

function derUTCTime(date) {
  const yy = String(date.getUTCFullYear() % 100).padStart(2, '0');
  const mm = String(date.getUTCMonth() + 1).padStart(2, '0');
  const dd = String(date.getUTCDate()).padStart(2, '0');
  const hh = String(date.getUTCHours()).padStart(2, '0');
  const mi = String(date.getUTCMinutes()).padStart(2, '0');
  const ss = String(date.getUTCSeconds()).padStart(2, '0');
  return derTag(0x17, Buffer.from(`${yy}${mm}${dd}${hh}${mi}${ss}Z`, 'ascii'));
}

function derExplicit(tagNumber, content) {
  return derTag(0xa0 | tagNumber, content);
}

function derNull() {
  return Buffer.from([0x05, 0x00]);
}

function derToPem(derBuffer, label = 'CERTIFICATE') {
  const b64 = derBuffer.toString('base64');
  const lines = [];
  for (let i = 0; i < b64.length; i += 64) {
    lines.push(b64.substring(i, i + 64));
  }
  return `-----BEGIN ${label}-----\n${lines.join('\n')}\n-----END ${label}-----\n`;
}

function bigIntToBufferBE(value, length) {
  let hex = value.toString(16);
  if (hex.length % 2 !== 0) hex = '0' + hex;
  while (hex.length < length * 2) hex = '0' + hex;
  return Buffer.from(hex, 'hex');
}

// ── OID strings ────────────────────────────────────────────────────────────────

const OID = {
  EC_PUBLIC_KEY: '1.2.840.10045.2.1',
  PRIME256V1: '1.2.840.10045.3.1.7',
  ECDSA_SHA256: '1.2.840.10045.4.3.2',
  COMMON_NAME: '2.5.4.3',
  ORG_NAME: '2.5.4.10',
  BASIC_CONSTRAINTS: '2.5.29.19',
  KEY_USAGE: '2.5.29.15',
  EXT_KEY_USAGE: '2.5.29.37',
  SERVER_AUTH: '1.3.6.1.5.5.7.3.1',
  CLIENT_AUTH: '1.3.6.1.5.5.7.3.2',
};

// ═══════════════════════════════════════════════════════════════════════════════
// Certificate derivation — matches Java CertificateGenerator exactly
// ═══════════════════════════════════════════════════════════════════════════════

function sha256(data) {
  return crypto.createHash('sha256').update(data).digest();
}

/**
 * Derive a deterministic scalar from seed + label over secp256r1.
 * Algorithm: hash = SHA256(current || label), d = hash % n, repeat until d != 0.
 */
function deriveScalar(seed, label, n) {
  const labelBytes = Buffer.from(label, 'utf-8');
  let current = seed;
  for (;;) {
    const hash = sha256(Buffer.concat([current, labelBytes]));
    const d = BigInt('0x' + hash.toString('hex')) % n;
    current = hash;
    if (d !== 0n) return d;
  }
}

/**
 * Derive a deterministic EC key pair (secp256r1 / prime256v1) from password.
 * Returns { d, dBuffer, publicKey, keyObject }.
 */
function deriveKeyPair(password, label) {
  const seed = sha256(Buffer.from(password, 'utf-8'));
  const d = deriveScalar(seed, label, SECP256R1_N);
  const dBuffer = bigIntToBufferBE(d, 32);

  const ecdh = crypto.createECDH('prime256v1');
  ecdh.setPrivateKey(dBuffer);
  const publicKey = ecdh.getPublicKey(); // 65 bytes, uncompressed 0x04||x||y

  // Build PKCS#8 DER for KeyObject import
  const ecPrivKey = derSequence(Buffer.concat([
    derInteger(1n),
    derOctetString(dBuffer),
    derExplicit(1, derBitString(publicKey)),
  ]));

  const pkcs8 = derSequence(Buffer.concat([
    derInteger(0n),
    derSequence(Buffer.concat([derOID(OID.EC_PUBLIC_KEY), derOID(OID.PRIME256V1)])),
    derOctetString(ecPrivKey),
  ]));

  const keyObject = crypto.createPrivateKey({ key: pkcs8, format: 'der', type: 'pkcs8' });

  return { d, dBuffer, publicKey, keyObject };
}

function buildX509Name(cn, org = 'Newspaper') {
  return derSequence(Buffer.concat([
    derSet(derSequence(Buffer.concat([derOID(OID.COMMON_NAME), derUtf8String(cn)]))),
    derSet(derSequence(Buffer.concat([derOID(OID.ORG_NAME), derUtf8String(org)]))),
  ]));
}

function buildExtension(oidStr, critical, valueDer) {
  const parts = [derOID(oidStr)];
  if (critical) parts.push(derBoolean(true));
  parts.push(derOctetString(valueDer));
  return derSequence(Buffer.concat(parts));
}

function signTbs(tbsDer, signerKeyObject) {
  const sign = crypto.createSign('SHA256');
  sign.update(tbsDer);
  return sign.sign(signerKeyObject);
}

function buildCaCertificate(keyPair) {
  const now = Date.now();
  const notBefore = new Date(now - 60000);
  const notAfter = new Date(now + VALIDITY_MS);
  const name = buildX509Name('Newspaper-CA');

  const spki = derSequence(Buffer.concat([
    derSequence(Buffer.concat([derOID(OID.EC_PUBLIC_KEY), derOID(OID.PRIME256V1)])),
    derBitString(keyPair.publicKey),
  ]));

  const extensions = derExplicit(3, derSequence(Buffer.concat([
    buildExtension(OID.BASIC_CONSTRAINTS, true, derSequence(derBoolean(true))),
    buildExtension(OID.KEY_USAGE, true, derBitString(Buffer.from([0x06]), 1)),
  ])));

  const tbs = derSequence(Buffer.concat([
    derExplicit(0, derInteger(2n)),
    derInteger(1n),
    derSequence(derOID(OID.ECDSA_SHA256)),
    name,
    derSequence(Buffer.concat([derUTCTime(notBefore), derUTCTime(notAfter)])),
    name,
    spki,
    extensions,
  ]));

  const signature = signTbs(tbs, keyPair.keyObject);

  return derSequence(Buffer.concat([
    tbs,
    derSequence(derOID(OID.ECDSA_SHA256)),
    derBitString(signature),
  ]));
}

function buildEndCertificate(subjectKeyPair, caKeyPair, cn) {
  const now = Date.now();
  const notBefore = new Date(now - 60000);
  const notAfter = new Date(now + VALIDITY_MS);
  const issuerName = buildX509Name('Newspaper-CA');
  const subjectName = buildX509Name(cn);

  const spki = derSequence(Buffer.concat([
    derSequence(Buffer.concat([derOID(OID.EC_PUBLIC_KEY), derOID(OID.PRIME256V1)])),
    derBitString(subjectKeyPair.publicKey),
  ]));

  const extensions = derExplicit(3, derSequence(Buffer.concat([
    buildExtension(OID.BASIC_CONSTRAINTS, true, derSequence(Buffer.alloc(0))),
    buildExtension(OID.KEY_USAGE, true, derBitString(Buffer.from([0xa0]), 5)),
    buildExtension(OID.EXT_KEY_USAGE, true, derSequence(Buffer.concat([
      derOID(OID.SERVER_AUTH),
      derOID(OID.CLIENT_AUTH),
    ]))),
  ])));

  const tbs = derSequence(Buffer.concat([
    derExplicit(0, derInteger(2n)),
    derInteger(BigInt(now)),
    derSequence(derOID(OID.ECDSA_SHA256)),
    issuerName,
    derSequence(Buffer.concat([derUTCTime(notBefore), derUTCTime(notAfter)])),
    subjectName,
    spki,
    extensions,
  ]));

  const signature = signTbs(tbs, caKeyPair.keyObject);

  return derSequence(Buffer.concat([
    tbs,
    derSequence(derOID(OID.ECDSA_SHA256)),
    derBitString(signature),
  ]));
}

/**
 * Derive mTLS certificates from a shared password.
 * Returns { key: PEM, cert: PEM (chain), ca: PEM }.
 */
export function deriveCertificates(password, isServer) {
  const caKeyPair = deriveKeyPair(password, 'newspaper-ca');
  const endLabel = isServer ? 'newspaper-server' : 'newspaper-client';
  const endCn = isServer ? 'Newspaper-Server' : 'Newspaper-Client';
  const endKeyPair = deriveKeyPair(password, endLabel);

  const caCertDer = buildCaCertificate(caKeyPair);
  const endCertDer = buildEndCertificate(endKeyPair, caKeyPair, endCn);

  const keyPem = endKeyPair.keyObject.export({ type: 'sec1', format: 'pem' });
  const certPem = derToPem(endCertDer);
  const caPem = derToPem(caCertDer);

  return { key: keyPem, cert: certPem + caPem, ca: caPem };
}

// ═══════════════════════════════════════════════════════════════════════════════
// WebSocket frame encoding / decoding
// ═══════════════════════════════════════════════════════════════════════════════

const OPCODE = {
  CONTINUATION: 0x0,
  TEXT: 0x1,
  BINARY: 0x2,
  CLOSE: 0x8,
  PING: 0x9,
  PONG: 0xa,
};

function buildWsFrame(payload, opcode = OPCODE.BINARY, masked = false) {
  const parts = [Buffer.from([0x80 | opcode])];
  const maskBit = masked ? 0x80 : 0x00;
  const len = payload.length;

  if (len <= 125) {
    parts.push(Buffer.from([maskBit | len]));
  } else if (len <= 65535) {
    const b = Buffer.alloc(3);
    b[0] = maskBit | 126;
    b.writeUInt16BE(len, 1);
    parts.push(b);
  } else {
    const b = Buffer.alloc(9);
    b[0] = maskBit | 127;
    b.writeUInt32BE(Math.floor(len / 0x100000000), 1);
    b.writeUInt32BE(len % 0x100000000, 5);
    parts.push(b);
  }

  if (masked) {
    const maskKey = crypto.randomBytes(4);
    parts.push(maskKey);
    const masked = Buffer.allocUnsafe(len);
    for (let i = 0; i < len; i++) masked[i] = payload[i] ^ maskKey[i % 4];
    parts.push(masked);
  } else {
    parts.push(payload);
  }

  return Buffer.concat(parts);
}

/**
 * WsConnection — wraps a TLS socket with WebSocket frame handling.
 * Handles fragmentation, ping/pong, and close frames.
 */
class WsConnection {
  constructor(socket, isClient) {
    this.socket = socket;
    this.isClient = isClient; // client frames must be masked
    this._buffer = Buffer.alloc(0);
    this._fragments = [];
    this._fragmentOpcode = null;
    this.onMessage = null;
    this.onClose = null;
    this._closed = false;

    socket.on('data', (data) => this._onData(data));
    socket.on('close', () => this._onClose());
    socket.on('error', (err) => this._onClose(err));
  }

  _onData(data) {
    this._buffer = Buffer.concat([this._buffer, data]);
    this._processBuffer();
  }

  _processBuffer() {
    for (;;) {
      const frame = this._tryParseFrame();
      if (!frame) break;
      this._handleFrame(frame);
    }
  }

  _tryParseFrame() {
    if (this._buffer.length < 2) return null;
    const b1 = this._buffer[0];
    const b2 = this._buffer[1];
    const fin = (b1 & 0x80) !== 0;
    const opcode = b1 & 0x0f;
    const masked = (b2 & 0x80) !== 0;
    let length = b2 & 0x7f;
    let offset = 2;

    if (length === 126) {
      if (this._buffer.length < 4) return null;
      length = this._buffer.readUInt16BE(2);
      offset = 4;
    } else if (length === 127) {
      if (this._buffer.length < 10) return null;
      const hi = this._buffer.readUInt32BE(2);
      const lo = this._buffer.readUInt32BE(6);
      length = hi * 0x100000000 + lo;
      offset = 10;
    }

    let maskKey = null;
    if (masked) {
      if (this._buffer.length < offset + 4) return null;
      maskKey = this._buffer.subarray(offset, offset + 4);
      offset += 4;
    }

    if (this._buffer.length < offset + length) return null;

    let payload = this._buffer.subarray(offset, offset + length);
    if (masked && maskKey) {
      const unmasked = Buffer.allocUnsafe(length);
      for (let i = 0; i < length; i++) unmasked[i] = payload[i] ^ maskKey[i % 4];
      payload = unmasked;
    }

    this._buffer = this._buffer.subarray(offset + length);
    return { fin, opcode, payload };
  }

  _handleFrame(frame) {
    switch (frame.opcode) {
      case OPCODE.CONTINUATION:
        if (this._fragmentOpcode !== null) {
          this._fragments.push(frame.payload);
          if (frame.fin) {
            this.onMessage?.(Buffer.concat(this._fragments));
            this._fragments = [];
            this._fragmentOpcode = null;
          }
        }
        break;
      case OPCODE.TEXT:
      case OPCODE.BINARY:
        if (frame.fin) {
          this.onMessage?.(frame.payload);
        } else {
          this._fragmentOpcode = frame.opcode;
          this._fragments = [frame.payload];
        }
        break;
      case OPCODE.CLOSE:
        this._onClose();
        break;
      case OPCODE.PING:
        this.send(frame.payload, OPCODE.PONG);
        break;
      case OPCODE.PONG:
        break;
    }
  }

  send(payload, opcode = OPCODE.BINARY) {
    if (this._closed) return;
    this.socket.write(buildWsFrame(payload, opcode, this.isClient));
  }

  sendJSON(obj) {
    this.send(Buffer.from(JSON.stringify(obj), 'utf-8'), OPCODE.BINARY);
  }

  close() {
    if (this._closed) return;
    this._closed = true;
    try {
      this.socket.write(buildWsFrame(Buffer.alloc(0), OPCODE.CLOSE, this.isClient));
    } catch { /* ignore */ }
    this.socket.destroy();
  }

  _onClose(err) {
    if (this._closed) return;
    this._closed = true;
    this.onClose?.(err);
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
// WebSocket handshake helpers
// ═══════════════════════════════════════════════════════════════════════════════

function readHttpHeaders(socket) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;

    const onData = (data) => {
      chunks.push(data);
      total += data.length;
      const combined = Buffer.concat(chunks, total);
      const idx = combined.indexOf('\r\n\r\n');
      if (idx !== -1) {
        socket.off('data', onData);
        socket.off('error', onError);
        resolve({
          headers: combined.subarray(0, idx).toString('utf-8'),
          remaining: combined.subarray(idx + 4),
        });
      }
    };
    const onError = (err) => {
      socket.off('data', onData);
      socket.off('error', onError);
      reject(err);
    };
    socket.on('data', onData);
    socket.on('error', onError);
  });
}

function parseHeader(headers, name) {
  const lower = name.toLowerCase();
  for (const line of headers.split('\r\n')) {
    const idx = line.indexOf(':');
    if (idx === -1) continue;
    if (line.substring(0, idx).trim().toLowerCase() === lower) {
      return line.substring(idx + 1).trim();
    }
  }
  return null;
}

function computeWsAcceptKey(clientKey) {
  return crypto.createHash('sha1').update(clientKey + WS_GUID).digest('base64');
}

// ═══════════════════════════════════════════════════════════════════════════════
// JSON / encoding utilities
// ═══════════════════════════════════════════════════════════════════════════════

function decodePayload(payload) {
  return payload.toString('utf-8');
}

function parseJsonSafe(text) {
  try {
    return JSON.parse(text);
  } catch {
    const cleaned = text.replace(/[\x00-\x1f\x7f]/g, '');
    return JSON.parse(cleaned);
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
// NewspaperClient
// ═══════════════════════════════════════════════════════════════════════════════

export class NewspaperClient {
  /**
   * @param {object} options
   * @param {string} options.host - Server host
   * @param {number} options.port - Server port
   * @param {string} options.password - Shared password for certificate derivation
   * @param {number} [options.timeout=30000] - Default request timeout (ms)
   */
  constructor(options) {
    if (!options || !options.password) {
      throw new Error('password is required');
    }
    this.host = options.host || '127.0.0.1';
    this.port = options.port || 8080;
    this.password = options.password;
    this.timeout = options.timeout || DEFAULT_TIMEOUT;
    this.socket = null;
    this.ws = null;
    this._pendingResolve = null;
    this._pendingReject = null;
    this._pendingTimer = null;
    this.onEvent = null; // for unsolicited messages (e.g. subscribe push)
    this._connected = false;
  }

  async connect() {
    const certs = deriveCertificates(this.password, false);

    this.socket = tls.connect({
      host: this.host,
      port: this.port,
      key: certs.key,
      cert: certs.cert,
      ca: certs.ca,
      rejectUnauthorized: true,
      checkServerIdentity: () => undefined,
    });

    await new Promise((resolve, reject) => {
      this.socket.once('secureConnect', resolve);
      this.socket.once('error', reject);
    });

    // WebSocket upgrade
    const wsKey = crypto.randomBytes(16).toString('base64');
    const request =
      'GET / HTTP/1.1\r\n' +
      `Host: ${this.host}:${this.port}\r\n` +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Key: ${wsKey}\r\n` +
      'Sec-WebSocket-Version: 13\r\n' +
      '\r\n';
    this.socket.write(request);

    const { headers, remaining } = await readHttpHeaders(this.socket);
    if (!headers.includes('101')) {
      throw new Error('WebSocket handshake failed: ' + headers.split('\r\n')[0]);
    }

    this.ws = new WsConnection(this.socket, true);
    this.ws.onMessage = (payload) => this._onMessage(payload);
    this.ws.onClose = () => this._onClose();
    if (remaining.length > 0) this.ws._onData(remaining);
    this._connected = true;
  }

  _onMessage(payload) {
    const data = parseJsonSafe(decodePayload(payload));
    if (this._pendingResolve) {
      clearTimeout(this._pendingTimer);
      const resolve = this._pendingResolve;
      this._pendingResolve = null;
      this._pendingReject = null;
      this._pendingTimer = null;
      resolve(data);
    } else {
      this.onEvent?.(data);
    }
  }

  _onClose() {
    this._connected = false;
    if (this._pendingReject) {
      clearTimeout(this._pendingTimer);
      const reject = this._pendingReject;
      this._pendingResolve = null;
      this._pendingReject = null;
      this._pendingTimer = null;
      reject(new Error('Connection closed'));
    }
  }

  /**
   * Send a request and wait for the response.
   * @param {string} type - Operation type
   * @param {object} data - Request data
   * @param {number} [timeout] - Timeout in ms (defaults to this.timeout)
   * @returns {Promise<object>} Response object
   */
  async send(type, data = {}, timeout) {
    if (!this.ws || this.ws._closed) throw new Error('Not connected');

    const ms = timeout || this.timeout;
    const promise = new Promise((resolve, reject) => {
      this._pendingResolve = resolve;
      this._pendingReject = reject;
      this._pendingTimer = setTimeout(() => {
        if (this._pendingReject) {
          this._pendingResolve = null;
          this._pendingReject(new Error('Request timeout'));
          this._pendingReject = null;
          this._pendingTimer = null;
        }
      }, ms);
    });

    this.ws.sendJSON({ type, data });
    return promise;
  }

  disconnect() {
    this._connected = false;
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }

  isConnected() {
    return this._connected;
  }

  // ── Basic operations ──────────────────────────────────────────────────────

  async serverInfo() {
    return this.send('server_info', {});
  }

  async onlinePlayers() {
    return this.send('online_players', {});
  }

  async command(cmd) {
    return this.send('command', { command: cmd });
  }

  async commandAs(player, cmd) {
    return this.send('command2', { player, command: cmd });
  }

  /**
   * Execute a system shell command.
   * @param {string} command - Shell command
   * @param {object} [options]
   * @param {number} [options.timeout] - Timeout in seconds
   * @param {string} [options.encoding] - Output encoding (e.g. 'gbk' for Windows)
   */
  async shellCommand(command, options = {}) {
    const data = { command };
    if (options.timeout != null) data.timeout = options.timeout;
    if (options.encoding) data.encoding = options.encoding;
    return this.send('shell_command', data, options.timeout ? (options.timeout + 10) * 1000 : this.timeout);
  }

  async shutdown() {
    return this.send('shutdown', {});
  }

  async configReload() {
    return this.send('config_reload', {});
  }

  async configModify(key, value) {
    return this.send('config_modify', { [key]: value });
  }

  async consoleBroadcast(message) {
    return this.send('console_broadcast', { message });
  }

  // ── Poll-mode operations ──────────────────────────────────────────────────

  async consoleMessage(action = 'poll') {
    return this.send('console_message', { action });
  }

  async chatMessage(action = 'poll') {
    return this.send('chat_message', { action });
  }

  async playerJoin(action = 'poll') {
    return this.send('player_join', { action });
  }

  async playerQuit(action = 'poll') {
    return this.send('player_quit', { action });
  }

  // ── Chunked file transfer ────────────────────────────────────────────────

  /**
   * Upload a file using chunked transfer protocol.
   * @param {string} localPath - Local file path
   * @param {string} remotePath - Remote absolute path
   */
  async uploadFile(localPath, remotePath) {
    const transferId = crypto.randomUUID();
    const fileData = fs.readFileSync(localPath);
    const hash = crypto.createHash('sha256').update(fileData).digest('hex');
    const totalSize = fileData.length;
    const chunkSize = DEFAULT_CHUNK_SIZE;
    const totalChunks = Math.ceil(totalSize / chunkSize);

    // 1. start
    const startResp = await this.send('file_transfer_start', {
      transfer_id: transferId,
      direction: 'upload',
      filename: remotePath,
    });
    if (startResp.status !== 'ok') throw new Error(startResp.message || 'Upload start failed');

    // 2. meta
    const metaResp = await this.send('file_transfer_meta', {
      transfer_id: transferId,
      total_size: totalSize,
      chunk_size: chunkSize,
      total_chunks: totalChunks,
      hash,
    });
    if (metaResp.status !== 'ok') throw new Error(metaResp.message || 'Upload meta failed');

    // 3. chunks
    for (let i = 0; i < totalChunks; i++) {
      const start = i * chunkSize;
      const end = Math.min(start + chunkSize, totalSize);
      const chunk = fileData.subarray(start, end);
      const resp = await this.send('file_transfer_chunk', {
        transfer_id: transferId,
        chunk_index: i,
        data: chunk.toString('base64'),
      });
      if (resp.status !== 'ok') throw new Error(resp.message || `Chunk ${i} upload failed`);
    }

    // 4. end
    const endResp = await this.send('file_transfer_end', {
      transfer_id: transferId,
      hash,
    });
    if (endResp.status !== 'ok') throw new Error(endResp.message || 'Upload end failed');
    return endResp;
  }

  /**
   * Download a file using chunked transfer protocol.
   * @param {string} remotePath - Remote absolute path
   * @param {string} [localPath] - Local save path (if omitted, returns buffer)
   * @returns {Promise<{data: Buffer, hash: string, size: number}>}
   */
  async downloadFile(remotePath, localPath) {
    const transferId = crypto.randomUUID();

    // 1. start
    const startResp = await this.send('file_transfer_start', {
      transfer_id: transferId,
      direction: 'download',
      filename: remotePath,
    });
    if (startResp.status !== 'ok') throw new Error(startResp.message || 'Download start failed');

    const { total_size, total_chunks, hash: expectedHash } = startResp;

    // 2. chunks
    const chunks = [];
    for (let i = 0; i < total_chunks; i++) {
      const resp = await this.send('file_transfer_chunk', {
        transfer_id: transferId,
        chunk_index: i,
      });
      if (resp.status !== 'ok') throw new Error(resp.message || `Chunk ${i} download failed`);
      chunks.push(Buffer.from(resp.data, 'base64'));
    }

    const fileData = Buffer.concat(chunks);

    // 3. end
    await this.send('file_transfer_end', {
      transfer_id: transferId,
      hash: expectedHash,
    });

    // 4. verify hash
    const actualHash = crypto.createHash('sha256').update(fileData).digest('hex');
    if (actualHash !== expectedHash) {
      throw new Error(`Hash mismatch: expected=${expectedHash} actual=${actualHash}`);
    }

    // 5. save
    if (localPath) {
      fs.mkdirSync(path.dirname(localPath), { recursive: true });
      fs.writeFileSync(localPath, fileData);
    }

    return { data: fileData, hash: actualHash, size: total_size };
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
// NewspaperServer
// ═══════════════════════════════════════════════════════════════════════════════

export class NewspaperServer {
  /**
   * @param {object} options
   * @param {string} [options.host='0.0.0.0'] - Bind host
   * @param {number} [options.port=8080] - Bind port
   * @param {string} options.password - Shared password for certificate derivation
   */
  constructor(options) {
    if (!options || !options.password) {
      throw new Error('password is required');
    }
    this.host = options.host || '0.0.0.0';
    this.port = options.port || 8080;
    this.password = options.password;
    this._handlers = new Map();
    this._dispatcher = null;
    this._server = null;
    this._connections = new Set();
    this._running = false;
  }

  /**
   * Register a handler for an operation type.
   * @param {string} type - Operation type
   * @param {function(object): object} handler - Receives data, returns response
   */
  handle(type, handler) {
    this._handlers.set(type, handler);
    return this;
  }

  /**
   * Set a global dispatcher that handles all messages.
   * @param {function(string, object): object} fn - (type, data) => response
   */
  setDispatcher(fn) {
    this._dispatcher = fn;
    return this;
  }

  _dispatch(type, data) {
    try {
      if (this._dispatcher) return this._dispatcher(type, data);
      const handler = this._handlers.get(type);
      if (handler) return handler(data);
      return { status: 'error', message: `No handler for: ${type}` };
    } catch (err) {
      return { status: 'error', message: String(err.message || err) };
    }
  }

  start() {
    if (this._running) return;
    const certs = deriveCertificates(this.password, true);

    this._server = tls.createServer({
      key: certs.key,
      cert: certs.cert,
      ca: certs.ca,
      requestCert: true,
      rejectUnauthorized: true,
    }, (socket) => this._handleConnection(socket));

    this._server.listen(this.port, this.host, () => {
      this._running = true;
      console.log(`[Server] Listening on ${this.host}:${this.port} (mTLS)`);
    });

    this._server.on('error', (err) => {
      console.error('[Server] Error:', err.message);
    });
  }

  async _handleConnection(socket) {
    try {
      const { headers, remaining } = await readHttpHeaders(socket);
      const key = parseHeader(headers, 'sec-websocket-key');
      if (!key) {
        socket.write('HTTP/1.1 400 Bad Request\r\n\r\n');
        socket.destroy();
        return;
      }

      const acceptKey = computeWsAcceptKey(key);
      socket.write(
        'HTTP/1.1 101 Switching Protocols\r\n' +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        `Sec-WebSocket-Accept: ${acceptKey}\r\n` +
        '\r\n'
      );

      const ws = new WsConnection(socket, false);
      this._connections.add(ws);

      const peerCert = socket.getPeerCertificate();
      const cn = peerCert?.subject?.CN || '?';
      console.log(`[Server] Client connected: CN=${cn}, addr=${socket.remoteAddress}`);

      ws.onMessage = (payload) => {
        try {
          const msg = parseJsonSafe(decodePayload(payload));
          const type = msg.type;
          const data = msg.data || {};
          const response = this._dispatch(type, data);
          ws.sendJSON(response);
        } catch (err) {
          ws.sendJSON({ status: 'error', message: String(err.message || err) });
        }
      };

      ws.onClose = () => {
        this._connections.delete(ws);
        console.log('[Server] Client disconnected');
      };

      if (remaining.length > 0) ws._onData(remaining);
    } catch (err) {
      console.error('[Server] Connection error:', err.message);
      socket.destroy();
    }
  }

  broadcast(data) {
    const payload = Buffer.from(JSON.stringify(data), 'utf-8');
    for (const ws of this._connections) ws.send(payload);
  }

  getActiveConnections() {
    return this._connections.size;
  }

  stop() {
    this._running = false;
    for (const ws of this._connections) ws.close();
    this._connections.clear();
    if (this._server) {
      this._server.close();
      this._server = null;
    }
    console.log('[Server] Stopped');
  }
}

