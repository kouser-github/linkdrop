package com.sentrocx.app

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** All encryption lives here. AES-256-GCM everywhere; keys come from pairing (ECDH P-256 + HKDF). */
object Crypto {
    const val CH = 64 * 1024
    private val rnd = SecureRandom()

    fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    fun sha(vararg p: ByteArray): ByteArray { val d = MessageDigest.getInstance("SHA-256"); p.forEach { d.update(it) }; return d.digest() }
    fun hmac(key: ByteArray, vararg p: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key, "HmacSHA256")); p.forEach { m.update(it) }; return m.doFinal()
    }
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun t(s: String) = s.toByteArray()

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(salt, ikm)
        var prev = ByteArray(0); val out = java.io.ByteArrayOutputStream(); var i = 1
        while (out.size() < len) { prev = hmac(prk, prev, info, byteArrayOf(i.toByte())); out.write(prev); i++ }
        return out.toByteArray().copyOf(len)
    }

    // ---- pairing math ----
    fun newEc(): KeyPair { val g = KeyPairGenerator.getInstance("EC"); g.initialize(ECGenParameterSpec("secp256r1")); return g.generateKeyPair() }
    private fun fix32(b: BigInteger): ByteArray {
        val a = b.toByteArray(); val o = ByteArray(32)
        if (a.size >= 32) System.arraycopy(a, a.size - 32, o, 0, 32) else System.arraycopy(a, 0, o, 32 - a.size, a.size)
        return o
    }
    fun rawPub(k: PublicKey): ByteArray { val w = (k as ECPublicKey).w; return byteArrayOf(4) + fix32(w.affineX) + fix32(w.affineY) }
    fun ecdh(mine: KeyPair, peerRaw: ByteArray): ByteArray {
        if (peerRaw.size != 65 || peerRaw[0] != 4.toByte()) throw IOException("Bad key from laptop")
        val spec = ECPublicKeySpec(
            ECPoint(BigInteger(1, peerRaw.copyOfRange(1, 33)), BigInteger(1, peerRaw.copyOfRange(33, 65))),
            (mine.public as ECPublicKey).params)
        val peer = KeyFactory.getInstance("EC").generatePublic(spec)
        val ka = KeyAgreement.getInstance("ECDH"); ka.init(mine.private); ka.doPhase(peer, true)
        return ka.generateSecret()
    }
    fun sas(phonePub: ByteArray, laptopPub: ByteArray, nonce: ByteArray): String {
        val n = ByteBuffer.wrap(sha(t("sas"), phonePub, laptopPub, nonce)).int.toLong() and 0xffffffffL
        return (n % 1000000).toString().padStart(6, '0')
    }
    fun pairKeys(secret: ByteArray, phonePub: ByteArray, laptopPub: ByteArray, nonce: ByteArray): ByteArray =
        hkdf(secret, sha(t("salt"), phonePub, laptopPub, nonce), t("sentrocx v1"), 64)  // 0..32 encrypt, 32..64 sign

    // ---- small JSON messages: nonce(12) + ciphertext + tag ----
    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); c.updateAAD(aad); return c
    }
    fun seal(key: ByteArray, text: String, aad: String): ByteArray {
        val n = random(12); return n + gcm(Cipher.ENCRYPT_MODE, key, n, t(aad)).doFinal(t(text))
    }
    fun open(key: ByteArray, b: ByteArray, aad: String): String {
        if (b.size < 28) throw IOException("Bad reply")
        try { return String(gcm(Cipher.DECRYPT_MODE, key, b.copyOfRange(0, 12), t(aad)).doFinal(b, 12, b.size - 12)) }
        catch (e: GeneralSecurityException) { throw IOException("Reply failed security check") }
    }

    // ---- files: salt(8) then frames [4-byte length, top bit = last][ciphertext + tag] ----
    private fun nonce(salt: ByteArray, i: Int) = ByteBuffer.allocate(12).put(salt).putInt(i).array()
    private fun aad(id: String, dir: String, last: Boolean) = t("$id:$dir:${if (last) 1 else 0}")
    private fun readFull(inp: InputStream, b: ByteArray): Int {
        var n = 0
        while (n < b.size) { val r = inp.read(b, n, b.size - n); if (r < 0) break; n += r }
        return n
    }

    /** dir: "u" for phone -> laptop, "d" for laptop -> phone. */
    fun encryptStream(inp: InputStream, out: OutputStream, key: ByteArray, id: String, dir: String,
                      cancel: AtomicBoolean, onBytes: (Long) -> Unit) {
        val salt = random(8); out.write(salt)
        var cur = ByteArray(CH); var curN = readFull(inp, cur)
        var i = 0; var total = 0L
        while (true) {
            if (cancel.get()) throw CancelledException()
            var nxt: ByteArray? = null; var nxtN = 0
            if (curN == CH) { nxt = ByteArray(CH); nxtN = readFull(inp, nxt) }
            val last = nxtN == 0
            val ct = gcm(Cipher.ENCRYPT_MODE, key, nonce(salt, i++), aad(id, dir, last)).doFinal(cur, 0, curN)
            out.write(ByteBuffer.allocate(4).putInt(if (last) ct.size or Int.MIN_VALUE else ct.size).array())
            out.write(ct)
            total += curN; onBytes(total)
            if (last) break
            cur = nxt!!; curN = nxtN
        }
    }

    fun decryptStream(inp: InputStream, out: OutputStream, key: ByteArray, id: String, dir: String,
                      cancel: AtomicBoolean, onBytes: (Long) -> Unit): Long {
        val dis = DataInputStream(inp)
        val salt = ByteArray(8); dis.readFully(salt)
        var i = 0; var total = 0L
        while (true) {
            if (cancel.get()) throw CancelledException()
            val h = dis.readInt(); val last = h < 0; val len = h and 0x7fffffff
            if (len < 16 || len > CH + 16) throw IOException("Bad data from laptop")
            val ct = ByteArray(len); dis.readFully(ct)
            val pt = try { gcm(Cipher.DECRYPT_MODE, key, nonce(salt, i++), aad(id, dir, last)).doFinal(ct) }
                     catch (e: GeneralSecurityException) { throw IOException("Data failed security check") }
            out.write(pt); total += pt.size; onBytes(total)
            if (last) break
        }
        return total
    }
}
