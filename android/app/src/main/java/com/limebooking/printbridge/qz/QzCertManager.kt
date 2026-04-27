package com.limebooking.printbridge.qz

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Generates and persists a self-signed PKCS12 keystore for our local WSS daemon
 * pretending to be QZ Tray on `wss://localhost:8181`.
 *
 * Mirrors the desktop QZ Tray's CertificateManager (src/qz/installer/certificate/CertificateManager.java)
 * but stripped down: we only need a server cert for localhost, since the WebView in our own app is the
 * sole client and we explicitly bypass its SSL validation for localhost in MainActivity.
 */
class QzCertManager(private val context: Context) {

    companion object {
        private const val TAG = "QzCertManager"
        private const val KEYSTORE_FILE = "qz-localhost.p12"
        private const val KEYSTORE_PASSWORD = "qz-tray-localhost"
        private const val ALIAS = "qz-tray"

        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    fun loadOrCreate(): SSLContext {
        val keystoreFile = File(context.filesDir, KEYSTORE_FILE)
        val keyStore = if (keystoreFile.exists()) {
            try {
                loadKeyStore(keystoreFile)
            } catch (e: Exception) {
                Log.w(TAG, "Existing keystore corrupt, regenerating", e)
                generateAndStore(keystoreFile)
            }
        } else {
            generateAndStore(keystoreFile)
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, KEYSTORE_PASSWORD.toCharArray())

        // Trust everything — only our WebView talks to us, and TLS here is just to satisfy
        // the qz-tray.js client's wss:// connection requirement (HTTPS pages can't open ws://).
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, trustAll, SecureRandom())
        return ctx
    }

    private fun loadKeyStore(file: File): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(file).use { fis ->
            ks.load(fis, KEYSTORE_PASSWORD.toCharArray())
        }
        return ks
    }

    private fun generateAndStore(file: File): KeyStore {
        Log.i(TAG, "Generating self-signed cert for localhost")
        val keyPair = generateKeyPair()
        val cert = generateSelfSignedCert(keyPair)

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, KEYSTORE_PASSWORD.toCharArray())
        ks.setKeyEntry(ALIAS, keyPair.private as PrivateKey, KEYSTORE_PASSWORD.toCharArray(), arrayOf(cert))

        FileOutputStream(file).use { fos ->
            ks.store(fos, KEYSTORE_PASSWORD.toCharArray())
        }
        return ks
    }

    private fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA", "BC")
        gen.initialize(2048, SecureRandom())
        return gen.generateKeyPair()
    }

    private fun generateSelfSignedCert(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + 10L * 365 * 24 * 60 * 60 * 1000) // 10 years

        val issuer = X500Name("CN=localhost, O=Lime Booking Print Bridge, OU=QZ Tray Emulator")
        val serial = BigInteger.valueOf(SecureRandom().nextLong()).abs()

        val builder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            notBefore,
            notAfter,
            issuer, // self-signed
            keyPair.public
        )

        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth))
        )

        val sans: Array<ASN1Encodable> = arrayOf(
            GeneralName(GeneralName.dNSName, "localhost"),
            GeneralName(GeneralName.dNSName, "localhost.qz.io"),
            GeneralName(GeneralName.iPAddress, "127.0.0.1")
        )
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames.getInstance(DERSequence(sans)))

        val signer = JcaContentSignerBuilder("SHA256WithRSAEncryption")
            .setProvider("BC")
            .build(keyPair.private)

        val holder = builder.build(signer)
        return JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(holder)
    }
}
