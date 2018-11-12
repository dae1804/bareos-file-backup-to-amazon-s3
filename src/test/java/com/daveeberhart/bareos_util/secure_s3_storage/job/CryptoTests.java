package com.daveeberhart.bareos_util.secure_s3_storage.job;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for crypto methods.
 *
 * @author deberhar
 */
public class CryptoTests {

  @Test
  public void testKeyWrapping() throws InvalidCipherTextException {
    BackupJob job = new BackupJob();
    job.encryptionKey = "test key";

    for (int i = 0; i < 10; i++) {
      KeyParameter kSess = job.newSessionKey();
      byte[] kencSess = job.wrapKey(kSess);
      KeyParameter kDecSess = job.unwrapKey(kencSess);
      Assert.assertArrayEquals(kSess.getKey(), kDecSess.getKey());
      Assert.assertEquals(Job.WRAPPED_AES_KEY_SIZE_BYTES, kencSess.length);
    }
  }

  @Test(expected=InvalidCipherTextException.class)
  public void testKeyUnwrappingWrongKek() throws InvalidCipherTextException {
    BackupJob kek1 = new BackupJob();
    kek1.encryptionKey = "test key";

    BackupJob kek2 = new BackupJob();
    kek2.encryptionKey = "wrong key";

    KeyParameter kSess = kek1.newSessionKey();
    byte[] kencSess = kek1.wrapKey(kSess);
    kek2.unwrapKey(kencSess);
  }

  @Test
  public void testAesGcm() throws IllegalStateException, InvalidCipherTextException {
    BackupJob kek1 = new BackupJob();
    kek1.encryptionKey = "test key";

    KeyParameter kSess = kek1.newSessionKey();
    byte[] nonce = kek1.newNonce();

    byte[] ciphertext = new byte[1024];
    byte[] inputText = "This is a very simple test message!".getBytes(Charset.defaultCharset());
    GCMBlockCipher encrypt = kek1.createSessionDataCipher(kSess, nonce, true);
    int outOff = encrypt.processBytes(inputText, 0, inputText.length, ciphertext, 0);
    int ciphertextLen = outOff + encrypt.doFinal(ciphertext, outOff);

    byte[] buff = new byte[1024];
    GCMBlockCipher decrypt = kek1.createSessionDataCipher(kSess, nonce, false);
    int plaintextLength = decrypt.processBytes(ciphertext, 0, ciphertextLen, buff, 0);
    plaintextLength += decrypt.doFinal(buff, plaintextLength); // <-- process any remaining data & check auth tag -- VERY IMPORTANT!

    byte[] plaintext = new byte[plaintextLength];
    System.arraycopy(buff, 0, plaintext, 0, plaintextLength);
    Assert.assertArrayEquals(inputText, plaintext);
  }

  @Test(expected=InvalidCipherTextException.class)
  public void testAesGcmTamperData() throws IllegalStateException, InvalidCipherTextException {
    BackupJob kek1 = new BackupJob();
    kek1.encryptionKey = "test key";

    KeyParameter kSess = kek1.newSessionKey();
    byte[] nonce = kek1.newNonce();

    byte[] ciphertext = new byte[1024];
    byte[] inputText = "This is a very simple test message!".getBytes(Charset.defaultCharset());
    GCMBlockCipher encrypt = kek1.createSessionDataCipher(kSess, nonce, true);
    int outOff = encrypt.processBytes(inputText, 0, inputText.length, ciphertext, 0);
    int ciphertextLen = outOff + encrypt.doFinal(ciphertext, outOff);

    ciphertext[4] ^= 42;

    byte[] buff = new byte[1024];
    GCMBlockCipher decrypt = kek1.createSessionDataCipher(kSess, nonce, false);
    int plaintextLength = decrypt.processBytes(ciphertext, 0, ciphertextLen, buff, 0);
    decrypt.doFinal(buff, plaintextLength); // <-- process any remaining data & check auth tag -- VERY IMPORTANT!
  }

  @Test(expected=InvalidCipherTextException.class)
  public void testAesGcmTamperNonce() throws IllegalStateException, InvalidCipherTextException {
    BackupJob kek1 = new BackupJob();
    kek1.encryptionKey = "test key";

    KeyParameter kSess = kek1.newSessionKey();
    byte[] nonce = kek1.newNonce();

    byte[] ciphertext = new byte[1024];
    byte[] inputText = "This is a very simple test message!".getBytes(Charset.defaultCharset());
    GCMBlockCipher encrypt = kek1.createSessionDataCipher(kSess, nonce, true);
    int outOff = encrypt.processBytes(inputText, 0, inputText.length, ciphertext, 0);
    int ciphertextLen = outOff + encrypt.doFinal(ciphertext, outOff);

    nonce[4] ^= 42;

    byte[] buff = new byte[1024];
    GCMBlockCipher decrypt = kek1.createSessionDataCipher(kSess, nonce, false);
    int plaintextLength = decrypt.processBytes(ciphertext, 0, ciphertextLen, buff, 0);
    decrypt.doFinal(buff, plaintextLength); // <-- process any remaining data & check auth tag -- VERY IMPORTANT!
  }

  @Test
  public void testFileCrypto() throws IOException {
    BackupJob backup = new BackupJob();
    backup.encryptionKey = "test key";

    byte[] haiku = ("The first cold shower\n" +
                   "even the monkey seems to want\n" +
                   "a little coat of straw").getBytes(Charset.forName("ASCII"));

    File fTempIn = File.createTempFile("junit", ".tmp");
    File fTempEnc = new File(fTempIn.getParentFile(), fTempIn.getName() + ".enc");
    File fTempDec = new File(fTempIn.getParentFile(), fTempIn.getName() + ".dec");
    try {
      Files.write(fTempIn.toPath(), haiku, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

      backup.encrypt(fTempIn, fTempEnc);

      String hexEncrypted = Hex.toHexString(Files.readAllBytes(fTempEnc.toPath()));
      String hexHaiku = Hex.toHexString(haiku);
      Assert.assertFalse(hexEncrypted.contains(hexHaiku));

      RestoreJobs restore = new RestoreJobs();
      restore.encryptionKey = backup.encryptionKey;

      restore.decrypt(fTempEnc, fTempDec);
      byte[] decrypted = Files.readAllBytes(fTempDec.toPath());
      String hexDecrypted = Hex.toHexString(decrypted);
      Assert.assertEquals(hexHaiku, hexDecrypted);
    } finally {
      fTempIn.delete();
      fTempEnc.delete();
      fTempDec.delete();
    }
  }

}
