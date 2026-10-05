# GitHub uploader 2.0

Android app for managing GitHub repositories directly from a phone.

## What changed in V2

- Create repositories from ZIP files.
- Update an existing repository.
- Exact Sync mode: remove remote files that are no longer in the ZIP.
- Download a repository branch as a ZIP.
- Changed-file detection using Git blob SHA, so unchanged files are skipped.
- ZIP processing uses temporary files and bounded buffers instead of keeping the whole project in RAM.
- Background WorkManager jobs with progress notifications.
- GitHub token storage is encrypted with Android Keystore.
- Manual Personal Access Token remains fully supported.
- Cleaner Material 3 interface with Arabic/English support and no decorative emoji labels.
- Release signing credentials are no longer stored in the repository.

## Authentication

### Personal Access Token

This is the simplest and most reliable option for direct API uploads. Use a Fine-grained token with access to the repositories you need and repository Contents permission.

## Large files

GitHub's Git database has file-size limits. The app rejects individual ZIP entries above 90 MB and processes files one at a time to keep memory usage bounded. For projects containing very large assets, Git LFS or another artifact storage strategy is recommended.

## Update vs Exact Sync

**Update** keeps remote-only files and replaces/adds only files that differ.

**Exact Sync** makes the Git tree match the selected ZIP and deletes remote files that are absent from the ZIP.

The app uses GitHub API authentication with a Personal Access Token. The app ignores common generated/local content such as `.git/`, `build/`, `.gradle/`, `.idea/`, `local.properties`, and log files.

## CI

The GitHub Actions workflow builds and lints the release variant. Signing is intentionally external to the repository; configure secure CI signing variables if you want signed release artifacts.

## Security

The old committed release keystore and hardcoded signing credentials were removed from the V2 branch. If that key was ever used for a distributed application, treat it as compromised and rotate the signing strategy as appropriate.

Project: https://github.com/ahmedmarawan0X1/GitHub_uploader
