/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.log4j.nt;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.StringTokenizer;

import junit.framework.TestCase;
import org.apache.log4j.LogManager;


/**
 * Tests of the NTEventLogAppender DLL path resolution that run on any platform.
 *
 * <p>NTEventLogAppender loads its native library from a static initializer.
 * Through {@link System#loadLibrary(String)} the library is searched for on
 * <code>java.library.path</code> (the PATH and working directory on Windows),
 * where a planted DLL would be picked up.  Before NTEventLogAppender is
 * initialized, this test plants rogue NTEventLogAppender libraries in the
 * first directory of <code>java.library.path</code> and sets
 * <code>log4j.nteventlog.dll.path</code> to the absolute path of a native
 * library shipped with the running JRE.  The class only initializes if that
 * absolute path is loaded with {@link System#load(String)} and the planted
 * libraries are never loaded, which also lets the remaining tests run on
 * systems without the NTEventLogAppender DLL.
 */
public class NTEventLogAppenderDllPathTest extends TestCase {

  /**
   * Native libraries shipped with the JRE that are not loaded at startup.
   */
  private static final String[] JRE_LIBRARIES =
      new String[] { "jaas_unix", "jaas_nt", "jaas", "j2pkcs11" };

  private static final boolean planted = plantRogueLibraries();

  private static final String configuredLibrary = findJreLibrary();

  private static final Throwable initializationFailure =
      initializeAppender(configuredLibrary);

  private String originalDllPath;

  public void setUp() {
    originalDllPath = System.getProperty(NTEventLogAppender.DLL_PATH_PROPERTY);
    clearDllPathProperty();
  }

  public void tearDown() {
    if (originalDllPath != null) {
      System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, originalDllPath);
    } else {
      clearDllPathProperty();
    }
    LogManager.shutdown();
  }

  /**
   * Removes the DLL path system property
   * (System.clearProperty is not available before Java 5).
   */
  private static void clearDllPathProperty() {
    System.getProperties().remove(NTEventLogAppender.DLL_PATH_PROPERTY);
  }

  /**
   * Plants files that are not loadable libraries under the names
   * NTEventLogAppender searches for in the first directory of
   * java.library.path, so loading any of them fails.
   */
  private static boolean plantRogueLibraries() {
    String libraryPath = System.getProperty("java.library.path");
    if (libraryPath == null) {
      return false;
    }
    StringTokenizer dirs = new StringTokenizer(libraryPath, File.pathSeparator);
    if (!dirs.hasMoreTokens()) {
      return false;
    }
    File dir = new File(dirs.nextToken());
    dir.mkdirs();
    String[] names = new String[] {
        "NTEventLogAppender." + System.getProperty("os.arch"), "NTEventLogAppender" };
    try {
      for (int i = 0; i < names.length; i++) {
        File rogue = new File(dir, System.mapLibraryName(names[i]));
        rogue.deleteOnExit();
        OutputStream os = new FileOutputStream(rogue);
        try {
          os.write("planted by NTEventLogAppenderDllPathTest".getBytes());
        } finally {
          os.close();
        }
      }
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Finds a native library of the running JRE by its absolute path.
   */
  private static String findJreLibrary() {
    String libraryPath = System.getProperty("sun.boot.library.path");
    if (libraryPath == null) {
      return null;
    }
    StringTokenizer dirs = new StringTokenizer(libraryPath, File.pathSeparator);
    while (dirs.hasMoreTokens()) {
      File dir = new File(dirs.nextToken());
      for (int i = 0; i < JRE_LIBRARIES.length; i++) {
        File library = new File(dir, System.mapLibraryName(JRE_LIBRARIES[i]));
        if (library.isAbsolute() && library.isFile()) {
          return library.getAbsolutePath();
        }
      }
    }
    return null;
  }

  /**
   * Initializes NTEventLogAppender with the DLL path property set to
   * the given library and returns the failure, if any.
   */
  private static Throwable initializeAppender(String libraryPath) {
    if (libraryPath == null) {
      return null;
    }
    String original = System.getProperty(NTEventLogAppender.DLL_PATH_PROPERTY);
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, libraryPath);
    try {
      Class.forName("org.apache.log4j.nt.NTEventLogAppender");
      return null;
    } catch (Throwable t) {
      return t;
    } finally {
      if (original != null) {
        System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, original);
      } else {
        clearDllPathProperty();
      }
    }
  }

  /**
   * When the system property is an absolute path to an existing library,
   * NTEventLogAppender must load that file and must not load the libraries
   * planted on the library search path.
   */
  public void testConfiguredAbsolutePathIsLoadedInsteadOfPlantedDll() {
    assertTrue("Could not plant rogue libraries on java.library.path", planted);
    assertNotNull("No native library of the JRE found to configure", configuredLibrary);
    if (initializationFailure != null) {
      fail("NTEventLogAppender did not load the configured library "
          + configuredLibrary + ": " + initializationFailure);
    }
  }

  /**
   * A DLL planted in the working directory must not be selected through a
   * relative path in the system property, which would be resolved against
   * the working directory.
   */
  public void testRelativePathToPlantedDllIsRejected() throws IOException {
    File workingDir = new File(System.getProperty("user.dir"));
    File rogue = File.createTempFile("NTEventLogAppender", ".dll", workingDir);
    rogue.deleteOnExit();
    try {
      String relativePath = rogue.getName();
      assertTrue(new File(relativePath).exists());
      System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, relativePath);
      assertNull(NTEventLogAppender.resolveConfiguredDllPath());
    } finally {
      rogue.delete();
    }
  }

  /**
   * When the system property is not set, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_notSet() {
    clearDllPathProperty();
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is an empty string, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_emptyString() {
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, "");
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is whitespace only, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_whitespaceOnly() {
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, "   ");
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is a relative path, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_relativePath() {
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, "relative/path/NTEventLogAppender.dll");
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is an absolute path to a file that does not exist,
   * resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_absoluteButMissing() {
    String missingPath = new File(System.getProperty("java.io.tmpdir"), "no_such_NTEventLogAppender.dll")
        .getAbsolutePath();
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, missingPath);
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is an absolute path to an existing file,
   * resolveConfiguredDllPath should return that absolute path.
   */
  public void testResolveConfiguredDllPath_absoluteAndExists() throws IOException {
    File tempFile = File.createTempFile("NTEventLogAppender", ".dll");
    tempFile.deleteOnExit();
    try {
      String absolutePath = tempFile.getAbsolutePath();
      System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, absolutePath);
      String result = NTEventLogAppender.resolveConfiguredDllPath();
      assertNotNull(result);
      assertEquals(absolutePath, result);
    } finally {
      tempFile.delete();
    }
  }
}
