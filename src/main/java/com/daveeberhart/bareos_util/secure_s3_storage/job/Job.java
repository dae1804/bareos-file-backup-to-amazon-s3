package com.daveeberhart.bareos_util.secure_s3_storage.job;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.PBEParametersGenerator;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.engines.AESWrapEngine;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.daveeberhart.bareos_util.secure_s3_storage.error.JobFailedException;
import com.daveeberhart.bareos_util.secure_s3_storage.error.JobFailedException.IntegrityCheckFailedException;
import com.daveeberhart.bareos_util.secure_s3_storage.progress.CryptoProgressListener;

/**
 * Base class for all jobs.
 * <p>
 * The lifecycle of a job:
 * <ol>
 * <li>{@link #setScratchDir(File)}</li>
 * <li>{@link #setRemainingArgs(List)}</li>
 * <li>{@link #prepare()}</li>
 * <li>{@link #run()}</li>
 * <li>{@link #cleanup()}</li>
 * </ol>
 * <p>
 * Note: You should call {@link #cleanup()} if you've even attempted a call to
 * {@link #prepare()}.
 *
 * @author deberhar
 */
public abstract class Job {
  private static final String PROP_ENCRYPTION_KEY = "encryption.key";

  private static final int HEADER_SIZE = 512;
  /** The magic bytes "{@code BAREOS-S3-ENC}".  Used to ID our files. */
  private static final byte[] MAGIC = "BAREOS-S3-ENC".getBytes(Charset.forName("ASCII"));
  private static final short FILE_VERSION = 1;

  private static final long GIGABYTE = 1024L * 1024L * 1024L;
  private static final int AES_KEY_SIZE_BITS = 128;
  /** AESWrap length = wrapped key length + 1/2 block (64 bits) of checksum data */
  private static final int WRAPPED_AES_KEY_SIZE_BITS = AES_KEY_SIZE_BITS + 64;
  static final int WRAPPED_AES_KEY_SIZE_BYTES = WRAPPED_AES_KEY_SIZE_BITS / Byte.SIZE;
  /** Size of the auth tag in bits.  This is the max length allowed (strongest anti-forgery). */
  private static final int AEAD_MAC_TAG_SIZE_BITS = 128;
  /** Recommended AES-GCM nonce size, per NIST Special Publication 800-38D p8, 5.2.1.1 */
  private static final int AES_GCM_NONCE_SIZE_BITS = 96;
  private static final int AES_GCM_NONCE_SIZE_BYTES = AES_GCM_NONCE_SIZE_BITS / Byte.SIZE;

  private static final byte[] SALT = Base64.getDecoder().decode("6YEuJ+6T8Wzc3PV6uqRTHu9AM8m9cWDFXF7dQk2QwLo=");
  private static final File configFile = new File(System.getProperty("config.file.location", "/etc/bareos/s3-storage.properties"));

  private static final SecureRandom random;
  static {
    try {
      random = SecureRandom.getInstanceStrong();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  protected AmazonS3 s3;
  protected TransferManager tm;
  protected String bucket;
  protected String encryptionKey;
  protected File scratchDir;
  private  byte[] kek;
  private boolean configFileNotLoaded;

  public Job() {
    if (configFile.exists()) {
      Properties etcProps = new Properties();
      try (FileInputStream fs = new FileInputStream(configFile)) {
        etcProps.load(fs);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }

      for (Entry<Object,Object> entry : etcProps.entrySet()) {
        System.getProperties().putIfAbsent(entry.getKey(), entry.getValue());
      }
    } else {
      configFileNotLoaded = true;
    }
  }

  /**
   * Verify the config for the job, and set up Amazon webservices client.
   */
  public void prepare() {
    if (configFileNotLoaded) {
      System.err.println("Warning: Config file not found at " + configFile.getAbsolutePath());
    }

    s3 = AmazonS3ClientBuilder.standard()
        .withRegion(getRequiredProperty("aws.region"))
        .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(getRequiredProperty("aws.accessKeyId"), getRequiredProperty("aws.secretKeyId"))))
        .build();

    tm = TransferManagerBuilder.standard()
        .withS3Client(s3)
        .build();

    encryptionKey = getRequiredProperty(PROP_ENCRYPTION_KEY);
    bucket        = getRequiredProperty("aws.bucket");
  }

  /**
   * @param p_prop The name of the property to load
   * @return The property's value, preferring properties set via the commandline.
   */
  private String getRequiredProperty(String p_prop) {
    String val = System.getProperty(p_prop);
    if (val == null || val.trim().length() == 0) {
      System.err.println("A value is required for the setting (property) " + p_prop);
      System.err.println("Either add it to " + configFile + ", or pass a value on the command line (e.g. -D" + p_prop + "=\"value\"");
      System.err.println();
      System.exit(2);
    }

    return val;
  }

  /**
   * @return Directory to upload/download volumes from/to (we also create our temp files here)
   */
  public File getScratchDir() {
    return scratchDir;
  }

  /**
   * @param p_scratchDir Directory to upload/download volumes from/to (we also create our temp files here)
   */
  public void setScratchDir(File p_scratchDir) {
    scratchDir = p_scratchDir;
  }

