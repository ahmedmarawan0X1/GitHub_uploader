package com.jhftyyyty.githubuploader
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
class DownloadWorker(app:android.content.Context,params:WorkerParameters):CoroutineWorker(app,params){
 override suspend fun doWork():Result{
  val token=inputData.getString(KEY_TOKEN).orEmpty().ifBlank{TokenStore(applicationContext).get()}
  if(token.isBlank())return Result.failure(workDataOf(KEY_ERROR to "GitHub token is missing"))
  val repo=RepoInfo(inputData.getString(KEY_OWNER).orEmpty(),inputData.getString(KEY_REPO).orEmpty(),inputData.getString(KEY_FULL_NAME).orEmpty(),inputData.getString(KEY_BRANCH).orEmpty().ifBlank{"main"},false)
  return try{val out=File(applicationContext.getExternalFilesDir(null),"downloads/"+repo.name+"-"+System.currentTimeMillis()+".zip").apply{parentFile?.mkdirs()};GitHubApi.downloadRepository(token,repo,out){d,t->setProgress(workDataOf(KEY_DONE to d,KEY_TOTAL to t))};Result.success(workDataOf(KEY_RESULT_PATH to out.absolutePath))}catch(e:Exception){Result.failure(workDataOf(KEY_ERROR to(e.message?:e.javaClass.simpleName)))}
 }
 companion object{const val WORK_NAME="github_download_job";const val KEY_TOKEN="token";const val KEY_OWNER="owner";const val KEY_REPO="repo";const val KEY_FULL_NAME="full_name";const val KEY_BRANCH="branch";const val KEY_DONE="done";const val KEY_TOTAL="total";const val KEY_RESULT_PATH="result_path";const val KEY_ERROR="error"}
}