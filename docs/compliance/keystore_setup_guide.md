# Production Keystore Setup Guide

To upload an app to the Google Play Store, it must be cryptographically signed with a Release Keystore. You cannot use the debug keystore.

Follow these steps carefully. **If you lose your keystore file or password, you will not be able to update your app on the Play Store.**

### Step 1: Generate the Keystore File
Open your terminal (PowerShell or Command Prompt) and navigate to the `ScrollGuardFixed` root folder. Run the following command:

```bash
keytool -genkey -v -keystore scrollguard-release.jks -alias sg_key -keyalg RSA -keysize 2048 -validity 10000
```

* **What it asks:** It will prompt you for a password (make it strong and save it in a password manager). It will then ask for your Name, Organization, etc. You can fill these out or just hit Enter to skip them. Type `yes` when it asks if the information is correct.
* **Result:** This creates a file named `scrollguard-release.jks` in your root folder. 

> [!CAUTION]
> **BACK THIS FILE UP!** Email it to yourself, put it on a USB drive, and save it in Google Drive. If you lose `scrollguard-release.jks`, you lose the ability to push updates to your app.

### Step 2: Configure `keystore.properties`
In your root folder, create (or edit) a file named `keystore.properties`. **Do not commit this file to GitHub** (it should already be in your `.gitignore`).

Add the following text to it, replacing `YOUR_PASSWORD_HERE` with the password you just created:

```properties
storePassword=YOUR_PASSWORD_HERE
keyPassword=YOUR_PASSWORD_HERE
keyAlias=sg_key
storeFile=../scrollguard-release.jks
```

### Step 3: Build the Signed APK/Bundle
Now you can build the official signed release bundle that Google Play requires. Run:

```bash
.\gradlew bundleRelease
```

Once the build finishes, you will find a `.aab` (Android App Bundle) file located at:
`app/build/outputs/bundle/release/app-release.aab`

**This `.aab` file is exactly what you upload to the Google Play Console!**