  /**
   * Set remaining job-specific commandline arguments
   * @param p_args remaining commandline arguments
   */
  public abstract void setRemainingArgs(List<String> p_args);

  /**
   * Run the job.
   */
  public abstract void run();

  /**
   * Shutdown the job and release all resources.
   */
  public void cleanup() {
    if (tm != null) {
      tm.shutdownNow();
    }

    if (s3 != null) {
      s3.shutdown();
    }
  }

  /**
   * @return A new, random AES key for encrypting the file's actual data.
   */
  protected KeyParameter newSessionKey() {
    byte[] key = new byte[AES_KEY_SIZE_BITS / Byte.SIZE];
    random.nextBytes(key);
    return new KeyParameter(key);
  }

  /**
   * @return A new single-use number (Nonce, i.e. n-once).
   */
  protected byte[] newNonce() {
    byte[] nonce = new byte[AES_GCM_NONCE_SIZE_BYTES];
    random.nextBytes(nonce);
    return nonce;
  }

  /**
   * Wrap a session key using the key-encryption key.
   * @param sessionKey The session key to wrap (this is the key used to encrypt the actual data for the current file).
   * @return Encrypted key
   */
  protected byte[] wrapKey(KeyParameter sessionKey) {
    AESWrapEngine wrapper = new AESWrapEngine();
    wrapper.init(true, new KeyParameter(getKeyEncryptionKey()));
    return wrapper.wrap(sessionKey.getKey(), 0, sessionKey.getKey().length);
  }

  /**
   * Unwrap session key using the key-encryption key.
   * @param p_encryptedKey The encrypted key.  Should have a length of {@link #WRAPPED_AES_KEY_SIZE_BYTES}.
   * @return The decrypted session key (this is the key used to decrypt the actual data for the current file).
   */
  protected KeyParameter unwrapKey(byte[] p_encryptedKey) throws InvalidCipherTextException {
    AESWrapEngine wrapper = new AESWrapEngine();
    wrapper.init(false, new KeyParameter(getKeyEncryptionKey()));
    return new KeyParameter(wrapper.unwrap(p_encryptedKey, 0, p_encryptedKey.length));
  }

  /**
   * Create the cipher user to encrypt/decrypt the actual file content.
   *
   * @param nonce
   *          Nonce generated using {@link #newNonce()}. Not secret, but DO NOT
   *          RE-USE EVER!!!
   * @param key
   *          Session key generated using {@link #newSessionKey()}
   */
  protected GCMBlockCipher createSessionDataCipher(KeyParameter key, byte[] nonce, boolean forEncryption) {
    GCMBlockCipher cipher = new GCMBlockCipher(new AESEngine());
    cipher.init(forEncryption, new AEADParameters(key, AEAD_MAC_TAG_SIZE_BITS, nonce));
    return cipher;
  }

  /**
   * Encrypt a file in the current file-format version.
   * <p>
   * The file format is:
   * <ul>
   * <li>A {@value #HEADER_SIZE}-byte header, containing:<ul>
   *   <li>{@link #MAGIC}</li>
   *   <li>A 16-bit integer (file format version number)</li>
   *   <li>{@value #AES_GCM_NONCE_SIZE_BYTES} bytes of Nonce (see {@link #newNonce()})</li>
   *   <li>{@value #WRAPPED_AES_KEY_SIZE_BYTES} bytes of encrypted session key, wrapped using AESWrap (see {@link #newSessionKey()} and {@link #wrapKey(KeyParameter)})</li>
   *   <li>Zero-padding to fill out the remainder of the file header</li>
 *   </ul></li>
   * <li>The actual file content, encrypted with AES-GCM (see {@link #createSessionDataCipher(KeyParameter, byte[], boolean)}).</li>
   * <li>{@value #AEAD_MAC_TAG_SIZE_BITS} bits of AEAD auth tag</li>
   * </ul>
   */
  protected void encrypt(File p_in, File p_out) throws FileNotFoundException, IOException {
    if (p_in.length() > 64 * GIGABYTE) {
      throw new JobFailedException("Current AES-GCM implementation can't handle files >64GB, sorry!  Consider setting a max volume size on your file device in Bareos.");
    }

    KeyParameter sessionKey = newSessionKey();
    byte[] nonce = newNonce();
    if (nonce.length != AES_GCM_NONCE_SIZE_BYTES) {
      throw new IllegalStateException("Nonce length was " + nonce.length + "; expected " + AES_GCM_NONCE_SIZE_BYTES);
    }
    byte[] wrappedSessionKey = wrapKey(sessionKey);
    if (wrappedSessionKey.length != WRAPPED_AES_KEY_SIZE_BYTES) {
      throw new IllegalStateException("Wrapped session key length was " + wrappedSessionKey.length + "; expected " + WRAPPED_AES_KEY_SIZE_BYTES);
    }

    try (FileOutputStream fout = new FileOutputStream(p_out, false)) {
      fout.write(createHeader(nonce, wrappedSessionKey));

      final byte[] inbuff  = new byte[64 * 1024];
      final byte[] outbuff = new byte[64 * 1024];
      CryptoProgressListener listener = new CryptoProgressListener(p_in.getName(), "Encrypt", p_in.length());
      final GCMBlockCipher cipher = createSessionDataCipher(sessionKey, nonce, true);
      try (final FileInputStream fin = new FileInputStream(p_in)) {
        int lenIn;
        while ( (lenIn = fin.read(inbuff, 0, inbuff.length)) >= 0) {
          final int lenOut = cipher.processBytes(inbuff, 0, lenIn, outbuff, 0);
          fout.write(outbuff, 0, lenOut);
          listener.addBytesProcessed(lenIn);
        }
      }

      final int lenFinal = cipher.doFinal(outbuff, 0);
      fout.write(outbuff, 0, lenFinal);
      listener.done();
    } catch (InvalidCipherTextException e) {
      throw new IllegalStateException("InvalidCipherTextException is not expected while encrypting!", e);
    }
  }

