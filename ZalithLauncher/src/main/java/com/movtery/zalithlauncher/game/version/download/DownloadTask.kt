/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.version.download

import com.movtery.zalithlauncher.utils.file.check7z
import com.movtery.zalithlauncher.utils.file.checkZip
import com.movtery.zalithlauncher.utils.file.compareSHA1
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.downloadFromMirrorList
import com.movtery.zalithlauncher.utils.string.getMessageOrToString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.FileNotFoundException

private const val TAG = "DownloadTask"
private const val MAX_RETRY_ATTEMPTS = 3

class DownloadTask(
    val urls: List<String>,
    private val verifyIntegrity: Boolean,
    private val bufferSize: Int = 32768,
    val targetFile: File,
    val sha1: String?,
    /** 是否本身是可以被下载的，如果不可下载，则通过提供url尝试下载，如果失败则抛出 FileNotFoundException */
    val isDownloadable: Boolean,
    private val onDownloadFailed: (DownloadTask) -> Unit = {},
    private val onFileDownloadedSize: (Long) -> Unit = {},
    private val onFileDownloaded: () -> Unit = {},
    private val expectedFileSize: Long = 0L
) {
    /**
     * 文件下载成功后执行的任务
     */
    var fileDownloadedTask: (suspend () -> Unit)? = null
    private var attemptCount: Int = 0

    suspend fun download() {
        //若目标文件存在，验证通过或关闭完整性验证时，跳过此次下载
        val file = targetFile
        if (file.exists() && verifySha1(file)) {
            downloadedSize(FileUtils.sizeOf(file))
            downloadedFile()
            return
        }

        runCatching {
            runInterruptible {
                downloadFromMirrorList(
                    urls = urls,
                    sha1 = sha1,
                    outputFile = file,
                    bufferSize = bufferSize
                ) { size ->
                    downloadedSize(size)
                }
            }
            downloadedFile()
        }.onFailure { e ->
            if (e is CancellationException) throw e
            
            attemptCount++
            val fileSize = if (file.exists()) FileUtils.sizeOf(file) else 0L
            val isCorrupted = isFileCorrupted(fileSize)
            
            Logger.error(
                TAG, 
                "Download failed (Attempt $attemptCount/$MAX_RETRY_ATTEMPTS): ${file.absolutePath}\n" +
                "File size: ${formatFileSize(fileSize)}, Expected: ${formatFileSize(expectedFileSize)}\n" +
                "Corrupted: $isCorrupted\n" +
                "Error: ${e.getMessageOrToString()}"
            )
            
            // Solo eliminar archivos después de máximo de reintentos o si está claramente corrupto
            if (isCorrupted && attemptCount >= MAX_RETRY_ATTEMPTS) {
                Logger.warning(TAG, "File appears corrupted after $MAX_RETRY_ATTEMPTS attempts. Deleting: ${file.absolutePath}")
                FileUtils.deleteQuietly(file)
            } else if (attemptCount < MAX_RETRY_ATTEMPTS && fileSize > 0 && !isCorrupted) {
                // Mantener el archivo parcialmente descargado para reintentos
                Logger.info(TAG, "Keeping partial download for resumption. Size: ${formatFileSize(fileSize)}")
            }
            
            if (!isDownloadable && e is FileNotFoundException) throw e
            onDownloadFailed(this)
        }
    }

    private fun downloadedSize(size: Long) {
        onFileDownloadedSize(size)
    }

    private suspend fun downloadedFile() {
        onFileDownloaded()
        withContext(Dispatchers.IO) {
            fileDownloadedTask?.invoke()
        }
    }

    /**
     * 若目标文件存在，验证完整性
     * @return 是否跳过此次下载
     */
    private fun verifySha1(file: File): Boolean {
        if (!file.exists()) return false
        if (!verifyIntegrity) return true

        if (sha1.isNullOrBlank()) {
            //排除目标无法被下载的情况，比如Forge的client
            if (!isDownloadable) return true
            return verifyFileWithoutSha1(file)
        }

        return if (compareSHA1(file, sha1)) {
            Logger.info(TAG, "File verified successfully: ${file.absolutePath}")
            true
        } else {
            val fileSize = FileUtils.sizeOf(file)
            Logger.warning(TAG, "SHA1 mismatch for ${file.absolutePath}. Size: ${formatFileSize(fileSize)}")
            
            // Solo eliminar si el archivo está claramente corrupto
            if (isFileCorrupted(fileSize)) {
                FileUtils.deleteQuietly(file)
            } else {
                Logger.info(TAG, "Keeping partial download for retry")
            }
            false
        }
    }

    private fun verifyFileWithoutSha1(file: File): Boolean {
        val isAvailable = when (file.extension.lowercase()) {
            "zip", "jar" -> checkZip(file)
            "7z" -> check7z(file)
            else -> {
                //普通文件或是暂不受支持的压缩包
                return true
            }
        }

        if (isAvailable) {
            Logger.info(TAG, "File structure verified: ${file.absolutePath}")
            return true
        }

        Logger.warning(TAG, "File structure is invalid: ${file.absolutePath}")
        val fileSize = FileUtils.sizeOf(file)
        
        // Solo eliminar si el archivo está obviamente corrupto
        if (isFileCorrupted(fileSize)) {
            FileUtils.deleteQuietly(file)
        }
        return false
    }
    
    /**
     * Determina si un archivo está corrupto basándose en su tamaño
     */
    private fun isFileCorrupted(fileSize: Long): Boolean {
        if (fileSize == 0L) return false
        
        // Si no conocemos el tamaño esperado
        if (expectedFileSize <= 0) {
            return fileSize < 1024 * 50 // Menos de 50KB es sospechoso
        }
        
        // Si el archivo es menos del 30% del tamaño esperado
        val ratio = fileSize.toDouble() / expectedFileSize.toDouble()
        return ratio < 0.3
    }
    
    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
