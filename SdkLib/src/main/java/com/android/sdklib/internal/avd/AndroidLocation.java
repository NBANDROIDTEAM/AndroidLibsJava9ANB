/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.sdklib.internal.avd;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.prefs.AndroidLocation.AndroidLocationException;
import com.android.sdklib.repository.AndroidSdkHandler;
import com.android.utils.FileUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the location of the android files (including emulator files, ddms
 * config, debug keystore)
 */
public final class AndroidLocation {

    /**
     * The name of the .android folder returned by {@link #getFolder}.
     */
    public static final String FOLDER_DOT_ANDROID = ".android";

    /**
     * Virtual Device folder inside the path returned by {@link #getFolder}
     */
    public static final String FOLDER_AVD = "avd";

    /**
     * Throw when the location of the android folder couldn't be found.
     */
    private static String sPrefsLocation = null;
    private static String avdLocationPath = null;

    /**
     * Enum describing which variables to check and whether they should be
     * checked via {@link System#getProperty(String)} or {@link System#getenv()}
     * or both.
     */
    private enum Global {
        ANDROID_AVD_HOME("ANDROID_AVD_HOME", true, true), // both sys prop and env var
        ANDROID_SDK_HOME("ANDROID_SDK_HOME", true, true), // both sys prop and env var
        TEST_TMPDIR("TEST_TMPDIR", false, true), // Bazel kludge
        USER_HOME("user.home", true, false), // sys prop only
        HOME("HOME", false, true);  // env var only

        final String mName;
        final boolean mIsSysProp;
        final boolean mIsEnvVar;

        Global(String name, boolean isSysProp, boolean isEnvVar) {
            mName = name;
            mIsSysProp = isSysProp;
            mIsEnvVar = isEnvVar;
        }

        @Nullable
        public String validatePath(boolean silent) throws AndroidLocationException {
            String path;
            if (mIsSysProp) {
                path = checkPath(System.getProperty(mName), silent);
                if (path != null) {
                    return path;
                }
            }

            if (mIsEnvVar) {
                path = checkPath(System.getenv(mName), silent);
                if (path != null) {
                    return path;
                }
            }
            return null;
        }

        @Nullable
        private String checkPath(@Nullable String path, boolean silent)
                throws AndroidLocationException {
            if (path == null) {
                return null;
            }
            File file = new File(path);
            if (!file.isDirectory()) {
                return null;
            }
            if (!(this == ANDROID_SDK_HOME && isSdkRootWithoutDotAndroid(file))) {
                return path;
            }
            if (!silent) {
                throw new AndroidLocationException(String.format(
                        "ANDROID_SDK_HOME is set to the root of your SDK: %1$s\n"
                        + "This is the path of the preference folder expected by the Android tools.\n"
                        + "It should NOT be set to the same as the root of your SDK.\n"
                        + "Please set it to a different folder or do not set it at all.\n"
                        + "If this is not set we default to: %2$s",
                        path, findValidPath(TEST_TMPDIR, USER_HOME, HOME)));
            }
            return null;
        }

        private static boolean isSdkRootWithoutDotAndroid(@NonNull File folder) {
            return subFolderExist(folder, "platforms")
                    && subFolderExist(folder, "platform-tools")
                    && !subFolderExist(folder, FOLDER_DOT_ANDROID);
        }

        private static boolean subFolderExist(@NonNull File folder, @NonNull String subFolder) {
            return new File(folder, subFolder).isDirectory();
        }
    }

    /**
     * Returns the folder used to store android related files. If the folder is
     * not created yet, it will be created here.
     *
     * @return an OS specific path, terminated by a separator.
     * @throws AndroidLocationException
     */
    public static String getFolder() throws AndroidLocationException {
        if (sPrefsLocation == null) {
            sPrefsLocation = findHomeFolder();
        }

        // make sure the folder exists!
        File f = new File(sPrefsLocation);
        if (!f.exists()) {
            try {
                FileUtils.mkdirs(f);
            } catch (SecurityException e) {
                AndroidLocationException e2 = new AndroidLocationException(String.format(
                        "Unable to create folder '%1$s'. "
                        + "This is the path of preference folder expected by the Android tools.",
                        sPrefsLocation));
                e2.initCause(e);
                throw e2;
            }
        } else if (f.isFile()) {
            throw new AndroidLocationException(String.format(
                    "%1$s is not a directory!\n"
                    + "This is the path of preference folder expected by the Android tools.", sPrefsLocation));
        }
        return sPrefsLocation;
    }

