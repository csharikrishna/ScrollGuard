# Google Play Data Safety Form Guide

When submitting ScrollGuard to the Google Play Console, you must fill out the **Data Safety** section. If you answer incorrectly, Google will reject the app. 

Follow these exact answers based on ScrollGuard's current architecture (Firebase Auth + Firestore).

---

### Step 1: Data Collection and Security
* **Does your app collect or share any of the required user data types?** 
  * ✅ **Yes**
* **Is all of the user data collected by your app encrypted in transit?** 
  * ✅ **Yes** *(Firebase automatically encrypts all data in transit using HTTPS/TLS).*
* **Do you provide a way for users to request that their data be deleted?** 
  * ✅ **Yes** *(We have a button in the app that deletes their auth account and Firestore documents).*

### Step 2: Data Types Collected
Check the following boxes based on what Firebase is collecting:

**1. Personal Info**
* **Email address:** Check this box. 

**2. App Info and Performance**
* **Crash logs:** Check this box. *(Collected by Crashlytics).*
* **Diagnostics:** Check this box. *(Collected by Crashlytics).*

### Step 3: Data Usage Details
For each data type you checked above, you must explain *why* and *how* it is used.

#### Email Address
* **Is this data collected, shared, or both?** 
  * ✅ Collected
* **Is this data processed ephemerally?** 
  * ✅ No
* **Is this data required for your app or can users choose whether it's collected?** 
  * ✅ Data collection is required *(Required to create an account and pair devices).*
* **Why is this user data collected?** 
  * ✅ App functionality *(Used for Account Management and Authentication).*

#### Crash Logs & Diagnostics
* **Is this data collected, shared, or both?** 
  * ✅ Collected
* **Is this data processed ephemerally?** 
  * ✅ No
* **Is this data required for your app or can users choose whether it's collected?** 
  * ✅ Data collection is optional *(Users can usually opt-out of crash reporting at the OS level, but checking 'Required' is also fine if you don't have an in-app toggle).*
* **Why is this user data collected?** 
  * ✅ Analytics

---

> [!IMPORTANT]  
> **Accessibility Services Declaration:** 
> Elsewhere in the Play Console (App Content -> Sensitive permissions and APIs), you will be asked why you use the Accessibility API. 
> 
> **Copy/paste this exact response:**
> *"ScrollGuard uses the AccessibilityService API exclusively to detect the package name of the active foreground application. This is necessary to determine if the user has opened an app that they have explicitly blocked or for which a parent has set a time limit. If a restricted app is detected, we draw an overlay to block access. We do not use the API to observe text, screen content, or collect any personal data. All package detection happens locally on the device."*
