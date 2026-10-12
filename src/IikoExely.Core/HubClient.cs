using System;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Runtime.Serialization.Json;
using System.Threading;
using System.Threading.Tasks;

namespace IikoExely
{
    public interface IHubClient
    {
        Task<StaysResponse> GetActiveStaysAsync(CancellationToken ct);
        Task<GuestsResponse> GetGuestsAsync(string stayId, CancellationToken ct);
        Task<CatalogResponse> GetCatalogAsync(CancellationToken ct);
    }

    /// <summary>Клиент Hotel Hub API v1 (только чтение, Bearer-токен).</summary>
    public class HubClient : IHubClient
    {
        private readonly HttpClient _http;
        private readonly string _property;

        public HubClient(PluginConfig cfg, HttpMessageHandler handler = null)
        {
            _property = Uri.EscapeDataString(cfg.PropertyCode ?? "");
            _http = handler == null ? new HttpClient() : new HttpClient(handler);
            _http.BaseAddress = new Uri(cfg.HubUrl.TrimEnd('/') + "/");
            _http.Timeout = TimeSpan.FromSeconds(Math.Max(cfg.RequestTimeoutSeconds, 1));
            _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", cfg.HubToken);
        }

        public Task<StaysResponse> GetActiveStaysAsync(CancellationToken ct) =>
            GetAsync<StaysResponse>($"v1/properties/{_property}/stays?active=1&limit=2000", ct);

        public Task<GuestsResponse> GetGuestsAsync(string stayId, CancellationToken ct) =>
            GetAsync<GuestsResponse>($"v1/properties/{_property}/stays/{Uri.EscapeDataString(stayId)}/guests", ct);

        public Task<CatalogResponse> GetCatalogAsync(CancellationToken ct) =>
            GetAsync<CatalogResponse>($"v1/properties/{_property}/catalog", ct);

        private async Task<T> GetAsync<T>(string path, CancellationToken ct)
        {
            using (var resp = await _http.GetAsync(path, ct).ConfigureAwait(false))
            {
                if (!resp.IsSuccessStatusCode)
                    throw new HubException((int)resp.StatusCode, $"Hotel Hub вернул HTTP {(int)resp.StatusCode} на {path.Split('?')[0]}");
                var bytes = await resp.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
                using (var ms = new MemoryStream(bytes))
                    return (T)new DataContractJsonSerializer(typeof(T)).ReadObject(ms);
            }
        }
    }

    public class HubException : Exception
    {
        public int Status { get; }
        public HubException(int status, string message) : base(message) { Status = status; }
    }
}
