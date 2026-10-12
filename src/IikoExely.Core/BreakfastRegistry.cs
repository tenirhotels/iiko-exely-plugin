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
    public enum RegistryStatus { Expected, Partial, Used, NotUsed, Cancelled, NotEntitled }

    public static class RegistryStatusText
    {
        public static string Ru(RegistryStatus s)
        {
            switch (s)
            {
                case RegistryStatus.Expected: return "ожидается";
                case RegistryStatus.Partial: return "использован частично";
                case RegistryStatus.Used: return "использован";
                case RegistryStatus.NotUsed: return "не использован";
                case RegistryStatus.Cancelled: return "отменён";
                default: return "не положен";
            }
        }
    }

    [DataContract]
    public class RegistryRow
    {
        [DataMember(Name = "stayId")] public string StayId { get; set; }
        [DataMember(Name = "booking")] public string Booking { get; set; }
        [DataMember(Name = "room")] public string Room { get; set; }
        [DataMember(Name = "names")] public string Names { get; set; }
        [DataMember(Name = "ratePlan")] public string RatePlan { get; set; }
        [DataMember(Name = "adults")] public int Adults { get; set; }
        [DataMember(Name = "children")] public int Children { get; set; }
        [DataMember(Name = "arrival")] public string Arrival { get; set; }
        [DataMember(Name = "departure")] public string Departure { get; set; }
        [DataMember(Name = "adultPortions")] public int AdultPortions { get; set; }
        [DataMember(Name = "childPortions")] public int ChildPortions { get; set; }
        [DataMember(Name = "usedAdults")] public int UsedAdults { get; set; }
        [DataMember(Name = "usedChildren")] public int UsedChildren { get; set; }
        [DataMember(Name = "extraPersons")] public int ExtraPersons { get; set; }
        [DataMember(Name = "discountPersons")] public int DiscountPersons { get; set; }
        [DataMember(Name = "removed")] public bool Removed { get; set; }
        [DataMember(Name = "status")] public string StatusName { get; set; }
        [DataMember(Name = "lastChangeAt")] public string LastChangeAt { get; set; }
        [DataMember(Name = "lastChangeSource")] public string LastChangeSource { get; set; }
        [DataMember(Name = "lastChangeNote")] public string LastChangeNote { get; set; }

        public int Portions => AdultPortions + ChildPortions;
        public int Used => UsedAdults + UsedChildren;
        public RegistryStatus Status
        {
            get => Enum.TryParse<RegistryStatus>(StatusName, out var s) ? s : RegistryStatus.Expected;
            set => StatusName = value.ToString();
        }
    }

    [DataContract]
    public class DayRegistry
    {
        [DataMember(Name = "day")] public string Day { get; set; }
        [DataMember(Name = "syncedAt")] public string SyncedAt { get; set; }
        [DataMember(Name = "rows")] public List<RegistryRow> Rows { get; set; } = new List<RegistryRow>();

        public int Count(RegistryStatus s) => Rows.Count(r => r.Status == s);
        public int EntitledAdults => Rows.Where(r => r.Status != RegistryStatus.Cancelled).Sum(r => r.AdultPortions);
        public int EntitledChildren => Rows.Where(r => r.Status != RegistryStatus.Cancelled).Sum(r => r.ChildPortions);
        public int UsedAdults => Rows.Sum(r => r.UsedAdults);
        public int UsedChildren => Rows.Sum(r => r.UsedChildren);

        public string Summary()
        {
            var sb = new StringBuilder();
            sb.AppendLine($"Реестр завтраков {Day}: ожидается {Count(RegistryStatus.Expected) + Count(RegistryStatus.Partial)}, " +
                          $"использован {Count(RegistryStatus.Used)}, не использован {Count(RegistryStatus.NotUsed)}, отменён {Count(RegistryStatus.Cancelled)}.");
            sb.Append($"Положено: взрослых {EntitledAdults}, детей {EntitledChildren}. Выдано: взрослых {UsedAdults}, детей {UsedChildren}.");
            return sb.ToString();
        }

        public string ToCsv()
        {
            var sb = new StringBuilder("﻿Номер;Гости;Бронь;Заезд;Выезд;Взрослые;Дети;Включено взр.;Включено дет.;Выдано взр.;Выдано дет.;Сверх нормы;Со скидкой;Статус;Изменено;Источник;Что изменилось\r\n");
            foreach (var r in Rows)
                sb.Append(string.Join(";", new[]
                {
                    Csv.Q(r.Room), Csv.Q(r.Names), Csv.Q(r.Booking), Csv.Q(r.Arrival), Csv.Q(r.Departure),
                    r.Adults.ToString(CultureInfo.InvariantCulture), r.Children.ToString(CultureInfo.InvariantCulture),
                    r.AdultPortions.ToString(CultureInfo.InvariantCulture), r.ChildPortions.ToString(CultureInfo.InvariantCulture),
                    r.UsedAdults.ToString(CultureInfo.InvariantCulture), r.UsedChildren.ToString(CultureInfo.InvariantCulture),
                    r.ExtraPersons.ToString(CultureInfo.InvariantCulture), r.DiscountPersons.ToString(CultureInfo.InvariantCulture),
                    Csv.Q(RegistryStatusText.Ru(r.Status)), Csv.Q(r.LastChangeAt), Csv.Q(r.LastChangeSource), Csv.Q(r.LastChangeNote),
                })).Append("\r\n");
            return sb.ToString();
        }
    }

    internal static class Csv
    {
        // защита от CSV-инъекции формул и от «;» и кавычек в значениях
        public static string Q(string v)
        {
            v = v ?? "";
            if (v.Length > 0 && "=+-@".IndexOf(v[0]) >= 0) v = "'" + v;
            return "\"" + v.Replace("\"", "\"\"") + "\"";
        }
    }

    /// <summary>Файлы реестра по дням: registry-YYYY-MM-DD.json.</summary>
    public class RegistryStore
    {
        private readonly string _dir;
        public RegistryStore(string dir) { _dir = dir; }

        private string PathFor(DateTime day) => System.IO.Path.Combine(_dir, $"registry-{UsageStore.DayKey(day)}.json");

        public DayRegistry Load(DateTime day)
        {
            try
            {
                var p = PathFor(day);
                if (!File.Exists(p)) return null;
                using (var fs = File.OpenRead(p))
                    return (DayRegistry)new DataContractJsonSerializer(typeof(DayRegistry)).ReadObject(fs);
            }
            catch (Exception) { return null; }
        }

        public void Save(DayRegistry reg, DateTime day)
        {
            Directory.CreateDirectory(_dir);
            var p = PathFor(day);
            var tmp = p + ".tmp";
            using (var fs = File.Create(tmp))
                new DataContractJsonSerializer(typeof(DayRegistry)).WriteObject(fs, reg);
            if (File.Exists(p)) File.Delete(p);
            File.Move(tmp, p);
        }
    }

    /// <summary>
    /// Электронный реестр завтраков на день. Пересобирается из текущих данных хаба и журнала выдачи;
    /// прошлый снимок нужен, чтобы заметить изменение брони (комната, гости, даты) и выезд.
    /// </summary>
    public class BreakfastRegistry
    {
        private readonly PluginConfig _cfg;
        private readonly RegistryStore _store;

        public BreakfastRegistry(PluginConfig cfg, RegistryStore store) { _cfg = cfg; _store = store; }

        public TimeSpan WindowStart => ParseTime(_cfg.BreakfastStart, new TimeSpan(7, 0, 0));
        public TimeSpan WindowEnd => ParseTime(_cfg.BreakfastEnd, new TimeSpan(11, 0, 0));

        private static TimeSpan ParseTime(string s, TimeSpan fallback) =>
            TimeSpan.TryParse(s, CultureInfo.InvariantCulture, out var t) ? t : fallback;

        public DayRegistry Sync(IEnumerable<InHouseGuest> guests, BreakfastPolicy policy, UsageStore usage, DateTime now)
        {
            var day = now.Date;
            var prev = (_store.Load(day)?.Rows ?? new List<RegistryRow>()).ToDictionary(r => r.StayId, r => r);
            var seen = new HashSet<string>();
            var rows = new List<RegistryRow>();
            var entries = usage.ForDay(day);

            foreach (var g in guests)
            {
                var st = g.Stay;
                var used = usage.Used(st.Id, day);
                var dec = policy.Evaluate(st, day, used.adults, used.children);
                prev.TryGetValue(st.Id, out var old);
                if (dec.Right == BreakfastRight.OutsideStay && old == null) continue;
                seen.Add(st.Id);

                var row = new RegistryRow
                {
                    StayId = st.Id, Booking = st.Booking, Room = g.Room, Names = g.Names, RatePlan = st.RatePlan?.Name,
                    Adults = st.Adults, Children = st.Children,
                    Arrival = (st.ActualInTime ?? st.PlannedInTime)?.ToString("yyyy-MM-dd"),
                    Departure = (st.ActualOutTime ?? st.PlannedOutTime)?.ToString("yyyy-MM-dd"),
                    AdultPortions = dec.AdultPortions, ChildPortions = dec.ChildPortions,
                };
                FillUsage(row, entries);
                var hubDiff = old == null ? "добавлен в реестр" : Diff(old, row);
                Carry(row, old);
                if (hubDiff != null) SetChange(row, now, "Exely/хаб", hubDiff);
                else ApplyUsageEvent(row, entries, old);
                row.Status = StatusFor(row, now);
                rows.Add(row);
            }

            // были в реестре, из списка проживающих пропали: выезд или снятие брони
            foreach (var old in prev.Values.Where(r => !seen.Contains(r.StayId)))
            {
                FillUsage(old, entries);
                if (!old.Removed) SetChange(old, now, "Exely/хаб", "выезд или бронь снята");
                old.Removed = true;
                old.Status = StatusFor(old, now);
                rows.Add(old);
            }

            var reg = new DayRegistry
            {
                Day = UsageStore.DayKey(day),
                SyncedAt = now.ToString("s"),
                Rows = rows.OrderBy(r => r.Room, StringComparer.CurrentCultureIgnoreCase).ToList(),
            };
            _store.Save(reg, day);
            return reg;
        }

        private static void FillUsage(RegistryRow row, List<UsageEntry> entries)
        {
            var mine = entries.Where(e => e.StayId == row.StayId && !e.Cancelled).ToList();
            var inc = mine.Where(e => e.Kind == UsageStore.KindIncluded).ToList();
            row.UsedAdults = inc.Sum(e => e.Adults);
            row.UsedChildren = inc.Sum(e => e.Children);
            row.ExtraPersons = inc.Where(e => e.Extra).Sum(e => e.Persons);
            row.DiscountPersons = mine.Where(e => e.Kind == UsageStore.KindDiscount).Sum(e => e.Persons);
        }

        private static void Carry(RegistryRow row, RegistryRow old)
        {
            if (old == null) return;
            row.LastChangeAt = old.LastChangeAt; row.LastChangeSource = old.LastChangeSource; row.LastChangeNote = old.LastChangeNote;
        }

        private static void SetChange(RegistryRow row, DateTime at, string source, string note)
        {
            row.LastChangeAt = at.ToString("s"); row.LastChangeSource = source; row.LastChangeNote = note;
        }

        private static string Diff(RegistryRow a, RegistryRow b)
        {
            var parts = new List<string>();
            if (a.Room != b.Room) parts.Add($"комната {a.Room}→{b.Room}");
            if (a.Adults != b.Adults || a.Children != b.Children) parts.Add($"гости {a.Adults}+{a.Children}→{b.Adults}+{b.Children}");
            if (a.Departure != b.Departure) parts.Add($"выезд {a.Departure}→{b.Departure}");
            if (a.Arrival != b.Arrival) parts.Add($"заезд {a.Arrival}→{b.Arrival}");
            if (a.Portions != b.Portions && parts.Count == 0) parts.Add($"завтраков {a.Portions}→{b.Portions}");
            if (a.Removed) parts.Add("гость снова в списке проживающих");
            return parts.Count == 0 ? null : string.Join("; ", parts);
        }

        private static void ApplyUsageEvent(RegistryRow row, List<UsageEntry> entries, RegistryRow old)
        {
            // последнее событие кассы по гостю: выдача или отмена
            UsageEntry last = null; DateTime lastAt = DateTime.MinValue; bool lastIsCancel = false;
            foreach (var e in entries.Where(e => e.StayId == row.StayId))
            {
                var t = e.TimeValue ?? DateTime.MinValue;
                if (t >= lastAt) { last = e; lastAt = t; lastIsCancel = false; }
                if (e.Cancelled && Stay.ParseTime(e.CancelledAt) is DateTime ct && ct >= lastAt) { last = e; lastAt = ct; lastIsCancel = true; }
            }
            if (last == null) return;
            var prevAt = Stay.ParseTime(row.LastChangeAt) ?? DateTime.MinValue;
            if (lastAt <= prevAt && old != null) return;
            if (lastIsCancel)
                SetChange(row, lastAt, "iiko: " + (last.CancelledBy ?? "?"), "отмена отметки: " + last.CancelReason);
            else if (last.Kind == UsageStore.KindIncluded)
                SetChange(row, lastAt, "iiko: " + (last.Employee ?? "?"), $"выдан завтрак {last.Adults}+{last.Children}" + (last.Extra ? " (сверх нормы)" : "") + (last.OrderNumber > 0 ? $", заказ {last.OrderNumber}" : ""));
            else
                SetChange(row, lastAt, "iiko: " + (last.Employee ?? "?"), $"скидка «{last.Discount}», {last.Persons} чел.");
        }

        public RegistryStatus StatusFor(RegistryRow r, DateTime now)
        {
            var past = now.TimeOfDay >= WindowEnd;
            if (r.Removed && r.Used == 0 && !past) return RegistryStatus.Cancelled;
            if (r.Portions == 0) return RegistryStatus.NotEntitled;
            if (r.Used > 0 && r.Used >= r.Portions) return RegistryStatus.Used;
            if (r.Used > 0) return RegistryStatus.Partial;
            return past ? RegistryStatus.NotUsed : RegistryStatus.Expected;
        }
    }
}
