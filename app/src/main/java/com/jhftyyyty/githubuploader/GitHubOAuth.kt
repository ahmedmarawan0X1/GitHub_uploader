package com.jhftyyyty.githubuploader

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import kotlinx.coroutines.delay

internal object GitHubOAuth{
    data class DeviceCode(val deviceCode:String,val userCode:String,val verificationUri:String,val expiresIn:Int,val interval:Int)
    private val clientId get()=BuildConfig.GITHUB_CLIENT_ID
    fun requestDeviceCode():DeviceCode{
        check(clientId.isNotBlank()){"Browser login is not configured in this build"}
        val body="client_id="+enc(clientId)+"&scope="+enc("repo read:user workflow")
        val r=post("https://github.com/login/device/code",body);check(r.code in 200..299){"GitHub OAuth "+r.code+": "+r.body}
        val o=JSONObject(r.body);return DeviceCode(o.getString("device_code"),o.getString("user_code"),o.getString("verification_uri"),o.getInt("expires_in"),o.optInt("interval",5))
    }
    suspend fun pollForToken(d:DeviceCode):String{
        var wait=d.interval.toLong();val deadline=System.currentTimeMillis()+d.expiresIn*1000L
        while(System.currentTimeMillis()<deadline){
            delay(wait*1000)
            val body="client_id="+enc(clientId)+"&device_code="+enc(d.deviceCode)+"&grant_type="+enc("urn:ietf:params:oauth:grant-type:device_code")
            val r=post("https://github.com/login/oauth/access_token",body);val o=JSONObject(r.body);o.optString("access_token").takeIf{it.isNotBlank()}?.let{return it}
            when(o.optString("error")){"authorization_pending"->Unit;"slow_down"->wait+=5;"access_denied"->error("Authorization was denied");"expired_token"->error("Authorization code expired");else->error(o.optString("error_description",o.optString("error","OAuth failed")))}
        }
        error("Authorization timed out")
    }
    private fun enc(v:String)=URLEncoder.encode(v,"UTF-8")
    private fun post(url:String,body:String):R{val c=URL(url).openConnection() as HttpURLConnection;c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Accept","application/json");c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");c.connectTimeout=20000;c.readTimeout=30000;c.outputStream.use{it.write(body.toByteArray())};val code=c.responseCode;val text=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty();c.disconnect();return R(code,text)}
    private data class R(val code:Int,val body:String)
}