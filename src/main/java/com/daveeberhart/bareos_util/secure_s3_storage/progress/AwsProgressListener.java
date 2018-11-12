package com.daveeberhart.bareos_util.secure_s3_storage.progress;

import com.amazonaws.event.ProgressEvent;
import com.amazonaws.services.s3.transfer.PersistableTransfer;
import com.amazonaws.services.s3.transfer.internal.S3ProgressListener;

/**
 * @author deberhar
 */
public class AwsProgressListener extends BaseProgressListener implements S3ProgressListener  {
  /** 200MB */
  private static final long REPORT_INTERVAL = 200 * 1024 * 1024;

  private long totalBytesProcessed;

  public AwsProgressListener(String caption, long totalBytes) {
    super(caption, "Upload", totalBytes, REPORT_INTERVAL);
  }

  /* (non-Javadoc)
   * @see com.amazonaws.event.ProgressListener#progressChanged(com.amazonaws.event.ProgressEvent)
   */
  @Override
  public void progressChanged(ProgressEvent p_progressEvent) {
    totalBytesProcessed += p_progressEvent.getBytesTransferred();
    reportProgress(totalBytesProcessed);
  }

  /* (non-Javadoc)
   * @see com.amazonaws.services.s3.transfer.internal.S3ProgressListener#onPersistableTransfer(com.amazonaws.services.s3.transfer.PersistableTransfer)
   */
  @Override
  public void onPersistableTransfer(PersistableTransfer p_persistableTransfer) {
    // Nop.
  }


}
