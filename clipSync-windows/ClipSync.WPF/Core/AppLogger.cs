using System;
using System.IO;
using System.Linq;

namespace ClipSync.WPF.Core
{
    public static class AppLogger
    {
        private static readonly object SyncRoot = new object();
        // 日志目录：仓库根目录下的 logs 文件夹
        // 从 bin/Debug/net8.0-windows 回溯 6 级到仓库根目录 (clipSync/)，再进入 logs
        // 路径: .../clipSync/clipSync-windows/ClipSync.WPF/bin/Debug/net8.0-windows/
        private static readonly string RepoRoot = Path.GetFullPath(
            Path.Combine(AppDomain.CurrentDomain.BaseDirectory,
            "..", "..", "..", "..", "..", ".."));
        private static readonly string LogDirectory = Path.Combine(RepoRoot, "logs");

        // 保留旧日志文件的最大天数
        private const int MaxRetentionDays = 30;

        /// <summary>
        /// 根据当前日期生成日志文件路径，格式: client_win_2026-04-27.log
        /// </summary>
        private static string GetCurrentLogFilePath()
        {
            var fileName = $"client_win_{DateTime.Now:yyyy-MM-dd}.log";
            return Path.Combine(LogDirectory, fileName);
        }

        public static void Info(string source, string message)
        {
            Write("INFO", source, message);
        }

        public static void Warn(string source, string message)
        {
            Write("WARN", source, message);
        }

        public static void Error(string source, string message, Exception? ex = null)
        {
            var finalMessage = ex == null ? message : $"{message} | {ex.GetType().Name}: {ex.Message}";
            Write("ERROR", source, finalMessage);
        }

        private static void Write(string level, string source, string message)
        {
            try
            {
                var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [{level}] [{source}] {message}";
                System.Diagnostics.Debug.WriteLine(line);

                lock (SyncRoot)
                {
                    Directory.CreateDirectory(LogDirectory);
                    var currentLogPath = GetCurrentLogFilePath();
                    File.AppendAllText(currentLogPath, line + Environment.NewLine);

                    // 定期清理旧日志文件
                    CleanupOldLogFiles();
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"[AppLogger] Failed to write log: {ex.Message}");
            }
        }

        /// <summary>
        /// 清理超过保留天数的旧日志文件
        /// </summary>
        private static void CleanupOldLogFiles()
        {
            try
            {
                if (!Directory.Exists(LogDirectory))
                    return;

                var cutoffDate = DateTime.Now.AddDays(-MaxRetentionDays);
                var logFiles = Directory.GetFiles(LogDirectory, "client_win_*.log");

                foreach (var file in logFiles)
                {
                    var fileName = Path.GetFileNameWithoutExtension(file);
                    // 解析文件名中的日期部分: client_win_2026-04-27
                    var datePart = fileName.Replace("client_win_", "");
                    if (DateTime.TryParseExact(datePart, "yyyy-MM-dd", null, System.Globalization.DateTimeStyles.None, out var fileDate))
                    {
                        if (fileDate < cutoffDate)
                        {
                            File.Delete(file);
                        }
                    }
                }
            }
            catch
            {
                // 清理失败不影响日志写入
            }
        }

        // 启动时输出日志目录路径到调试输出
        static AppLogger()
        {
            System.Diagnostics.Debug.WriteLine($"[AppLogger] Log directory: {LogDirectory}");
            System.Diagnostics.Debug.WriteLine($"[AppLogger] Repository root: {RepoRoot}");
        }
    }
}
