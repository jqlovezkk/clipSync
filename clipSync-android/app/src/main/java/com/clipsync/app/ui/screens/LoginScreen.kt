package com.clipsync.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.clipsync.app.ui.theme.ClipSyncPrimary
import com.clipsync.app.ui.theme.ClipSyncPrimaryGlow
import com.clipsync.app.ui.theme.ClipSyncSecondary

@Composable
fun LoginScreen(
    onLogin: (serverUrl: String, httpUrl: String, username: String, password: String) -> Unit,
    onRegister: (serverUrl: String, httpUrl: String, username: String, password: String) -> Unit,
    isLoggingIn: Boolean,
    errorMessage: String?,
    onClearError: () -> Unit
) {
    var serverUrl by rememberSaveable { mutableStateOf("ws://8.141.100.238:8080") }
    var httpUrl by rememberSaveable { mutableStateOf("http://8.141.100.238:8081") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isRegisterMode by rememberSaveable { mutableStateOf(false) }
    
    // 流体渐变背景动画
    val infiniteTransition = rememberInfiniteTransition(label = "gradient")
    val gradientShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gradientShift"
    )
    
    // 渐变背景
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    ),
                    start = androidx.compose.ui.geometry.Offset(0f, gradientShift * 1000f),
                    end = androidx.compose.ui.geometry.Offset(gradientShift * 1000f, 1000f)
                )
            )
    ) {
        // 装饰性光晕效果
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 100.dp)
                // 使用 offset 实现越界装饰，避免负 padding 在运行时崩溃
                .offset(x = 50.dp)
                .size(200.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(ClipSyncPrimaryGlow, androidx.compose.ui.graphics.Color.Transparent)
                    )
                )
        )
        
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 150.dp)
                // 使用 offset 实现越界装饰，避免负 padding 在运行时崩溃
                .offset(x = (-80).dp)
                .size(250.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ClipSyncSecondary.copy(alpha = 0.15f),
                            androidx.compose.ui.graphics.Color.Transparent
                        )
                    )
                )
        )
        
        // 主内容卡片
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 玻璃态卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Logo 和品牌名
                    Text(
                        text = "⚡ ClipSync",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Sync your clipboard across devices",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // 输入表单
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = {
                            serverUrl = it
                            httpUrl = it.replace("ws://", "http://").replace("wss://", "https://").replace(":8080", ":8081")
                        },
                        label = { Text("WebSocket Server URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = httpUrl,
                        onValueChange = { httpUrl = it },
                        label = { Text("HTTP Server URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; onClearError() },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; onClearError() },
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // 错误信息
                    errorMessage?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // 按钮组
                    if (isLoggingIn) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Button(
                            onClick = { 
                                if (isRegisterMode) {
                                    onRegister(serverUrl, httpUrl, username, password)
                                } else {
                                    onLogin(serverUrl, httpUrl, username, password)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = username.isNotBlank() && password.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                text = if (isRegisterMode) "Create Account" else "Sign In",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        TextButton(
                            onClick = { isRegisterMode = !isRegisterMode },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (isRegisterMode) "Already have an account? Sign In" else "Don't have an account? Create One",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }
}
