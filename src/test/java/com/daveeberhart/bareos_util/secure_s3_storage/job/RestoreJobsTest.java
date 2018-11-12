package com.daveeberhart.bareos_util.secure_s3_storage.job;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.Headers;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.StorageClass;
import com.amazonaws.services.s3.transfer.Download;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.daveeberhart.bareos_util.secure_s3_storage.error.BadArgsException;
import com.daveeberhart.bareos_util.secure_s3_storage.error.JobFailedException.GlacierRestoreInProgressException;
import com.daveeberhart.bareos_util.secure_s3_storage.error.JobFailedException.IntegrityCheckFailedException;
import com.daveeberhart.bareos_util.secure_s3_storage.error.JobFailedException.JobNotFoundException;


/**
 * @author deberhar
 *
 */
public class RestoreJobsTest {
  private final File fTestDir = new File(new File(System.getProperty("java.io.tmpdir")), UUID.randomUUID().toString());
  private RestoreJobs rj = Mockito.spy(RestoreJobs.class);

  public RestoreJobsTest() {
    Mockito.doNothing().when(rj).prepare();
    rj.bucket = "bucket";
    rj.encryptionKey = "secret key";

    rj.s3 = Mockito.mock(AmazonS3.class);
    rj.tm = Mockito.mock(TransferManager.class);

    rj.setScratchDir(fTestDir);
  }

  @Test(expected=BadArgsException.class)
  public void testBadJobId() {
    fTestDir.mkdir();

    rj.setRemainingArgs(Arrays.asList("TESTVOL-0001"));
  }

  @Test(expected=JobNotFoundException.class)
  public void testNoSuchJob() {
    fTestDir.mkdir();

    Mockito.when(rj.s3.listObjects(Mockito.anyString(), Mockito.anyString())).then(inv -> {
      Assert.assertEquals(rj.bucket, inv.getArgument(0));
      Assert.assertEquals("bb-123-", inv.getArgument(1));

      ObjectListing res = new ObjectListing();
      return res;
    });

    rj.setRemainingArgs(Arrays.asList("123"));
    rj.prepare();
    rj.run();
  }

  @Test
  public void testOneResult() {
    fTestDir.mkdir();

    Mockito.when(rj.s3.listObjects(Mockito.anyString(), Mockito.anyString())).then(inv -> {
      Assert.assertEquals(rj.bucket, inv.getArgument(0));
      Assert.assertEquals("bb-123-", inv.getArgument(1));

      ObjectListing res = new ObjectListing();
      S3ObjectSummary summary = new S3ObjectSummary();
      summary.setKey("bb-123-TESTVOL-0001.enc");
      res.getObjectSummaries().add(summary);
      return res;
    });
    Mockito.when(rj.s3.getObjectMetadata(Mockito.anyString(), Mockito.anyString())).then(inv -> {
      Assert.assertEquals(rj.bucket, inv.getArgument(0));
      Assert.assertEquals("bb-123-TESTVOL-0001.enc", inv.getArgument(1));

      ObjectMetadata md = new ObjectMetadata();
      md.setOngoingRestore(false);
      return md;
    });
    mockResult(rj);

    rj.setRemainingArgs(Arrays.asList("123"));
    rj.prepare();
    rj.run();

    Mockito.verify(rj.s3).listObjects(rj.bucket, "bb-123-");
    Mockito.verify(rj.s3).getObjectMetadata(rj.bucket, "bb-123-TESTVOL-0001.enc");
    Mockito.verify(rj.tm).download(Mockito.any(GetObjectRequest.class), Mockito.any(), Mockito.any());
    Mockito.verifyNoMoreInteractions(rj.s3, rj.tm);
  }

  @Test
  public void testStorageClass() {
    fTestDir.mkdir();

    Mockito.when(rj.s3.listObjects(Mockito.anyString(), Mockito.anyString())).then(inv -> {
      Assert.assertEquals(rj.bucket, inv.getArgument(0));
      Assert.assertEquals("bb-123-", inv.getArgument(1));

      ObjectListing res = new ObjectListing();
      S3ObjectSummary summary = new S3ObjectSummary();
      summary.setKey("bb-123-TESTVOL-0001.enc");
      res.getObjectSummaries().add(summary);
      return res;
    });
    Mockito.when(rj.s3.getObjectMetadata(Mockito.anyString(), Mockito.anyString())).then(inv -> {
      Assert.assertEquals(rj.bucket, inv.getArgument(0));
      Assert.assertEquals("bb-123-TESTVOL-0001.enc", inv.getArgument(1));

      ObjectMetadata md = new ObjectMetadata();
      md.setOngoingRestore(false);
      md.setHeader(Headers.STORAGE_CLASS, StorageClass.OneZoneInfrequentAccess.toString());
      return md;
    });
    mockResult(rj);

    rj.setRemainingArgs(Arrays.asList("123"));
    rj.prepare();
    rj.run();

    Mockito.verify(rj.s3).listObjects(rj.bucket, "bb-123-");
    Mockito.verify(rj.s3).getObjectMetadata(rj.bucket, "bb-123-TESTVOL-0001.enc");
    Mockito.verify(rj.tm).download(Mockito.any(GetObjectRequest.class), Mockito.any(), Mockito.any());
    Mockito.verifyNoMoreInteractions(rj.s3, rj.tm);
  }

