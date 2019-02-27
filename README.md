# What is this?

A command-line utility for encrypting backup files, and uploading them to the Amazon S3 storage cloud.  It can also retrieve files from the cloud, and decrypt/restore them back to local disk.

### Why is this?

I use [Bareos](https://www.bareos.org/) to back up to tape and local disk.  I wanted to be able to upload some of these disk-based backup files to the S3 cloud for off-site protection.

There are other solutions for storing the files offsite (including Bareos' new S3 integration),
however none of them provided client-managed encryption (you had to store your data in S3 in the 
clear, or use cloud-managed encryption keys).

### What does it do?

* Uploads files into a dedicated Amazon S3 bucket.
** Can be automated by invoking this utility as a "post-run script"
* Retrieve files by jobId+volume name, or retrieve all files for a given jobId.
* Encrypts files using AES-GCM (for confidentiality, integrity and authenticity protections).
  - All encryption is performed locally, and chains back to a passphrase in a local config file.
  - Amazon cannot read your backups.

### What doesn't it do?

* Automatic retrieval of files during restores.
  - You need to manually fetch the needed volumes back to local disk using the command-line client before starting your restore job.
  - Possible future enhancement: automate this via a storage daemon plugin.
* Archiving/expiration of objects in the Amazon S3 bucket.
  - But you can configure a retention/expiration policy for your bucket directly in the Amazon S3 console.

### How are my backups protected?

Using state-of-the-art cryptographic algorithms that protect against both someone being able to read your files, or tamper with them (at least, not without you noticing).  Keys are derived from a password-like `encryption.key` setting you specify in your config file.  All of the encryption is done before upload to S3; the encryption key does not leave your own computer.

It's worth noting that if you lose your `encryption.key`, your backups become worthless.  Consider backing it up somewhere in hardcopy -- say, on paper in a safe-deposit box.

For the crypto geeks:

In your configuration file, you specify a password-like `encryption.key`.  This is used to derive a "master key" (the key-encryption key, or KEK) that protects the session keys for each file.  The master key is derived using 50,000 iterations of [PBKDF2](https://en.wikipedia.org/wiki/PBKDF2).  This exceeds the relevant [NIST recommendations for key derivation](https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-132.pdf).

Individual files are protected using [AES](https://en.wikipedia.org/wiki/Advanced_Encryption_Standard) in [GCM mode](https://en.wikipedia.org/wiki/Galois/Counter_Mode), an [AEAD](https://en.wikipedia.org/wiki/Authenticated_encryption) cipher.  Each uploaded file receives a randomly-generated nonce and "session" (AES-GCM) key.  The session key is encrypted with the master key (KEK) using [AESWrap](https://tools.ietf.org/html/rfc3394); the nonce, encrypted session key, encrypted data and a 128-bit auth tag are saved into the uploaded file.

# Building

To begin, you will need [Git](https://git-scm.com/), and the [Java JDK](https://jdk.java.net/) 1.8 or later installed on your system.  Check that you can run the following commands:

```
git --version
javac -version
```

If not, install the commands using your distribution's package manager (apt-get, yum, etc), or manually download them and add them to your `PATH`.

Now, check out and build the project:

```bash
git clone https://github.com/dae1804/bareos-file-backup-to-amazon-s3.git
cd bareos-file-backup-to-amazon-s3/
./gradlew shadowJar
```

Windows users should instead run:

```bash
git clone https://github.com/dae1804/bareos-file-backup-to-amazon-s3.git
cd bareos-file-backup-to-amazon-s3
gradlew.bat shadowJar
```

The result will be an all-in-one executable JAR file located under `build/libs/`.

# Setup

### Amazon AWS Setup

1. Create a new S3 storage bucket to receive your backup files.  AWS S3 console: https://s3.console.aws.amazon.com/s3/home
1. View your new bucket, then click on the Management tab.  Add a lifecycle rule to expire uploaded content after some time (you can also set a lifecycle operation to move your backups into Amazon Glacier.  If you plan to keep your backups for 90+ days, this is a cheaper option with regards to monthly storage costs... but you'll pay more if you actually need to retrieve the data).
1. Create a new user in AWS IAM.  Grant the user full read/write on your bucket (to do this the "right" way, IIRC, you'd create a custom policy allowing access to the S3 bucket, assign it to a group, and put your new user in that group.  Or, if you only have the one S3 bucket, you could just assign the predefined `AmazonS3FullAccess ` policy).
1. For the new user, under "Security Credentials," create a new access key.  Make a note of the access key ID and secret key ID; you'll need them later.

### SecureS3StorageForBareos Tool Setup

1. Copy `SecureS3StorageForBareos-all.jar` to your Baros (or backup) server, e.g. under `/opt/`.  This is the JAR file you produced during the build step, above, under `build/libs/`.
1. Make sure that `java` 1.8 or later is installed on your server.
1. Try running the tool: `java -jar /opt/SecureS3StorageForBareos-all.jar`
1. Copy `s3-storage.properties.sample` from the root of this project to `/etc/bareos/s3-storage.properties` on your backup server.
1. Fill in values for the four AWS properties using the values from the AWS Setup section above.  Follow the instructions in the file.
1. Fill in a new, random password as `encryption.key`.
1. Save a copy of all of the information in  `s3-storage.properties` in a secure place.

Note that you should keep a copy of the information in `s3-storage.properties` in a secure, **offsite** location.  Your offsite backups won't help you if you can't access or decrypt them!

### Bareos Setup
1. Create a new directory to receive your uploads to S3 (and downloads retrieved from S3)
```
mkdir /var/lib/bareos/storage/s3
chown bareos:bareos /var/lib/bareos/storage/s3
```

2. Create a new storage daemon device under `/etc/bareos/bareos-sd.d/device`, e.g.
```
Device {
  Name = Offsite-S3;
  Media Type = File;
  Label Media = yes;
  Random Access = yes;
  AutomaticMount = yes;
  RemovableMedia = no;
  AlwaysOpen = no;
  Collect Statistics = yes
  
  # Shouldn't exceed what you set on the Pool resource below.
  Maximum Filesize = 10g;
  
  # Must be a folder Bareos has permission to write to:
  Archive Device = /var/lib/bareos/storage/s3;
}
```

3. Create a new director storage definition under `/etc/bareos/bareos-dir.d/storage`, e.g.
```
Storage {
  Name = localhost-sd-Offsite-S3
  Address = 127.0.0.1
  Password = "change this to match the password in /etc/bareos/bareos-sd.d/director/bareos-dir.conf"
  Media Type = File
  Maximum Concurrent Jobs = 4
  Collect Statistics = yes
  Allow Compression = Yes
  
  # Must match the value from the file you created under /etc/bareos/bareos-sd.d/device:
  Device = Offsite-S3
}
```

4. Create a new pool under `/etc/bareos/bareos-dir.d/pool`, e.g.
```
Pool {
  Name = Offsite-S3
  Pool Type = Backup
  Auto Prune = yes
  
  # Set these to match your expiration policy in AWS S3:
  File Retention   = 90 days
  Job Retention    = 90 days
  Volume Retention = 90 days
  
  # Must match the value in the file under /etc/bareos/bareos-dir.d/storage/
  Storage = localhost-sd-Offsite-S3
  
  # Controls naming of the individual backup volume files.
  Label Format = "S3OFFSITE-"
  
  # Set this to 60g or less.
  # The upload tool can't handle files larger than 64GB.
  Maximum Volume Bytes = 10g
  
  # Don't try to write to the volume more than once, as
  # we'll upload it and delete the local file after.
  Maximum Volume Jobs = 1
  Recycle = no
}
```

5. Create a JobDef under X, e.g.
```
JobDefs {
  Name = "Offsite-S3"
  Type = Backup
  Level = Incremental
  Messages = Standard
  Priority = 5
  Write Bootstrap = "/var/lib/bareos/%c_%n.bsr"
  Spool Data = no
  Prune Files = yes
  Prune Jobs = yes
  Prune Volumes = yes
  
  # Must match the value on the pool resource under /etc/bareos/bareos-dir.d/pool/
  Pool = "Offsite-S3"
  
  # Must match the value in the director-storage resource under /etc/bareos/bareos-dir.d/storage/
  Storage = localhost-sd-Offsite-S3
  
  # This is where the magic happens:
  # Tell Bareos to run a command to upload your backups to the cloud after they complete:
  RunScript {
    RunsWhen = After
    RunsOnSuccess = Yes
    RunsOnFailure = No
    FailJobOnError = Yes
    Runs On Client = No
    
    # Paths might need updated here, if you changed them above:
    Command  = "/usr/bin/java -jar /opt/SecureS3StorageForBareos-all.jar backup /var/lib/bareos/storage/s3 %i '%V'"
  }
}
```

6. Now you can write some standard Bareos jobs, and specify `JobDefs = Offsite-S3` to have them upload to AWS S3.  When you've finished your configuration, restart Bareos:
```
systemctl restart bareos-sd
systemctl restart bareos-dir
```
and fix any errors reported (might need to check `journalctl`).

7. Run your new backup job(s), and verify that they successfully uploaded to S3.  Upload progress (and any errors) should be visible in `bconsole` messages, and in the Bareos webinterface, under the logs for your backup job).

# Restoring from S3

Unfortunately, restoring from backups currently requires some manual command-line prep before you can kick off the restore in Bareos.

Restores look like this:

1. Use `bconsole` (or the WebUI) to figure out what jobs you want to restore from.  You should end up with one or more numeric job IDs you'll need to fetch backup volumes for.  **Don't start the restore job yet!**
1. Use this tool to retrieve the backup volumes.  E.g. to retrieve all volumes for jobs 123 and 456, run: `java -jar /opt/SecureS3StorageForBareos-all.jar restore-jobs /var/lib/bareos/storage/s3 123 456`
1. Wait for the download and decryption to finish.
1. Start the restore job in Bareos.

When the Bareos restore job is completed, you should delete the retrieved volume files (neither Bareos nor this tool will automatically delete them for you).

### Restoring from Amazon Glacier

If you migrated some of your backups into Glacier, the `restore-jobs` command will automatically start retrieval of the files from Glacier into your S3 bucket, and then fail with an explanatory message.  You should re-run the `restore-jobs` command after 3-5 hours, at which point your backups should be available.  **Please note that there are non-trivial charges for retrieving files from Glacier**, so make sure you really need the files before you run the restore command.

Files retrieved from Glacier are kept in your S3 bucket for three days, before they revert back to Glacier-only (and there's another charge and delay for retrieval).

You can add the following properties in your s3-storage.properties file, and change them to override this default behavior:

```properties
# Number of days to keep restored files in S3 before another Glacier retrieval is required: 
aws.glacier.restoreRetentionDays=3

# How fast (expensive) should the retrieval from Glacier be?
# Bulk: takes longer, but a bit cheaper than Standard
# Expedited: takes only a few minutes, but pricy
aws.glacier.restoreTier=Standard
```
