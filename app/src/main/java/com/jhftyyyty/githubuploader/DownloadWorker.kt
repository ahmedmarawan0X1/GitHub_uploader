package com.jhftyyyty.githubuploader
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
class DownloadWorker(app:android.content.Context,params:WorkerParameters):CoroutineWorker(app,params){
 override suspend fun doWork():Result{
  val token=inputData.getString(KEY_TOKEN).orEmpty().ifBlank{TokenStore(applicationContext).get()}
  if(token.isBlank())return Result.failure(workDataOf(KEY_ERROR to "GitHub token is missing"))
  val repo=RepoInfo(inputData.getString(KEY_OWNER).orEmpty(),inputData.getString(KEY_REPO).orEmpty(),inputData.getString(KEY_FULL_NAME).orEmpty(),inputData.getString(KEY_BRANCH).orEmpty().ifBlank{"main"},false)
  return try{
   val temp=File(applicationContext.cacheDir,"download_"+System.currentTimeMillis()+".zip")
   GitHubApi.downloadRepository(token,repo,temp){d,t->setProgress(workDataOf(KEY_DONE to d,KEY_TOTAL to t))}
   val uri=if(Build.VERSION.SDK_INT>=29){
    val values=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,repo.name+"-"+System.currentTimeMillis()+".zip");put(MediaStore.Downloads.MIME_TYPE,"application/zip");put(MediaStore.Downloads.RELATIVE_PATH,"Download/GitHubUploader");put(MediaStore.Downloads.IS_PENDING,1)}
    val u=applicationContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("Could not create download")
    applicationContext.contentResolver.openOutputStream(u)?.use{out->temp.inputStream().use{it.copyTo(out,64*1024)}}?:error("Could not write download")
    applicationContext.contentResolver.update(u,ContentValues().apply{put(MediaStore.Downloads.IS_PENDING,0)},null,null);temp.delete();u.toString()
   }else{val dir=applicationContext.getExternalFilesDir(null)!!.resolve("downloads").apply{mkdirs()};val out=dir.resolve(repo.name+"-"+System.currentTimeMillis()+".zip");temp.copyTo(out,true);temp.delete();out.absolutePath}
   Result.success(workDataOf(KEY_RESULT_PATH to uri))
  }catch(e:Exception){Result.failure(workDataOf(KEY_ERROR to(e.message?:e.javaClass.simpleName)))}
 }
 companion object{const val WORK_NAME="github_download_job";const val KEY_TOKEN="token";const val KEY_OWNER="owner";const val KEY_REPO="repo";const val KEY_FULL_NAME="full_name";const val KEY_BRANCH="branch";const val KEY_DONE="done";const val KEY_TOTAL="total";const val KEY_RESULT_PATH="result_path";const val KEY_ERROR="error"}
}