    /**
     * get custom AVD folder with SDK path hash code
     *
     * @see https://github.com/NBANDROIDTEAM/NBANDROID-V2/issues/199
     * @param sdkPath
     * @return
     */
    private static File getAvdFolderWithHash(File sdkPath) throws AndroidLocationException {
        String hashCode = ""+sdkPath.getAbsolutePath().hashCode();
        hashCode = hashCode.replace("-", "N");
        return new File(getFolder() + File.separator + "avd_" + hashCode);
        
    }

    /**
     * Returns the folder used to store android related files. This method will
     * not create the folder if it doesn't exist yet.\
     *
     * @return an OS specific path, terminated by a separator or null if no path
     * is found or an error occurred.
     */
    public static String getFolderWithoutWrites() {
        if (sPrefsLocation == null) {
            try {
                sPrefsLocation = findHomeFolder();
            } catch (AndroidLocationException e) {
                return null;
            }
        }
        return sPrefsLocation;
    }

    /**
     * Check the if ANDROID_SDK_HOME variable points to a SDK. If it points to
     * an SDK
     *
     * @throws AndroidLocationException
     */
    public static void checkAndroidSdkHome() throws AndroidLocationException {
        Global.ANDROID_SDK_HOME.validatePath(false);
    }

    /**
     * Returns the folder where the users AVDs are stored.
     *
     * @return an OS specific path, terminated by a separator.
     * @throws AndroidLocationException
     */
    @NonNull
    public static String getAvdFolder() throws AndroidLocationException {
        if (avdLocationPath == null) {
            String home = findValidPath(Global.ANDROID_AVD_HOME);
            if (home == null) {
                home = getFolder() + FOLDER_AVD;
            }
            avdLocationPath = home;
            if (!avdLocationPath.endsWith(File.separator)) {
                avdLocationPath += File.separator;
            }
        }
        return avdLocationPath;
    }

    /**
     * Returns the folder where the users AVDs are stored with multi SDK
     * support.
     *
     * @see https://github.com/NBANDROIDTEAM/NBANDROID-V2/issues/199
     * @param sdkHandler
     * @return an OS specific path, terminated by a separator.
     * @throws AndroidLocationException
     */
    @NonNull
    public static String getAvdFolder(AndroidSdkHandler sdkHandler) throws AndroidLocationException {
        File sdkLocation = sdkHandler.getLocation();
        String sdkLocationPath = sdkLocation.getAbsolutePath();
        File avdFolderWithHash = getAvdFolderWithHash(sdkLocation);
        if (avdFolderWithHash.exists()) {
            return avdFolderWithHash.getAbsolutePath();
        } else {
            String homePath = findValidPath(Global.ANDROID_AVD_HOME);
            if (avdLocationPath == null) {
                if (homePath == null) {
                    homePath = getFolder() + FOLDER_AVD;
                }
                avdLocationPath = homePath;
                if (!avdLocationPath.endsWith(File.separator)) {
                    avdLocationPath += File.separator;
                }
            }
            File avdLocation = new File(avdLocationPath);
            if (avdLocation.exists()) {
                File validInfo = new File(getSdkInfo(avdLocationPath));
                if (validInfo.exists()) {
                    Properties properties = readValidInfo(avdLocationPath);
                    String sdkpath = properties.getProperty(SDK_LOCATION, null);
                    if (sdkpath == null) {
                        return validateAvds(avdLocation, sdkLocationPath, avdFolderWithHash);
                    }
                    if (sdkLocationPath.equals(sdkpath)) {
                        return avdLocationPath;
                    } else {
                        avdFolderWithHash.mkdirs();
                        return avdFolderWithHash.getAbsolutePath();
                    }

                } else {
                    return validateAvds(avdLocation, sdkLocationPath, avdFolderWithHash);
                }

            } else {
                //AVD folder dont exist, create it and mark as valid
                avdLocation.mkdirs();
                writeValidInfo(avdLocationPath, sdkLocationPath);
                return avdLocationPath;
            }
        }
    }

