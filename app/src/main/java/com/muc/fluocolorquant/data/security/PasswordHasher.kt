package com.muc.fluocolorquant.data.security

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class PasswordVerification(
    val matches: Boolean,
    /** 旧明文、旧算法或过低迭代次数在成功登录后必须立即重算。 */
    val needsUpgrade: Boolean
)

interface PasswordHasher {
    fun hash(password: String): String
    fun verify(password: String, storedValue: String): PasswordVerification
}

/**
 * 本地账户密码的版本化 PBKDF2 存储实现。
 *
 * 格式为 `算法版本:迭代次数:saltHex:hashHex`。salt 每次随机生成，验证使用常量时间比较。
 * Android 旧版本若没有 SHA-256 provider 会退回 HMAC-SHA1，并把实际算法写入前缀；
 * 未来在支持 SHA-256 的设备成功登录后会自动升级，不会把兼容回退伪装成同一算法。
 */
@Singleton
class Pbkdf2PasswordHasher @Inject constructor() : PasswordHasher {

    override fun hash(password: String): String {
        require(password.isNotEmpty()) { "密码不能为空" }
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val algorithm = preferredAlgorithm()
        val derived = derive(
            password = password,
            salt = salt,
            iterations = CURRENT_ITERATIONS,
            keyLengthBits = KEY_LENGTH_BITS,
            jcaAlgorithm = algorithm.jcaName
        )
        return listOf(
            algorithm.storageCode,
            CURRENT_ITERATIONS.toString(),
            salt.toHex(),
            derived.toHex()
        ).joinToString(DELIMITER)
    }

    override fun verify(password: String, storedValue: String): PasswordVerification {
        val parts = storedValue.split(DELIMITER)
        val algorithm = parts.firstOrNull()?.let(Algorithm::fromStorageCode)
        if (algorithm == null) {
            // 未知的版本化摘要必须失败闭合，不能被误当作明文；普通旧明文即使自身包含冒号，
            // 仍按完整字符串校验，避免老用户因为密码格式碰巧像四段数据而无法登录升级。
            if (storedValue.startsWith(VERSIONED_HASH_PREFIX, ignoreCase = true)) {
                return PasswordVerification(matches = false, needsUpgrade = false)
            }
            val matches = MessageDigest.isEqual(
                password.toByteArray(Charsets.UTF_8),
                storedValue.toByteArray(Charsets.UTF_8)
            )
            return PasswordVerification(matches = matches, needsUpgrade = matches)
        }
        if (parts.size != 4) {
            return PasswordVerification(matches = false, needsUpgrade = false)
        }
        val iterations = parts[1].toIntOrNull()
            ?.takeIf { it in MIN_ACCEPTED_ITERATIONS..MAX_ACCEPTED_ITERATIONS }
            ?: return PasswordVerification(matches = false, needsUpgrade = false)
        val salt = parts[2].hexToBytesOrNull()
            ?.takeIf { it.size >= MIN_SALT_BYTES }
            ?: return PasswordVerification(matches = false, needsUpgrade = false)
        val expected = parts[3].hexToBytesOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return PasswordVerification(matches = false, needsUpgrade = false)

        val actual = runCatching {
            derive(
                password = password,
                salt = salt,
                iterations = iterations,
                keyLengthBits = expected.size * Byte.SIZE_BITS,
                jcaAlgorithm = algorithm.jcaName
            )
        }.getOrNull() ?: return PasswordVerification(matches = false, needsUpgrade = false)

        val matches = MessageDigest.isEqual(actual, expected)
        val preferred = preferredAlgorithm()
        return PasswordVerification(
            matches = matches,
            needsUpgrade = matches &&
                (algorithm != preferred || iterations < CURRENT_ITERATIONS || expected.size * Byte.SIZE_BITS < KEY_LENGTH_BITS)
        )
    }

    private fun preferredAlgorithm(): Algorithm {
        return try {
            SecretKeyFactory.getInstance(Algorithm.SHA256.jcaName)
            Algorithm.SHA256
        } catch (_: NoSuchAlgorithmException) {
            Algorithm.SHA1
        }
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        iterations: Int,
        keyLengthBits: Int,
        jcaAlgorithm: String
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLengthBits)
        return try {
            SecretKeyFactory.getInstance(jcaAlgorithm).generateSecret(spec).encoded
        } finally {
            // PBEKeySpec 内部保留 char[]；计算结束立即清除，缩短明文驻留内存的时间。
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0 || any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private enum class Algorithm(
        val storageCode: String,
        val jcaName: String
    ) {
        SHA256("pbkdf2-sha256-v1", "PBKDF2WithHmacSHA256"),
        SHA1("pbkdf2-sha1-v1", "PBKDF2WithHmacSHA1");

        companion object {
            fun fromStorageCode(code: String): Algorithm? = entries.firstOrNull {
                it.storageCode == code
            }
        }
    }

    private companion object {
        const val DELIMITER = ":"
        const val VERSIONED_HASH_PREFIX = "pbkdf2-"
        const val CURRENT_ITERATIONS = 210_000
        const val MIN_ACCEPTED_ITERATIONS = 10_000
        const val MAX_ACCEPTED_ITERATIONS = 2_000_000
        const val SALT_BYTES = 16
        const val MIN_SALT_BYTES = 12
        const val KEY_LENGTH_BITS = 256
        val secureRandom = SecureRandom()
    }
}
