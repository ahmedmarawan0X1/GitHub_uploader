package com.jhftyyyty.githubuploader

internal const val PREFS = "github_zip_uploader"
internal const val PREF_THEME = "theme"
internal const val PREF_LANGUAGE = "language"
internal const val PREF_AUTO_NAMING = "auto_naming"
internal const val WORK_NAME = "github_upload_job"
internal const val TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"
internal const val MAX_FILE_SIZE = 90L * 1024L * 1024L
internal const val https://github.com/ahmedmarawan0X1/GitHub_uploader = "https://github.com/jhftyyyty/GitHub_uploader"
internal enum class ThemeMode { SYSTEM, LIGHT, AMOLED }
internal enum class LanguageMode { SYSTEM, ARABIC, ENGLISH }
internal enum class UploadMode { NEW, EXISTING, SYNC, DOWNLOAD }
internal enum class Screen { HOME, SETTINGS, HELP }
internal data class RepoInfo(val owner:String,val name:String,val fullName:String,val defaultBranch:String,val private:Boolean)
internal data class GitHubUser(val login:String,val name:String?,val avatarUrl:String?)