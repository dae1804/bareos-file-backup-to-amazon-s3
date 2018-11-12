package com.daveeberhart.bareos_util.secure_s3_storage.job;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.StorageClass;
import com.amazonaws.services.s3.transfer.model.UploadResult;
import com.daveeberhart.bareos_util.secure_s3_storage.error.BadArgsException;
import com.daveeberhart.bareos_util.secure_s3_storage.error.JobFailedException;
import com.daveeberhart.bareos_util.secure_s3_storage.error.JobFailedException.VolumeMissingException;
import com.daveeberhart.bareos_util.secure_s3_storage.progress.AwsProgressListener;

/**
 * Move backup volumes into Amazon S3, deleting them off disk in the case of a successful upload.
 * <p>
 * Important:
 * <ul>
 * <li>In Bareos:<ol>
 *   <li>Make sure to limit your volumes to one job per volume</li>
 *   <li>Call the S3 client as the successful-post-run job script on the relevant job resource(s)</li>
 * </ol></li>
 * <li>In Amazon, create a dedicated S3 storage bucket:<ol>
 *   <li>Make sure to set an expiration (under lifecycle policy) on your bucket!</li>
 *   <li>If you plan to keep backups for 90+ days, consider setting a transition to Glacier-tier storage as well.</li>
 * </ol></li>
 * </ul>
 *
 * @author deberhar
 */
public class BackupJob extends Job {
  private String jobId;
  protected List<String> volumeNames;

  /* (non-Javadoc)
   * @see com.daveeberhart.bareos_util.secure_s3_storage.job.Job#setRemainingArgs(java.util.List)
   */
  @Override
  public void setRemainingArgs(List<String> p_args) {
    if (p_args.size() < 1) {
      throw new BadArgsException("Missing required Job ID");

    }

    jobId = p_args.get(0);
    if (!jobId.matches("[0-9]+")) {
      throw new BadArgsException("Job ID must be numeric; was " + jobId);
    }

    volumeNames = p_args
        .subList(1, p_args.size())
        .stream()
        .filter(arg -> arg != null)
        .flatMap(arg -> Arrays.stream(arg.split(" *\\| *")))
        .map(String::trim)
        .filter(arg -> arg.length() > 0)
        .collect(Collectors.toList());
  }

  /* (non-Javadoc)
   * @see com.daveeberhart.bareos_util.secure_s3_storage.job.Job#run()
   */
  @Override
  public void run() {
    System.out.println("Now uploading volumes " + volumeNames);
    volumeNames.parallelStream().forEach(this::uploadAndRemove);
    System.out.println("Done uploading " + volumeNames.size() + " volumes...");
  }

  private void uploadAndRemove(String volume) {
    File fSrc = new File(scratchDir, volume);
    if (!fSrc.exists()) {
      throw new VolumeMissingException("Could not find volume " + volume + " in " + scratchDir);
    }

    String bucketKey = "bb-" + jobId + "-" + volume + ".enc";
    File fEncrypted = new File(scratchDir, bucketKey);
    try {
      System.out.println("Encrypting volume " + volume);
      encrypt(fSrc, fEncrypted);

      System.out.println("Uploading volume " + volume + " as " + bucketKey);
      PutObjectRequest req = new PutObjectRequest(bucket, bucketKey, fEncrypted);
      req.setStorageClass(StorageClass.OneZoneInfrequentAccess);

      AwsProgressListener progress = new AwsProgressListener(bucketKey, fEncrypted.length());
      UploadResult res = tm.upload(req, progress).waitForUploadResult();
      progress.done();
      System.out.println("[OK] Uploaded " + volume + " as " + res.getETag());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      throw new JobFailedException("Thread interrupted while waiting for upload", e);
    } finally {
      fEncrypted.delete();
    }

    // OK, successful upload, delete source file:
    fSrc.delete();

  }

}
