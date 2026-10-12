using System.Collections.Generic;
using System.IO;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;

namespace IikoExely
{
    /// <summary>Настройки плагина; файл iiko-exely.json лежит в папке настроек плагина.</summary>
    [DataContract]
    public class PluginConfig
    {
        [DataMember(Name = "hubUrl")] public string HubUrl { get; set; } = "http://localhost:8000";
        /// <summary>Номер отеля в Exely (код property в Hotel Hub).</summary>
        [DataMember(Name = "propertyCode")] public string PropertyCode { get; set; } = "";
        /// <summary>Bearer-токен потребителя Hotel Hub; права: stays, catalog, guests (последнее — для фамилий).</summary>
        [DataMember(Name = "hubToken")] public string HubToken { get; set; } = "";
        [DataMember(Name = "refreshSeconds")] public int RefreshSeconds { get; set; } = 120;
        [DataMember(Name = "requestTimeoutSeconds")] public int RequestTimeoutSeconds { get; set; } = 8;
        [DataMember(Name = "showGuestNames")] public bool ShowGuestNames { get; set; } = true;

        /// <summary>Коды питания Exely, дающие завтрак: BB, HB, FB, AI и т.п.</summary>
        [DataMember(Name = "breakfastMealPlanCodes")]
        public List<string> BreakfastMealPlanCodes { get; set; } = new List<string> { "BB", "HB", "FB", "AI", "UAI" };
        /// <summary>Запасной признак: подстрока в названии включённой услуги/услуги тарифа.</summary>
        [DataMember(Name = "breakfastKeywords")]
        public List<string> BreakfastKeywords { get; set; } = new List<string> { "завтрак", "breakfast" };
        /// <summary>Завтрак в день заезда (по умолчанию нет: заезд днём, первый завтрак — утром следующего дня).</summary>
        [DataMember(Name = "breakfastOnArrivalDay")] public bool BreakfastOnArrivalDay { get; set; }
        /// <summary>Завтрак в день выезда (по умолчанию да).</summary>
        [DataMember(Name = "breakfastOnDepartureDay")] public bool BreakfastOnDepartureDay { get; set; } = true;
        /// <summary>Дети считаются отдельными порциями завтрака.</summary>
        [DataMember(Name = "childrenGetBreakfast")] public bool ChildrenGetBreakfast { get; set; } = true;

        /// <summary>Скидка iiko для включённого завтрака (100% на группу «Завтрак»; настраивается в iikoOffice).</summary>
        [DataMember(Name = "includedBreakfastDiscount")] public string IncludedBreakfastDiscount { get; set; } = "Завтрак проживающего";
        /// <summary>Скидка проживающего, если уровень не найден в правилах.</summary>
        [DataMember(Name = "defaultResidentDiscount")] public string DefaultResidentDiscount { get; set; } = "Проживающий";
        /// <summary>Правила уровня: первое сработавшее задаёт скидку. Сначала смотрится лояльность, потом тариф.</summary>
        [DataMember(Name = "residentDiscountRules")]
        public List<DiscountRule> ResidentDiscountRules { get; set; } = new List<DiscountRule>();

        /// <summary>Комментарий в заказ: шаблон с {room}, {guest}, {kind}.</summary>
        [DataMember(Name = "orderCommentTemplate")] public string OrderCommentTemplate { get; set; } = "Отель: комн. {room}, {guest} ({kind})";

        public static PluginConfig Load(string path)
        {
            if (!File.Exists(path)) return new PluginConfig();
            using (var fs = File.OpenRead(path))
                return (PluginConfig)new DataContractJsonSerializer(typeof(PluginConfig)).ReadObject(fs);
        }

        public void Save(string path)
        {
            using (var fs = File.Create(path))
            using (var w = JsonReaderWriterFactory.CreateJsonWriter(fs, System.Text.Encoding.UTF8, true, true, "  "))
                new DataContractJsonSerializer(typeof(PluginConfig)).WriteObject(w, this);
        }
    }

    [DataContract]
    public class DiscountRule
    {
        /// <summary>"loyalty" или "ratePlan".</summary>
        [DataMember(Name = "field")] public string Field { get; set; }
        /// <summary>Подстрока без учёта регистра.</summary>
        [DataMember(Name = "contains")] public string Contains { get; set; }
        [DataMember(Name = "discount")] public string Discount { get; set; }
    }
}
