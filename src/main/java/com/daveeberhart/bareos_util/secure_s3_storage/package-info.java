/**
 * A utility to store file-based Bareos backups into Amazon S3 storage buckets,
 * and retrieve them on demand.
 * <p>
 * Backups are encrypted locally using AES-GCM prior to upload to protect your privacy.
 */
package com.daveeberhart.bareos_util.secure_s3_storage;