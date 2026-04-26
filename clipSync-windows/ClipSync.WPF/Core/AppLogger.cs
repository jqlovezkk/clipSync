using System;
using System.IO;

namespace ClipSync.WPF.Core
{
    public static class AppLogger
    {
        private static readonly object SyncRoot = new object();
        private static readonly string LogDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "ClipSync",
            "logs");
        private static readonly string LogFilePath = Path.Combine(LogDirectory, "client.log");

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
                // 统一写入调试输出和本地日志文件，方便没有附加调试器时排查问题。
                var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [{level}] [{source}] {message}";
                System.Diagnostics.Debug.WriteLine(line);

                lock (SyncRoot)
                {
                    Directory.CreateDirectory(LogDirectory);
                    File.AppendAllText(LogFilePath, line + Environment.NewLine);
                }
            }
            catch
            {
                // 日志不能影响主流程。
            }
        }
    }
}
