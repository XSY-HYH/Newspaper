package com.newspaper.encryption;

import java.io.File;
import java.util.logging.Logger;

final class BouncyCastleCertGenerator {

    private BouncyCastleCertGenerator() {
    }

    static void generateSelfSignedKeystore(File keystoreFile, char[] password, Logger logger) throws Exception {
        String keystorePath = keystoreFile.getAbsolutePath();

        ProcessBuilder pb = new ProcessBuilder(
                "keytool",
                "-genkeypair",
                "-alias", "newspaper",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "3650",
                "-keystore", keystorePath,
                "-storetype", "JKS",
                "-storepass", new String(password),
                "-keypass", new String(password),
                "-dname", "CN=Newspaper-WebSocket, OU=Newspaper, O=Newspaper, L=Unknown, ST=Unknown, C=US"
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();

        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("keytool failed (exit " + exitCode + "): " + new String(output));
        }

        logger.info("Self-signed TLS certificate generated: " + keystorePath);
    }
}