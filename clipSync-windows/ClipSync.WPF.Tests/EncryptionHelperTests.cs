using ClipSync.WPF.Core;
using Xunit;

namespace ClipSync.WPF.Tests
{
    public class EncryptionHelperTests
    {
        private const string TestPassword = "testpassword123";

        [Fact]
        public void EncryptDecrypt_RoundTrip_Text()
        {
            // Arrange
            string originalText = "Hello, Clipboard!";

            // Act
            string encrypted = EncryptionHelper.Encrypt(originalText, TestPassword);
            string decrypted = EncryptionHelper.Decrypt(encrypted, TestPassword);

            // Assert
            Assert.Equal(originalText, decrypted);
            Assert.NotEqual(originalText, encrypted);
        }

        [Fact]
        public void EncryptDecrypt_WithChineseText()
        {
            // Arrange
            string originalText = "剪贴板同步测试";

            // Act
            string encrypted = EncryptionHelper.Encrypt(originalText, TestPassword);
            string decrypted = EncryptionHelper.Decrypt(encrypted, TestPassword);

            // Assert
            Assert.Equal(originalText, decrypted);
        }

        [Fact]
        public void EncryptDecrypt_WithEmptyString()
        {
            // Arrange
            string originalText = "";

            // Act
            string encrypted = EncryptionHelper.Encrypt(originalText, TestPassword);
            string decrypted = EncryptionHelper.Decrypt(encrypted, TestPassword);

            // Assert
            Assert.Equal(originalText, decrypted);
        }

        [Fact]
        public void EncryptDecrypt_DifferentPasswords_Fails()
        {
            // Arrange
            string originalText = "Secret data";
            string encryptPassword = "password1";
            string decryptPassword = "password2";

            // Act & Assert
            string encrypted = EncryptionHelper.Encrypt(originalText, encryptPassword);
            Assert.Throws<System.Security.Cryptography.CryptographicException>(() => 
                EncryptionHelper.Decrypt(encrypted, decryptPassword));
        }

        [Fact]
        public void Encrypt_SameTextDifferentOutputs()
        {
            // Arrange
            string originalText = "Same text";

            // Act
            string encrypted1 = EncryptionHelper.Encrypt(originalText, TestPassword);
            string encrypted2 = EncryptionHelper.Encrypt(originalText, TestPassword);

            // Assert - Due to random salt/IV, outputs should differ
            Assert.NotEqual(encrypted1, encrypted2);
        }

        [Fact]
        public void Decrypt_InvalidFormat_ThrowsException()
        {
            // Arrange
            string invalidData = "not_valid_encrypted_data";

            // Act & Assert
            Assert.Throws<System.FormatException>(() => 
                EncryptionHelper.Decrypt(invalidData, TestPassword));
        }
    }
}
