using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;
using System.Text;

namespace IikoExely
{
    public class RoomServiceQuote
    {
        public decimal Check { get; set; }
        /// <summary>Полная стоимость доставки; получает сотрудник.</summary>
        public decimal DeliveryFee { get; set; }
        public decimal GuestFee { get; set; }
        /// <summary>Компенсация отеля сотруднику: DeliveryFee - GuestFee.</summary>
        public decimal HotelCompensation => DeliveryFee - GuestFee;
        /// <summary>К оплате/на счёт номера: чек плюс доля доставки гостя.</summary>
        public decimal TotalToGuest => Check + GuestFee;
    }

    public static class RoomServicePolicy
    {
        /// <summary>Лесенка: чем больше чек, тем меньше доставки платит гость; самая высокая подходящая ступень.</summary>
        public static RoomServiceQuote Quote(decimal check, PluginConfig cfg)
        {
            var fee = Math.Max(cfg.RoomServiceDeliveryFee, 0m);
            var tier = (cfg.RoomServiceTiers ?? new List<RoomServiceTier>())
                .OrderByDescending(t => t.MinCheck).FirstOrDefault(t => check >= t.MinCheck);
            var guest = tier == null ? fee : Math.Min(Math.Max(tier.GuestFee, 0m), fee);
            return new RoomServiceQuote { Check = check, DeliveryFee = fee, GuestFee = guest };
        }
    }

    [DataContract]
    public class RoomCharge
    {
        public const string KindRoomService = "roomservice";
        public const string KindRestaurant = "restaurant";
        public const string StatusPending = "pending";
        public const string StatusPosted = "posted";
        public const string StatusCancelled = "cancelled";

        [DataMember(Name = "id")] public string Id { get; set; } = Guid.NewGuid().ToString("N");
        [DataMember(Name = "date")] public string Date { get; set; }
        [DataMember(Name = "time")] public string Time { get; set; }
        [DataMember(Name = "kind")] public string Kind { get; set; }
        [DataMember(Name = "stayId")] public string StayId { get; set; }
        [DataMember(Name = "room")] public string Room { get; set; }
        [DataMember(Name = "guest")] public string Guest { get; set; }
        [DataMember(Name = "orderId")] public string OrderId { get; set; }
        [DataMember(Name = "orderNumber")] public int OrderNumber { get; set; }
        [DataMember(Name = "check")] public decimal Check { get; set; }
        [DataMember(Name = "guestDeliveryFee")] public decimal GuestDeliveryFee { get; set; }
        [DataMember(Name = "hotelCompensation")] public decimal HotelCompensation { get; set; }
        [DataMember(Name = "deliveryEmployee")] public string DeliveryEmployee { get; set; }
        [DataMember(Name = "employee")] public string Employee { get; set; }
        [DataMember(Name = "status")] public string Status { get; set; } = StatusPending;
        [DataMember(Name = "statusBy")] public string StatusBy { get; set; }
        [DataMember(Name = "statusAt")] public string StatusAt { get; set; }
        [DataMember(Name = "note")] public string Note { get; set; }

        public decimal TotalToGuest => Check + GuestDeliveryFee;
    }

    [DataContract]
    internal class RoomChargeFile
    {
        [DataMember(Name = "charges")] public List<RoomCharge> Charges { get; set; } = new List<RoomCharge>();
    }

    /// <summary>
    /// Журнал отнесений на счёт номера. Плагин не пишет в Exely: администратор рецепции по этому журналу
    /// проводит начисление в PMS и отмечает строку как проведённую.
    /// </summary>
    public class RoomChargeStore
    {
        private readonly string _path;
        private readonly object _lock = new object();
        private List<RoomCharge> _items = new List<RoomCharge>();

        public RoomChargeStore(string path)
        {
            _path = path;
            try
            {
                if (File.Exists(_path))
                    using (var fs = File.OpenRead(_path))
                        _items = ((RoomChargeFile)new DataContractJsonSerializer(typeof(RoomChargeFile)).ReadObject(fs)).Charges ?? new List<RoomCharge>();
            }
            catch (Exception)
            {
                try { File.Move(_path, _path + ".broken-" + DateTime.Now.ToString("yyyyMMddHHmmss")); } catch (Exception) { }
            }
        }

        public bool HasActive(string orderId, string kind)
        {
            lock (_lock) return _items.Any(c => c.OrderId == orderId && c.Kind == kind && c.Status != RoomCharge.StatusCancelled);
        }

        public void Add(RoomCharge c) { lock (_lock) { _items.Add(c); Save(); } }

        public List<RoomCharge> ForDay(DateTime day)
        {
            var k = UsageStore.DayKey(day);
            lock (_lock) return _items.Where(c => c.Date == k).ToList();
        }

        public List<RoomCharge> Pending()
        {
            lock (_lock) return _items.Where(c => c.Status == RoomCharge.StatusPending).ToList();
        }

