package com.jhftyyyty.githubuploader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class TokenStore(private val context:Context){
    private val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    fun get():String{
        val encrypted=prefs.getString(KEY_TOKEN,null)
        if(!encrypted.isNullOrBlank()) return runCatching{decrypt(encrypted)}.getOrDefault("")
        val legacy=prefs.getString("token","").orEmpty()
        if(legacy.isNotBlank()){save(legacy);prefs.edit().remove("token").apply()}
        return legacy
    }
    fun save(value:String){if(value.isBlank())prefs.edit().remove(KEY_TOKEN).apply()else prefs.edit().putString(KEY_TOKEN,encrypt(value)).apply()}
    fun clear(){prefs.edit().remove(KEY_TOKEN).remove("token").apply()}
    private fun key():SecretKey{
        val ks=java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey(KEY_ALIAS,null) as? SecretKey)?.let{return it}
        val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return gen.generateKey()
    }
    private fun encrypt(value:String):String{val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());val data=c.doFinal(value.toByteArray(StandardCharsets.UTF_8));return Base64.encodeToString(c.iv+data,Base64.NO_WRAP)}
    private fun decrypt(value:String):String{val all=Base64.decode(value,Base64.NO_WRAP);val iv=all.copyOfRange(0,12);val data=all.copyOfRange(12,all.size);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv));return String(c.doFinal(data),StandardCharsets.UTF_8)}
    companion object{private const val KEY_ALIAS="github_uploader_token";private const val KEY_TOKEN="token_secure"}
}