package com.jhftyyyty.githubuploader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity:ComponentActivity(){
 private var selectedUri by mutableStateOf<Uri?>(null)
 private var selectedName by mutableStateOf("")
 private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)acceptZip(uri)}
 private fun acceptZip(uri:Uri){
  val name=contentResolver.query(uri,null,null,null,null)?.use{c->val i=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);if(i>=0&&c.moveToFirst())c.getString(i)else null}.orEmpty()
  if(name.lowercase().endsWith(".zip")||contentResolver.getType(uri).orEmpty().contains("zip")){runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};selectedUri=uri;selectedName=name.ifBlank{"project.zip"}}
 }
 override fun onCreate(state:Bundle?){installSplashScreen();super.onCreate(state);setContent{
  val prefs=remember{getSharedPreferences(PREFS,Context.MODE_PRIVATE)}
  var theme by remember{mutableStateOf(runCatching{ThemeMode.valueOf(prefs.getString(PREF_THEME,ThemeMode.SYSTEM.name)!!)}.getOrDefault(ThemeMode.SYSTEM))}
  var language by remember{mutableStateOf(runCatching{LanguageMode.valueOf(prefs.getString(PREF_LANGUAGE,LanguageMode.SYSTEM.name)!!)}.getOrDefault(LanguageMode.SYSTEM))}
  val ar=when(language){LanguageMode.SYSTEM->java.util.Locale.getDefault().language.equals("ar",true);LanguageMode.ARABIC->true;LanguageMode.ENGLISH->false}
  val dark=when(theme){ThemeMode.SYSTEM->androidx.compose.foundation.isSystemInDarkTheme();ThemeMode.LIGHT->false;ThemeMode.AMOLED->true}
  val t=AppStrings(ar);var screen by remember{mutableStateOf(Screen.HOME)};var helpOrigin by remember{mutableStateOf(Screen.HOME)}
  BackHandler(screen!=Screen.HOME){screen=if(screen==Screen.HELP)helpOrigin else Screen.HOME}
  AppTheme(dark){when(screen){
   Screen.HOME->HomeScreen(t,selectedUri,selectedName,{picker.launch(arrayOf("application/zip","application/octet-stream"))},{screen=Screen.SETTINGS},{helpOrigin=Screen.HOME;screen=Screen.HELP})
   Screen.SETTINGS->SettingsScreen(t,theme,language,{theme=it;prefs.edit().putString(PREF_THEME,it.name).apply()},{language=it;prefs.edit().putString(PREF_LANGUAGE,it.name).apply()},{screen=Screen.HOME},{helpOrigin=Screen.SETTINGS;screen=Screen.HELP})
   Screen.HELP->HelpScreen(t){screen=helpOrigin}
  }}
 }}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun HomeScreen(t:AppStrings,uri:Uri?,name:String,pick:()->Unit,settings:()->Unit,help:()->Unit){
 val context=LocalContext.current;val scope=rememberCoroutineScope();val store=remember{TokenStore(context)}
 var token by remember{mutableStateOf(store.get())};var account by remember{mutableStateOf<String?>(null)}
 var mode by remember{mutableStateOf(UploadMode.NEW)};var sync by remember{mutableStateOf(false)};var repos by remember{mutableStateOf<List<RepoInfo>>(emptyList())};var selected by remember{mutableStateOf<RepoInfo?>(null)}
 var repoName by remember{mutableStateOf("")};var description by remember{mutableStateOf("")};var privateRepo by remember{mutableStateOf(true)};var busy by remember{mutableStateOf(false)}
 var message by remember{mutableStateOf("")};var ok by remember{mutableStateOf<Boolean?>(null)};var progress by remember{mutableStateOf(0f)};var progressText by remember{mutableStateOf("")};var result by remember{mutableStateOf("")};var showToken by remember{mutableStateOf(false)}
 var oauthDialog by remember{mutableStateOf(false)}
 LaunchedEffect(Unit){if(token.isNotBlank())account=runCatching{GitHubApi.currentUser(token).login}.getOrNull()}
 LaunchedEffect(Unit){
  val wm=WorkManager.getInstance(context)
  while(true){
   val up=withContext(Dispatchers.IO){wm.getWorkInfosForUniqueWork(WORK_NAME).get().firstOrNull()}
   val down=withContext(Dispatchers.IO){wm.getWorkInfosForUniqueWork(DownloadWorker.WORK_NAME).get().firstOrNull()}
   val info=if(mode==UploadMode.DOWNLOAD)down else up
   if(info!=null){
    busy=info.state==WorkInfo.State.RUNNING||info.state==WorkInfo.State.ENQUEUED
    val total=info.progress.getInt(UploadWorker.KEY_TOTAL,0);val done=info.progress.getInt(UploadWorker.KEY_DONE,0);progress=if(total>0)done.toFloat()/total else 0f;progressText=info.progress.getString(UploadWorker.KEY_TEXT).orEmpty()
    when(info.state){WorkInfo.State.SUCCEEDED->{ok=true;message=t.done;result=if(mode==UploadMode.DOWNLOAD)info.outputData.getString(DownloadWorker.KEY_RESULT_PATH).orEmpty()else info.outputData.getString(UploadWorker.KEY_RESULT_URL).orEmpty()};WorkInfo.State.FAILED->{ok=false;message=info.outputData.getString(UploadWorker.KEY_ERROR).orEmpty()};WorkInfo.State.CANCELLED->{ok=false;message=t.cancelled};else->Unit}
   };delay(700)
  }
 }
 Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=18.dp)){
  TopAppBar(title={Column{Text(t.app,fontWeight=FontWeight.Bold);Text(t.subtitle,style=MaterialTheme.typography.labelMedium)}},actions={IconButton(help){Icon(Icons.Default.HelpOutline,t.help)};IconButton(settings){Icon(Icons.Default.Settings,t.settings)}})
  Spacer(Modifier.height(8.dp))
  ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.AccountCircle,null,Modifier.size(32.dp));Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(account?:t.notConnected,fontWeight=FontWeight.SemiBold);Text(t.authHint,style=MaterialTheme.typography.bodySmall)};if(account!=null)Icon(Icons.Default.CheckCircle,null,tint=MaterialTheme.colorScheme.primary)}
   OutlinedTextField(token,{token=it;store.save(it)},Modifier.fillMaxWidth(),label={Text(t.token)},singleLine=true,visualTransformation=if(showToken)VisualTransformation.None else PasswordVisualTransformation(),trailingIcon={TextButton({showToken=!showToken}){Text(if(showToken)t.hide else t.show)}})
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){if(BuildConfig.GITHUB_CLIENT_ID.isNotBlank())Button(enabled=!busy,onClick={oauthDialog=true}){Icon(Icons.Default.Language,null);Spacer(Modifier.width(6.dp));Text(t.browserLogin)};OutlinedButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(TOKEN_URL)))}){Text(t.createToken)}}
  }}
  Spacer(Modifier.height(14.dp));Text(t.operation,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);Spacer(Modifier.height(6.dp))
  Row(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.fillMaxWidth()){
   FilterChip(mode==UploadMode.NEW,{mode=UploadMode.NEW},label={Text(t.newRepo)},modifier=Modifier.weight(1f))
   FilterChip(mode==UploadMode.EXISTING||mode==UploadMode.SYNC,{mode=UploadMode.EXISTING;sync=false},label={Text(t.updateRepo)},modifier=Modifier.weight(1f))
   FilterChip(mode==UploadMode.DOWNLOAD,{mode=UploadMode.DOWNLOAD},label={Text(t.download)},modifier=Modifier.weight(1f))
  }
  if(mode==UploadMode.NEW){Spacer(Modifier.height(10.dp));OutlinedTextField(repoName,{repoName=it},Modifier.fillMaxWidth(),label={Text(t.repoName)},singleLine=true);Spacer(Modifier.height(8.dp));OutlinedTextField(description,{description=it},Modifier.fillMaxWidth(),label={Text(t.description)});Row(verticalAlignment=Alignment.CenterVertically){Switch(privateRepo,{privateRepo=it});Spacer(Modifier.width(8.dp));Text(if(privateRepo)t.privateRepo else t.publicRepo)}}
  if(mode==UploadMode.EXISTING||mode==UploadMode.DOWNLOAD){Spacer(Modifier.height(10.dp));Row(verticalAlignment=Alignment.CenterVertically){Text(t.repository,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);IconButton(enabled=token.isNotBlank()&&!busy,onClick={scope.launch{runCatching{repos=withContext(Dispatchers.IO){GitHubApi.listRepositories(token)};account=GitHubApi.currentUser(token).login}.onFailure{message=t.error+": "+it.message;ok=false}}}){Icon(Icons.Default.Refresh,t.refresh)}};if(repos.isNotEmpty()){var expanded by remember{mutableStateOf(false)};ExposedDropdownMenuBox(expanded,{expanded=!expanded}){OutlinedTextField(selected?.fullName?:"",{},Modifier.fillMaxWidth().menuAnchor(),readOnly=true,label={Text(t.chooseRepo)},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)});ExposedDropdownMenu(expanded,{expanded=false}){repos.forEach{r->DropdownMenuItem({Text(r.fullName)},{selected=r;expanded=false})}}}}}
  if(mode==UploadMode.EXISTING&&selected!=null){Row(verticalAlignment=Alignment.CenterVertically){Switch(sync,{sync=it});Spacer(Modifier.width(8.dp));Column{Text(t.syncMode);Text(t.syncHint,style=MaterialTheme.typography.bodySmall)}}}
  if(mode!=UploadMode.DOWNLOAD){Spacer(Modifier.height(10.dp));ElevatedCard(Modifier.fillMaxWidth()){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.FolderZip,null,Modifier.size(30.dp));Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(t.source,fontWeight=FontWeight.SemiBold);Text(if(name.isBlank())t.noFile else name,style=MaterialTheme.typography.bodySmall)};OutlinedButton(enabled=!busy,onClick=pick){Text(t.chooseZip)}}}}
  Spacer(Modifier.height(14.dp))
  val enabled=!busy&&token.isNotBlank()&&if(mode==UploadMode.DOWNLOAD)selected!=null else uri!=null&&((mode==UploadMode.NEW&&repoName.isNotBlank())||(mode!=UploadMode.NEW&&selected!=null))
  Button(enabled=enabled,onClick={scope.launch{busy=true;ok=null;message="";result="";try{if(mode==UploadMode.DOWNLOAD){val d=workDataOf(DownloadWorker.KEY_TOKEN to token,DownloadWorker.KEY_OWNER to selected!!.owner,DownloadWorker.KEY_REPO to selected!!.name,DownloadWorker.KEY_FULL_NAME to selected!!.fullName,DownloadWorker.KEY_BRANCH to selected!!.defaultBranch);WorkManager.getInstance(context).enqueueUniqueWork(DownloadWorker.WORK_NAME,ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(d).build())}else{val dir=File(context.filesDir,"pending_uploads").apply{mkdirs()};val file=File(dir,"job_"+System.currentTimeMillis()+".zip");withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri!!)?.use{input->file.outputStream().use(input::copyTo)}?:error(t.openZip)};val actual=if(mode==UploadMode.NEW)UploadMode.NEW.name else if(sync)UploadMode.SYNC.name else UploadMode.EXISTING.name;val d=workDataOf(UploadWorker.KEY_FILE_PATH to file.absolutePath,UploadWorker.KEY_TOKEN to token,UploadWorker.KEY_MODE to actual,UploadWorker.KEY_NEW_REPO to repoName.trim(),UploadWorker.KEY_DESCRIPTION to description.trim(),UploadWorker.KEY_PRIVATE to privateRepo,UploadWorker.KEY_OWNER to selected?.owner.orEmpty(),UploadWorker.KEY_REPO to selected?.name.orEmpty(),UploadWorker.KEY_FULL_NAME to selected?.fullName.orEmpty(),UploadWorker.KEY_BRANCH to selected?.defaultBranch.orEmpty());WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME,ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<UploadWorker>().setInputData(d).build())}}catch(e:Exception){busy=false;ok=false;message=t.error+": "+e.message}}},Modifier.fillMaxWidth(),contentPadding=PaddingValues(15.dp)){Icon(if(mode==UploadMode.DOWNLOAD)Icons.Default.Download else Icons.Default.CloudUpload,null);Spacer(Modifier.width(8.dp));Text(if(busy)t.working else if(mode==UploadMode.DOWNLOAD)t.download else t.start)}}
  if(busy||progressText.isNotBlank()){Spacer(Modifier.height(8.dp));LinearProgressIndicator({progress},Modifier.fillMaxWidth());Text(progressText,style=MaterialTheme.typography.bodySmall)}
  if(message.isNotBlank()){Spacer(Modifier.height(8.dp));Card(Modifier.fillMaxWidth()){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(if(ok==true)Icons.Default.CheckCircle else Icons.Default.ErrorOutline,null,tint=if(ok==true)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error);Spacer(Modifier.width(10.dp));Text(message,fontWeight=FontWeight.Medium)}}}
  if(result.isNotBlank()){Spacer(Modifier.height(8.dp));OutlinedCard(onClick={if(mode!=UploadMode.DOWNLOAD)context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(result)))},Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(t.result,fontWeight=FontWeight.SemiBold);Text(result,color=MaterialTheme.colorScheme.primary)}}}
  Spacer(Modifier.height(24.dp))
 }
 if(oauthDialog)BrowserLoginDialog(t,{oauthDialog=false},{newToken->token=newToken;store.save(newToken);account=runCatching{GitHubApi.currentUser(newToken).login}.getOrNull();oauthDialog=false})
}

