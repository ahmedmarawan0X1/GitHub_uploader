# GitHub uploader 2.0

Android app for managing GitHub repositories directly from a phone.

## What changed in V2

- Create repositories from ZIP files.
- Update an existing repository using Git SHA change detection.
- Resume interrupted uploads, cancel active uploads, and upload changed blobs in parallel.
- Download a repository branch as a ZIP.
- Changed-file detection using Git blob SHA, so unchanged files are skipped.
- ZIP processing uses temporary files and bounded buffers instead of keeping the whole project in RAM.
- Background WorkManager jobs with progress notifications.
- GitHub token storage is encrypted with Android Keystore.
- Manual Personal Access Token is the only authentication flow.
- Cleaner Material 3 interface with Arabic/English resources and no decorative emoji labels.
- UI screens are separated from the Activity, app constants are centralized, and pending upload files are managed in one place.
- Release signing credentials are no longer stored in the repository.

## Authentication

### Personal Access Token

This is the simplest and most reliable option for direct API uploads. Use a Fine-grained token with access to the repositories you need and repository Contents permission.

## Large files

GitHub's Git database has file-size limits. The app rejects individual ZIP entries above 90 MB and processes ZIP entries in bounded parallel batches to keep memory usage bounded. For projects containing very large assets, Git LFS or another artifact storage strategy is recommended.


## CI

The GitHub Actions workflow builds and lints the release variant. Signing is intentionally external to the repository; configure secure CI signing variables if you want signed release artifacts.

## Security

The old committed release keystore and hardcoded signing credentials were removed from the V2 branch. If that key was ever used for a distributed application, treat it as compromised and rotate the signing strategy as appropriate.

Project: https://github.com/ahmedmarawan0X1/GitHub_uploader
