using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace ClipSync.WPF.Core
{
    /// <summary>
    /// AES-256-CBC encryption/decryption helper.
    /// 
    /// Unified format (compatible with Android and Server):
    ///   base64(salt):base64(IV + ciphertext)
    /// 
    /// Key derivation: PBKDF2-SHA256(password, salt, 10000 iterations, 32 bytes)
    /// IV: 16 bytes random
    /// Padding: PKCS7
    /// </summary>
    public static class EncryptionHelper
    {
        private const int KeySize = 32;
        private const int IvSize = 16;
        private const int SaltSize = 16;
        private const int Iterations = 10000;

        /// <summary>
        /// Encrypt plaintext content.
        /// Returns string in format: base64(salt):base64(IV + ciphertext)
        /// Throws on failure instead of returning plaintext.
        /// </summary>
        public static string Encrypt(string plainText, string password)
        {
            var salt = GenerateSalt();
            var key = DeriveKey(password, salt);

            using var aes = Aes.Create();
            aes.Key = key;
            aes.GenerateIV();
            aes.Mode = CipherMode.CBC;
            aes.Padding = PaddingMode.PKCS7;

            var iv = aes.IV;
            var plainBytes = Encoding.UTF8.GetBytes(plainText);

            using var encryptor = aes.CreateEncryptor();
            var cipherBytes = encryptor.TransformFinalBlock(plainBytes, 0, plainBytes.Length);

            var ivAndCipher = new byte[IvSize + cipherBytes.Length];
            Buffer.BlockCopy(iv, 0, ivAndCipher, 0, IvSize);
            Buffer.BlockCopy(cipherBytes, 0, ivAndCipher, IvSize, cipherBytes.Length);

            // Format: base64(salt):base64(IV+ciphertext)
            var saltB64 = Convert.ToBase64String(salt);
            var contentB64 = Convert.ToBase64String(ivAndCipher);
            return $"{saltB64}:{contentB64}";
        }

        /// <summary>
        /// Decrypt encrypted content.
        /// Expects format: base64(salt):base64(IV + ciphertext)
        /// Throws on failure instead of returning raw data.
        /// </summary>
        public static string Decrypt(string encryptedData, string password)
        {
            var separatorIndex = encryptedData.IndexOf(':');
            if (separatorIndex < 0)
            {
                throw new FormatException("Invalid encrypted data format: expected 'salt:content'");
            }

            var saltB64 = encryptedData.Substring(0, separatorIndex);
            var contentB64 = encryptedData.Substring(separatorIndex + 1);

            var salt = Convert.FromBase64String(saltB64);
            if (salt.Length != SaltSize)
            {
                throw new FormatException($"Invalid salt size: expected {SaltSize}, got {salt.Length}");
            }

            var ivAndCipher = Convert.FromBase64String(contentB64);
            if (ivAndCipher.Length < IvSize)
            {
                throw new FormatException("Ciphertext too short");
            }

            var iv = new byte[IvSize];
            Buffer.BlockCopy(ivAndCipher, 0, iv, 0, IvSize);

            var cipherBytes = new byte[ivAndCipher.Length - IvSize];
            Buffer.BlockCopy(ivAndCipher, IvSize, cipherBytes, 0, cipherBytes.Length);

            var key = DeriveKey(password, salt);

            using var aes = Aes.Create();
            aes.Key = key;
            aes.IV = iv;
            aes.Mode = CipherMode.CBC;
            aes.Padding = PaddingMode.PKCS7;

            using var decryptor = aes.CreateDecryptor();
            var plainBytes = decryptor.TransformFinalBlock(cipherBytes, 0, cipherBytes.Length);

            return Encoding.UTF8.GetString(plainBytes);
        }

        public static string ComputeChecksum(string content)
        {
            using var sha256 = SHA256.Create();
            var hash = sha256.ComputeHash(Encoding.UTF8.GetBytes(content));
            return BitConverter.ToString(hash).Replace("-", "").ToLowerInvariant();
        }

        public static string ComputeChecksum(byte[] content)
        {
            using var sha256 = SHA256.Create();
            var hash = sha256.ComputeHash(content);
            return BitConverter.ToString(hash).Replace("-", "").ToLowerInvariant();
        }

        private static byte[] GenerateSalt()
        {
            var salt = new byte[SaltSize];
            using var rng = RandomNumberGenerator.Create();
            rng.GetBytes(salt);
            return salt;
        }

        private static byte[] DeriveKey(string password, byte[] salt)
        {
            using var pbkdf2 = new Rfc2898DeriveBytes(password, salt, Iterations, HashAlgorithmName.SHA256);
            return pbkdf2.GetBytes(KeySize);
        }
    }
}
