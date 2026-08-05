/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.onboarding;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Discovers and opens the GOClient onboarding sourcedata XML files, whether bundled on the
 * runtime classpath or read from a filesystem directory.
 */
final class OnboardingSourceFiles {

  private static final String SAMPLE_DATA_RESOURCE_ROOT =
      "com/etendoerp/go/onboarding/sampledata";
  private static final String SAMPLE_DATA_RESOURCE_DIRECTORY =
      SAMPLE_DATA_RESOURCE_ROOT + "/GOClient";
  private static final String SAMPLE_DATA_INDEX_RESOURCE =
      SAMPLE_DATA_RESOURCE_ROOT + "/index.txt";
  private static final String RESOURCE_PATH_SEPARATOR = "/";

  private OnboardingSourceFiles() {
    // Utility class.
  }

  static ClassLoader defaultClassLoader() {
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    return contextClassLoader != null
        ? contextClassLoader
        : OnboardingSourceFiles.class.getClassLoader();
  }

  static String tableName(String sourceFileName) {
    int suffix = sourceFileName.lastIndexOf('.');
    return suffix == -1 ? sourceFileName : sourceFileName.substring(0, suffix);
  }

  static SourceFileProvider directorySourceFileProvider(Path sampleDataDirectory) {
    return () -> {
      List<SourceFile> files = new ArrayList<>();
      try (var stream = Files.list(sampleDataDirectory)) {
        stream.filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().endsWith(".xml"))
            .filter(path -> OnboardingDatasetDefinition.shouldIncludeTable(
                tableName(path.getFileName().toString())))
            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
            .forEach(path -> files.add(new SourceFile(path.getFileName().toString(),
                () -> openFileSystemSourceFile(path))));
      } catch (Exception e) {
        throw new OnboardingDatasetNormalizationException(
            "Failed to list onboarding sourcedata in " + sampleDataDirectory, e);
      }
      return files;
    };
  }

  static SourceFileProvider classpathSourceFileProvider(ClassLoader classLoader) {
    Objects.requireNonNull(classLoader, "classLoader is required");
    return () -> {
      List<SourceFile> files = new ArrayList<>();
      for (String fileName : readBundledSourceFileNames(classLoader)) {
        if (fileName.endsWith(".xml")
            && OnboardingDatasetDefinition.shouldIncludeTable(tableName(fileName))) {
          files.add(new SourceFile(fileName, () -> openBundledSourceFile(classLoader, fileName)));
        }
      }
      files.sort(Comparator.comparing(sourceFile -> sourceFile.fileName));
      return files;
    };
  }

  private static List<String> readBundledSourceFileNames(ClassLoader classLoader) {
    try (InputStream inputStream = classLoader.getResourceAsStream(SAMPLE_DATA_INDEX_RESOURCE)) {
      if (inputStream == null) {
        throw new OnboardingDatasetNormalizationException(
            "Bundled GOClient sampledata index not found on the classpath: "
                + SAMPLE_DATA_INDEX_RESOURCE);
      }

      List<String> fileNames = new ArrayList<>();
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          String fileName = line.trim();
          if (!fileName.isEmpty()) {
            fileNames.add(fileName);
          }
        }
      }

      if (fileNames.isEmpty()) {
        throw new OnboardingDatasetNormalizationException(
            "Bundled GOClient sampledata index is empty: " + SAMPLE_DATA_INDEX_RESOURCE);
      }
      return fileNames;
    } catch (IOException e) {
      throw new OnboardingDatasetNormalizationException(
          "Failed to read bundled GOClient sampledata index " + SAMPLE_DATA_INDEX_RESOURCE, e);
    }
  }

  private static InputStream openFileSystemSourceFile(Path path) throws SourceFileAccessException {
    try {
      return Files.newInputStream(path);
    } catch (IOException e) {
      throw new SourceFileAccessException(
          "Failed to open onboarding sourcedata file " + path.getFileName(), e);
    }
  }

  private static InputStream openBundledSourceFile(ClassLoader classLoader, String fileName)
      throws SourceFileAccessException {
    String resourcePath = String.join(RESOURCE_PATH_SEPARATOR, SAMPLE_DATA_RESOURCE_DIRECTORY, fileName);
    InputStream inputStream = classLoader.getResourceAsStream(resourcePath);
    if (inputStream == null) {
      throw new SourceFileAccessException(
          "Bundled GOClient sampledata file not found on the classpath: " + resourcePath);
    }
    return inputStream;
  }

  /**
   * Lazily lists the sourcedata files to normalize, so directory/classpath access happens when the
   * dataset is actually built rather than when the provider is created.
   */
  @FunctionalInterface
  interface SourceFileProvider {
    /**
     * Returns the sourcedata files to include in the dataset being built.
     *
     * @return the included source files
     */
    List<SourceFile> listIncludedSourceFiles();
  }

  /**
   * Opens a single sourcedata file's content stream on demand.
   */
  @FunctionalInterface
  interface SourceFileOpener {
    /**
     * Opens the sourcedata file's content stream.
     *
     * @return the opened stream
     * @throws SourceFileAccessException if the file cannot be opened
     */
    InputStream open() throws SourceFileAccessException;
  }

  static final class SourceFile {
    final String fileName;
    private final SourceFileOpener opener;

    SourceFile(String fileName, SourceFileOpener opener) {
      this.fileName = Objects.requireNonNull(fileName, "fileName is required");
      this.opener = Objects.requireNonNull(opener, "opener is required");
    }

    InputStream openStream() throws SourceFileAccessException {
      return opener.open();
    }
  }

  static final class SourceFileAccessException extends IOException {
    private static final long serialVersionUID = 1L;

    SourceFileAccessException(String message) {
      super(message);
    }

    SourceFileAccessException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
