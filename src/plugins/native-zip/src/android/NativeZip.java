package com.foxdebug.acode.rk.zip;

import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.cordova.*;
import org.json.*;

public class NativeZip extends CordovaPlugin {

  @Override
  public boolean execute(
    String action,
    CordovaArgs args,
    CallbackContext callbackContext
  ) throws JSONException {
    cordova.getThreadPool().execute(() -> {
      try {
        switch (action) {
          case "extractZipFileToDir":
            extractZipFileToDir(
              args.getString(0),
              args.getString(1),
              callbackContext
            );
            break;
          case "compressDirToZipFile":
            compressDirToZipFile(
              args.getString(0),
              args.getString(1),
              callbackContext
            );
            break;
          default:
            callbackContext.error("Invalid action: " + action);
        }
      } catch (Exception e) {
        callbackContext.error(e.getMessage());
      }
    });

    return true;
  }

  private void extractZipFileToDir(
    String zipFilePath,
    String destDirPath,
    CallbackContext callbackContext
  ) {
    try {
      File destDir = new File(destDirPath);
      if (!destDir.exists()) {
        destDir.mkdirs();
      }

      String canonicalDest = destDir.getCanonicalPath();

      try (
        ZipInputStream zis = new ZipInputStream(
          new FileInputStream(zipFilePath)
        )
      ) {
        ZipEntry entry;
        byte[] buffer = new byte[8192];

        while ((entry = zis.getNextEntry()) != null) {
          File outFile = new File(destDir, entry.getName());

          // Zip slip protection
          if (
            !outFile
              .getCanonicalPath()
              .startsWith(canonicalDest + File.separator)
          ) {
            callbackContext.error("Zip slip detected: " + entry.getName());
            return;
          }

          if (entry.isDirectory()) {
            outFile.mkdirs();
          } else {
            outFile.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
              int len;
              while ((len = zis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
              }
            }
          }

          zis.closeEntry();
        }
      }

      callbackContext.success();
    } catch (IOException e) {
      callbackContext.error("Extract failed: " + e.getMessage());
    }
  }

  private void compressDirToZipFile(
    String dirPath,
    String zipFilePath,
    CallbackContext callbackContext
  ) {
    try {
      File sourceDir = new File(dirPath);
      if (!sourceDir.exists() || !sourceDir.isDirectory()) {
        callbackContext.error("Source directory does not exist: " + dirPath);
        return;
      }

      try (
        ZipOutputStream zos = new ZipOutputStream(
          new FileOutputStream(zipFilePath)
        )
      ) {
        compressDir(sourceDir, sourceDir.getName(), zos);
      }

      callbackContext.success();
    } catch (IOException e) {
      callbackContext.error("Compress failed: " + e.getMessage());
    }
  }

  private void compressDir(File dir, String baseName, ZipOutputStream zos)
    throws IOException {
    File[] files = dir.listFiles();
    if (files == null) return;

    byte[] buffer = new byte[8192];

    for (File file : files) {
      String entryName = baseName + "/" + file.getName();

      if (file.isDirectory()) {
        ZipEntry dirEntry = new ZipEntry(entryName + "/");
        zos.putNextEntry(dirEntry);
        zos.closeEntry();
        compressDir(file, entryName, zos);
      } else {
        ZipEntry entry = new ZipEntry(entryName);
        entry.setSize(file.length());
        entry.setLastModifiedTime(
          java.nio.file.attribute.FileTime.fromMillis(file.lastModified())
        );
        zos.putNextEntry(entry);

        try (FileInputStream fis = new FileInputStream(file)) {
          int len;
          while ((len = fis.read(buffer)) > 0) {
            zos.write(buffer, 0, len);
          }
        }

        zos.closeEntry();
      }
    }
  }
}
