package com.example.wimudatasampler.utils

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

import java.io.InputStream
import java.io.OutputStream

class ImageUtil {
    companion object {
        fun getImageFolderPath(context: Context): String {
            val dir =
                File(context.getExternalFilesDir(null), "picture").apply { mkdirs() }
            return dir.absolutePath
        }

        fun saveImageToExternalStorage(
            context: Context,
            uri: Uri,
            fileName: String,
        ): Boolean {
            return try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return false
                val userDir = getImageFolderPath(context=context)
                val outputFile = File(userDir, fileName)
                inputStream.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        copyStream(input, output)
                    }
                }
                outputFile.exists()
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
        private fun copyStream(input: InputStream?, output: OutputStream) {
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (input?.read(buffer).also { bytesRead = it ?: -1 } != -1) {
                output.write(buffer, 0, bytesRead)
            }
        }
    }
}