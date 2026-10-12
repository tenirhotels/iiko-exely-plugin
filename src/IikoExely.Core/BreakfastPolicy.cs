using System;
using System.Collections.Generic;
using System.Linq;

namespace IikoExely
{
    public enum BreakfastRight
    {
        /// <summary>Завтрак включён в тариф и ещё есть неиспользованные порции.</summary>
        Included,
        /// <summary>Завтрак включён, но все порции за сегодня уже выданы.</summary>
        Exhausted,
        /// <summary>Завтрака в тарифе нет — гость проживающий, но платит сам (скидка по уровню).</summary>
        NotIncluded,
        /// <summary>Гость сегодня не проживает (до заезда или после выезда).</summary>
        OutsideStay,
    }

    public class BreakfastDecision
    {
        public BreakfastRight Right { get; set; }
        /// <summary>Сколько порций положено на день.</summary>
        public int Portions { get; set; }
        public int Used { get; set; }
        public int Remaining => Math.Max(Portions - Used, 0);
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

        public int Portions(Stay stay) =>
            Math.Max(stay.Adults + (_cfg.ChildrenGetBreakfast ? stay.Children : 0), 1);

        /// <summary>Положен ли завтрак в указанную дату (местное время отеля).</summary>
        public BreakfastDecision Evaluate(Stay stay, DateTime day, int usedToday)
        {
            var d = day.Date;
            var arrival = (stay.ActualInTime ?? stay.PlannedInTime)?.Date;
            var departure = (stay.ActualOutTime ?? stay.PlannedOutTime)?.Date;
            if (arrival == null || departure == null || d < arrival || d > departure)
                return new BreakfastDecision { Right = BreakfastRight.OutsideStay, Note = "не проживает сегодня" };

            var portions = Portions(stay);
            var included = HasIncludedBreakfast(stay);
            if (included)
            {
                if (d == arrival && !_cfg.BreakfastOnArrivalDay)
                    return new BreakfastDecision { Right = BreakfastRight.NotIncluded, Portions = 0, Note = "день заезда: завтрак не положен" };
                if (d == departure && !_cfg.BreakfastOnDepartureDay)
                    return new BreakfastDecision { Right = BreakfastRight.NotIncluded, Portions = 0, Note = "день выезда: завтрак не положен" };
            }
            if (!included)
                return new BreakfastDecision { Right = BreakfastRight.NotIncluded, Portions = 0, Note = "завтрак не входит в тариф" };

            var dec = new BreakfastDecision { Portions = portions, Used = usedToday };
            dec.Right = dec.Remaining > 0 ? BreakfastRight.Included : BreakfastRight.Exhausted;
            return dec;
        }
    }
}
