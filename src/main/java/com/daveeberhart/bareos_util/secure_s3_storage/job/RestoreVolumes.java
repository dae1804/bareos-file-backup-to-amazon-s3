package com.daveeberhart.bareos_util.secure_s3_storage.job;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.daveeberhart.bareos_util.secure_s3_storage.error.JobFailedException.VolumeMissingException;

/**
 * Restore a list of jobId-VOLNAME tuples.
 *
 * @author deberhar
 */
public class RestoreVolumes extends AbstractRestoreJob {
  private List<String> jobDashVolumeNames;

  /* (non-Javadoc)
   * @see com.daveeberhart.bareos_util.secure_s3_storage.job.Job#setRemainingArgs(java.util.List)
   */
  @Override
  public void setRemainingArgs(List<String> p_args) {
    jobDashVolumeNames = p_args;
  }

  /* (non-Javadoc)
   * @see com.daveeberhart.bareos_util.secure_s3_storage.job.Job#run()
   */
  @Override
  public void run() {
    List<String> notFound = new ArrayList<>();
    Map<String,ObjectMetadata> objectsToRestore = new LinkedHashMap<>();

    System.out.println("Now checking status of S3 objects: " + jobDashVolumeNames);
    for (String volume : jobDashVolumeNames) {
      String key = "bb-" + volume + ".enc";
      try {
        ObjectMetadata mdata = s3.getObjectMetadata(bucket, key);
        objectsToRestore.put(key, mdata);
      } catch (AmazonS3Exception e) {
        if (e.getMessage() != null && e.getMessage().startsWith("Not Found")) {
          notFound.add(key);
        } else {
          throw e;
        }
      }
    }

    if (!notFound.isEmpty()) {
      throw new VolumeMissingException(
          "Your restore operation could not be completed because the following S3 objects could not be found:\n" +
          notFound.stream().collect(Collectors.joining("\n  ", "  ", "")) + "\n" +
          "\n" +
          "Check for typos in the jobId-VOLNAME pairs listed above.\n" +
          "Also, check your Amazon S3 bucket's configured retention policy to make sure your objects weren't deleted early.\n" +
          "Unfortunately, in the latter case, this error means that your data is already gone."
      );
    }

    restore(objectsToRestore);
  }



}
