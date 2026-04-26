using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using Microsoft.Data.Sqlite;

namespace ClipSync.WPF.Storage
{
    public class LocalDatabase : IDisposable
    {
        private readonly string _dbPath;
        private bool _isInitialized;
        private bool _isDisposed;

        public LocalDatabase()
        {
            var appDataPath = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
            var clipSyncDir = Path.Combine(appDataPath, "ClipSync");
            if (!Directory.Exists(clipSyncDir))
            {
                Directory.CreateDirectory(clipSyncDir);
            }
            _dbPath = Path.Combine(clipSyncDir, "clipsync.db");
        }

        public async Task InitializeAsync()
        {
            if (_isInitialized) return;

            await Task.Run(() =>
            {
                using var connection = new SqliteConnection($"Data Source={_dbPath}");
                connection.Open();

                var createTableCommand = connection.CreateCommand();
                createTableCommand.CommandText = @"
                    CREATE TABLE IF NOT EXISTS clipboard_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        content_type TEXT NOT NULL,
                        content TEXT NOT NULL,
                        format TEXT NOT NULL,
                        size INTEGER DEFAULT 0,
                        checksum TEXT NOT NULL,
                        source_device_id TEXT NOT NULL,
                        source_device_name TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )";
                createTableCommand.ExecuteNonQuery();

                var createIndexCommand = connection.CreateCommand();
                createIndexCommand.CommandText = @"
                    CREATE INDEX IF NOT EXISTS idx_clipboard_created_at
                    ON clipboard_history(created_at DESC)";
                createIndexCommand.ExecuteNonQuery();

                _isInitialized = true;
            });
        }

        public async Task InsertClipboardItemAsync(Network.ClipboardItem item)
        {
            await EnsureInitializedAsync();

            await Task.Run(() =>
            {
                using var connection = new SqliteConnection($"Data Source={_dbPath}");
                connection.Open();

                var command = connection.CreateCommand();
                command.CommandText = @"
                    INSERT INTO clipboard_history
                    (content_type, content, format, size, checksum, source_device_id, source_device_name, created_at)
                    VALUES
                    (@contentType, @content, @format, @size, @checksum, @sourceDeviceId, @sourceDeviceName, @createdAt)";
                command.Parameters.AddWithValue("@contentType", item.ContentType);
                command.Parameters.AddWithValue("@content", item.Content);
                command.Parameters.AddWithValue("@format", item.Format);
                command.Parameters.AddWithValue("@size", item.Size);
                command.Parameters.AddWithValue("@checksum", item.Checksum);
                command.Parameters.AddWithValue("@sourceDeviceId", item.SourceDeviceId);
                command.Parameters.AddWithValue("@sourceDeviceName", item.SourceDeviceName);
                command.Parameters.AddWithValue("@createdAt", item.CreatedAt);
                command.ExecuteNonQuery();

                // Keep only last 50 items
                var deleteCommand = connection.CreateCommand();
                deleteCommand.CommandText = @"
                    DELETE FROM clipboard_history
                    WHERE id NOT IN (
                        SELECT id FROM clipboard_history
                        ORDER BY created_at DESC
                        LIMIT 50
                    )";
                deleteCommand.ExecuteNonQuery();
            });
        }

        public async Task<List<Network.ClipboardItem>> GetClipboardHistoryAsync(int limit = 50)
        {
            await EnsureInitializedAsync();

            var items = new List<Network.ClipboardItem>();

            await Task.Run(() =>
            {
                using var connection = new SqliteConnection($"Data Source={_dbPath}");
                connection.Open();

                var command = connection.CreateCommand();
                command.CommandText = @"
                    SELECT id, content_type, content, format, size, checksum,
                           source_device_id, source_device_name, created_at
                    FROM clipboard_history
                    ORDER BY created_at DESC
                    LIMIT @limit";
                command.Parameters.AddWithValue("@limit", limit);

                using var reader = command.ExecuteReader();
                while (reader.Read())
                {
                    items.Add(new Network.ClipboardItem
                    {
                        Id = reader.GetInt64(0),
                        ContentType = reader.GetString(1),
                        Content = reader.GetString(2),
                        Format = reader.GetString(3),
                        Size = reader.GetInt64(4),
                        Checksum = reader.GetString(5),
                        SourceDeviceId = reader.GetString(6),
                        SourceDeviceName = reader.GetString(7),
                        CreatedAt = reader.GetInt64(8)
                    });
                }
            });

            return items;
        }

        public async Task ClearHistoryAsync()
        {
            await EnsureInitializedAsync();

            await Task.Run(() =>
            {
                using var connection = new SqliteConnection($"Data Source={_dbPath}");
                connection.Open();

                var command = connection.CreateCommand();
                command.CommandText = "DELETE FROM clipboard_history";
                command.ExecuteNonQuery();
            });
        }

        private async Task EnsureInitializedAsync()
        {
            if (!_isInitialized)
            {
                await InitializeAsync();
            }
        }

        public void Dispose()
        {
            if (_isDisposed) return;
            _isDisposed = true;
        }
    }
}
