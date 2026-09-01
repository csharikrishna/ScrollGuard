# Privacy Policy for ScrollGuard

**Last Updated:** September 1, 2026

ScrollGuard ("we," "our," or "us") is committed to protecting your privacy and ensuring transparency in how your data is handled. This Privacy Policy explains our practices regarding data collection, usage, and your rights, with a specific focus on our use of Android’s Accessibility Services and our parental control features.

## 1. Use of Accessibility Services (IMPORTANT)
ScrollGuard relies on the Android **AccessibilityService API** to function. 

* **Why we use it:** We use this service exclusively to detect which application is currently active on your screen. This allows ScrollGuard to determine if a blocked or time-restricted application has been opened, so it can display the blocking overlay and enforce your focus timers or parent-set limits.
* **What data is accessed:** The service only reads the **package name** of the active window (e.g., `com.instagram.android`). 
* **What data is NOT accessed:** We **do not** read your screen content, passwords, private messages, or any personal data visible on your screen. 
* **Data Transmission:** The package name data is processed **entirely locally on your device**. It is never transmitted to our servers, shared with third parties, or sold.

## 2. Data Collection and Usage
When you use ScrollGuard, we collect the minimum amount of data necessary to provide our services:

* **Account Information:** If you create an account, we collect your email address for authentication purposes (via Firebase Authentication).
* **Parental Control Synchronization:** If you pair devices using our Parental Control feature, we securely store the family configuration in the cloud (via Google Cloud Firestore). This includes the list of restricted apps, time limits, and pairing statuses. This data is strictly used to synchronize rules between the parent and child devices.
* **Crash Reports:** We may collect anonymized crash reports to help us identify and fix bugs (via Firebase Crashlytics). This does not include personal identifiable information.

## 3. Data Sharing and Disclosure
We **do not sell, rent, or trade** your personal data to third parties. Data is only shared with trusted service providers (like Google Cloud) strictly for the purpose of hosting and authenticating the app's infrastructure.

## 4. Child Privacy (COPPA Compliance)
ScrollGuard includes features designed for parents to manage their children's device usage. 
* We do not knowingly collect personal information directly from children under the age of 13 without verifiable parental consent. 
* The parent initiates the device pairing process and has full control over the data synced between devices. 
* If you believe we have inadvertently collected information from a child without proper consent, please contact us so we can delete the data immediately.

## 5. Data Retention and Deletion
You have the right to request the deletion of your data at any time. 
* You can delete your account and all associated data directly from within the ScrollGuard app settings.
* Upon account deletion, all paired family data, restriction rules, and authentication records are permanently erased from our servers.

## 6. Contact Us
If you have any questions or concerns about this Privacy Policy or our use of the AccessibilityService API, please contact us at: [Insert Contact Email Here]
