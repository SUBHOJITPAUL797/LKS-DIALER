# 🚀 LKS Dialer Release Guide

This guide explains how to properly build and publish a new update for the **LKS-DIALER** app so that all your users receive the update automatically via the in-app updater.

---

## Step 1: Update the Version Number
Before you build your new APK, you **MUST** increase the version number so the app knows it's a newer version.

1. Open `app/build.gradle.kts` in Android Studio.
2. Find the `defaultConfig` section.
3. Increase `versionCode` by 1.
4. Increase `versionName`. 
   *(Example: if it was `"1.0.0"`, change it to `"1.0.1"` or `"1.1.0"`)*
5. Sync your Gradle files.

```kotlin
defaultConfig {
    applicationId = "com.example"
    minSdk = 24
    targetSdk = 34
    versionCode = 2       // <-- INCREASE THIS (+1)
    versionName = "1.0.1" // <-- INCREASE THIS
}
```

---

## Step 2: Build the Signed APK
You must generate a signed release APK (not a debug APK) to ensure Android allows users to install the update over the old version.

1. In Android Studio, go to **Build > Generate Signed Bundle / APK...**
2. Select **APK** and click Next.
3. Choose your Keystore, enter passwords, and click Next.
4. Select the **release** variant and click **Create**.
5. Once complete, locate your `app-release.apk` file.

---

## Step 3: Publish the Release on GitHub
The app automatically checks the `SUBHOJITPAUL797/LKS-DIALER` GitHub repository for the latest release.

1. Go to your GitHub repository in your web browser: `https://github.com/SUBHOJITPAUL797/LKS-DIALER`.
2. On the right side, click on **Releases** and then click **Draft a new release**.
3. **Choose a tag**: Type `v` followed by your new `versionName` (e.g., `v1.0.1`) and click **Create new tag**.
   > **IMPORTANT:** The tag **MUST** start with `v` and match your `versionName` (e.g., `v1.0.1`). If you don't use this format, the app won't detect the update properly.
4. **Release Title**: Give your update a title (e.g., `LKS Dialer v1.0.1 - Bug Fixes`).
5. **Describe the release**: Write the release notes here. This exact text will appear inside the app's Update Dialog under "What's new:".
   
   > **WARNING:** **Forced/Mandatory Update:** If this is a critical update that users *must* install to continue using the app, simply type `[MANDATORY]` or `[CRITICAL]` anywhere in this description box. The app will detect this and lock the screen until they update.
6. **Attach binaries**: Drag and drop your `app-release.apk` file into the "Attach binaries by dropping them here" box. Wait for it to upload.
7. Click **Publish release**.

---

## Step 4: The Magic Happens ✨
That's it! As soon as you click Publish:
1. The next time a user opens LKS Dialer, the app will instantly detect the new `v1.0.1` tag.
2. It will compare it against their current version (`v1.0.0`).
3. The beautiful `UpdateDialog` will appear.
4. When they click "Download & Install", the app will download your attached APK directly from GitHub and prompt them to install it!