@Composable private fun BrowserLoginDialog(t:AppStrings,close:()->Unit,done:(String)->Unit){
 val context=LocalContext.current;val scope=rememberCoroutineScope();var device by remember{mutableStateOf<GitHubOAuth.DeviceCode?>(null)};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text(t.browserLogin)},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text(if(device==null)t.browserLoginHint else t.enterCode+": "+device!!.userCode);if(device!=null)OutlinedButton({context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(device!!.verificationUri)))},Modifier.fillMaxWidth()){Text(t.openBrowser)};if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)}},confirmButton={Button(enabled=!busy,onClick={scope.launch{busy=true;error="";try{val d=device?:GitHubOAuth.requestDeviceCode().also{device=it};if(device==null)context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(d.verificationUri)));done(GitHubOAuth.pollForToken(d))}catch(e:Exception){error=e.message?:t.error}finally{busy=false}}}){Text(if(busy)t.waiting else if(device==null)t.connect else t.check)}},dismissButton={TextButton(close){Text(t.cancel)}})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SettingsScreen(t:AppStrings,theme:ThemeMode,language:LanguageMode,changeTheme:(ThemeMode)->Unit,changeLanguage:(LanguageMode)->Unit,back:()->Unit,help:()->Unit){
 Column(Modifier.fillMaxSize().safeDrawingPadding()){TopAppBar(title={Text(t.settings,fontWeight=FontWeight.Bold)},navigationIcon={IconButton(back){Icon(Icons.Default.ArrowBack,t.back)}});Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){SettingRow(Icons.Default.DarkMode,t.theme,when(theme){ThemeMode.SYSTEM->t.system;ThemeMode.LIGHT->t.light;ThemeMode.AMOLED->t.amoled}){changeTheme(if(theme==ThemeMode.SYSTEM)ThemeMode.LIGHT else if(theme==ThemeMode.LIGHT)ThemeMode.AMOLED else ThemeMode.SYSTEM)};SettingRow(Icons.Default.Language,t.language,when(language){LanguageMode.SYSTEM->t.system;LanguageMode.ARABIC->t.arabic;LanguageMode.ENGLISH->t.english}){changeLanguage(if(language==LanguageMode.SYSTEM)LanguageMode.ARABIC else if(language==LanguageMode.ARABIC)LanguageMode.ENGLISH else LanguageMode.SYSTEM)};SettingRow(Icons.Default.Security,t.security,t.securityHint){};OutlinedButton(help,Modifier.fillMaxWidth()){Text(t.help)}}}
}
@Composable private fun SettingRow(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,sub:String,onClick:()->Unit){OutlinedCard(onClick=onClick,modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null);Spacer(Modifier.width(12.dp));Column{Text(title,fontWeight=FontWeight.SemiBold);Text(sub,style=MaterialTheme.typography.bodySmall)}}}}
@Composable private fun HelpScreen(t:AppStrings,back:()->Unit){Column(Modifier.fillMaxSize().safeDrawingPadding()){TopAppBar(title={Text(t.help)},navigationIcon={IconButton(back){Icon(Icons.Default.ArrowBack,t.back)}});Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){listOf(t.help1,t.help2,t.help3,t.help4,t.help5).forEach{Text(it)}}}}