  @Test
  public void testFileOnDisk() throws IOException {
    fTestDir.mkdir();

    Mockito.when(rj.s3.listObjects(Mockito.anyString(), Mockito.anyString())).then(inv -> {
      Assert.assertEquals(rj.bucket, inv.getArgument(0));
      Assert.assertEquals("bb-123-", inv.getArgument(1));

      ObjectListing res = new ObjectListing();
      S3ObjectSummary summary = new S3ObjectSummary();
      summary.setKey("bb-123-TESTVOL-0001.enc");
      res.getObjectSummaries().add(summary);
      return res;
    });
    Mockito.when(rj.s3.getObjectMetadata(Mockito.anyString(), Mockito.anyString())).then(inv -> {
      Assert.assertEquals(rj.bucket, inv.getArgument(0));
      ObjectMetadata md = new ObjectMetadata();
      md.setOngoingRestore(false);
      return md;
    });
    Files.write(new File(rj.getScratchDir(), "TESTVOL-0001").toPath(), new byte[0]);

    rj.setRemainingArgs(Arrays.asList("123"));
    rj.prepare();
    rj.run();

    Mockito.verify(rj.s3).listObjects(rj.bucket, "bb-123-");
    Mockito.verify(rj.s3).getObjectMetadata(rj.bucket, "bb-123-TESTVOL-0001.enc");
    Mockito.verifyNoMoreInteractions(rj.s3, rj.tm);
  }

  @Test(expected=GlacierRestoreInProgressException.class)
  public void testGlacierRestoreNeeded() throws IOException {
    fTestDir.mkdir();

    Mockito.when(rj.s3.listObjects(Mockito.anyString(), Mockito.anyString())).then(inv -> {
      Assert.assertEquals(rj.bucket, inv.getArgument(0));
      Assert.assertEquals("bb-123-", inv.getArgument(1));

      ObjectListing res = new ObjectListing();
      S3ObjectSummary summary = new S3ObjectSummary();
      summary.setKey("bb-123-TESTVOL-0001.enc");
      res.getObjectSummaries().add(summary);
      return res;
    });
    Mockito.when(rj.s3.getObjectMetadata(Mockito.anyString(), Mockito.anyString())).then(inv -> {
      Assert.assertEquals(rj.bucket, inv.getArgument(0));
      ObjectMetadata md = new ObjectMetadata();
      md.setOngoingRestore(false);
      md.setHeader(Headers.STORAGE_CLASS, StorageClass.Glacier.toString());
      return md;
    });

    rj.setRemainingArgs(Arrays.asList("123"));
    rj.prepare();
    rj.run();
  }

  @Test(expected=GlacierRestoreInProgressException.class)
  public void testGlacierRestoreInProgress() throws IOException {
    fTestDir.mkdir();

    Mockito.when(rj.s3.listObjects(Mockito.anyString(), Mockito.anyString())).then(inv -> {
      Assert.assertEquals(rj.bucket, inv.getArgument(0));
      Assert.assertEquals("bb-123-", inv.getArgument(1));

      ObjectListing res = new ObjectListing();
      S3ObjectSummary summary = new S3ObjectSummary();
      summary.setKey("bb-123-TESTVOL-0001.enc");
      res.getObjectSummaries().add(summary);
      return res;
    });
    Mockito.when(rj.s3.getObjectMetadata(Mockito.anyString(), Mockito.anyString())).then(inv -> {
      Assert.assertEquals(rj.bucket, inv.getArgument(0));
      ObjectMetadata md = new ObjectMetadata();
      md.setOngoingRestore(true);
      md.setHeader(Headers.STORAGE_CLASS, StorageClass.Glacier.toString());
      return md;
    });

    rj.setRemainingArgs(Arrays.asList("123"));
    rj.prepare();
    rj.run();
  }

