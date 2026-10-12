using System;
using System.Linq;

namespace IikoExely
{
    /// <summary>Выбор скидки проживающего по уровню (лояльность или тариф).</summary>
    public static class DiscountSelector
    {
        public static string ResidentDiscountName(Stay stay, PluginConfig cfg)
        {
            foreach (var r in cfg.ResidentDiscountRules.Where(r => !string.IsNullOrWhiteSpace(r.Contains)))
            {
                var value = string.Equals(r.Field, "ratePlan", StringComparison.OrdinalIgnoreCase)
                    ? stay.RatePlan?.Name
                    : stay.Loyalty;
                if (!string.IsNullOrEmpty(value) && value.IndexOf(r.Contains, StringComparison.OrdinalIgnoreCase) >= 0)
                    return r.Discount;
            }
            return cfg.DefaultResidentDiscount;
        }
    }
}
