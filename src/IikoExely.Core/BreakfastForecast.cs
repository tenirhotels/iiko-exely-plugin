using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace IikoExely
{
    public class ForecastSlot
    {
        public string Start { get; set; }
        public double Share { get; set; }
        public int ExpectedPeople { get; set; }
    }

    public class ForecastResult
    {
        public DateTime Day { get; set; }
        public int EntitledAdults { get; set; }
        public int EntitledChildren { get; set; }
        public int ExpectedAdults { get; set; }
        public int ExpectedChildren { get; set; }
        public double AdultAttendance { get; set; } = 1;
        public double ChildAttendance { get; set; } = 1;
        public int HistoryDays { get; set; }
        public int Rooms { get; set; }
        public List<ForecastSlot> Slots { get; set; } = new List<ForecastSlot>();

        public string Text()
        {
            var sb = new StringBuilder();
            sb.AppendLine($"Прогноз завтраков на {Day:dd.MM.yyyy}");
            sb.AppendLine($"Номеров с завтраком: {Rooms}. Положено: взрослых {EntitledAdults}, детей {EntitledChildren} (всего {EntitledAdults + EntitledChildren}).");
            if (HistoryDays > 0)
                sb.AppendLine($"С учётом явки за {HistoryDays} дн. (взрослые {KitchenStore.Pct(AdultAttendance)}, дети {KitchenStore.Pct(ChildAttendance)}): " +
                              $"ждать взрослых {ExpectedAdults}, детей {ExpectedChildren} (всего {ExpectedAdults + ExpectedChildren}).");
            else
                sb.AppendLine("Истории явки пока нет — прогноз равен количеству положенных порций.");
            if (Slots.Count > 0)
            {
                sb.AppendLine("По времени (по статистике выдачи):");
                foreach (var s in Slots) sb.AppendLine($"  {s.Start}: ~{s.ExpectedPeople} чел. ({KitchenStore.Pct(s.Share)})");
            }
            return sb.ToString().TrimEnd();
        }
    }

    /// <summary>
    /// Прогноз для кухни. Берёт положенные порции по броням на дату и умножает на историческую явку
    /// (доля фактически выданных к положенным по дням реестра); время — из распределения выдач по слотам.
    /// </summary>
    public static class BreakfastForecast
    {
        public static ForecastResult Build(IEnumerable<Stay> activeStays, BreakfastPolicy policy, PluginConfig cfg,
            RegistryStore registry, UsageStore usage, DateTime today, DateTime target)
        {
            var res = new ForecastResult { Day = target.Date };
            foreach (var st in activeStays ?? Enumerable.Empty<Stay>())
            {
                // на сегодня считаем только заехавших; на будущие дни — ещё и ожидаемых по плану броней
                var statusOk = st.IsCheckedIn || (target.Date > today.Date && string.Equals(st.Status, "booked", StringComparison.OrdinalIgnoreCase));
                if (!statusOk) continue;
                var dec = policy.Evaluate(st, target);
                if (dec.Right != BreakfastRight.Included) continue;
                res.Rooms++;
                res.EntitledAdults += dec.AdultPortions;
                res.EntitledChildren += dec.ChildPortions;
            }

            long ea = 0, ua = 0, ec = 0, uc = 0; int days = 0;
            for (var i = 1; i <= Math.Max(cfg.ForecastHistoryDays, 0); i++)
            {
                var reg = registry.Load(today.Date.AddDays(-i));
                if (reg == null) continue;
                var ents = reg.Rows.Where(r => r.Status != RegistryStatus.Cancelled).ToList();
                if (ents.Sum(r => r.Portions) == 0) continue;
                days++;
                ea += ents.Sum(r => r.AdultPortions); ua += ents.Sum(r => r.UsedAdults);
                ec += ents.Sum(r => r.ChildPortions); uc += ents.Sum(r => r.UsedChildren);
            }
            res.HistoryDays = days;
            if (days > 0)
            {
                res.AdultAttendance = ea == 0 ? 1 : Math.Min((double)ua / ea, 1.0);
                res.ChildAttendance = ec == 0 ? 1 : Math.Min((double)uc / ec, 1.0);
            }
            res.ExpectedAdults = (int)Math.Ceiling(res.EntitledAdults * res.AdultAttendance);
            res.ExpectedChildren = (int)Math.Ceiling(res.EntitledChildren * res.ChildAttendance);

            // распределение по времени
            var slot = Math.Max(cfg.ForecastSlotMinutes, 5);
            var hist = usage.Between(today.Date.AddDays(-Math.Max(cfg.ForecastHistoryDays, 1)), today.Date.AddDays(-1))
                .Where(e => e.Kind == UsageStore.KindIncluded && !e.Cancelled && e.TimeValue != null)
                .ToList();
            var total = hist.Sum(e => e.Persons);
            if (total > 0)
            {
                var expected = res.ExpectedAdults + res.ExpectedChildren;
                res.Slots = hist
                    .GroupBy(e => (int)e.TimeValue.Value.TimeOfDay.TotalMinutes / slot)
                    .OrderBy(g => g.Key)
                    .Select(g =>
                    {
                        var share = (double)g.Sum(e => e.Persons) / total;
                        var start = TimeSpan.FromMinutes(g.Key * slot);
                        return new ForecastSlot { Start = start.ToString(@"hh\:mm"), Share = share, ExpectedPeople = (int)Math.Round(share * expected) };
                    }).ToList();
            }
            return res;
        }
    }
}
