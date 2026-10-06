package com.jhftyyyty.githubuploader

import com.jhftyyyty.githubuploader.core.*

import android.content.Context
import android.util.Base64OutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

internal object GitHubApi {
    private const val API="https://api.github.com"
    private const val INITIAL_PARALLELISM = 6
    private const val MIN_PARALLELISM = 3
    private const val MAX_PARALLELISM = 10
    private const val INLINE_FILE_LIMIT = 256L * 1024L
    private const val INLINE_BATCH_LIMIT = 2L * 1024L * 1024L
    private const val MAX_HTTP_RETRIES = 3
    private val RETRYABLE_CODES = setOf(408, 429, 500, 502, 503, 504)
    private val RETRY_DELAYS_MS = longArrayOf(1000L, 2500L, 5000L)

    fun currentUser(token:String):GitHubUser {
        val r=request("GET",API+"/user",token); checkOk(r,"Authentication failed")
        val o=JSONObject(r.body); return GitHubUser(o.getString("login"),o.optString("name").ifBlank{null},o.optString("avatar_url").ifBlank{null})
    }

    fun listRepositories(token:String):List<RepoInfo>{
        val out=mutableListOf<RepoInfo>(); var page=1
        while(page<=10){
            val r=request("GET",API+"/user/repos?per_page=100&sort=updated&page="+page,token);checkOk(r,"Could not load repositories")
            val a=JSONArray(r.body);if(a.length()==0)break
            for(i in 0 until a.length()){val o=a.getJSONObject(i);out+=RepoInfo(o.getJSONObject("owner").getString("login"),o.getString("name"),o.getString("full_name"),o.optString("default_branch","main"),o.optBoolean("private"))}
            if(a.length()<100)break;page++
        }
        return out
    }

