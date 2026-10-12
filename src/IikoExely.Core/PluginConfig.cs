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

        // --- завтрак: окно, менеджер, отмена ---
        /// <summary>Начало и конец завтрака (местное время, ЧЧ:ММ): от этого зависят статусы «ожидается / не использован».</summary>
        [DataMember(Name = "breakfastStart")] public string BreakfastStart { get; set; } = "07:00";
        [DataMember(Name = "breakfastEnd")] public string BreakfastEnd { get; set; } = "11:00";
        /// <summary>Код права iikoFront, подтверждаемого менеджером (допуск сверх нормы, отмена). Пусто — подтверждение не требуется.</summary>
        [DataMember(Name = "managerPermissionCode")] public string ManagerPermissionCode { get; set; } = "";
        [DataMember(Name = "cancelRequiresManager")] public bool CancelRequiresManager { get; set; } = true;

        // --- прогноз ---
        [DataMember(Name = "forecastHistoryDays")] public int ForecastHistoryDays { get; set; } = 14;
        [DataMember(Name = "forecastSlotMinutes")] public int ForecastSlotMinutes { get; set; } = 30;

        // --- рум-сервис ---
        /// <summary>Полная стоимость доставки; её получает сотрудник (часть платит гость, часть компенсирует отель).</summary>
        [DataMember(Name = "roomServiceDeliveryFee")] public decimal RoomServiceDeliveryFee { get; set; } = 5000m;
        /// <summary>Лесенка: чем больше чек, тем меньше платит гость. Берётся самая высокая подходящая ступень.</summary>
        [DataMember(Name = "roomServiceTiers")]
        public List<RoomServiceTier> RoomServiceTiers { get; set; } = new List<RoomServiceTier>
        {
            new RoomServiceTier { MinCheck = 40000m, GuestFee = 0m },
            new RoomServiceTier { MinCheck = 20000m, GuestFee = 2500m },
            new RoomServiceTier { MinCheck = 0m, GuestFee = 5000m },
        };
        /// <summary>Позиция меню с открытой ценой для доставки; не должна входить в базу бонусов официантов.</summary>
        [DataMember(Name = "roomServiceDeliveryProduct")] public string RoomServiceDeliveryProduct { get; set; } = "Доставка рум-сервис";
        /// <summary>Ценовая категория (отдельное меню) рум-сервиса; при другой категории плагин предупредит.</summary>
        [DataMember(Name = "roomServicePriceCategory")] public string RoomServicePriceCategory { get; set; } = "Рум-сервис";

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
    public class RoomServiceTier
    {
        [DataMember(Name = "minCheck")] public decimal MinCheck { get; set; }
        /// <summary>Сколько из доставки платит гость при чеке от minCheck.</summary>
        [DataMember(Name = "guestFee")] public decimal GuestFee { get; set; }
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
