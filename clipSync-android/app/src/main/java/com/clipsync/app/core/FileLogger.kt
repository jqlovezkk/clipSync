package com.clipsync.app.core

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 日志工具类，同时将日志输出到Logcat和文件
 * 日志文件按日期滚动，保留最近30天
 */
object FileLogger {

    private const val LOG_TAG = "ClipSync"
    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_PREFIX = "clipsync_android_"
    private const val LOG_FILE_SUFFIX = ".log"
    private const val MAX_RETENTION_DAYS = 30

    // 日期格式用于文件名
    private val FILE_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    // 日期格式用于日志内容
    private val LOG_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // 异步日志写入队列
    private val logQueue = LinkedBlockingQueue<String>()
    private val executor = Executors.newSingleThreadExecutor()
    private var currentLogDateString = ""
    private var logWriter: PrintWriter? = null
    private var appContext: Context? = null
    private var logDirPath: String = "not initialized"

    /**
     * 初始化日志器
     */
    fun init(context: Context) {
        try {
            appContext = context.applicationContext
            currentLogDateString = FILE_DATE_FORMAT.format(Date())

            // 尝试写入到内部存储，如果失败则使用缓存目录
            val success = openLogFile()
            if (!success) {
                Log.e(LOG_TAG, "Failed to initialize file logger, will only output to Logcat")
            }

            // 启动后台线程处理日志写入
            executor.execute {
                while (true) {
                    try {
                        val logLine = logQueue.poll(1, TimeUnit.SECONDS)
                        if (logLine != null) {
                            writeToFile(logLine)
                        }

                        // 检查是否需要滚动日志（跨天）
                        val todayString = FILE_DATE_FORMAT.format(Date())
                        if (todayString != currentLogDateString) {
                            rotateLogFile()
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Error in log writer thread", e)
                    }
                }
            }

            // 启动时清理旧日志
            cleanupOldLogFiles()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to initialize FileLogger", e)
        }
    }

    /**
     * 打开今天的日志文件
     * @return true if successful, false otherwise
     */
    private fun openLogFile(): Boolean {
        return try {
            val logDir = getLogDirectory()
            if (!logDir.exists()) {
                val created = logDir.mkdirs()
                if (!created) {
                    Log.w(LOG_TAG, "Failed to create log directory: ${logDir.absolutePath}")
                }
            }

            val fileName = "$LOG_FILE_PREFIX$currentLogDateString$LOG_FILE_SUFFIX"
            val logFile = File(logDir, fileName)
            logDirPath = logDir.absolutePath

            logWriter = PrintWriter(FileWriter(logFile, true), true)
            Log.d(LOG_TAG, "Log file opened: ${logFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to open log file", e)
            false
        }
    }

    /**
     * 获取日志目录
     */
    private fun getLogDirectory(): File {
        val context = appContext ?: throw IllegalStateException("FileLogger not initialized")
        // 优先使用内部存储 (私有目录，不需要权限)
        return File(context.filesDir, LOG_DIR_NAME)
    }

    /**
     * 写入日志到文件
     */
    private fun writeToFile(line: String) {
        try {
            val writer = logWriter
            if (writer == null) {
                Log.w(LOG_TAG, "Log writer is null, cannot write to file. Dir: $logDirPath")
                return
            }
            writer.println(line)
            writer.flush()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to write to log file", e)
        }
    }

    /**
     * 滚动日志文件（跨天时调用）
     */
    private fun rotateLogFile() {
        try {
            logWriter?.close()
            logWriter = null

            currentLogDateString = FILE_DATE_FORMAT.format(Date())
            val success = openLogFile()
            if (success) {
                Log.d(LOG_TAG, "Log file rotated to: $logDirPath")
            }

            // 滚动时清理旧日志
            cleanupOldLogFiles()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to rotate log file", e)
        }
    }

    /**
     * 清理超过保留天数的旧日志文件
     */
    private fun cleanupOldLogFiles() {
        try {
            val logDir = getLogDirectory()
            if (!logDir.exists()) return

            val cutoffDate = System.currentTimeMillis() - (MAX_RETENTION_DAYS * 24L * 60 * 60 * 1000)
            val logFiles = logDir.listFiles { _, name ->
                name.startsWith(LOG_FILE_PREFIX) && name.endsWith(LOG_FILE_SUFFIX)
            }

            if (logFiles != null && logFiles.isNotEmpty()) {
                Log.d(LOG_TAG, "Found ${logFiles.size} log files, cleaning up old ones")
                logFiles.forEach { file ->
                    if (file.lastModified() < cutoffDate) {
                        val deleted = file.delete()
                        Log.d(LOG_TAG, "Deleted old log file: ${file.name}, success=$deleted")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to cleanup old log files", e)
        }
    }

    /**
     * 输出Debug级别日志
     */
    @JvmStatic
    fun d(tag: String, message: String) {
        Log.d(LOG_TAG, "[$tag] $message")
        queueLogLine("D", tag, message)
    }

    /**
     * 输出Info级别日志
     */
    @JvmStatic
    fun i(tag: String, message: String) {
        Log.i(LOG_TAG, "[$tag] $message")
        queueLogLine("I", tag, message)
    }

    /**
     * 输出Warning级别日志
     */
    @JvmStatic
    fun w(tag: String, message: String) {
        Log.w(LOG_TAG, "[$tag] $message")
        queueLogLine("W", tag, message)
    }

    /**
     * 输出Error级别日志
     */
    @JvmStatic
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message | ${throwable::class.java.simpleName}: ${throwable.message}"
        } else {
            message
        }
        Log.e(LOG_TAG, "[$tag] $fullMessage")
        queueLogLine("E", tag, fullMessage)
        if (throwable != null) {
            queueLogLine("E", tag, Log.getStackTraceString(throwable))
        }
    }

    /**
     * 将日志行加入队列
     */
    private fun queueLogLine(level: String, tag: String, message: String) {
        try {
            val timestamp = LOG_DATE_FORMAT.format(Date())
            val logLine = "$timestamp [$level] [$tag] $message"
            val offered = logQueue.offer(logLine)
            if (!offered) {
                Log.w(LOG_TAG, "Failed to enqueue log line")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error queuing log line", e)
        }
    }

    /**
     * 关闭日志器
     */
    fun shutdown() {
        try {
            logWriter?.close()
            logWriter = null
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to shutdown logger", e)
        }
    }
}
