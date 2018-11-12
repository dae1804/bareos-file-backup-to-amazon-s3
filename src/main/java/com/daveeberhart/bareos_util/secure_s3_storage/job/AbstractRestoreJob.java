package com.daveeberhart.bareos_util.secure_s3_storage.job;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.GlacierJobParameters;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.RestoreObjectRequest;
import com.amazonaws.services.s3.model.StorageClass;
import com.amazonaws.services.s3.model.Tier;
import com.daveeberhart.bareos_util.secure_s3_storage.error.JobFailedException;
import com.daveeberhart.bareos_util.secure_s3_storage.error.JobFailedException.GlacierRestoreInProgressException;
import com.daveeberhart.bareos_util.secure_s3_storage.progress.AwsProgressListener;

/**
 * Base class for the restore jobs.
 * <p>
 * Handle fetching files back from Amazon Glacier, and all the usual messy downloading and decrypting...
 *
 * @author deberhar
 */
public abstract class AbstractRestoreJob extends Job {
  private static final Pattern KEY_PATTERN = Pattern.compile("bb-([0-9]+)-(.+)\\.enc");

  protected void restore(Map<String,ObjectMetadata> p_objects) {
    Tier restoreTier = Tier.fromValue(System.getProperty("aws.glacier.restoreTier", "Standard"));
    int retentionDays = Integer.getInteger("aws.glacier.restoreRetentionDays", 3);

    List<String> ongoingRestores = new ArrayList<>();
    List<File> alreadyOnDisk = new ArrayList<>();
    List<RestoreVolume> toRestore = new ArrayList<>();
    for (Entry<String, ObjectMetadata> entry : p_objects.entrySet()) {
      RestoreVolume volume = new RestoreVolume(entry.getKey(), entry.getValue());
      ObjectMetadata mdata = entry.getValue();

      if (volume.output.exists()) {
        System.err.println("[" + volume + "] Skipping download; file already present on local disk.");
        alreadyOnDisk.add(volume.output);
        continue;
      }

      if (mdata.getStorageClass() != null && StorageClass.Glacier.toString().equals(mdata.getStorageClass())) {
        if (mdata.getOngoingRestore() != null && mdata.getOngoingRestore()) {
          System.out.println("Restore of object " + entry.getKey() + " from Amazon Glacier is already underway, but not yet complete.");
          ongoingRestores.add(entry.getKey());
        } else {
          RestoreObjectRequest rreq = new RestoreObjectRequest(bucket, entry.getKey());
          rreq.setExpirationInDays(retentionDays);
          rreq.setGlacierJobParameters(new GlacierJobParameters().withTier(restoreTier));
          s3.restoreObjectV2(rreq);
          System.out.println("Started restore of object " + entry.getKey() + " from Amazon Glacier to S3 (eta: " + getRestoreTime(restoreTier) + ")");
          ongoingRestores.add(entry.getKey());
        }
      } else {
        toRestore.add(volume);
      }
    }

    if (!ongoingRestores.isEmpty()) {
      throw new GlacierRestoreInProgressException(
          "Your restore job cannot be completed right now because some of the requested volumes were migrated to Amazon Glacier.\n" +
          "Restores from Glacier were started (or already running) for the following volumes:\n" +
           ongoingRestores + "\n" +
          "\n" +
          "Please try re-running the restore command job after " + getRestoreTime(restoreTier) + ". (but don't wait more than " + retentionDays + " days!)"
      );
    }

    System.out.println("Restoring " + p_objects.size() + " objects from AWS S3 to local disk...");

    List<File> restored = toRestore.parallelStream().map(this::restore).collect(Collectors.toList());
    System.out.println();
    System.out.println("Restore operation has completed successfully!");
    if (!alreadyOnDisk.isEmpty()) {
      System.out.println();
      System.out.println("The following volumes were found on local disk:");
      alreadyOnDisk.stream().forEach(System.out::println);
    }
    if (!restored.isEmpty()) {
      System.out.println();
      System.out.println("The following volumes were restored to local disk:");
      restored.stream().forEach(System.out::println);
    }
    System.out.println();
    System.out.println("You can now start your restore job in the Bareos console!");
    System.out.println();
  }

  protected File restore(RestoreVolume vol) {
    System.out.println("Retrieving: " + vol);

    // Okay, download from AWS to a temp file:
    File fTmp = new File(scratchDir, vol.volumeName + ".enc");
    try {
      System.out.println("Downloading " + vol.key);
      GetObjectRequest req = new GetObjectRequest(bucket, vol.key);
      AwsProgressListener progress = new AwsProgressListener(vol.volumeName, vol.length);
      tm.download(req, fTmp, progress).waitForCompletion();

      System.out.println("Decrypting " + vol.volumeName);
      decrypt(fTmp, vol.output);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      throw new JobFailedException("Thread interrupted while waiting for download", e);
    } finally {
      fTmp.delete();
    }

    System.out.println("[OK] Retrieved " + vol);
    return vol.output;
  }

  private String getRestoreTime(Tier restoreTier) {
    switch (restoreTier) {
    case Bulk:
      return "5-12 hours";
    case Expedited:
      return "1-5 minutes";
    default:
      return "3-5 hours";
    }
  }

  protected class RestoreVolume {
    private final String key;
    private final String jobId;
    private final String volumeName;
    private final File output;
    private final long length;

    public RestoreVolume(String key, ObjectMetadata p_metadata) {
      this.key = key;

      Matcher keyMatcher = KEY_PATTERN.matcher(key);
      if (!keyMatcher.matches()) {
        throw new JobFailedException("Object " + key + " does not match the pattern bb-jobId-VOLUMENAME.enc?!");
      }

      jobId  = keyMatcher.group(1);
      volumeName = keyMatcher.group(2);
      output = new File(scratchDir, volumeName);
      length = p_metadata.getInstanceLength();
    }

    @Override
    public String toString() {
      return "job " + jobId + ", volume " + volumeName;
    }
  }

}
