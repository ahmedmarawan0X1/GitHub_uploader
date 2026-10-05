package com.jhftyyyty.githubuploader

import android.content.Context
import android.util.Base64OutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

internal object GitHubApi {
    private const val API="https://api.github.com"

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

    suspend fun uploadZipFile(context:Context,filePath:String,token:String,mode:UploadMode,newRepoName:String,description:String,privateRepo:Boolean,existing:RepoInfo?,progress:suspend (Int,Int,String)->Unit):String{
        val target=resolveRepo(token,mode,newRepoName,description,privateRepo,existing)
        val head=branchHead(token,target.owner,target.name,target.branch)
        val remote=tree(token,target.owner,target.name,head.treeSha)
        val total=countFiles(filePath);check(total>0){"ZIP file contains no usable files"}
        val seen=HashSet<String>();val treeEntries=JSONArray();var processed=0;var changed=0
        ZipInputStream(File(filePath).inputStream().buffered()).use{zip->
            var e=zip.nextEntry
            while(e!=null){
                if(!e.isDirectory){
                    val path=safePath(e.name)
                    if(path!=null&&!ignored(path)){
                        processed++;seen+=path
                        val temp=File(context.cacheDir,"ghu_"+System.nanoTime()+".bin")
                        val h=copyAndHash(zip,temp)
                        if(remote[path]?.sha!=h.gitSha){
                            changed++;val sha=createBlob(token,target.owner,target.name,temp);treeEntries.put(JSONObject().put("path",path).put("mode","100644").put("type","blob").put("sha",sha));progress(processed,total,path)
                        }else progress(processed,total,"Unchanged • "+path)
                        temp.delete()
                    }
                }
                zip.closeEntry();e=zip.nextEntry
            }
        }
        if(mode==UploadMode.SYNC)remote.keys.filter{it !in seen&&remote[it]?.type=="blob"}.forEach{treeEntries.put(JSONObject().put("path",it).put("mode","100644").put("type","blob").put("sha",JSONObject.NULL));changed++}
        if(treeEntries.length()==0){progress(total,total,"No changes");return target.url}
        val tree=request("POST",API+"/repos/"+enc(target.owner)+"/"+enc(target.name)+"/git/trees",token,JSONObject().put("base_tree",head.treeSha).put("tree",treeEntries).toString());checkOk(tree,"Git tree creation failed")
        val treeSha=JSONObject(tree.body).getString("sha")
        val commit=request("POST",API+"/repos/"+enc(target.owner)+"/"+enc(target.name)+"/git/commits",token,JSONObject().put("message",if(mode==UploadMode.SYNC)"Sync project" else "Update project").put("tree",treeSha).put("parents",JSONArray().put(head.sha)).toString());checkOk(commit,"Commit creation failed")
        val newSha=JSONObject(commit.body).getString("sha")
        val update=request("PATCH",API+"/repos/"+enc(target.owner)+"/"+enc(target.name)+"/git/refs/heads/"+enc(target.branch),token,JSONObject().put("sha",newSha).put("force",false).toString());checkOk(update,"Branch update failed")
        progress(total,total,"Completed • "+changed+" changed");return target.url
    }

    suspend fun downloadRepository(token:String,repo:RepoInfo,out:File,progress:suspend (Long,Long)->Unit){
        val c=URL(API+"/repos/"+enc(repo.owner)+"/"+enc(repo.name)+"/zipball/"+enc(repo.defaultBranch)).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Accept","application/vnd.github+json");c.connectTimeout=20000;c.readTimeout=120000
        check(c.responseCode in 200..299){"Download failed: "+c.responseCode}
        val total=c.contentLengthLong;var done=0L
        c.inputStream.use{input->out.outputStream().use{output->val b=ByteArray(64*1024);while(true){val n=input.read(b);if(n<0)break;output.write(b,0,n);done+=n;progress(done,total)}}};c.disconnect()
    }

