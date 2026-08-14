using System;
using System.Globalization;
using System.Numerics;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace Newspaper;

/// <summary>
/// mTLS 证书派生工具。从共享密码确定性派生 CA 和 Client 证书，
/// 与 Java 服务端的 CertificateGenerator 算法完全一致。
/// </summary>
public static class CryptoHelper
{
    // secp256r1 曲线阶
    private static readonly BigInteger CurveN = BigInteger.Parse(
        "00FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
        NumberStyles.HexNumber);

    private const string CaLabel = "newspaper-ca";
    private const string ClientLabel = "newspaper-client";

    private const string CaSubject = "CN=Newspaper-CA,O=Newspaper";
    private const string ClientSubject = "CN=Newspaper-Client,O=Newspaper";

    private static readonly TimeSpan Validity = TimeSpan.FromDays(3650);

    /// <summary>
    /// 从密码派生 CA 证书和 Client 证书。
    /// CA 证书自签名，Client 证书由 CA 签发，两者均使用 secp256r1 (nistP256) ECDSA。
    /// </summary>
    /// <param name="password">共享密码</param>
    /// <returns>(ClientCert, CaCert) — ClientCert 包含私钥，CaCert 仅供验证</returns>
    public static (X509Certificate2 ClientCert, X509Certificate2 CaCert) DeriveCerts(string password)
    {
        byte[] seed = SHA256Data(Encoding.UTF8.GetBytes(password));

        // 派生 CA 私钥
        byte[] caD = DeriveScalar(seed, CaLabel);
        using ECDsa caKey = CreateECDsaFromD(caD);

        // 派生 Client 私钥
        byte[] clientD = DeriveScalar(seed, ClientLabel);
        using ECDsa clientKey = CreateECDsaFromD(clientD);

        var notBefore = DateTimeOffset.Now.AddMinutes(-1);
        var notAfter = DateTimeOffset.Now.Add(Validity);

        // 创建 CA 自签名证书（包含私钥，用于签发终端实体证书）
        X509Certificate2 caCert = CreateCaCertificate(caKey, notBefore, notAfter);

        // 创建 Client 证书（由 CA 签发，附带私钥）
        X509Certificate2 clientCert = CreateEndEntityCertificate(
            clientKey, caCert, ClientSubject, notBefore, notAfter);

        return (clientCert, caCert);
    }

    // ── 标量派生 ──

    /// <summary>
    /// 循环 SHA256(current || label) mod n，直到 d != 0。
    /// 与 Java/Python 实现完全一致。
    /// </summary>
    private static byte[] DeriveScalar(byte[] seed, string label)
    {
        byte[] labelBytes = Encoding.UTF8.GetBytes(label);
        byte[] current = seed;
        BigInteger d;

        do
        {
            byte[] hash = SHA256Data(Concat(current, labelBytes));
            // 无符号大端解析，然后 mod n
            d = new BigInteger(hash, isUnsigned: true, isBigEndian: true) % CurveN;
            current = hash;
        }
        while (d == BigInteger.Zero);

        // 转换为 32 字节大端私钥（不足 32 字节时左填充零）
        byte[] dBytes = d.ToByteArray(isUnsigned: true, isBigEndian: true);
        if (dBytes.Length < 32)
        {
            byte[] padded = new byte[32];
            Buffer.BlockCopy(dBytes, 0, padded, 32 - dBytes.Length, dBytes.Length);
            dBytes = padded;
        }
        return dBytes;
    }

    /// <summary>
    /// 从 32 字节大端私钥创建 ECDsa 密钥（.NET 自动计算公钥 Q = G * D）。
    /// </summary>
    private static ECDsa CreateECDsaFromD(byte[] d)
    {
        var parameters = new ECParameters
        {
            Curve = ECCurve.NamedCurves.nistP256,
            D = d
        };

        var ec = ECDsa.Create();
        ec.ImportParameters(parameters);
        return ec;
    }

    // ── 证书创建 ──

    /// <summary>
    /// 创建 CA 自签名证书。
    /// CN=Newspaper-CA, O=Newspaper, KeyUsage: keyCertSign|crlSign, BasicConstraints: CA=true
    /// </summary>
    private static X509Certificate2 CreateCaCertificate(
        ECDsa caKey, DateTimeOffset notBefore, DateTimeOffset notAfter)
    {
        var request = new CertificateRequest(
            new X500DistinguishedName(CaSubject),
            caKey,
            HashAlgorithmName.SHA256);

        // BasicConstraints: CA = true
        request.CertificateExtensions.Add(
            new X509BasicConstraintsExtension(true, true, 0, true));

        // KeyUsage: keyCertSign | crlSign
        request.CertificateExtensions.Add(
            new X509KeyUsageExtension(
                X509KeyUsageFlags.KeyCertSign | X509KeyUsageFlags.CrlSign,
                critical: true));

        // 自签名（包含私钥）
        return request.CreateSelfSigned(notBefore, notAfter);
    }

    /// <summary>
    /// 创建终端实体证书（由 CA 签发）。
    /// KeyUsage: digitalSignature|keyEncipherment, EKU: serverAuth|clientAuth, BasicConstraints: CA=false
    /// </summary>
    private static X509Certificate2 CreateEndEntityCertificate(
        ECDsa subjectKey, X509Certificate2 caCert, string subject,
        DateTimeOffset notBefore, DateTimeOffset notAfter)
    {
        var request = new CertificateRequest(
            new X500DistinguishedName(subject),
            subjectKey,
            HashAlgorithmName.SHA256);

        // BasicConstraints: CA = false
        request.CertificateExtensions.Add(
            new X509BasicConstraintsExtension(false, false, 0, true));

        // KeyUsage: digitalSignature | keyEncipherment
        request.CertificateExtensions.Add(
            new X509KeyUsageExtension(
                X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment,
                critical: true));

        // EKU: serverAuth | clientAuth
        request.CertificateExtensions.Add(
            new X509EnhancedKeyUsageExtension(
                new OidCollection
                {
                    new Oid("1.3.6.1.5.5.7.3.1"), // serverAuth
                    new Oid("1.3.6.1.5.5.7.3.2"), // clientAuth
                },
                critical: true));

        // 序列号 = 当前时间戳毫秒（与 Java 一致）
        long timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        byte[] serial = BitConverter.GetBytes(timestamp);
        if (BitConverter.IsLittleEndian)
            Array.Reverse(serial);

        // 使用 CA 证书签发（CA 证书需包含私钥）
        var cert = request.Create(caCert, notBefore, notAfter, serial);

        // 关联终端实体私钥
        return cert.CopyWithPrivateKey(subjectKey);
    }

    // ── 工具方法 ──

    private static byte[] SHA256Data(byte[] data)
    {
        return SHA256.HashData(data);
    }

    private static byte[] Concat(byte[] a, byte[] b)
    {
        byte[] result = new byte[a.Length + b.Length];
        Buffer.BlockCopy(a, 0, result, 0, a.Length);
        Buffer.BlockCopy(b, 0, result, a.Length, b.Length);
        return result;
    }
}
