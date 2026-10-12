using System;
using System.Collections.Generic;
using System.Globalization;
using System.Runtime.Serialization;

namespace IikoExely
{
    // Формы ответов Hotel Hub API v1 (hotelhub/web/api.py). Даты приходят строками ISO 8601 без смещения —
    // это местное время отеля.

    [DataContract]
    public class StaysResponse
    {
        [DataMember(Name = "stays")] public List<Stay> Stays { get; set; } = new List<Stay>();
    }

    [DataContract]
    public class Stay
    {
        [DataMember(Name = "id")] public string Id { get; set; }
        [DataMember(Name = "booking")] public string Booking { get; set; }
        [DataMember(Name = "status")] public string Status { get; set; }
        [DataMember(Name = "room")] public string Room { get; set; }
        [DataMember(Name = "room_type")] public NamedRef RoomType { get; set; }
        [DataMember(Name = "rate_plan")] public NamedRef RatePlan { get; set; }
        [DataMember(Name = "loyalty")] public string Loyalty { get; set; }
        [DataMember(Name = "planned_in")] public string PlannedIn { get; set; }
        [DataMember(Name = "planned_out")] public string PlannedOut { get; set; }
        [DataMember(Name = "actual_in")] public string ActualIn { get; set; }
        [DataMember(Name = "actual_out")] public string ActualOut { get; set; }
        [DataMember(Name = "adults")] public int Adults { get; set; }
        [DataMember(Name = "children")] public int Children { get; set; }
        [DataMember(Name = "services")] public List<StayServiceInfo> Services { get; set; } = new List<StayServiceInfo>();

        public DateTime? PlannedInTime => ParseTime(PlannedIn);
        public DateTime? PlannedOutTime => ParseTime(PlannedOut);
        public DateTime? ActualInTime => ParseTime(ActualIn);
        public DateTime? ActualOutTime => ParseTime(ActualOut);

        public bool IsCheckedIn => string.Equals(Status, "checked_in", StringComparison.OrdinalIgnoreCase);

        public static DateTime? ParseTime(string s)
        {
            if (string.IsNullOrWhiteSpace(s)) return null;
            // хаб отдаёт местное время отеля без смещения; значение оставляем как есть
            return DateTime.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.None, out var t)
                ? DateTime.SpecifyKind(t, DateTimeKind.Unspecified)
                : (DateTime?)null;
        }
    }

    [DataContract]
    public class NamedRef
    {
        [DataMember(Name = "id")] public string Id { get; set; }
        [DataMember(Name = "name")] public string Name { get; set; }
    }

    [DataContract]
    public class StayServiceInfo
    {
        [DataMember(Name = "id")] public string Id { get; set; }
        [DataMember(Name = "name")] public string Name { get; set; }
        [DataMember(Name = "included_in_rate")] public bool IncludedInRate { get; set; }
        [DataMember(Name = "meal_plan_code")] public string MealPlanCode { get; set; }
    }

    [DataContract]
    public class GuestsResponse
    {
        [DataMember(Name = "guests")] public List<HotelGuest> Guests { get; set; } = new List<HotelGuest>();
    }

    [DataContract]
    public class HotelGuest
    {
        [DataMember(Name = "role")] public string Role { get; set; }
        [DataMember(Name = "last_name")] public string LastName { get; set; }
        [DataMember(Name = "first_name")] public string FirstName { get; set; }

        public string ShortName
        {
            get
            {
                var ln = (LastName ?? "").Trim();
                var fn = (FirstName ?? "").Trim();
                return fn.Length > 0 ? ln + " " + fn.Substring(0, 1) + "." : ln;
            }
        }
    }

    [DataContract]
    public class CatalogResponse
    {
        [DataMember(Name = "rate_plans")] public List<CatalogRatePlan> RatePlans { get; set; } = new List<CatalogRatePlan>();
    }

    [DataContract]
    public class CatalogRatePlan
    {
        [DataMember(Name = "id")] public string Id { get; set; }
        [DataMember(Name = "name")] public string Name { get; set; }
        [DataMember(Name = "included_services")] public List<NamedRef> IncludedServices { get; set; } = new List<NamedRef>();
    }
}