    public static String validateAvds(File avdLocation, String sdkLocationPath, File avdFolderWithHash) {
        File[] listFiles = avdLocation.listFiles();
        boolean found = false;
        boolean folderFound = false;
        for (File folder : listFiles) {
            if (folder.isDirectory()) {
                folderFound = true;
                found = walkAndCheck(folder, sdkLocationPath);
                if (found) {
                    break;
                }

            }
        }
        if (found) {
            writeValidInfo(avdLocationPath, sdkLocationPath);
            return avdLocationPath;
        } else {
            if (folderFound) {
                //AVDs Exists and not from this sdk
                //create hash folder
                avdFolderWithHash.mkdirs();
                return avdFolderWithHash.getAbsolutePath();
            }
            return avdLocationPath;
        }
    }

    private static boolean walkAndCheck(File folder, String sdkLocationPath) {
        File[] iniFiles = folder.listFiles((File dir, String name) -> {
            switch (name) {
                case "config.ini":
                case "hardware-qemu.ini":
                case "hardware.ini":
                    return true;
                default:
                    return false;
            }
        });
        for (File f : iniFiles) {
            try (FileInputStream fi = new FileInputStream(f)) {
                Properties p = new Properties();
                p.load(fi);
                String property = p.getProperty("skin.path", null);
                if (property != null && property.startsWith(sdkLocationPath + File.separator)) {
                    return true;
                }
                property = p.getProperty("kernel.path", null);
                if (property != null && property.startsWith(sdkLocationPath + File.separator)) {
                    return true;
                }
                property = p.getProperty("disk.ramdisk.path", null);
                if (property != null && property.startsWith(sdkLocationPath + File.separator)) {
                    return true;
                }
                property = p.getProperty("disk.systemPartition.initPath", null);
                if (property != null && property.startsWith(sdkLocationPath + File.separator)) {
                    return true;
                }
            } catch (FileNotFoundException ex) {
                Logger.getLogger(AndroidLocation.class.getName()).log(Level.SEVERE, null, ex);
            } catch (IOException ex) {
                Logger.getLogger(AndroidLocation.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        return false;
    }

    private static void writeValidInfo(String avdLocationPath, String sdkLocationPath) {
        Properties p = new Properties(2);
        p.put(SDK_LOCATION, sdkLocationPath);
        try (FileOutputStream fo = new FileOutputStream(getSdkInfo(avdLocationPath))) {
            p.store(fo, "Created by NBANDROID. Do not modify!");
        } catch (FileNotFoundException ex) {
            Logger.getLogger(AndroidLocation.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(AndroidLocation.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private static Properties readValidInfo(String avdLocationPath) {
        Properties p = new Properties();
        try (FileInputStream fi = new FileInputStream(getSdkInfo(avdLocationPath))) {
            p.load(fi);
        } catch (FileNotFoundException ex) {
            Logger.getLogger(AndroidLocation.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(AndroidLocation.class.getName()).log(Level.SEVERE, null, ex);
        }
        return p;
    }
    public static final String SDK_LOCATION = "SDK_LOCATION";

    private static String getSdkInfo(String avdLocationPath) {
        return avdLocationPath + File.separator + "sdk.info";
    }

    public static String getUserHomeFolder() throws AndroidLocationException {
        return findValidPath(Global.TEST_TMPDIR, Global.USER_HOME, Global.HOME);
    }

    private static String findHomeFolder() throws AndroidLocationException {
        String home = findValidPath(Global.ANDROID_SDK_HOME, Global.TEST_TMPDIR, Global.USER_HOME, Global.HOME);

        // if the above failed, we throw an exception.
        if (home == null) {
            throw new AndroidLocationException("prop: " + System.getProperty("ANDROID_SDK_HOME"));
        }
        if (!home.endsWith(File.separator)) {
            home += File.separator;
        }
        return home + FOLDER_DOT_ANDROID + File.separator;
    }

    /**
     * Resets the folder used to store android related files. For testing.
     */
    public static void resetFolder() {
        sPrefsLocation = null;
        avdLocationPath = null;
    }

    /**
     * Checks a list of system properties and/or system environment variables
     * for validity, and returns the first one.
     *
     * @param vars The variables to check. Order does matter.
     * @return the content of the first property/variable that is a valid
     * directory.
     */
    @Nullable
    private static String findValidPath(Global... vars) throws AndroidLocationException {
        for (Global var : vars) {
            String path = var.validatePath(true);
            if (path != null) {
                return path;
            }
        }
        return null;
    }
}
