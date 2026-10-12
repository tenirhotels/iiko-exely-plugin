using System;
using System.Collections.Generic;
using System.Linq;

namespace IikoExely
{
    public enum BreakfastRight
    {
        /// <summary>Завтрак включён и ещё есть неиспользованные порции.</summary>
        Included,
        /// <summary>Завтрак включён, но все порции за сегодня уже выданы.</summary>
        Exhausted,
        /// <summary>Завтрака в тарифе (на этот день) нет — гость проживающий, но платит сам.</summary>
        NotIncluded,
        /// <summary>Гость в этот день не проживает.</summary>
        OutsideStay,
    }

    public class BreakfastDecision
    {
        public BreakfastRight Right { get; set; }
        public int AdultPortions { get; set; }
        public int ChildPortions { get; set; }
        public int UsedAdults { get; set; }
        public int UsedChildren { get; set; }
        public int Portions => AdultPortions + ChildPortions;
        public int Used => UsedAdults + UsedChildren;
        public int RemainingAdults => Math.Max(AdultPortions - UsedAdults, 0);
        public int RemainingChildren => Math.Max(ChildPortions - UsedChildren, 0);
        public int Remaining => RemainingAdults + RemainingChildren;
        public string Note { get; set; }
    }

    public class BreakfastPolicy
    {
        private readonly PluginConfig _cfg;
        private readonly Dictionary<string, CatalogRatePlan> _plans;

        public BreakfastPolicy(PluginConfig cfg, CatalogResponse catalog = null)
        {
            _cfg = cfg;
            _plans = (catalog?.RatePlans ?? new List<CatalogRatePlan>())
                .Where(p => p.Id != null)
                .GroupBy(p => p.Id)
                .ToDictionary(g => g.Key, g => g.First());
        }

        /// <summary>Входит ли завтрак в проживание: код питания, услуга стоянки, услуга тарифа из справочника.</summary>
        public bool HasIncludedBreakfast(Stay stay)
        {
            foreach (var s in stay.Services ?? new List<StayServiceInfo>())
            {
                if (!string.IsNullOrWhiteSpace(s.MealPlanCode) &&
                    _cfg.BreakfastMealPlanCodes.Any(c => string.Equals(c, s.MealPlanCode.Trim(), StringComparison.OrdinalIgnoreCase)))
                    return true;
                // допуслуга «Завтрак», купленная отдельно от тарифа, тоже оплачена в отеле — права на завтрак это не меняет
                if (NameMatches(s.Name)) return true;
            }
            var planId = stay.RatePlan?.Id;
            if (planId != null && _plans.TryGetValue(planId, out var plan))
                return (plan.IncludedServices ?? new List<NamedRef>()).Any(x => NameMatches(x.Name));
            return false;
        }

        private bool NameMatches(string name) =>
            !string.IsNullOrWhiteSpace(name) &&
            _cfg.BreakfastKeywords.Any(k => name.IndexOf(k, StringComparison.OrdinalIgnoreCase) >= 0);

        public int AdultPortions(Stay stay) => Math.Max(stay.Adults, stay.Children > 0 ? 0 : 1);
        public int ChildPortions(Stay stay) => _cfg.ChildrenGetBreakfast ? Math.Max(stay.Children, 0) : 0;

        /// <summary>Положен ли завтрак в указанную дату (местное время отеля) и сколько порций осталось.</summary>
        public BreakfastDecision Evaluate(Stay stay, DateTime day, int usedAdults = 0, int usedChildren = 0)
        {
            var d = day.Date;
            var arrival = (stay.ActualInTime ?? stay.PlannedInTime)?.Date;
            var departure = (stay.ActualOutTime ?? stay.PlannedOutTime)?.Date;
            if (arrival == null || departure == null || d < arrival || d > departure)
                return new BreakfastDecision { Right = BreakfastRight.OutsideStay, Note = "не проживает в этот день" };

            if (!HasIncludedBreakfast(stay))
                return new BreakfastDecision { Right = BreakfastRight.NotIncluded, Note = "завтрак не входит в тариф" };
            if (d == arrival && !_cfg.BreakfastOnArrivalDay)
                return new BreakfastDecision { Right = BreakfastRight.NotIncluded, Note = "день заезда: завтрак не положен" };
            if (d == departure && !_cfg.BreakfastOnDepartureDay)
                return new BreakfastDecision { Right = BreakfastRight.NotIncluded, Note = "день выезда: завтрак не положен" };

            var dec = new BreakfastDecision
            {
                AdultPortions = AdultPortions(stay),
                ChildPortions = ChildPortions(stay),
                UsedAdults = usedAdults,
                UsedChildren = usedChildren,
            };
            dec.Right = dec.Remaining > 0 ? BreakfastRight.Included : BreakfastRight.Exhausted;
            return dec;
        }
    }
}
