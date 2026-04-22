package com.kankan.globaltraveling

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kankan.globaltraveling.ui.theme.ShadowTheme

class GuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShadowTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GuideScreen(onFinish = {
                        startActivity(Intent(this@GuideActivity, MainActivity::class.java))
                        finish()
                    })
                }
            }
        }
    }
}

@Composable
fun GuideScreen(onFinish: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("欢迎使用位置模拟器", fontSize = 24.sp)
        Spacer(modifier = Modifier.height(32.dp))
        Text("使用前请完成以下步骤：")
        Spacer(modifier = Modifier.height(16.dp))
        Text("1. 安装 Xposed 框架并激活本模块")
        Text("2. 授予 Root 权限和位置权限")
        Text("3. 在 Xposed 中勾选「系统框架」和「目标应用」并重启")
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onFinish) {
            Text("开始使用")
        }
    }
}