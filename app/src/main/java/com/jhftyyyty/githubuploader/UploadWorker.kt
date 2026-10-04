package com.jhftyyyty.githubuploader
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
class UploadWorker(app:Context,params:WorkerParameters):CoroutineWorker(app,params){
 override suspend fun doWork():Result{
  val path=inputData.getString(KEY_FILE_PATH)?:return Result.failure(workDataOf(KEY_ERROR to "ZIP file is missing"))
  val token=inputData.getString(KEY_TOKEN).orEmpty().ifBlank{TokenStore(applicationContext).get()}
  if(token.isBlank())return Result.failure(workDataOf(KEY_ERROR to "GitHub token is missing"))
  val mode=runCatching{UploadMode.valueOf(inputData.getString(KEY_MODE)?:UploadMode.NEW.name)}.getOrDefault(UploadMode.NEW)
  val existing=if(mode!=UploadMode.NEW)RepoInfo(inputData.getString(KEY_OWNER).orEmpty(),inputData.getString(KEY_REPO).orEmpty(),inputData.getString(KEY_FULL_NAME).orEmpty(),inputData.getString(KEY_BRANCH).orEmpty().ifBlank{"main"},false)else null
  return try{
   setForeground(foreground(0,0))
   val url=GitHubApi.uploadZipFile(applicationContext,path,token,mode,inputData.getString(KEY_NEW_REPO).orEmpty(),inputData.getString(KEY_DESCRIPTION).orEmpty(),inputData.getBoolean(KEY_PRIVATE,true),existing){d,t,msg->setProgress(workDataOf(KEY_DONE to d,KEY_TOTAL to t,KEY_TEXT to msg));notifyProgress(d,t)}
   Result.success(workDataOf(KEY_RESULT_URL to url,KEY_TEXT to "Completed"))
  }catch(e:Exception){Result.failure(workDataOf(KEY_ERROR to(e.message?:e.javaClass.simpleName)))}finally{runCatching{java.io.File(path).delete()}}
 }
 private fun foreground(d:Int,t:Int):ForegroundInfo{ensureChannel();val n=NotificationCompat.Builder(applicationContext,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("$d / $t").setOngoing(true).setOnlyAlertOnce(true).setProgress(t.coerceAtLeast(0),d.coerceIn(0,t.coerceAtLeast(0)),t<=0).build();return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else ForegroundInfo(NOTIFICATION_ID,n)}
 private fun notifyProgress(d:Int,t:Int){ensureChannel();val n=NotificationCompat.Builder(applicationContext,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("$d / $t").setOngoing(true).setOnlyAlertOnce(true).setProgress(t.coerceAtLeast(0),d.coerceIn(0,t.coerceAtLeast(0)),t<=0).build();(applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)as NotificationManager).notify(NOTIFICATION_ID,n)}
 private fun ensureChannel(){if(Build.VERSION.SDK_INT>=26)(applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL,"GitHub uploads",NotificationManager.IMPORTANCE_LOW))}
 companion object{const val KEY_FILE_PATH="file_path";const val KEY_TOKEN="token";const val KEY_MODE="mode";const val KEY_NEW_REPO="new_repo";const val KEY_DESCRIPTION="description";const val KEY_PRIVATE="private";const val KEY_OWNER="owner";const val KEY_REPO="repo";const val KEY_FULL_NAME="full_name";const val KEY_BRANCH="branch";const val KEY_DONE="done";const val KEY_TOTAL="total";const val KEY_TEXT="text";const val KEY_ERROR="error";const val KEY_RESULT_URL="result_url";const val CHANNEL="github_uploads";const val NOTIFICATION_ID=2401}
}