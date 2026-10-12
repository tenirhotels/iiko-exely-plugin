using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;

namespace IikoExely
{
    public class ReportRow
    {
        public string Room { get; set; }
        public string Names { get; set; }
        public string RatePlan { get; set; }
        public string Loyalty { get; set; }
        public BreakfastDecision Decision { get; set; }
        public int DiscountedPersons { get; set; }
        public string Discounts { get; set; }
    }

    /// <summary>Утренняя сверка: кто из проживающих имел право на завтрак, кто пришёл, кто пришёл без права.</summary>
    public class BreakfastReport
    {
        public DateTime Day { get; set; }
        public List<ReportRow> Rows { get; set; } = new List<ReportRow>();
        /// <summary>Положен завтрак, никто не пришёл.</summary>
        public IEnumerable<ReportRow> NotCame => Rows.Where(r => r.Decision.Portions > 0 && r.Decision.Used == 0);
        public IEnumerable<ReportRow> Partial => Rows.Where(r => r.Decision.Used > 0 && r.Decision.Remaining > 0);
        /// <summary>Проживающие, которым завтрак не положен, но они ели со скидкой.</summary>
        public IEnumerable<ReportRow> PaidWithDiscount => Rows.Where(r => r.Decision.Portions == 0 && r.DiscountedPersons > 0);
        public int EntitledPortions => Rows.Sum(r => r.Decision.Portions);
        public int UsedPortions => Rows.Sum(r => r.Decision.Used);

        public static BreakfastReport Build(IEnumerable<InHouseGuest> guests, BreakfastPolicy policy, UsageStore usage, DateTime day)
        {
            var log = usage.ForDay(day);
            var rep = new BreakfastReport { Day = day.Date };
            foreach (var g in guests)
            {
                var used = usage.UsedPortions(g.Stay.Id, day);
                var dec = policy.Evaluate(g.Stay, day, used);
                if (dec.Right == BreakfastRight.OutsideStay) continue;
                var disc = log.Where(e => e.StayId == g.Stay.Id && e.Kind == UsageStore.KindDiscount).ToList();
                rep.Rows.Add(new ReportRow
                {
                    Room = g.Room,
                    Names = g.Names,
                    RatePlan = g.Stay.RatePlan?.Name,
                    Loyalty = g.Stay.Loyalty,
                    Decision = dec,
                    DiscountedPersons = disc.Sum(e => e.Persons),
                    Discounts = string.Join("; ", disc.Select(e => e.Discount).Distinct()),
                });
            }
            rep.Rows = rep.Rows.OrderBy(r => r.Room, StringComparer.CurrentCultureIgnoreCase).ToList();
            return rep;
        }

        public string Summary(int maxLines = 12)
        {
            var sb = new StringBuilder();
            sb.AppendLine($"Завтраки {Day:dd.MM.yyyy}: положено порций {EntitledPortions}, выдано {UsedPortions}.");
            AppendList(sb, "Не пришли (положен завтрак)", NotCame, maxLines, r => $"{r.Room} {r.Names} — {r.Decision.Portions}");
            AppendList(sb, "Пришли не все", Partial, maxLines, r => $"{r.Room} {r.Names} — {r.Decision.Used} из {r.Decision.Portions}");
            AppendList(sb, "Без права на завтрак (со скидкой проживающего)", PaidWithDiscount, maxLines, r => $"{r.Room} {r.Names} — {r.DiscountedPersons} чел., {r.Discounts}");
            return sb.ToString().TrimEnd();
        }

        private static void AppendList(StringBuilder sb, string title, IEnumerable<ReportRow> rows, int max, Func<ReportRow, string> fmt)
        {
            var list = rows.ToList();
            if (list.Count == 0) return;
            sb.AppendLine().AppendLine($"{title}: {list.Count}");
            foreach (var r in list.Take(max)) sb.AppendLine("  " + fmt(r).Trim());
            if (list.Count > max) sb.AppendLine($"  … и ещё {list.Count - max}");
        }

        /// <summary>CSV с «;» и BOM — открывается в Excel.</summary>
        public string ToCsv()
        {
            var sb = new StringBuilder("﻿Номер;Гости;Тариф;Уровень;Положено;Выдано;Осталось;Со скидкой чел.;Скидка;Статус\r\n");
            foreach (var r in Rows)
                sb.Append(string.Join(";", new[]
                {
                    Q(r.Room), Q(r.Names), Q(r.RatePlan), Q(r.Loyalty),
                    r.Decision.Portions.ToString(CultureInfo.InvariantCulture),
                    r.Decision.Used.ToString(CultureInfo.InvariantCulture),
                    r.Decision.Remaining.ToString(CultureInfo.InvariantCulture),
                    r.DiscountedPersons.ToString(CultureInfo.InvariantCulture), Q(r.Discounts), Q(StatusText(r)),
                })).Append("\r\n");
            return sb.ToString();
        }

        private static string StatusText(ReportRow r)
        {
            if (r.Decision.Portions == 0) return r.DiscountedPersons > 0 ? "без права, скидка" : "без права";
            if (r.Decision.Used == 0) return "не пришли";
            return r.Decision.Remaining > 0 ? "пришли не все" : "все пришли";
        }

        // защита от CSV-инъекции формул и от «;» и кавычек в значениях
        private static string Q(string v)
        {
            v = v ?? "";
            if (v.Length > 0 && "=+-@".IndexOf(v[0]) >= 0) v = "'" + v;
            return "\"" + v.Replace("\"", "\"\"") + "\"";
        }
    }
}
