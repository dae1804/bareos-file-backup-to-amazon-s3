package com.daveeberhart.bareos_util.secure_s3_storage.error;

/**
 * @author deberhar
 *
 */
public class JobFailedException extends RuntimeException {

  public JobFailedException(String p_mesg) {
    super(p_mesg);
  }

  public JobFailedException(String p_mesg, Exception e) {
    super(p_mesg, e);
  }

  public static class VolumeMissingException extends JobFailedException {
    public VolumeMissingException(String p_mesg) {
      super(p_mesg);
    }
  }

  public static class JobNotFoundException extends JobFailedException {
    public JobNotFoundException(String p_mesg) {
      super(p_mesg);
    }
  }

  public static class GlacierRestoreInProgressException extends JobFailedException {
    public GlacierRestoreInProgressException(String p_mesg) {
      super(p_mesg);
    }
  }

  public static class IntegrityCheckFailedException extends JobFailedException {
    public IntegrityCheckFailedException(String p_mesg, Exception p_e) {
      super(p_mesg, p_e);
    }
  }

}