    suspend fun previewZip(
        context: Context,
        filePath: String,
        token: String,
        mode: UploadMode,
        existing: RepoInfo?
    ): UploadReview {
        val remote = if (mode == UploadMode.EXISTING) {
            val selected = existing ?: error("No repository selected")
            val target = resolveRepo(token, UploadMode.EXISTING, "", "", false, selected, false)
            val head = branchHead(token, target.owner, target.name, target.branch)
            tree(token, target.owner, target.name, head.treeSha)
        } else emptyMap()
        val rules = readIgnoreRules(filePath)
        var files = 0
        var additions = 0
        var modified = 0
        var unchanged = 0
        var ignoredCount = 0
        var totalBytes = 0L
        val seen = HashSet<String>()

        ZipInputStream(File(filePath).inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                coroutineContext.ensureActive()
                if (!entry.isDirectory) {
                    val path = safePath(entry.name)
                    if (path != null) {
                        if (isIgnored(path, rules)) {
                            ignoredCount++
                        } else {
                            val temp = File(context.cacheDir, "preview_" + System.nanoTime() + ".bin")
                            val hash = copyAndHash(zip, temp)
                            temp.delete()
                            files++
                            seen += path
                            totalBytes += hash.size
                            when {
                                remote.isEmpty() -> additions++
                                remote[path]?.sha == hash.gitSha -> unchanged++
                                else -> modified++
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val remoteOnly = if (mode == UploadMode.EXISTING) {
            remote.entries.count { it.value.type == "blob" && it.key !in seen && it.key != ".githubuploaderignore" }
        } else 0
        return UploadReview(files, additions, modified, unchanged, ignoredCount, remoteOnly, totalBytes)
    }

    suspend fun uploadZipFile(
        context: Context,
        filePath: String,
        token: String,
        mode: UploadMode,
        newRepoName: String,
        description: String,
        privateRepo: Boolean,
        existing: RepoInfo?,
        resumeCheck: Boolean,
        progress: suspend (Int, Int, String) -> Unit
    ): String {
        val target = resolveRepo(token, mode, newRepoName, description, privateRepo, existing, resumeCheck)
        val head = branchHead(token, target.owner, target.name, target.branch)
        val remote = tree(token, target.owner, target.name, head.treeSha)
        val rules = readIgnoreRules(filePath)
        val total = countFiles(filePath, rules)
        check(total > 0) { "ZIP file contains no usable files" }

        val seen = HashSet<String>()
        val treeEntries = JSONArray()
        var processed = 0
        var changed = 0
        var inlineBytes = 0L
        var parallelism = INITIAL_PARALLELISM

        data class Pending(val path: String, val temp: File, val sha: String)
        val pending = ArrayList<Pending>()

        suspend fun uploadBatch() {
            if (pending.isEmpty()) return
            val batch = pending.take(parallelism)
            repeat(batch.size) { pending.removeAt(0) }
            val started = System.nanoTime()
            try {
                val results = coroutineScope {
                    batch.map { item ->
                        async(Dispatchers.IO) {
                            coroutineContext.ensureActive()
                            val sha = if (resumeCheck && blobExists(token, target.owner, target.name, item.sha)) {
                                item.sha
                            } else {
                                createBlob(token, target.owner, target.name, item.temp)
                            }
                            item to sha
                        }
                    }.awaitAll()
                }
                for ((item, sha) in results) {
                    changed++
                    treeEntries.put(JSONObject().put("path", item.path).put("mode", "100644").put("type", "blob").put("sha", sha))
                    item.temp.delete()
                    progress(processed, total, item.path)
                }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                parallelism = when {
                    elapsedMs < 2500L -> (parallelism + 1).coerceAtMost(MAX_PARALLELISM)
                    elapsedMs > 10000L -> (parallelism - 1).coerceAtLeast(MIN_PARALLELISM)
                    else -> parallelism
                }
            } catch (e: Exception) {
                parallelism = (parallelism - 1).coerceAtLeast(MIN_PARALLELISM)
                throw e
            }
        }

        ZipInputStream(File(filePath).inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                coroutineContext.ensureActive()
                if (!e.isDirectory) {
                    val path = safePath(e.name)
                    if (path != null && !isIgnored(path, rules)) {
                        processed++
                        seen += path
                        val temp = File(context.cacheDir, "ghu_" + System.nanoTime() + ".bin")
                        val h = copyAndHash(zip, temp)
                        if (remote[path]?.sha != h.gitSha) {
                            val inline = if (h.size <= INLINE_FILE_LIMIT && inlineBytes + h.size <= INLINE_BATCH_LIMIT) readInlineText(temp) else null
                            if (inline != null) {
                                treeEntries.put(JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("content", inline))
                                inlineBytes += h.size
                                changed++
                                temp.delete()
                                progress(processed, total, path)
                            } else {
                                pending += Pending(path, temp, h.gitSha)
                                if (pending.size >= parallelism) uploadBatch()
                            }
                        } else {
                            temp.delete()
                            progress(processed, total, "Unchanged • " + path)
                        }
                    }
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        while (pending.isNotEmpty()) uploadBatch()

        if (treeEntries.length() == 0) {
            progress(total, total, "No changes")
            return target.url
        }

        val treeResponse = request("POST", API + "/repos/" + enc(target.owner) + "/" + enc(target.name) + "/git/trees", token,
            JSONObject().put("base_tree", head.treeSha).put("tree", treeEntries).toString())
        checkOk(treeResponse, "Git tree creation failed")

        val treeSha = JSONObject(treeResponse.body).getString("sha")
        val commitResponse = request("POST", API + "/repos/" + enc(target.owner) + "/" + enc(target.name) + "/git/commits", token,
            JSONObject().put("message", if (mode == UploadMode.NEW) "Upload project" else "Update project").put("tree", treeSha).put("parents", JSONArray().put(head.sha)).toString())
        checkOk(commitResponse, "Git commit creation failed")

        val newSha = JSONObject(commitResponse.body).getString("sha")
        val update = request("PATCH", API + "/repos/" + enc(target.owner) + "/" + enc(target.name) + "/git/refs/heads/" + enc(target.branch), token,
            JSONObject().put("sha", newSha).put("force", false).toString())
        checkOk(update, "Branch update failed")
        progress(total, total, "Completed • " + changed + " changed")
        return target.url
    }

    suspend fun downloadRepository(token:String,repo:RepoInfo,out:File,progress:suspend (Long,Long)->Unit){
        val c=URL(API+"/repos/"+enc(repo.owner)+"/"+enc(repo.name)+"/zipball/"+enc(repo.defaultBranch)).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Accept","application/vnd.github+json");c.connectTimeout=20000;c.readTimeout=120000
        val code = c.responseCode
        if(code !in 200..299){
            val body = c.errorStream?.bufferedReader()?.use{it.readText()}.orEmpty()
            checkOk(R(code, body), "Download failed")
        }
        val total=c.contentLengthLong;var done=0L
        c.inputStream.use{input->out.outputStream().use{output->val b=ByteArray(64*1024);while(true){val n=input.read(b);if(n<0)break;output.write(b,0,n);done+=n;progress(done,total)}}};c.disconnect()
    }

    private data class Target(val owner:String,val name:String,val branch:String,val url:String)
    private fun resolveRepo(token:String,mode:UploadMode,name:String,desc:String,privateRepo:Boolean,existing:RepoInfo?,resumeCheck:Boolean):Target{
        if(mode==UploadMode.NEW){
            check(name.matches(Regex("[A-Za-z0-9._-]{1,100}"))){"Invalid repository name"}
            val r=request("POST",API+"/user/repos",token,JSONObject().put("name",name).put("description",desc).put("private",privateRepo).put("auto_init",true).toString())
            if(r.code==422 && resumeCheck){
                val login=currentUser(token).login
                val existingResponse=request("GET",API+"/repos/"+enc(login)+"/"+enc(name),token)
                checkOk(existingResponse,"Could not resume repository creation")
                val o=JSONObject(existingResponse.body);return Target(login,o.getString("name"),o.optString("default_branch","main"),o.getString("html_url"))
            }
            checkOk(r,"Repository creation failed")
            val o=JSONObject(r.body);return Target(o.getJSONObject("owner").getString("login"),o.getString("name"),o.optString("default_branch","main"),o.getString("html_url"))
        }
        val s=existing?:error("No repository selected");val r=request("GET",API+"/repos/"+enc(s.owner)+"/"+enc(s.name),token);checkOk(r,"Could not access repository")
        val o=JSONObject(r.body);return Target(s.owner,s.name,o.optString("default_branch",s.defaultBranch.ifBlank{"main"}),o.getString("html_url"))
    }

    private data class Head(val sha:String,val treeSha:String)
    private fun branchHead(token:String,owner:String,repo:String,branch:String):Head{
        val r=request("GET",API+"/repos/"+enc(owner)+"/"+enc(repo)+"/git/ref/heads/"+enc(branch),token);checkOk(r,"Could not read branch")
        val sha=JSONObject(r.body).getJSONObject("object").getString("sha");val c=request("GET",API+"/repos/"+enc(owner)+"/"+enc(repo)+"/git/commits/"+sha,token);checkOk(c,"Could not read commit")
        return Head(sha,JSONObject(c.body).getJSONObject("tree").getString("sha"))
    }

    private data class Remote(val sha:String,val type:String)
    private fun tree(token: String, owner: String, repo: String, sha: String): Map<String, Remote> {
        val first = request("GET", API + "/repos/" + enc(owner) + "/" + enc(repo) + "/git/trees/" + sha + "?recursive=1", token)
        checkOk(first, "Could not read repository tree")
        val root = JSONObject(first.body)
        val out = HashMap<String, Remote>()
        if (!root.optBoolean("truncated", false)) {
            val a = root.getJSONArray("tree")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out[o.getString("path")] = Remote(o.optString("sha"), o.optString("type"))
            }
            return out
        }
        data class Node(val prefix: String, val treeSha: String)
        val stack = ArrayDeque<Node>()
        stack.addLast(Node("", sha))
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val response = request("GET", API + "/repos/" + enc(owner) + "/" + enc(repo) + "/git/trees/" + enc(node.treeSha), token)
            checkOk(response, "Could not read repository tree")
            val entries = JSONObject(response.body).getJSONArray("tree")
            for (i in 0 until entries.length()) {
                val o = entries.getJSONObject(i)
                val name = o.getString("path")
                val fullPath = if (node.prefix.isBlank()) name else node.prefix + "/" + name
                if (o.optString("type") == "tree") stack.addLast(Node(fullPath, o.getString("sha")))
                else out[fullPath] = Remote(o.optString("sha"), o.optString("type"))
            }
        }
        return out
    }

    private fun countFiles(path:String, rules: Set<String>):Int{var n=0;ZipInputStream(File(path).inputStream().buffered()).use{z->var e=z.nextEntry;while(e!=null){val p=safePath(e.name);if(!e.isDirectory&&p!=null&&!isIgnored(p,rules))n++;z.closeEntry();e=z.nextEntry}};return n}
    private fun readIgnoreRules(path:String):Set<String>{
        val rules=linkedSetOf<String>()
        ZipInputStream(File(path).inputStream().buffered()).use{z->
            var e=z.nextEntry
            while(e!=null){
                if(!e.isDirectory&&e.name.replace('\\','/').trimStart('/')==".githubuploaderignore"){
                    z.bufferedReader().useLines{lines->lines.forEach{line->
                        val rule=line.trim()
                        if(rule.isNotEmpty()&&!rule.startsWith("#")) rules+=rule
                    }}
                    break
                }
                z.closeEntry();e=z.nextEntry
            }
        }
        return rules
    }
    private fun isIgnored(path:String,rules:Set<String>):Boolean{
        if(path==".githubuploaderignore") return true
        if(path.split('/').any{it==".git"||it=="build"||it==".gradle"||it==".idea"}||path.endsWith("local.properties")||path.endsWith(".log")) return true
        return rules.any{ruleMatches(path,it)}
    }
    private fun ruleMatches(path:String,raw:String):Boolean{
        var rule=raw.replace('\\','/').trim().trimStart('/')
        if(rule.isEmpty()) return false
        if(rule.endsWith('/')) return path==rule.dropLast(1)||path.startsWith(rule)
        if(rule.contains('/')){
            val regex=rule.replace(".","\\.").replace("**",".*").replace("*","[^/]*").replace("?",".")
            return Regex("^$regex$").matches(path)
        }
        val name=path.substringAfterLast('/')
        val regex=rule.replace(".","\\.").replace("**",".*").replace("*",".*").replace("?",".")
        return Regex("^$regex$").matches(name)
    }
    private data class HashResult(val gitSha:String,val size:Long)
    private fun copyAndHash(input:InputStream,temp:File):HashResult{
        val b=ByteArray(64*1024);var size=0L
        temp.outputStream().use{out->while(true){val n=input.read(b);if(n<0)break;size+=n;check(size<=MAX_FILE_SIZE){"A file exceeds 90 MB"};out.write(b,0,n)}}
        val md=MessageDigest.getInstance("SHA-1");md.update(("blob "+size+"\u0000").toByteArray())
        temp.inputStream().buffered().use{while(true){val n=it.read(b);if(n<0)break;md.update(b,0,n)}}
        return HashResult(md.digest().toHex(),size)
    }

    private fun blobExists(token: String, owner: String, repo: String, sha: String): Boolean {
        var attempt = 0
        while (true) {
            val c = URL(API + "/repos/" + enc(owner) + "/" + enc(repo) + "/git/blobs/" + enc(sha)).openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.setRequestProperty("Authorization", "Bearer " + token)
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            c.connectTimeout = 20000
            c.readTimeout = 30000
            try {
                val code = c.responseCode
                if (code in 200..299) return true
                if (code == 404) return false
                if (attempt < MAX_HTTP_RETRIES && code in RETRYABLE_CODES) {
                    Thread.sleep(retryDelay(c, attempt))
                    attempt++
                    continue
                }
                val body = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                checkOk(R(code, body), "Blob check failed")
                return false
            } finally { c.disconnect() }
        }
    }

    private fun createBlob(token: String, owner: String, repo: String, temp: File): String {
        var attempt = 0
        while (true) {
            val c = URL(API + "/repos/" + enc(owner) + "/" + enc(repo) + "/git/blobs").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer " + token)
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            c.setRequestProperty("Content-Type", "application/json")
            c.connectTimeout = 20000
            c.readTimeout = 120000
            try {
                c.outputStream.use { out ->
                    out.write("{\"content\":\"".toByteArray())
                    temp.inputStream().buffered().use { input ->
                        val b64 = Base64OutputStream(NonClosingOutputStream(out), android.util.Base64.NO_WRAP)
                        input.copyTo(b64, 64 * 1024)
                        b64.close()
                    }
                    out.write("\",\"encoding\":\"base64\"}".toByteArray())
                }
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) return JSONObject(body).getString("sha")
                if (attempt < MAX_HTTP_RETRIES && code in RETRYABLE_CODES) {
                    Thread.sleep(retryDelay(c, attempt))
                    attempt++
                    continue
                }
                checkOk(R(code, body), "Blob upload failed")
            } catch (e: IOException) {
                if (attempt < MAX_HTTP_RETRIES) {
                    Thread.sleep(RETRY_DELAYS_MS[attempt])
                    attempt++
                    continue
                }
                throw e
            } finally { c.disconnect() }
        }
    }

    private class NonClosingOutputStream(private val delegate:OutputStream):OutputStream(){override fun write(b:Int)=delegate.write(b);override fun write(b:ByteArray,off:Int,len:Int)=delegate.write(b,off,len);override fun flush()=delegate.flush();override fun close(){flush()}}

    private fun safePath(raw:String):String?{val p=raw.replace('\\','/').trimStart('/');if(p.isBlank()||p.startsWith("__MACOSX/")||p.split('/').any{it==".."||it.isBlank()&&p.contains("//")})return null;return p}

    private fun ByteArray.toHex()=joinToString(""){"%02x".format(it)}
    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8").replace("+","%20")
    private data class R(val code:Int,val body:String)
    internal fun friendlyError(message:String):String{
        val m=message.lowercase()
        return when{
            m.contains("unknownhost")||m.contains("unable to resolve host")||m.contains("timeout")||m.contains("connection") ->
                "تعذر الاتصال بالإنترنت. سيُستأنف الرفع عند عودة الاتصال."
            m.contains("90 mb")||m.contains("exceeds") -> "يوجد ملف أكبر من الحد المسموح وهو 90 MB."
            m.contains("token is missing") -> "أضف رمز GitHub من الإعدادات أولًا."
            else -> message
        }
    }

    private fun checkOk(r:R,msg:String){
        if(r.code in 200..299) return
        val body=r.body
        val errorMessage=when(r.code){
            401 -> "رمز GitHub غير صالح أو منتهي."
            403 -> "لا يملك رمز GitHub الصلاحيات المطلوبة، أو تم تجاوز حد الطلبات."
            404 -> "المستودع غير موجود أو لا يمكن الوصول إليه."
            409 -> "حدث تعارض أثناء تنفيذ العملية. أعد المحاولة."
            422 -> {
                val lower=body.lowercase()
                when {
                    lower.contains("field\\\":\\\"name") && lower.contains("already exists") ->
                        "اسم المستودع مستخدم بالفعل على هذا الحساب."
                    lower.contains("name") && lower.contains("already exists") ->
                        "اسم المستودع مستخدم بالفعل على هذا الحساب."
                    else -> "البيانات المدخلة غير مقبولة من GitHub."
                }
            }
            429 -> "تم تجاوز حد طلبات GitHub. انتظر قليلًا ثم أعد المحاولة."
            in 500..599 -> "خادم GitHub غير متاح مؤقتًا. أعد المحاولة لاحقًا."
            else -> "$msg."
        }
        error(errorMessage)
    }
    private fun request(method: String, url: String, token: String, body: String? = null): R {
        var attempt = 0
        while (true) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer " + token)
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            c.setRequestProperty("Content-Type", "application/json")
            c.connectTimeout = 20000
            c.readTimeout = 120000
            try {
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val rateLimited403 = code == 403 && text.contains("rate limit", ignoreCase = true)
                if (attempt < MAX_HTTP_RETRIES && (code in RETRYABLE_CODES || rateLimited403)) {
                    Thread.sleep(retryDelay(c, attempt))
                    attempt++
                    continue
                }
                return R(code, text)
            } catch (e: IOException) {
                if (attempt < MAX_HTTP_RETRIES) {
                    Thread.sleep(RETRY_DELAYS_MS[attempt])
                    attempt++
                    continue
                }
                throw e
            } finally { c.disconnect() }
        }
    }

    private fun readInlineText(file: File): String? {
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.any { it.toInt() == 0 }) return null
            val text = String(bytes, StandardCharsets.UTF_8)
            if (text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) text else null
        }.getOrNull()
    }

    private fun retryDelay(connection: HttpURLConnection, attempt: Int): Long {
        val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()
        return retryAfter?.coerceIn(1L, 30L)?.times(1000L) ?: RETRY_DELAYS_MS[attempt]
    }

}