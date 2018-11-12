package com.daveeberhart.bareos_util.secure_s3_storage.job;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;
import com.amazonaws.services.s3.transfer.model.UploadResult;
import com.daveeberhart.bareos_util.secure_s3_storage.error.BadArgsException;
import com.daveeberhart.bareos_util.secure_s3_storage.error.JobFailedException.VolumeMissingException;

/**
 * Test backup job class.
 *
 * @author deberhar
 */
public class BackupJobTest {
  private final File fTestDir = new File(new File(System.getProperty("java.io.tmpdir")), UUID.randomUUID().toString());

  @Test(expected=BadArgsException.class)
  public void testNoArgs() {
    BackupJob job = new TestableBackupJob();
    job.setRemainingArgs(Arrays.asList());
  }

  @Test
  public void testOnlyJobIdArg() {
    BackupJob job = new TestableBackupJob();
    job.setRemainingArgs(Arrays.asList("123"));
  }

  @Test(expected=BadArgsException.class)
  public void testNonNumericJobIdArg() {
    BackupJob job = new TestableBackupJob();
    job.setRemainingArgs(Arrays.asList("TESTVOL-0001", "TESTVOL-0002"));
  }

  @Test
  public void testArgPipeParsing() {
    BackupJob job = new TestableBackupJob();

    job.setRemainingArgs(Arrays.asList("123", "a", "b |c", "d|", null, "e", " "));
    Assert.assertArrayEquals(new String[]{
        "a",
        "b",
        "c",
        "d",
        "e",
    }, job.volumeNames.toArray());
  }

  @Test
  public void testUploadFile() throws IOException {
    final String strExpected = "It's a test!";

    fTestDir.mkdir();
    try {
      File fTestVol001 = new File(fTestDir, "TESTVOL-0001");
      Files.write(fTestVol001.toPath(), Arrays.asList(strExpected), StandardOpenOption.CREATE);
      String origDigest1 = digest(fTestVol001);

      BackupJob job = new TestableBackupJob();
      job.setScratchDir(fTestDir);
      job.setRemainingArgs(Arrays.asList("123", "TESTVOL-0001"));
      job.prepare();
      Mockito.when(job.tm.upload(Mockito.any(), Mockito.any())).then(inv -> {
        UploadResult res = new UploadResult();

        PutObjectRequest req = inv.getArgument(0);
        res.setETag(digest(req.getFile()));

        Assert.assertEquals("bb-123-TESTVOL-0001.enc", req.getFile().getName());
        Assert.assertNotEquals(origDigest1, res.getETag());

        File fTmp = new File(fTestDir, res.getETag());
        job.decrypt(req.getFile(), fTmp);
        String strDecryptedDigest = digest(fTmp);
        Assert.assertEquals(origDigest1, strDecryptedDigest);

        Upload upload = Mockito.mock(Upload.class);
        Mockito.when(upload.waitForUploadResult()).thenReturn(res);
        return upload;
      });

      job.run();

      Assert.assertFalse(fTestVol001.exists());

      Mockito.verify(job.tm).upload(Mockito.any(), Mockito.any());
      Mockito.verifyNoMoreInteractions(job.s3);
    } finally {
      FileUtils.deleteDirectory(fTestDir);
    }
  }

  @Test
  public void testUploadTwoFile() throws IOException {
    fTestDir.mkdir();
    try {
      File fTestVol001 = new File(fTestDir, "TESTVOL-0001");
      File fTestVol002 = new File(fTestDir, "TESTVOL-0002");
      Files.write(fTestVol001.toPath(), Arrays.asList("It's a test!"), StandardOpenOption.CREATE);
      String origDigest1 = digest(fTestVol001);
      Files.write(fTestVol002.toPath(), Arrays.asList("It's another test!"), StandardOpenOption.CREATE);
      String origDigest2 = digest(fTestVol002);

      BackupJob job = new TestableBackupJob();
      job.setScratchDir(fTestDir);
      job.setRemainingArgs(Arrays.asList("123", "TESTVOL-0001", "TESTVOL-0002"));
      job.prepare();
      Mockito.when(job.tm.upload(Mockito.any(), Mockito.any())).then(inv -> {
        UploadResult res = new UploadResult();

        PutObjectRequest req = inv.getArgument(0);
        res.setETag(digest(req.getFile()));

        Assert.assertTrue("bb-123-TESTVOL-0001.enc".equals(req.getFile().getName()) || "bb-123-TESTVOL-0002.enc".equals(req.getFile().getName()));
        Assert.assertNotEquals(origDigest1, res.getETag());
        Assert.assertNotEquals(origDigest2, res.getETag());

        File fTmp = new File(fTestDir, res.getETag());
        job.decrypt(req.getFile(), fTmp);
        String strDecryptedDigest = digest(fTmp);
        Assert.assertTrue(strDecryptedDigest, Arrays.asList(origDigest1, origDigest2).contains(strDecryptedDigest));

        Upload upload = Mockito.mock(Upload.class);
        Mockito.when(upload.waitForUploadResult()).thenReturn(res);
        return upload;
      });

      job.run();

      Assert.assertFalse(fTestVol001.exists());

      Mockito.verify(job.tm, Mockito.times(2)).upload(Mockito.any(), Mockito.any());
      Mockito.verifyNoMoreInteractions(job.s3, job.tm);
    } finally {
      FileUtils.deleteDirectory(fTestDir);
    }
  }

  @Test(expected=VolumeMissingException.class)
  public void testMissingFile() throws IOException {
    fTestDir.mkdir();
    try {
      BackupJob job = new TestableBackupJob();
      job.setScratchDir(fTestDir);
      job.setRemainingArgs(Arrays.asList("123", "TESTVOL-0001"));
      job.prepare();
      Mockito.when(job.s3.putObject(Mockito.any())).thenThrow(new AssertionError("Should have failed!"));
      job.run();
    } finally {
      FileUtils.deleteDirectory(fTestDir);
    }
  }



  private static final class TestableBackupJob extends BackupJob {
    @Override
    public void prepare() {
      s3 = Mockito.mock(AmazonS3.class);
      tm = Mockito.mock(TransferManager.class);
      encryptionKey = "Dummy crypto key";
      bucket = "test-bucket";
    }
  }

  private String digest(File f) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      try (InputStream fin = new FileInputStream(f);
           DigestInputStream digfin = new DigestInputStream(fin, md))  {
          byte[] buff = new byte[64 * 1024];
          while (digfin.read(buff, 0, buff.length) > 0) {
            // Loop.
          }
        }

      return Hex.toHexString(md.digest());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

}
