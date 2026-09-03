package com.jhftyyyty.githubuploader

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

data class RepoInfo(val owner: String, val name: String, val fullName: String, val defaultBranch: String, val private: Boolean)

internal object GitHubApi {
    private const val API = "https://api.github.com"

    fun listRepositories(token: String): List<RepoInfo> {
        val r = request("GET", "$API/user/repos?per_page=100&sort=updated", token)
        check(r.code in 200..299) { "GitHub ${r.code}: ${r.body}" }
        val a = JSONArray(r.body)
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(RepoInfo(
                    o.getJSONObject("owner").getString("login"),
                    o.getString("name"),
                    o.getString("full_name"),
                    o.optString("default_branch", "main"),
                    o.optBoolean("private", false)
                ))
            }
        }
    }

    fun uploadZip(
        context: Context, uri: Uri, token: String, mode: UploadMode,
        newRepoName: String, description: String, privateRepo: Boolean, existing: RepoInfo?,
        progress: (Int, Int, String) -> Unit
    ): String {
        return uploadZipInternal(
            context, token, mode, newRepoName, description, privateRepo, existing,
            { context.contentResolver.openInputStream(uri) ?: error("Could not open ZIP file") }, progress
        )
    }

    private fun uploadZipInternal(
        context: Context, token: String, mode: UploadMode,
        newRepoName: String, description: String, privateRepo: Boolean, existing: RepoInfo?,
        openInput: () -> java.io.InputStream,
        progress: (Int, Int, String) -> Unit
    ): String {

        val owner: String
        val repo: String
        val branch: String

        if (mode == UploadMode.NEW) {
            val body = JSONObject().put("name", newRepoName).put("description", description)
                .put("private", privateRepo).put("auto_init", true)
            val r = request("POST", "$API/user/repos", token, body.toString())
            check(r.code in 200..299) { "Repository creation failed: ${r.body}" }
            val o = JSONObject(r.body)
            owner = o.getJSONObject("owner").getString("login")
            repo = o.getString("name")
            branch = o.optString("default_branch", "main")
        } else {
            val s = existing ?: error("No Repository selected")
            owner = s.owner
            repo = s.name
            val r = request("GET", "$API/repos/${enc(owner)}/${enc(repo)}", token)
            check(r.code in 200..299) { "Could not access Repository: ${r.body}" }
            branch = JSONObject(r.body).optString("default_branch", s.defaultBranch.ifBlank { "main" })
        }

        val files = readZipFromStream(openInput)
        if (files.isEmpty()) error("ZIP file contains no files")
        progress(0, files.size, "Found ${files.size} files")

        val ref = request("GET", "$API/repos/${enc(owner)}/${enc(repo)}/git/ref/heads/${enc(branch)}", token)
        check(ref.code in 200..299) { "Could not read branch $branch: ${ref.body}" }
        val head = JSONObject(ref.body).getJSONObject("object").getString("sha")

        val commit = request("GET", "$API/repos/${enc(owner)}/${enc(repo)}/git/commits/$head", token)
        check(commit.code in 200..299) { "Could not read latest commit: ${commit.body}" }
        val baseTree = JSONObject(commit.body).getJSONObject("tree").getString("sha")

        val entries = JSONArray()
        files.forEachIndexed { index, f ->
            val blob = request(
                "POST", "$API/repos/${enc(owner)}/${enc(repo)}/git/blobs", token,
                JSONObject().put("content", Base64.encodeToString(f.bytes, Base64.NO_WRAP)).put("encoding", "base64").toString()
            )
            check(blob.code in 200..299) { "Blob failed for ${f.path}: ${blob.body}" }
            val sha = JSONObject(blob.body).getString("sha")
            entries.put(JSONObject().put("path", f.path).put("mode", "100644").put("type", "blob").put("sha", sha))
            progress(index + 1, files.size, "Uploading and preparing: ${f.path}")
        }

        val tree = request(
            "POST", "$API/repos/${enc(owner)}/${enc(repo)}/git/trees", token,
            JSONObject().put("base_tree", baseTree).put("tree", entries).toString()
        )
        check(tree.code in 200..299) { "Git Tree creation failed: ${tree.body}" }
        val treeSha = JSONObject(tree.body).getString("sha")

        val newCommit = request(
            "POST", "$API/repos/${enc(owner)}/${enc(repo)}/git/commits", token,
            JSONObject().put("message", "Upload ZIP project").put("tree", treeSha)
                .put("parents", JSONArray().put(head)).toString()
        )
        check(newCommit.code in 200..299) { "Commit creation failed: ${newCommit.body}" }
        val newSha = JSONObject(newCommit.body).getString("sha")

        val update = request(
            "PATCH", "$API/repos/${enc(owner)}/${enc(repo)}/git/refs/heads/${enc(branch)}", token,
            JSONObject().put("sha", newSha).put("force", false).toString()
        )
        check(update.code in 200..299) { "Branch update failed: ${update.body}" }

        progress(files.size, files.size, "Upload completed")
        return "https://github.com/$owner/$repo"
    }

    fun uploadZipFile(
        context: Context, filePath: String, token: String, mode: UploadMode,
        newRepoName: String, description: String, privateRepo: Boolean, existing: RepoInfo?,
        progress: (Int, Int, String) -> Unit
    ): String {
        return uploadZipInternal(
            context, token, mode, newRepoName, description, privateRepo, existing,
            { java.io.FileInputStream(java.io.File(filePath)) }, progress
        )
    }

    private data class Z(val path: String, val bytes: ByteArray)

    private fun readZip(context: Context, uri: Uri): List<Z> =
        readZipFromStream { context.contentResolver.openInputStream(uri) ?: error("Could not open ZIP file") }

    private fun readZipFromStream(open: () -> java.io.InputStream): List<Z> {
        val result = mutableListOf<Z>()
        open().use { input ->
            ZipInputStream(input).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val path = e.name.replace('\\', '/').trimStart('/')
                        if (path.isNotBlank() && !path.split('/').any { it == ".." } && !path.startsWith("__MACOSX/")) {
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val n = zip.read(buffer)
                                if (n <= 0) break
                                total += n
                                if (total > MAX_FILE_SIZE) error("File $path is larger than 90MB")
                                out.write(buffer, 0, n)
                            }
                            result += Z(path, out.toByteArray())
                        }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        } ?: error("Could not open ZIP file")
        return result
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private data class R(val code: Int, val body: String)

    private fun request(method: String, endpoint: String, token: String, body: String? = null): R {
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 20000
            readTimeout = 120000
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        return R(code, text)
    }
}