    private data class Target(val owner:String,val name:String,val branch:String,val url:String)
    private fun resolveRepo(token:String,mode:UploadMode,name:String,desc:String,privateRepo:Boolean,existing:RepoInfo?):Target{
        if(mode==UploadMode.NEW){
            check(name.matches(Regex("[A-Za-z0-9._-]{1,100}"))){"Invalid repository name"}
            val r=request("POST",API+"/user/repos",token,JSONObject().put("name",name).put("description",desc).put("private",privateRepo).put("auto_init",true).toString());checkOk(r,"Repository creation failed")
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
    private fun tree(token:String,owner:String,repo:String,sha:String):Map<String,Remote>{
        val r=request("GET",API+"/repos/"+enc(owner)+"/"+enc(repo)+"/git/trees/"+sha+"?recursive=1",token);checkOk(r,"Could not read repository tree")
        val root=JSONObject(r.body);check(!root.optBoolean("truncated",false)){"Repository tree is too large for a safe sync"}
        val a=root.getJSONArray("tree");val out=HashMap<String,Remote>();for(i in 0 until a.length()){val o=a.getJSONObject(i);out[o.getString("path")]=Remote(o.optString("sha"),o.optString("type"))};return out
    }

    private fun countFiles(path:String):Int{var n=0;ZipInputStream(File(path).inputStream().buffered()).use{z->var e=z.nextEntry;while(e!=null){val p=safePath(e.name);if(!e.isDirectory&&p!=null&&!ignored(p))n++;z.closeEntry();e=z.nextEntry}};return n}
    private data class HashResult(val gitSha:String,val size:Long)
    private fun copyAndHash(input:InputStream,temp:File):HashResult{
        val b=ByteArray(64*1024);var size=0L
        temp.outputStream().use{out->while(true){val n=input.read(b);if(n<0)break;size+=n;check(size<=MAX_FILE_SIZE){"A file exceeds 90 MB"};out.write(b,0,n)}}
        val md=MessageDigest.getInstance("SHA-1");md.update(("blob "+size+"\u0000").toByteArray())
        temp.inputStream().buffered().use{while(true){val n=it.read(b);if(n<0)break;md.update(b,0,n)}}
        return HashResult(md.digest().toHex(),size)
    }

    private fun createBlob(token:String,owner:String,repo:String,temp:File):String{
        val c=URL(API+"/repos/"+enc(owner)+"/"+enc(repo)+"/git/blobs").openConnection() as HttpURLConnection
        c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Accept","application/vnd.github+json");c.setRequestProperty("X-GitHub-Api-Version","2026-03-10");c.setRequestProperty("Content-Type","application/json");c.connectTimeout=20000;c.readTimeout=120000
        val out=c.outputStream
        out.write("{\"content\":\"".toByteArray());out.flush()
        temp.inputStream().buffered().use{input->val b64=Base64OutputStream(out,android.util.Base64.NO_WRAP);input.copyTo(b64,64*1024);b64.flush()}
        out.write("\",\"encoding\":\"base64\"}".toByteArray());out.flush();out.close()
        val code=c.responseCode;val body=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty();c.disconnect()
        val r=R(code,body);checkOk(r,"Blob upload failed");return JSONObject(r.body).getString("sha")
    }

    private fun safePath(raw:String):String?{val p=raw.replace('\\','/').trimStart('/');if(p.isBlank()||p.startsWith("__MACOSX/")||p.split('/').any{it==".."||it.isBlank()&&p.contains("//")})return null;return p}
    private fun ignored(p:String)=p.split('/').any{it==".git"||it=="build"||it==".gradle"||it==".idea"}||p.endsWith("local.properties")||p.endsWith(".log")
    private fun ByteArray.toHex()=joinToString(""){"%02x".format(it)}
    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8").replace("+","%20")
    private data class R(val code:Int,val body:String)
    private fun checkOk(r:R,msg:String){check(r.code in 200..299){msg+" ("+r.code+"): "+r.body.take(500)}}
    private fun request(method:String,url:String,token:String,body:String?=null):R{
        val c=URL(url).openConnection() as HttpURLConnection;c.requestMethod=method;c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Accept","application/vnd.github+json");c.setRequestProperty("X-GitHub-Api-Version","2026-03-10");c.setRequestProperty("Content-Type","application/json");c.connectTimeout=20000;c.readTimeout=120000
        if(body!=null){c.doOutput=true;c.outputStream.use{it.write(body.toByteArray())}};val code=c.responseCode;val text=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty();c.disconnect();return R(code,text)
    }
}