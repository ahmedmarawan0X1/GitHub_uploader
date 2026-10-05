package com.jhftyyyty.githubuploader

internal enum class ThemeMode { SYSTEM, LIGHT, AMOLED }
internal enum class LanguageMode { SYSTEM, ARABIC, ENGLISH }
internal enum class UploadMode { NEW, EXISTING, DOWNLOAD }
internal enum class Screen { HOME, SETTINGS, HELP }
internal data class RepoInfo(
    val owner: String,
    val name: String,
    val fullName: String,
    val defaultBranch: String,
    val private: Boolean
)
internal data class GitHubUser(
    val login: String,
    val name: String?,
    val avatarUrl: String?
)
