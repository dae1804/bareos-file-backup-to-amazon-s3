package com.daveeberhart.bareos_util.secure_s3_storage;

import java.io.File;
import java.util.Arrays;

import com.daveeberhart.bareos_util.secure_s3_storage.error.BadArgsException;
import com.daveeberhart.bareos_util.secure_s3_storage.error.JobFailedException;
import com.daveeberhart.bareos_util.secure_s3_storage.job.BackupJob;
import com.daveeberhart.bareos_util.secure_s3_storage.job.Job;
import com.daveeberhart.bareos_util.secure_s3_storage.job.RestoreJobs;
import com.daveeberhart.bareos_util.secure_s3_storage.job.RestoreVolumes;

/**
 * Main class for the utility.
 *
 * @author deberhar
 */
public class Launcher {
  private static final String VERSION = "1.2";

  public static void main(String[] args) {
    new Launcher().run(args);
  }

  public void run(String[] args) {
    System.err.println("Amazon S3 Storage for Bareos backups v." + VERSION);
    System.err.println();

    if (args.length < 2) {
      showUsageAndQuit();
      return;
    }

    try {
      Job job = createJob(args);

      File fScratchDir = new File(args[1]);
      if (!fScratchDir.exists()) {
        throw new BadArgsException("Scratch directory does not exist: " + fScratchDir);
      }
      job.setScratchDir(fScratchDir);


      job.setRemainingArgs(Arrays.asList(args).subList(2, args.length));
      try {
        job.prepare();
        job.run();
      } finally {
        job.cleanup();
      }
      System.out.println("Job execution completed normally.");
    } catch (BadArgsException e) {
      System.err.println(e.getMessage());
      System.err.println();
      showUsageAndQuit();
    } catch (JobFailedException e) {
      System.out.flush();
      System.err.println();
      System.err.println("Job execution FAILED with the following error:");
      System.err.println(e.getMessage());

      if (Boolean.getBoolean("verbose")) {
        e.printStackTrace(System.err);
      }
      System.err.flush();

      exit(66);
    } catch (Exception e) {
      System.out.flush();
      System.err.println();
      System.err.println("Job execution FAILED with the following exception:");
      e.printStackTrace(System.err);
      System.err.flush();

      exit(99);
    }
  }

  protected Job createJob(String[] args) {
    Job job;
    switch (args[0].toLowerCase()) {
    case "backup":
      job = new BackupJob();
      break;
    case "restore-volumes":
      job = new RestoreVolumes();
      break;
    case "restore-jobs":
      job = new RestoreJobs();
      break;
    default:
      throw new BadArgsException("Unrecognized action: " + args[0]);
    }
    return job;
  }

  private void showUsageAndQuit() {
    System.err.println("Move Bareos file-backup volumes into the Amazon S3 storage cloud, or copy them from S3 back to local disk.");
    System.err.println();
    System.err.println("Usage:");
    System.err.println("  Backup:  `java -jar BareosS3-all.jar backup /path/to/scratch/dir 123 volume1 [volume2 [volume3 [...]]]`");
    System.err.println("    -or-");
    System.err.println("  Restore: `java -jar BareosS3-all.jar restore-volumes /path/to/scratch/dir 234-volume1 [345-volume2 [456-volume3 [...]]]`");
    System.err.println("    -or-");
    System.err.println("  Restore: `java -jar BareosS3-all.jar restore-jobs /path/to/scratch/dir 234 [345 [456 [...]]]`");
    System.err.println("Where:");
    System.err.println("  backup/restore is the action to take");
    System.err.println("  /path/to/scratch/dir is the path you specified in the Bareos sd config");
    System.err.println("  123 is the ID of the Bareos backup job");
    System.err.println("  volume1 (etc) are the name(s) of the Bareos disk volume file(s)");
    System.err.println("  234 (etc) are the ID(s) of the Bareos job(s) to restore disk volumes for");
    System.err.println("");
    exit(1);
  }

  protected void exit(int returnCode) {
    System.exit(returnCode);
  }
}
