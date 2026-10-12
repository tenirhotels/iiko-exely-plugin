using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;

namespace IikoExely
{
    [DataContract]
    public class UsageEntry
    {
        [DataMember(Name = "id")] public string Id { get; set; } = Guid.NewGuid().ToString("N");
        [DataMember(Name = "date")] public string Date { get; set; }          // yyyy-MM-dd (день отеля)
        [DataMember(Name = "time")] public string Time { get; set; }          // ISO, местное
        [DataMember(Name = "stayId")] public string StayId { get; set; }
        [DataMember(Name = "room")] public string Room { get; set; }
        [DataMember(Name = "guest")] public string Guest { get; set; }
        [DataMember(Name = "kind")] public string Kind { get; set; }           // "included" | "discount"
        [DataMember(Name = "discount")] public string Discount { get; set; }
        [DataMember(Name = "adults")] public int Adults { get; set; }
        [DataMember(Name = "children")] public int Children { get; set; }
        [DataMember(Name = "employee")] public string Employee { get; set; }
        [DataMember(Name = "source")] public string Source { get; set; } = "iiko";
        /// <summary>Сверх положенного, с разрешением менеджера.</summary>
        [DataMember(Name = "extra")] public bool Extra { get; set; }
        [DataMember(Name = "approvedBy")] public string ApprovedBy { get; set; }
        [DataMember(Name = "orderId")] public string OrderId { get; set; }
        [DataMember(Name = "orderNumber")] public int OrderNumber { get; set; }
        [DataMember(Name = "cancelled")] public bool Cancelled { get; set; }
        [DataMember(Name = "cancelledAt")] public string CancelledAt { get; set; }
        [DataMember(Name = "cancelledBy")] public string CancelledBy { get; set; }
        [DataMember(Name = "cancelReason")] public string CancelReason { get; set; }

        public int Persons => Adults + Children;
        public DateTime? TimeValue => Stay.ParseTime(Time);
    }

    [DataContract]
    internal class UsageFile
    {
        [DataMember(Name = "entries")] public List<UsageEntry> Entries { get; set; } = new List<UsageEntry>();
    }

    /// <summary>Журнал выданных завтраков и скидок в файле (переживает перезапуск кассы).</summary>
    public class UsageStore
    {
        public const string KindIncluded = "included";
        public const string KindDiscount = "discount";

        private readonly string _path;
        private readonly object _lock = new object();
        private List<UsageEntry> _entries = new List<UsageEntry>();

        public UsageStore(string path)
        {
            _path = path;
            Load();
        }

        public static string DayKey(DateTime d) => d.ToString("yyyy-MM-dd");

        public void Add(UsageEntry e)
        {
            lock (_lock)
            {
                _entries.Add(e);
                Save();
            }
        }

        /// <summary>Выданные завтраки (взрослые, дети) по стоянке за день; отменённые не считаются.</summary>
        public (int adults, int children) Used(string stayId, DateTime day)
        {
            var key = DayKey(day);
            lock (_lock)
            {
                var rows = _entries.Where(e => e.StayId == stayId && e.Date == key && e.Kind == KindIncluded && !e.Cancelled).ToList();
                return (rows.Sum(e => e.Adults), rows.Sum(e => e.Children));
            }
        }

        public int UsedPortions(string stayId, DateTime day)
        {
            var u = Used(stayId, day);
            return u.adults + u.children;
        }

        /// <summary>Заказ уже отмечен для этой стоянки (защита от повторной отметки того же заказа).</summary>
        public bool HasActiveEntry(string orderId, string stayId, string kind)
        {
            lock (_lock)
                return _entries.Any(e => !e.Cancelled && e.OrderId == orderId && e.StayId == stayId && e.Kind == kind);
        }

        public List<UsageEntry> ForDay(DateTime day, bool includeCancelled = true)
        {
            var key = DayKey(day);
            lock (_lock) return _entries.Where(e => e.Date == key && (includeCancelled || !e.Cancelled)).ToList();
        }

        public List<UsageEntry> Between(DateTime fromInclusive, DateTime toInclusive)
        {
            var a = DayKey(fromInclusive); var b = DayKey(toInclusive);
            lock (_lock) return _entries.Where(e => string.CompareOrdinal(e.Date, a) >= 0 && string.CompareOrdinal(e.Date, b) <= 0).ToList();
        }

        /// <summary>Отмена отметки; причина обязательна. Возвращает отменённую запись или null, если её нет.</summary>
        public UsageEntry Cancel(string entryId, string reason, string by, DateTime at)
        {
            if (string.IsNullOrWhiteSpace(reason)) throw new ArgumentException("Причина отмены обязательна.", nameof(reason));
            lock (_lock)
            {
                var e = _entries.FirstOrDefault(x => x.Id == entryId && !x.Cancelled);
                if (e == null) return null;
                e.Cancelled = true;
                e.CancelledAt = at.ToString("s");
                e.CancelledBy = by;
                e.CancelReason = reason.Trim();
                Save();
                return e;
            }
        }

        private void Load()
        {
            try
            {
                if (!File.Exists(_path)) return;
                using (var fs = File.OpenRead(_path))
                    _entries = ((UsageFile)new DataContractJsonSerializer(typeof(UsageFile)).ReadObject(fs)).Entries ?? new List<UsageEntry>();
            }
            catch (Exception)
            {
                // повреждённый файл не должен ломать кассу: сохраняем копию и начинаем журнал заново
                try { File.Move(_path, _path + ".broken-" + DateTime.Now.ToString("yyyyMMddHHmmss")); } catch (Exception) { }
                _entries = new List<UsageEntry>();
            }
        }

        private void Save()
        {
            var dir = Path.GetDirectoryName(_path);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            var tmp = _path + ".tmp";
            using (var fs = File.Create(tmp))
                new DataContractJsonSerializer(typeof(UsageFile)).WriteObject(fs, new UsageFile { Entries = _entries });
            if (File.Exists(_path)) File.Delete(_path);
            File.Move(tmp, _path);
        }
    }
}
