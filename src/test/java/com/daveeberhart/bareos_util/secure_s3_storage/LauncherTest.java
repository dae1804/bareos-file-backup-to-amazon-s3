package com.daveeberhart.bareos_util.secure_s3_storage;

import java.io.File;
import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import com.daveeberhart.bareos_util.secure_s3_storage.error.BadArgsException;
import com.daveeberhart.bareos_util.secure_s3_storage.error.JobFailedException;
import com.daveeberhart.bareos_util.secure_s3_storage.job.BackupJob;
import com.daveeberhart.bareos_util.secure_s3_storage.job.Job;

/**
 * @author deberhar
 */
public class LauncherTest {
  private Launcher launcher = Mockito.spy(Launcher.class);

  public LauncherTest() {
    Mockito.doNothing().when(launcher).exit(Mockito.anyInt());
  }

  @Test
  public void testNoArgs() {
    launcher.run(new String[] { });

    Mockito.verify(launcher).run(Mockito.any());
    Mockito.verify(launcher).exit(1);
    Mockito.verifyNoMoreInteractions(launcher);
  }

  @Test(expected=BadArgsException.class)
  public void testBadJob() {
    launcher.createJob(new String[] { "badjob"});
  }

  @Test
  public void testGoodJob() {
    Assert.assertSame(BackupJob.class, launcher.createJob(new String[] { "backup"}).getClass());
  }

  @Test
  public void testBogusPath() {
    String[] args = {"myjob", "/does/not/exist"};

    Job job = Mockito.mock(Job.class);
    Mockito.doReturn(job).when(launcher).createJob(args);
    launcher.run(args);

    Mockito.verify(launcher).run(Mockito.any());
    Mockito.verify(launcher).createJob(Mockito.any());
    Mockito.verify(launcher).exit(1);
    Mockito.verifyNoMoreInteractions(launcher, job);
  }

  @Test
  public void testStandardExec() {
    String[] args = {"myjob", "/"};

    Job job = Mockito.mock(Job.class);
    Mockito.doReturn(job).when(launcher).createJob(args);
    launcher.run(args);

    Mockito.verify(launcher).run(Mockito.any());
    Mockito.verify(launcher).createJob(Mockito.any());
    Mockito.verify(job).setScratchDir(new File("/"));
    Mockito.verify(job).setRemainingArgs(Arrays.asList());
    Mockito.verify(job).prepare();
    Mockito.verify(job).run();
    Mockito.verify(job).cleanup();
    Mockito.verifyNoMoreInteractions(launcher, job);
  }

  @Test
  public void testBadJobArgs() {
    String[] args = {"myjob", "/"};

    Job job = Mockito.mock(Job.class);
    Mockito.doReturn(job).when(launcher).createJob(args);
    Mockito.doThrow(new BadArgsException("Bad args here!")).when(job).setRemainingArgs(Mockito.any());
    launcher.run(args);

    Mockito.verify(launcher).run(Mockito.any());
    Mockito.verify(launcher).createJob(Mockito.any());
    Mockito.verify(job).setScratchDir(new File("/"));
    Mockito.verify(job).setRemainingArgs(Arrays.asList());
    Mockito.verify(launcher).exit(1);
    Mockito.verifyNoMoreInteractions(launcher, job);
  }

  @Test
  public void testExecFailsWithError() {
    String[] args = {"myjob", "/"};

    Job job = Mockito.mock(Job.class);
    Mockito.doReturn(job).when(launcher).createJob(args);
    Mockito.doThrow(new JobFailedException("kaBOOM, dead!")).when(job).run();
    launcher.run(args);

    Mockito.verify(launcher).run(Mockito.any());
    Mockito.verify(launcher).createJob(Mockito.any());
    Mockito.verify(job).setScratchDir(new File("/"));
    Mockito.verify(job).setRemainingArgs(Arrays.asList());
    Mockito.verify(job).prepare();
    Mockito.verify(job).run();
    Mockito.verify(job).cleanup();
    Mockito.verify(launcher).exit(66);
    Mockito.verifyNoMoreInteractions(launcher, job);
  }

  @Test
  public void testExecFailsWithException() {
    String[] args = {"myjob", "/"};

    Job job = Mockito.mock(Job.class);
    Mockito.doReturn(job).when(launcher).createJob(args);
    Mockito.doThrow(new RuntimeException("kaBOOM, dead!")).when(job).prepare();
    launcher.run(args);

    Mockito.verify(launcher).run(Mockito.any());
    Mockito.verify(launcher).createJob(Mockito.any());
    Mockito.verify(job).setScratchDir(new File("/"));
    Mockito.verify(job).setRemainingArgs(Arrays.asList());
    Mockito.verify(job).prepare();
    Mockito.verify(job).cleanup();
    Mockito.verify(launcher).exit(99);
    Mockito.verifyNoMoreInteractions(launcher, job);
  }

}
