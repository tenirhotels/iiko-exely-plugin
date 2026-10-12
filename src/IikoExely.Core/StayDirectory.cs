using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace IikoExely
{
    public class InHouseGuest
    {
        public Stay Stay { get; set; }
        /// <summary>Фамилии для списка; пусто, если у токена нет права guests.</summary>
        public string Names { get; set; } = "";
        public string Room => Stay.Room ?? "?";
    }

    /// <summary>Кэш проживающих из Hotel Hub. При сбое хаба отдаёт последние известные данные с пометкой «устарело».</summary>
    public class StayDirectory
    {
        private readonly IHubClient _hub;
        private readonly PluginConfig _cfg;
        private readonly Func<DateTime> _now;
        private readonly object _lock = new object();
        private List<InHouseGuest> _guests = new List<InHouseGuest>();
        private List<Stay> _allActive = new List<Stay>();
        private CatalogResponse _catalog = new CatalogResponse();
        private readonly Dictionary<string, (string updated, string names)> _nameCache = new Dictionary<string, (string, string)>();
        private DateTime _loadedAt = DateTime.MinValue;
        private bool _guestsForbidden;

        public StayDirectory(IHubClient hub, PluginConfig cfg, Func<DateTime> utcNow = null)
        {
            _hub = hub;
            _cfg = cfg;
            _now = utcNow ?? (() => DateTime.UtcNow);
        }

        public DateTime? LoadedAtUtc => _loadedAt == DateTime.MinValue ? (DateTime?)null : _loadedAt;
        public string LastError { get; private set; }
        public bool IsStale { get; private set; }
        public CatalogResponse Catalog { get { lock (_lock) return _catalog; } }

        /// <summary>Все активные стоянки (ожидаются и проживают) — основа прогноза на будущие дни.</summary>
        public IReadOnlyList<Stay> AllActive { get { lock (_lock) return _allActive.ToList(); } }

        public IReadOnlyList<InHouseGuest> Current { get { lock (_lock) return _guests.ToList(); } }

        /// <summary>Обновляет данные, если они старше refreshSeconds (или force). Возвращает текущий список.</summary>
        public async Task<IReadOnlyList<InHouseGuest>> RefreshAsync(bool force, CancellationToken ct)
        {
            lock (_lock)
                if (!force && (_now() - _loadedAt).TotalSeconds < _cfg.RefreshSeconds && _loadedAt != DateTime.MinValue)
                    return _guests.ToList();
            try
            {
                var active = (await _hub.GetActiveStaysAsync(ct).ConfigureAwait(false)).Stays
                    .Where(s => !string.IsNullOrEmpty(s.Id))
                    .ToList();
                var stays = active.Where(s => s.IsCheckedIn).ToList();
                CatalogResponse catalog = null;
                try { catalog = await _hub.GetCatalogAsync(ct).ConfigureAwait(false); }
                catch (HubException e) when (e.Status == 403) { /* нет права catalog: тариф определяется по услугам стоянки */ }

                var result = new List<InHouseGuest>();
                foreach (var st in stays.OrderBy(s => s.Room, StringComparer.CurrentCultureIgnoreCase))
                    result.Add(new InHouseGuest { Stay = st, Names = await NamesAsync(st, ct).ConfigureAwait(false) });

                lock (_lock)
                {
                    _guests = result;
                    _allActive = active;
                    if (catalog != null) _catalog = catalog;
                    _loadedAt = _now();
                    LastError = null;
                    IsStale = false;
                }
            }
            catch (Exception e) when (e is HubException || e is System.Net.Http.HttpRequestException || e is TaskCanceledException || e is OperationCanceledException)
            {
                lock (_lock)
                {
                    LastError = e.Message;
                    IsStale = _guests.Count > 0 || _loadedAt != DateTime.MinValue;
                }
                if (_loadedAt == DateTime.MinValue) throw; // данных нет вообще — кассир должен увидеть ошибку
            }
            return Current;
        }

        private async Task<string> NamesAsync(Stay st, CancellationToken ct)
        {
            if (!_cfg.ShowGuestNames || _guestsForbidden) return "";
            var stamp = st.Id + "|" + st.Room;
            lock (_lock)
                if (_nameCache.TryGetValue(st.Id, out var c) && c.updated == stamp) return c.names;
            try
            {
                var g = await _hub.GetGuestsAsync(st.Id, ct).ConfigureAwait(false);
                var names = string.Join(", ", g.Guests.Select(x => x.ShortName).Where(n => n.Length > 0).Distinct().Take(3));
                lock (_lock) _nameCache[st.Id] = (stamp, names);
                return names;
            }
            catch (HubException e) when (e.Status == 403)
            {
                _guestsForbidden = true; // у токена нет права guests — не стучимся каждый раз
                return "";
            }
            catch (HubException e) when (e.Status == 429)
            {
                return ""; // лимит хаба: в этот раз без фамилий
            }
        }
    }
}
