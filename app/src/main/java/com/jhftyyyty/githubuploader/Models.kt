package com.jhftyyyty.githubuploader

internal const val PREFS = "github_zip_uploader"
internal const val PREF_THEME = "theme"
internal const val PREF_LANGUAGE = "language"
internal const val PREF_TOKEN = "token"
internal const val DEFAULT_PROJECT_URL = "https://github.com/jhftyyyty/GitHub_uploader"
internal const val MAX_FILE_SIZE = 90L * 1024L * 1024L
internal const val WORK_NAME = "github_zip_upload"
internal const val TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"

internal enum class ThemeMode { SYSTEM, LIGHT, AMOLED }
internal enum class LanguageMode { SYSTEM, ARABIC, ENGLISH }
internal enum class UploadMode { NEW, EXISTING }
internal enum class Screen { HOME, SETTINGS, HELP }

