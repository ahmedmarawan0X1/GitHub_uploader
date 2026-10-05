package com.jhftyyyty.githubuploader
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
@Composable internal fun AppTheme(dark:Boolean,content:@Composable () -> Unit){MaterialTheme(colorScheme=if(dark)darkColorScheme(background=Color(0xFF090A0D),surface=Color(0xFF111318),surfaceVariant=Color(0xFF1A1D24)) else lightColorScheme(background=Color(0xFFF7F8FA),surface=Color.White),content=content)}