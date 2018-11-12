package com.daveeberhart.bareos_util.secure_s3_storage.progress;

/**
 * Progress listener that outputs to the console.
 *
 * @author deberhar
 */
public class BaseProgressListener {
  private static final boolean HAS_CONSOLE = !Boolean.getBoolean("nohup");
  private final long reportInterval;
  private final String action;
  private final String caption;
  private final long totalBytes;

  private long nextReport;
  private boolean reportedAnything = false;
  private boolean hit100 = false;

  public BaseProgressListener(String caption, String action, long totalBytes, long reportInterval) {
    this.reportInterval = reportInterval;
    this.caption = caption;
    this.action = action;
    this.totalBytes = Math.max(1, totalBytes); // Cheat a bit and avoid div/0 errors.
    nextReport = reportInterval;
  }

  protected void reportProgress(long totalBytesProcessed) {
    if (nextReport <= totalBytesProcessed) {
      reportedAnything = true;
      nextReport = totalBytesProcessed + reportInterval;

      double dPercent = ((100d * totalBytesProcessed) / totalBytes);
      long lPercent = Math.round(dPercent);
      StringBuilder sb = new StringBuilder(79);
      sb.append("[").append(caption).append("] ").append(action).append(" ");

      if (lPercent == 100) {
        hit100 = true;
      } else {
        sb.append(" ");
      }
      if (lPercent < 10) {
        sb.append(" ");
      }
      sb.append(lPercent).append("%");

      if (HAS_CONSOLE)  {
        sb.append(" [");
        int barWidth = 79 - (sb.length()+1);
        int cutoff = (int)(barWidth * dPercent / 100d);
        for (int i = 0; i < barWidth; i++) {
          sb.append(i <= cutoff ? '=' : ' ');
        }
        sb.append("]");
      }

      System.out.println(sb);
    }
  }

  public void done() {
    if (reportedAnything && !hit100) {
      // If we made any prior reports, force a report of 100% since we're done...
      nextReport = 0;
      reportProgress(totalBytes);
    }
  }

}
