package com.limebooking.printbridge.qz

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Tracks which client X.509 certificates we've previously approved for
 * connecting to our QZ Tray emulator. Mirrors the desktop QZ Tray's
 * "allowed" / "blocked" file behavior (src/qz/auth/Certificate.java).
 *
 * MVP: any cert that has been approved once is approved forever. We do NOT
 * verify signatures on every signed call (localhost-only attack surface).
 */
class QzAllowedCerts(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("qz_allowed_certs", Context.MODE_PRIVATE)

    enum class Decision { ALLOW, BLOCK, UNKNOWN }

    fun decisionFor(certPem: String): Decision {
        val fp = fingerprint(certPem)
        return when (prefs.getString(fp, null)) {
            "allow" -> Decision.ALLOW
            "block" -> Decision.BLOCK
            else -> Decision.UNKNOWN
        }
    }

    fun allow(certPem: String) {
        prefs.edit().putString(fingerprint(certPem), "allow").apply()
    }

    fun block(certPem: String) {
        prefs.edit().putString(fingerprint(certPem), "block").apply()
    }

    private fun fingerprint(certPem: String): String {
        val cleaned = certPem.replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\\s".toRegex(), "")
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(cleaned.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
