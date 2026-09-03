# GitHub Uploader

Android app built with Kotlin + Jetpack Compose.

## Release APK

GitHub Actions builds and verifies a signed Release APK automatically.

The project intentionally contains the release keystore so no GitHub Secrets are required.

**Security note:** do not use this embedded keystore for a publicly distributed production app. Anyone who obtains the keystore and its password could sign APKs with the same certificate. For a real production release, keep the keystore private and use GitHub Actions Secrets.
