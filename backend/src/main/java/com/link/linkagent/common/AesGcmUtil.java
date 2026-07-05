package com.link.linkagent.common;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加密工具类（P1-4：用户 API Key 加密存储）。
 * <p>
 * 为什么选 AES-256-GCM 而非 AES-CBC？
 * GCM 自带认证标签（128 bit），能同时做到加密 + 完整性校验 + 防篡改——
 * 如果有人改了数据库里的密文，解密时会直接报错而非返回被篡改的明文。
 * 这对 API Key 这种敏感凭证尤为重要。
 * <p>
 * 为什么 IV 随机生成（不固定/不派生）？
 * 固定 IV 意味着相同明文产生相同密文，存在模式分析风险；
 * 每次加密随机生成 12 字节 IV 作为密文前缀，确保同一明文多次加密的密文完全不同。
 * <p>
 * 为什么密钥用 Base64 编码从配置文件读取？
 * 原生 32 字节二进制密钥不便直接写在 YAML 里，Base64 编码后方便配置和环境变量注入。
 */
public final class AesGcmUtil {

    /** GCM 认证标签长度（bit），128 bit 是安全性与性能的最佳平衡点 */
    private static final int GCM_TAG_LENGTH = 128;
    /** GCM IV 长度（字节），12 字节是 NIST 推荐值 */
    private static final int GCM_IV_LENGTH = 12;
    /** AES-256 密钥长度（字节） */
    private static final int AES_KEY_LENGTH = 32;
    /** 加密算法标识 */
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    private AesGcmUtil() {
        // 工具类禁止实例化
    }

    /**
     * 将 Base64 编码的密钥字符串解码为字节数组，并校验长度。
     * <p>
     * 解码后长度必须为 32 字节（AES-256），否则说明配置错误——
     * 此时抛出明确异常比静默失败更好，因为加密链路不应该在密钥错误的情况下继续。
     *
     * @param base64Key Base64 编码的 32 字节密钥
     * @return 原始密钥字节数组
     * @throws IllegalArgumentException 密钥长度不正确
     */
    private static byte[] decodeKey(String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != AES_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "AES 密钥长度必须为 32 字节（AES-256），实际为 " + keyBytes.length + " 字节。" +
                    "请检查 linkagent.secret.aes-key 配置是否为 Base64 编码的 32 字节密钥。");
        }
        return keyBytes;
    }

    /**
     * AES-256-GCM 加密。
     * <p>
     * 返回格式：Base64(12 字节随机 IV || 密文 || 16 字节 GCM 认证标签)，
     * 解密时从此 Base64 字符串中提取 IV 前缀即可。
     *
     * @param plainText 明文（API Key）
     * @param base64Key Base64 编码的 32 字节密钥
     * @return Base64 编码的密文（含随机 IV 前缀）
     */
    public static String encrypt(String plainText, String base64Key) {
        if (plainText == null || plainText.isEmpty()) {
            return null;
        }
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "AES 加密密钥未配置（linkagent.secret.aes-key 为空），无法加密 API Key。");
        }
        try {
            byte[] keyBytes = decodeKey(base64Key);
            // 每次加密生成新的随机 IV，确保相同明文产生不同密文
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] cipherText = cipher.doFinal(plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // IV + 密文拼接后一起 Base64 编码，解密时用前 12 字节当 IV
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("AES-256-GCM 加密失败", e);
        }
    }

    /**
     * AES-256-GCM 解密。
     * <p>
     * 从 Base64 密文中提取前 12 字节作为 IV，剩余部分作为密文（含 GCM 认证标签），
     * GCM 模式下解密同时校验完整性——如果密文被篡改，解密会抛出 AEADBadTagException。
     *
     * @param cipherText Base64 编码的密文（IV 前缀 + 密文 + GCM 标签）
     * @param base64Key  Base64 编码的 32 字节密钥
     * @return 解密后的明文
     */
    public static String decrypt(String cipherText, String base64Key) {
        if (cipherText == null || cipherText.isEmpty()) {
            return null;
        }
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "AES 加密密钥未配置（linkagent.secret.aes-key 为空），无法解密 API Key。");
        }
        try {
            byte[] keyBytes = decodeKey(base64Key);
            byte[] combined = Base64.getDecoder().decode(cipherText);
            // 前 12 字节是加密时随机生成的 IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            // 剩余部分是密文（含 16 字节 GCM 认证标签）
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] plainBytes = cipher.doFinal(encrypted);
            return new String(plainBytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES-256-GCM 解密失败——密钥不匹配或密文已损坏", e);
        }
    }

    /**
     * 脱敏展示 API Key：仅保留前缀 + 后 4 位，中间用星号替代。
     * <p>
     * 例如 "sk-abc123def456ghi789" → "sk-****ghi789"。
     * 短于 8 个字符的 key 全部打码，避免信息泄露。
     *
     * @param plainKey 明文 API Key
     * @return 脱敏后的展示文本
     */
    public static String maskKey(String plainKey) {
        if (plainKey == null || plainKey.isEmpty()) {
            return null;
        }
        if (plainKey.length() <= 8) {
            return "****";
        }
        String prefix = plainKey.substring(0, Math.min(3, plainKey.length() - 4));
        String suffix = plainKey.substring(plainKey.length() - 4);
        return prefix + "****" + suffix;
    }
}