  @Test
  public void testPagedResults() {
    fTestDir.mkdir();

    AtomicInteger ctr = new AtomicInteger(0);
    Mockito.when(rj.s3.listObjects(Mockito.anyString(), Mockito.anyString())).then(inv -> {
      Assert.assertEquals(rj.bucket, inv.getArgument(0));
      Assert.assertEquals("bb-123-", inv.getArgument(1));

      ObjectListing res = new ObjectListing();
      for (int i = 0; i < 3; i++) {
        S3ObjectSummary summary = new S3ObjectSummary();
        summary.setKey("bb-123-TESTVOL-000" + ctr.incrementAndGet() + ".enc");
        res.getObjectSummaries().add(summary);
      }
      res.setTruncated(true);
      return res;
    });
    Mockito.when(rj.s3.listNextBatchOfObjects(Mockito.any(ObjectListing.class))).then(inv -> {
      ObjectListing res = new ObjectListing();
      for (int i = 0; i < 3; i++) {
        S3ObjectSummary summary = new S3ObjectSummary();
        summary.setKey("bb-123-TESTVOL-000" + ctr.incrementAndGet() + ".enc");
        res.getObjectSummaries().add(summary);
      }
      res.setTruncated(ctr.get() < 8);

      return res;
    });
    Mockito.when(rj.s3.getObjectMetadata(Mockito.anyString(), Mockito.anyString())).then(inv -> {
      Assert.assertEquals(rj.bucket, inv.getArgument(0));
      ObjectMetadata md = new ObjectMetadata();
      md.setOngoingRestore(false);
      return md;
    });
    mockResult(rj);

    rj.setRemainingArgs(Arrays.asList("123"));
    rj.prepare();
    rj.run();

    Mockito.verify(rj.s3).listObjects(rj.bucket, "bb-123-");
    Mockito.verify(rj.s3, Mockito.times(2)).listNextBatchOfObjects(Mockito.any(ObjectListing.class));
    for (int i = 1; i <= 9; i++) {
      Mockito.verify(rj.s3).getObjectMetadata(rj.bucket, "bb-123-TESTVOL-000" + i + ".enc");
    }
    Mockito.verify(rj.tm, Mockito.times(9)).download(Mockito.any(GetObjectRequest.class), Mockito.any(), Mockito.any());
    Mockito.verifyNoMoreInteractions(rj.s3, rj.tm);
  }

  @Test(expected=IntegrityCheckFailedException.class)
  public void testTampered() {
    fTestDir.mkdir();

    Mockito.when(rj.s3.listObjects(Mockito.anyString(), Mockito.anyString())).then(inv -> {
      Assert.assertEquals(rj.bucket, inv.getArgument(0));
      Assert.assertEquals("bb-123-", inv.getArgument(1));

      ObjectListing res = new ObjectListing();
      S3ObjectSummary summary = new S3ObjectSummary();
      summary.setKey("bb-123-TESTVOL-0001.enc");
      res.getObjectSummaries().add(summary);
      return res;
    });
    Mockito.when(rj.s3.getObjectMetadata(Mockito.anyString(), Mockito.anyString())).then(inv -> {
      Assert.assertEquals(rj.bucket, inv.getArgument(0));
      Assert.assertEquals("bb-123-TESTVOL-0001.enc", inv.getArgument(1));

      ObjectMetadata md = new ObjectMetadata();
      md.setOngoingRestore(false);
      return md;
    });

    BackupJob bj = new BackupJob();
    bj.encryptionKey = "nope";
    bj.tm = rj.tm;
    bj.setScratchDir(rj.getScratchDir());
    mockResult(bj);

    rj.setRemainingArgs(Arrays.asList("123"));
    rj.prepare();
    rj.run();

    Mockito.verify(rj.s3).listObjects(rj.bucket, "bb-123-");
    Mockito.verify(rj.s3).getObjectMetadata(rj.bucket, "bb-123-TESTVOL-0001.enc");
    Mockito.verify(rj.tm).download(Mockito.any(GetObjectRequest.class), Mockito.any(), Mockito.any());
    Mockito.verifyNoMoreInteractions(rj.s3, rj.tm);
  }

  private static void mockResult(Job p_rj) {
    Mockito.when(p_rj.tm.download((GetObjectRequest)Mockito.any(), Mockito.any(), Mockito.any())).then(inv -> {
      GetObjectRequest req = inv.getArgument(0);
      String key = req.getKey();

      String rnd = UUID.randomUUID().toString();
      File fDummyPlain = new File(p_rj.getScratchDir(), "utest-plain." + key + "." + rnd);
      Files.write(fDummyPlain.toPath(), key.getBytes());
      p_rj.encrypt(fDummyPlain, inv.getArgument(1));
      fDummyPlain.delete();

      return Mockito.mock(Download.class);
    });
  }

  @After
  public void checkMocks() throws IOException {
    FileUtils.deleteDirectory(fTestDir);
  }


}
