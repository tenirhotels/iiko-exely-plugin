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
        [DataMember(Name = "date")] public string Date { get; set; }          // yyyy-MM-dd (день отеля)
        [DataMember(Name = "time")] public string Time { get; set; }          // ISO, местное
        [DataMember(Name = "stayId")] public string StayId { get; set; }
        [DataMember(Name = "room")] public string Room { get; set; }
        [DataMember(Name = "guest")] public string Guest { get; set; }
        [DataMember(Name = "kind")] public string Kind { get; set; }           // "included" | "discount"
        [DataMember(Name = "discount")] public string Discount { get; set; }
        [DataMember(Name = "persons")] public int Persons { get; set; }
        [DataMember(Name = "orderId")] public string OrderId { get; set; }
        [DataMember(Name = "orderNumber")] public int OrderNumber { get; set; }
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
                _entries.RemoveAll(x => e.OrderId != null && x.OrderId == e.OrderId && x.StayId == e.StayId && x.Kind == e.Kind);
                _entries.Add(e);
                Save();
            }
        }

        /// <summary>Порции включённого завтрака, выданные по стоянке за день.</summary>
        public int UsedPortions(string stayId, DateTime day)
        {
            var key = DayKey(day);
            lock (_lock)
                return _entries.Where(e => e.StayId == stayId && e.Date == key && e.Kind == KindIncluded).Sum(e => e.Persons);
        }

        public List<UsageEntry> ForDay(DateTime day)
        {
            var key = DayKey(day);
            lock (_lock) return _entries.Where(e => e.Date == key).ToList();
        }

        /// <summary>Убирает записи заказа (заказ отменён/удалён до оплаты).</summary>
        public int RemoveOrder(string orderId)
        {
            lock (_lock)
            {
                var n = _entries.RemoveAll(e => e.OrderId == orderId);
                if (n > 0) Save();
                return n;
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
