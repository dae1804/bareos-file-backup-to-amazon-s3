package com.daveeberhart.bareos_util.secure_s3_storage.progress;

/**
 * @author deberhar
 */
public class CryptoProgressListener extends BaseProgressListener {
  /** 1/2 TB */
  private static final long REPORT_INTERVAL = 512 * 1024 * 1024;

  private long totalBytesProcessed;

  public CryptoProgressListener(String caption, String action, long totalBytes) {
    super(caption, action, totalBytes, REPORT_INTERVAL);
  }

  public void addBytesProcessed(long bytes) {
    totalBytesProcessed += bytes;
    reportProgress(totalBytesProcessed);
  }

}