        public RoomCharge SetStatus(string id, string status, string by, DateTime at, string note = null)
        {
            lock (_lock)
            {
                var c = _items.FirstOrDefault(x => x.Id == id);
                if (c == null) return null;
                c.Status = status; c.StatusBy = by; c.StatusAt = at.ToString("s");
                if (note != null) c.Note = note;
                Save();
                return c;
            }
        }

        private void Save()
        {
            var dir = Path.GetDirectoryName(_path);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            var tmp = _path + ".tmp";
            using (var fs = File.Create(tmp))
                new DataContractJsonSerializer(typeof(RoomChargeFile)).WriteObject(fs, new RoomChargeFile { Charges = _items });
            if (File.Exists(_path)) File.Delete(_path);
            File.Move(tmp, _path);
        }

        public static string ToCsv(IEnumerable<RoomCharge> items)
        {
            var sb = new StringBuilder("﻿Дата;Время;Номер;Гость;Тип;Заказ;Чек;Доставка с гостя;Компенсация отеля;К начислению на номер;Курьер;Кассир;Статус;Кем;Примечание\r\n");
            string N(decimal v) => v.ToString("0.00", CultureInfo.InvariantCulture);
            foreach (var c in items)
                sb.Append(string.Join(";", new[]
                {
                    Csv.Q(c.Date), Csv.Q(c.Time), Csv.Q(c.Room), Csv.Q(c.Guest), Csv.Q(c.Kind == RoomCharge.KindRoomService ? "рум-сервис" : "ресторан"),
                    c.OrderNumber.ToString(CultureInfo.InvariantCulture), N(c.Check), N(c.GuestDeliveryFee), N(c.HotelCompensation), N(c.TotalToGuest),
                    Csv.Q(c.DeliveryEmployee), Csv.Q(c.Employee), Csv.Q(c.Status), Csv.Q(c.StatusBy), Csv.Q(c.Note),
                })).Append("\r\n");
            return sb.ToString();
        }
    }

    [DataContract]
    public class KitchenEntry
    {
        [DataMember(Name = "date")] public string Date { get; set; }
        [DataMember(Name = "time")] public string Time { get; set; }
        [DataMember(Name = "prepared")] public int Prepared { get; set; }
        [DataMember(Name = "leftover")] public int Leftover { get; set; }
        [DataMember(Name = "wasted")] public int Wasted { get; set; }
        [DataMember(Name = "employee")] public string Employee { get; set; }
        [DataMember(Name = "note")] public string Note { get; set; }
    }

    [DataContract]
    internal class KitchenFile
    {
        [DataMember(Name = "entries")] public List<KitchenEntry> Entries { get; set; } = new List<KitchenEntry>();
    }

    /// <summary>Итоги кухни по завтраку: сколько приготовили, сколько осталось и списано.</summary>
    public class KitchenStore
    {
        private readonly string _path;
        private readonly object _lock = new object();
        private List<KitchenEntry> _items = new List<KitchenEntry>();

        public KitchenStore(string path)
        {
            _path = path;
            try
            {
                if (File.Exists(_path))
                    using (var fs = File.OpenRead(_path))
                        _items = ((KitchenFile)new DataContractJsonSerializer(typeof(KitchenFile)).ReadObject(fs)).Entries ?? new List<KitchenEntry>();
            }
            catch (Exception)
            {
                try { File.Move(_path, _path + ".broken-" + DateTime.Now.ToString("yyyyMMddHHmmss")); } catch (Exception) { }
            }
        }

        /// <summary>За день хранится последний ввод кухни (его можно уточнить).</summary>
        public void Set(KitchenEntry e)
        {
            lock (_lock)
            {
                _items.RemoveAll(x => x.Date == e.Date);
                _items.Add(e);
                var dir = Path.GetDirectoryName(_path);
                if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
                var tmp = _path + ".tmp";
                using (var fs = File.Create(tmp))
                    new DataContractJsonSerializer(typeof(KitchenFile)).WriteObject(fs, new KitchenFile { Entries = _items });
                if (File.Exists(_path)) File.Delete(_path);
                File.Move(tmp, _path);
            }
        }

        public KitchenEntry ForDay(DateTime day)
        {
            var k = UsageStore.DayKey(day);
            lock (_lock) return _items.FirstOrDefault(x => x.Date == k);
        }

        internal static string Pct(double v) => Math.Round(v * 100).ToString("0", CultureInfo.InvariantCulture) + "%";

        public string Summary(DateTime day, int served)
        {
            var e = ForDay(day);
            if (e == null) return "Кухня итоги за день не вводила.";
            var pct = e.Prepared > 0 ? (double)(e.Leftover + e.Wasted) / e.Prepared : 0;
            return $"Приготовлено {e.Prepared}, выдано по реестру {served}, осталось {e.Leftover}, списано {e.Wasted} ({Pct(pct)} от приготовленного).";
        }
    }
}
