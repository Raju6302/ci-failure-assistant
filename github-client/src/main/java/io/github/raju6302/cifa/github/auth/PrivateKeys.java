package io.github.raju6302.cifa.github.auth;

import java.io.IOException;
import java.io.StringReader;
import java.security.PrivateKey;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/** Reads the PEM private key GitHub generates for an App (PKCS#1 or PKCS#8). */
public final class PrivateKeys {

    private PrivateKeys() {
    }

    public static PrivateKey fromPem(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object parsed = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (parsed instanceof PEMKeyPair pair) {
                return converter.getPrivateKey(pair.getPrivateKeyInfo());
            }
            if (parsed instanceof PrivateKeyInfo info) {
                return converter.getPrivateKey(info);
            }
            throw new IllegalArgumentException("Not a PEM private key");
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the GitHub App private key", e);
        }
    }
}
