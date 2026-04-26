using System;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using ClipSync.WPF.Core;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace ClipSync.WPF.Network
{
    public class AuthResult
    {
        public bool Success { get; set; }
        public string? Token { get; set; }
        public string? DeviceId { get; set; }
        public string? Error { get; set; }
        public long ExpiresAt { get; set; }
    }

    public class HttpClient
    {
        private readonly SettingsManager _settingsManager;
        private readonly System.Net.Http.HttpClient _httpClient;

        public HttpClient(SettingsManager settingsManager)
        {
            _settingsManager = settingsManager;
            _httpClient = new System.Net.Http.HttpClient();
            _httpClient.Timeout = TimeSpan.FromSeconds(15);
        }

        public async Task<AuthResult> LoginAsync(string username, string password, string deviceName, string platform)
        {
            var httpUrl = _settingsManager.Settings.HttpUrl;
            var url = $"{httpUrl}/api/v1/auth/login";

            var requestBody = new
            {
                username,
                password,
                device_name = deviceName,
                platform
            };

            var json = JsonConvert.SerializeObject(requestBody);
            var content = new StringContent(json, Encoding.UTF8, "application/json");

            try
            {
                AppLogger.Info("HttpClient", $"开始登录请求: url={url}, username={username}, device_name={deviceName}, platform={platform}");
                var response = await _httpClient.PostAsync(url, content);
                var responseJson = await response.Content.ReadAsStringAsync();

                if (response.IsSuccessStatusCode)
                {
                    var result = JObject.Parse(responseJson);
                    AppLogger.Info("HttpClient", $"登录请求成功: username={username}, device_id={result.Value<string>("device_id")}");
                    return new AuthResult
                    {
                        Success = true,
                        Token = result.Value<string>("token"),
                        DeviceId = result.Value<string>("device_id"),
                        ExpiresAt = result.Value<long>("expires_at")
                    };
                }
                else
                {
                    var error = JObject.Parse(responseJson);
                    AppLogger.Warn("HttpClient", $"登录请求失败: status={(int)response.StatusCode}, error={error.Value<string>("error")}");
                    return new AuthResult
                    {
                        Success = false,
                        Error = error.Value<string>("error") ?? "Login failed"
                    };
                }
            }
            catch (Exception ex)
            {
                AppLogger.Error("HttpClient", $"登录请求异常: username={username}, url={url}", ex);
                return new AuthResult
                {
                    Success = false,
                    Error = $"Connection error: {ex.Message}"
                };
            }
        }

        public async Task<AuthResult> RegisterAsync(string username, string password, string deviceName, string platform)
        {
            var httpUrl = _settingsManager.Settings.HttpUrl;
            var url = $"{httpUrl}/api/v1/auth/register";

            var requestBody = new
            {
                username,
                password,
                device_name = deviceName,
                platform
            };

            var json = JsonConvert.SerializeObject(requestBody);
            var content = new StringContent(json, Encoding.UTF8, "application/json");

            try
            {
                AppLogger.Info("HttpClient", $"开始注册请求: url={url}, username={username}, device_name={deviceName}, platform={platform}");
                var response = await _httpClient.PostAsync(url, content);
                var responseJson = await response.Content.ReadAsStringAsync();

                if (response.IsSuccessStatusCode)
                {
                    var result = JObject.Parse(responseJson);
                    AppLogger.Info("HttpClient", $"注册请求成功: username={username}, device_id={result.Value<string>("device_id")}");
                    return new AuthResult
                    {
                        Success = true,
                        Token = result.Value<string>("token"),
                        DeviceId = result.Value<string>("device_id"),
                        ExpiresAt = result.Value<long>("expires_at")
                    };
                }
                else
                {
                    var error = JObject.Parse(responseJson);
                    AppLogger.Warn("HttpClient", $"注册请求失败: status={(int)response.StatusCode}, error={error.Value<string>("error")}");
                    return new AuthResult
                    {
                        Success = false,
                        Error = error.Value<string>("error") ?? "Registration failed"
                    };
                }
            }
            catch (Exception ex)
            {
                AppLogger.Error("HttpClient", $"注册请求异常: username={username}, url={url}", ex);
                return new AuthResult
                {
                    Success = false,
                    Error = $"Connection error: {ex.Message}"
                };
            }
        }

        public async Task<AuthResult> RefreshTokenAsync()
        {
            var httpUrl = _settingsManager.Settings.HttpUrl;
            var url = $"{httpUrl}/api/v1/auth/refresh";

            var request = new HttpRequestMessage(HttpMethod.Post, url);
            request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue(
                "Bearer", _settingsManager.Settings.Token);

            try
            {
                AppLogger.Info("HttpClient", $"开始刷新 Token: url={url}");
                var response = await _httpClient.SendAsync(request);
                var responseJson = await response.Content.ReadAsStringAsync();

                if (response.IsSuccessStatusCode)
                {
                    var result = JObject.Parse(responseJson);
                    AppLogger.Info("HttpClient", "刷新 Token 成功");
                    return new AuthResult
                    {
                        Success = true,
                        Token = result.Value<string>("token"),
                        ExpiresAt = result.Value<long>("expires_at")
                    };
                }
                else
                {
                    AppLogger.Warn("HttpClient", $"刷新 Token 失败: status={(int)response.StatusCode}");
                    return new AuthResult
                    {
                        Success = false,
                        Error = "Token refresh failed"
                    };
                }
            }
            catch (Exception ex)
            {
                AppLogger.Error("HttpClient", $"刷新 Token 异常: url={url}", ex);
                return new AuthResult
                {
                    Success = false,
                    Error = $"Connection error: {ex.Message}"
                };
            }
        }
    }
}
