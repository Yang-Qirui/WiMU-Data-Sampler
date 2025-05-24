package com.example.wimuutils.page.ai

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.content.MediaType.Companion.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wimuutils.helper.TorchHelper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth

@Composable
fun AiScreen() {
    val context = LocalContext.current
    val viewModel: aiViewModel = viewModel()
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                viewModel.selectedImage = BitmapFactory.decodeStream(stream)
            }
        }
    }

    // 初始化加载模型
    LaunchedEffect(Unit) {
        viewModel.loadModel(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 选择图片按钮
        Button(
            onClick = { imagePicker.launch("image/*") },
            enabled = !viewModel.processing
        ) {
            Text("Select Image")
        }

        // 显示图片
        viewModel.selectedImage?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Selected Image",
                modifier = Modifier
                    .height(200.dp)
                    .width(200.dp)
                    .padding(8.dp)
                    .background(Color.LightGray)
            )

            // 执行推理按钮
            Button(
                onClick = { viewModel.runInference() },
                enabled = !viewModel.processing
            ) {
                Text("Recognize Digit")
            }
        }

        // 显示状态
        when {
            viewModel.processing -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            viewModel.error != null -> Text(
                text = "Error: ${viewModel.error}",
                color = Color.Red,
                modifier = Modifier.padding(8.dp))
            !viewModel.result.isNullOrEmpty() -> Text(  // 更安全的空值检查
                text = viewModel.result!!,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}