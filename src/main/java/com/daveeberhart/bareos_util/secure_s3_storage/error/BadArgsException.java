package com.daveeberhart.bareos_util.secure_s3_storage.error;

/**
 * Causes launcher to print this error, the commandline usage, and then exit.
 *
 * @author deberhar
 */
public class BadArgsException extends RuntimeException {

  public BadArgsException(String p_mesg) {
    super(p_mesg);
  }

}
