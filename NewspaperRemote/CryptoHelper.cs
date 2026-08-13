using System;
using System.Numerics;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace MorningCat.Modules
{
    public static class CryptoHelper
    {
        // secp256r1 (nistP256) 曲线阶 n
        // FFFFFFFF 00000000 FFFFFFFF FFFFFFFF BCE6FAAD A7179E84 F3B9CAC2 FC632551
        private static readonly BigInteger Secp256R1N = new BigInteger(
            new byte[]
            {
                0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00,
                0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
                0xBC, 0xE6, 0xFA, 0xAD, 0xA7, 0x17, 0x9E, 0x84,
                0xF3, 0xB9, 0xCA, 0xC2, 0xFC, 0x63, 0x25, 0x51
            },
            isUnsigned: true,
            isBigEndian: true);

        private const long ValidityDays = 3650;

        public static byte[] DeriveKey(string password)
        {
            return SHA256.HashData(Encoding.UTF8.GetBytes(password));
        }

        public static byte[] Sha256(params byte[][] arrays)
        {
            return SHA256.HashData(Concatenate(arrays));
        }

        public static byte[] Concatenate(params byte[][] arrays)
        {
            var totalLen = 0;
            foreach (var arr in arrays) totalLen += arr.Length;
            var combined = new byte[totalLen];
            var offset = 0;
            foreach (var arr in arrays)
            {
                Array.Copy(arr, 0, combined, offset, arr.Length);
                offset += arr.Length;
            }
            return combined;
        }

        /// <summary>
        /// 证书确定性派生结果。C# 端只用 client 证书连接，CA 证书用于 truststore 验证服务端。
        /// </summary>
        public class CertificateSet
        {
            public X509Certificate2 CaCertificate { get; set; } = null!;
            public X509Certificate2 ClientCertificate { get; set; } = null!;
        }

        /// <summary>
        /// 基于 password 确定性派生 CA 和 Client 证书。
        /// 算法与 Java 端 CertificateGenerator 完全一致：
        /// seed = SHA256(password UTF-8)
        /// 对每个 label: hash = SHA256(seed || label), d = BigInteger(hash) mod n, 若 d==0 则 hash=SHA256(hash||label) 重算
        /// CA 自签名，Client 由 CA 签发，SHA256withECDSA。
        /// </summary>
        public static CertificateSet GenerateCertificates(string password)
        {
            byte[] seed = SHA256.HashData(Encoding.UTF8.GetBytes(password));

            using ECDsa caKey = DeriveEcdsaKeyPair(seed, "newspaper-ca");
            using ECDsa clientKey = DeriveEcdsaKeyPair(seed, "newspaper-client");

            var notBefore = DateTimeOffset.UtcNow.AddMinutes(-1);
            var notAfter = DateTimeOffset.UtcNow.AddDays(ValidityDays);

            // ---- CA 自签名证书 ----
            var caRequest = new CertificateRequest(
                "CN=Newspaper-CA,O=Newspaper",
                caKey,
                HashAlgorithmName.SHA256);
            caRequest.CertificateExtensions.Add(
                new X509BasicConstraintsExtension(true, true, 0, true));
            caRequest.CertificateExtensions.Add(
                new X509KeyUsageExtension(
                    X509KeyUsageFlags.KeyCertSign | X509KeyUsageFlags.CrlSign, true));

            using var caCertWithKey = caRequest.CreateSelfSigned(notBefore, notAfter);

            // CA 证书不含私钥的副本（用于 truststore）
            var caCert = X509CertificateLoader.LoadCertificate(caCertWithKey.Export(X509ContentType.Cert));

            // ---- Client 证书由 CA 签发 ----
            var clientRequest = new CertificateRequest(
                "CN=Newspaper-Client,O=Newspaper",
                clientKey,
                HashAlgorithmName.SHA256);
            clientRequest.CertificateExtensions.Add(
                new X509BasicConstraintsExtension(false, false, 0, true));
            clientRequest.CertificateExtensions.Add(
                new X509KeyUsageExtension(
                    X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
            clientRequest.CertificateExtensions.Add(
                new X509EnhancedKeyUsageExtension(
                    new OidCollection
                    {
                        new Oid("1.3.6.1.5.5.7.3.1"), // serverAuth
                        new Oid("1.3.6.1.5.5.7.3.2")  // clientAuth
                    }, true));

            var clientCertSigned = clientRequest.Create(caCertWithKey, notBefore, notAfter, new byte[] { 3 });
            var clientCertWithKey = clientCertSigned.CopyWithPrivateKey(clientKey);

            // 导出 PFX 再加载，确保证书和私钥正确关联（兼容 Windows SChannel）
            var pfx = clientCertWithKey.Export(X509ContentType.Pfx, "");
            var clientCert = X509CertificateLoader.LoadPkcs12(pfx, "",
                X509KeyStorageFlags.Exportable | X509KeyStorageFlags.PersistKeySet);

            return new CertificateSet
            {
                CaCertificate = caCert,
                ClientCertificate = clientCert
            };
        }

        private static ECDsa DeriveEcdsaKeyPair(byte[] seed, string label)
        {
            byte[] d = DeriveScalar(seed, label);
            var ecdsa = ECDsa.Create(ECCurve.NamedCurves.nistP256);
            var ecParams = new ECParameters
            {
                Curve = ECCurve.NamedCurves.nistP256,
                D = d,
                Q = new ECPoint()
            };
            ecdsa.ImportParameters(ecParams);
            return ecdsa;
        }

        /// <summary>
        /// 与 Java 端一致的确定性标量派生：
        /// current = seed
        /// do { hash = SHA256(current || label); d = BigInteger(hash, unsigned, big-endian) mod n; current = hash; } while (d == 0)
        /// 返回 32 字节大端私钥。
        /// </summary>
        private static byte[] DeriveScalar(byte[] seed, string label)
        {
            byte[] labelBytes = Encoding.UTF8.GetBytes(label);
            byte[] current = seed;

            while (true)
            {
                byte[] hash = SHA256.HashData(Concatenate(current, labelBytes));
                BigInteger dBig = new BigInteger(hash, isUnsigned: true, isBigEndian: true);
                dBig = dBig % Secp256R1N;

                if (dBig.Sign > 0)
                {
                    byte[] d = dBig.ToByteArray(isUnsigned: true, isBigEndian: true);
                    if (d.Length < 32)
                    {
                        var padded = new byte[32];
                        Array.Copy(d, 0, padded, 32 - d.Length, d.Length);
                        d = padded;
                    }
                    return d;
                }

                current = hash;
            }
        }
    }
}