  /**
   * Assemble the header to be written to the file.
   * This is always for the current file format version.
   */
  private byte[] createHeader(byte[] nonce, byte[] wrappedSessionKey) {
    ByteBuffer bbHeader = ByteBuffer.allocate(HEADER_SIZE);
    bbHeader.put(MAGIC);
    bbHeader.putShort(FILE_VERSION);
    bbHeader.put(wrappedSessionKey);
    bbHeader.put(nonce);
    return bbHeader.array();
  }

  /**
   * Decrypt an encrypted file.
   *
   * @see #encrypt(File, File)
   */
  protected void decrypt(File p_in, File p_out) throws FileNotFoundException, IOException {
    if (p_in.length() < HEADER_SIZE) {
      throw new JobFailedException(p_in.getName() + " is too short to be an encrypted backup file!");
    }

    try (FileInputStream fin = new FileInputStream(p_in)) {
      ByteBuffer bbHeader = ByteBuffer.allocate(HEADER_SIZE);
      fin.read(bbHeader.array());

      byte[] magic = new byte[MAGIC.length];
      bbHeader.get(magic);
      if (!Arrays.equals(magic, MAGIC)) {
        throw new JobFailedException(p_in.getName() + " doesn't appear to be an encrypted backup file (wrong magic at file start)");
      }

      short version = bbHeader.getShort();
      switch (version) {
      case 1:
        decryptV1(p_in.getName(), p_in.length(), fin, p_out, bbHeader);
        break;
      default:
        throw new JobFailedException(p_in.getName() + " uses unsupported file format version " + version);
      }
    }
  }

  /**
   * Decrypt file version 1.
   */
  private void decryptV1(String caption, long length, FileInputStream p_fin, File p_out, ByteBuffer p_bbHeader) throws FileNotFoundException, IOException {
    byte[] wrappedSessionKey = new byte[WRAPPED_AES_KEY_SIZE_BYTES];
    p_bbHeader.get(wrappedSessionKey);
    KeyParameter sessionKey;
    try {
      sessionKey = unwrapKey(wrappedSessionKey);
    } catch (InvalidCipherTextException e) {
      throw new IntegrityCheckFailedException("Failed to unwrap session key (check that your " + PROP_ENCRYPTION_KEY + " setting matches what this file was encrypted with!)", e);
    }

    byte[] nonce = new byte[AES_GCM_NONCE_SIZE_BYTES];
    p_bbHeader.get(nonce);

    final byte[] inbuff  = new byte[64 * 1024];
    final byte[] outbuff = new byte[64 * 1024];
    final GCMBlockCipher cipher = createSessionDataCipher(sessionKey, nonce, false);
    CryptoProgressListener listener = new CryptoProgressListener(caption, "Decrypt", length);
    try (FileOutputStream fout = new FileOutputStream(p_out, false)) {
      int lenIn;
      while ( (lenIn = p_fin.read(inbuff, 0, inbuff.length)) >= 0) {
        final int lenOut = cipher.processBytes(inbuff, 0, lenIn, outbuff, 0);
        fout.write(outbuff, 0, lenOut);
        listener.addBytesProcessed(lenIn);
      }

      int lenFinal = cipher.doFinal(outbuff, 0);
      fout.write(outbuff, 0, lenFinal);
      listener.done();
    } catch (InvalidCipherTextException e) {
      p_out.delete(); // <-- IMPORTANT: Decrypted contents fail auth check; DON'T leave them lying about!
      throw new IntegrityCheckFailedException(p_out.getName() + " failed integrity check!", e);
    }
  }

  /**
   * Derive a key-encryption key from a text passphrase using PBKDF v2.
   * <p>
   * This is used to protect the per-file AES-GCM crypto key, which is written into the
   * file header (encrypted, of course!).
   */
  private synchronized byte[] getKeyEncryptionKey() {
    if (kek == null) {
      PKCS5S2ParametersGenerator gen = new PKCS5S2ParametersGenerator();
      gen.init(PBEParametersGenerator.PKCS5PasswordToBytes(encryptionKey.toCharArray()), SALT, 50_000);
      kek = ((KeyParameter)gen.generateDerivedParameters(AES_KEY_SIZE_BITS)).getKey();
    }

    return kek;
  }

}
