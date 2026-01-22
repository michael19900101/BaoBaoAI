package com.aotuman.baobaoai.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object AssetUtil {
    private const val TAG = "AssetUtil"

    /**
     * 复制assets中的文件到内部存储
     */
    fun copyAsset(context: Context, assetPath: String, outFile: File) {
        try {
            // 检查文件是否存在且有内容
            if (outFile.exists() && outFile.length() > 0) {
                return
            }

            Log.d(TAG, "Copying asset $assetPath to ${outFile.absolutePath}")
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset: $assetPath", e)
            // 删除可能的部分文件
            if (outFile.exists()) {
                outFile.delete()
            }
        }
    }
}