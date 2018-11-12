package com.daveeberhart.bareos_util.secure_s3_storage.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.daveeberhart.bareos_util.secure_s3_storage.error.BadArgsException;
import com.daveeberhart.bareos_util.secure_s3_storage.error.JobFailedException.JobNotFoundException;

/**
 * Restore all volumes uploaded as part of a list of jobIds.
 *
 * @author deberhar
 */
public class RestoreJobs extends AbstractRestoreJob {
  private List<String> jobIds;

  /* (non-Javadoc)
   * @see com.daveeberhart.bareos_util.secure_s3_storage.job.Job#setRemainingArgs(java.util.List)
   */
  @Override
  public void setRemainingArgs(List<String> p_args) {
    jobIds = p_args;

    for (String jobId : p_args) {
      if (!jobId.matches("[0-9]+")) {
        throw new BadArgsException("Malformed jobId (should be numeric): " + jobId);
      }
    }
  }

  /* (non-Javadoc)
   * @see com.daveeberhart.bareos_util.secure_s3_storage.job.Job#run()
   */
  @Override
  public void run() {
    System.out.println("Now searching for S3 objects for job(s): " + jobIds);
    List<String> keys = new ArrayList<>();
    for (String jobId : jobIds) {
      ObjectListing listing = s3.listObjects(bucket, "bb-" + jobId + "-");
      if (listing.getObjectSummaries().isEmpty() && !listing.isTruncated()) {
        throw new JobNotFoundException("Could not find any volumes for job " + jobId);
      }

      listing.getObjectSummaries().stream().map(S3ObjectSummary::getKey).forEach(keys::add);
      while (listing.isTruncated()) {
        listing = s3.listNextBatchOfObjects(listing);
        listing.getObjectSummaries().stream().map(S3ObjectSummary::getKey).forEach(keys::add);
      }
    }

    System.out.println("Found the following " + keys.size() + " objects to be restored:");
    for (String key : keys) {
      System.out.println("  " + key);
    }
    System.out.println();

    System.out.println("Checking statuses of the objects...");
    Map<String, ObjectMetadata> objects = keys.parallelStream().collect(Collectors.toMap(
        key -> key,
        key -> s3.getObjectMetadata(bucket, key)
    ));
    restore(objects);
  }



}
