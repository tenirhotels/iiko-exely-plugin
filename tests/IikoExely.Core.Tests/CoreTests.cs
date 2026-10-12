using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using IikoExely;
using Xunit;

public class CoreTests
{
    static readonly DateTime Day = new DateTime(2026, 10, 13, 8, 30, 0);

    static Stay MakeStay(string id = "s1", string room = "101", string ratePlan = "bb", bool breakfast = true,
        string pin = "2026-10-12T14:00:00", string pout = "2026-10-15T12:00:00", int adults = 2, int children = 0, string loyalty = null)
    {
        var s = new Stay
        {
            Id = id, Room = room, Status = "checked_in", PlannedIn = pin, PlannedOut = pout, Adults = adults, Children = children,
            RatePlan = new NamedRef { Id = ratePlan, Name = "Тариф " + ratePlan }, Loyalty = loyalty,
        };
        if (breakfast) s.Services.Add(new StayServiceInfo { Id = "b", Name = "Завтрак", IncludedInRate = true, MealPlanCode = "BB" });
        return s;
    }

    static string TempFile() => Path.Combine(Path.GetTempPath(), "iikoexely-" + Guid.NewGuid().ToString("N") + ".json");

    [Fact]
    public void Included_breakfast_gives_portions_per_person()
    {
        var p = new BreakfastPolicy(new PluginConfig());
        var d = p.Evaluate(MakeStay(adults: 2, children: 1), Day, 0);
        Assert.Equal(BreakfastRight.Included, d.Right);
        Assert.Equal(3, d.Portions);
    }

    [Fact]
    public void Children_can_be_excluded()
    {
        var p = new BreakfastPolicy(new PluginConfig { ChildrenGetBreakfast = false });
        Assert.Equal(2, p.Evaluate(MakeStay(adults: 2, children: 1), Day, 0).Portions);
    }

    [Fact]
    public void Used_up_portions_are_exhausted()
    {
        var d = new BreakfastPolicy(new PluginConfig()).Evaluate(MakeStay(adults: 2), Day, 2);
        Assert.Equal(BreakfastRight.Exhausted, d.Right);
        Assert.Equal(0, d.Remaining);
    }

    [Fact]
    public void Partial_use_leaves_remaining()
    {
        var d = new BreakfastPolicy(new PluginConfig()).Evaluate(MakeStay(adults: 2), Day, 1);
        Assert.Equal(BreakfastRight.Included, d.Right);
        Assert.Equal(1, d.Remaining);
    }

    [Fact]
    public void No_breakfast_in_rate_means_not_included()
    {
        var d = new BreakfastPolicy(new PluginConfig()).Evaluate(MakeStay(breakfast: false), Day, 0);
        Assert.Equal(BreakfastRight.NotIncluded, d.Right);
    }

    [Fact]
    public void Arrival_day_has_no_breakfast_by_default_but_departure_day_has()
    {
        var p = new BreakfastPolicy(new PluginConfig());
        var st = MakeStay();
        Assert.Equal(BreakfastRight.NotIncluded, p.Evaluate(st, new DateTime(2026, 10, 12, 8, 0, 0), 0).Right);
        Assert.Equal(BreakfastRight.Included, p.Evaluate(st, new DateTime(2026, 10, 15, 8, 0, 0), 0).Right);
        Assert.Equal(BreakfastRight.OutsideStay, p.Evaluate(st, new DateTime(2026, 10, 16, 8, 0, 0), 0).Right);
        Assert.Equal(BreakfastRight.OutsideStay, p.Evaluate(st, new DateTime(2026, 10, 11, 8, 0, 0), 0).Right);
    }

    [Fact]
    public void Arrival_day_breakfast_can_be_enabled()
    {
        var p = new BreakfastPolicy(new PluginConfig { BreakfastOnArrivalDay = true });
        Assert.Equal(BreakfastRight.Included, p.Evaluate(MakeStay(), new DateTime(2026, 10, 12, 8, 0, 0), 0).Right);
    }

    [Fact]
    public void Actual_checkin_overrides_planned_arrival_day()
    {
        var st = MakeStay(pin: "2026-10-13T14:00:00");
        st.ActualIn = "2026-10-12T23:30:00"; // заехал ночью раньше плана
        var d = new BreakfastPolicy(new PluginConfig()).Evaluate(st, Day, 0);
        Assert.Equal(BreakfastRight.Included, d.Right);
    }

    [Fact]
    public void Breakfast_from_rate_plan_catalog_when_stay_has_no_services()
    {
        var catalog = new CatalogResponse
        {
            RatePlans = { new CatalogRatePlan { Id = "bb", IncludedServices = { new NamedRef { Id = "x", Name = "Завтрак «шведский стол»" } } } },
        };
        var d = new BreakfastPolicy(new PluginConfig(), catalog).Evaluate(MakeStay(breakfast: false), Day, 0);
        Assert.Equal(BreakfastRight.Included, d.Right);
    }

    [Fact]
    public void Breakfast_by_name_keyword_without_meal_plan_code()
    {
        var st = MakeStay(breakfast: false);
        st.Services.Add(new StayServiceInfo { Name = "Breakfast buffet", IncludedInRate = true });
        Assert.Equal(BreakfastRight.Included, new BreakfastPolicy(new PluginConfig()).Evaluate(st, Day, 0).Right);
    }

    [Fact]
    public void Room_only_meal_plan_code_gives_nothing()
    {
        var st = MakeStay(breakfast: false);
        st.Services.Add(new StayServiceInfo { Name = "Интернет Premium", MealPlanCode = "RO" });
        Assert.Equal(BreakfastRight.NotIncluded, new BreakfastPolicy(new PluginConfig()).Evaluate(st, Day, 0).Right);
    }

    [Fact]
    public void Discount_rules_loyalty_first_then_rate_plan_then_default()
    {
        var cfg = new PluginConfig
        {
            DefaultResidentDiscount = "Проживающий",
            ResidentDiscountRules =
            {
                new DiscountRule { Field = "loyalty", Contains = "gold", Discount = "Проживающий Gold" },
                new DiscountRule { Field = "ratePlan", Contains = "Люкс", Discount = "Проживающий Люкс" },
            },
        };
        Assert.Equal("Проживающий Gold", DiscountSelector.ResidentDiscountName(MakeStay(loyalty: "GOLD level"), cfg));
        var lux = MakeStay(); lux.RatePlan.Name = "Тариф Люкс";
        Assert.Equal("Проживающий Люкс", DiscountSelector.ResidentDiscountName(lux, cfg));
        Assert.Equal("Проживающий", DiscountSelector.ResidentDiscountName(MakeStay(), cfg));
    }

    static UsageEntry U(string stay, int adults, int children, string order, string kind = UsageStore.KindIncluded, string date = "2026-10-13", string time = "2026-10-13T08:10:00") =>
        new UsageEntry { Date = date, Time = time, StayId = stay, Kind = kind, Adults = adults, Children = children, OrderId = order, Employee = "Кассир" };

    [Fact]
    public void Usage_store_sums_adults_and_children_per_day_and_persists()
    {
        var path = TempFile();
        var u = new UsageStore(path);
        u.Add(U("s1", 1, 0, "o1"));
        u.Add(U("s1", 1, 1, "o2"));
        u.Add(U("s1", 5, 0, "o3", UsageStore.KindDiscount));
        u.Add(U("s1", 2, 0, "o4", date: "2026-10-14"));
        Assert.Equal((2, 1), u.Used("s1", Day));
        Assert.Equal((2, 1), new UsageStore(path).Used("s1", Day));
    }

    [Fact]
    public void Cancel_requires_reason_and_frees_portions()
    {
        var u = new UsageStore(TempFile());
        var e = U("s1", 2, 0, "o1"); u.Add(e);
        Assert.Throws<ArgumentException>(() => u.Cancel(e.Id, "  ", "М", Day));
        Assert.Equal(2, u.UsedPortions("s1", Day));
        var c = u.Cancel(e.Id, "ошибочная отметка", "Менеджер", Day);
        Assert.True(c.Cancelled);
        Assert.Equal("ошибочная отметка", c.CancelReason);
        Assert.Equal(0, u.UsedPortions("s1", Day));
        Assert.Null(u.Cancel(e.Id, "ещё раз", "М", Day)); // повторная отмена
        Assert.Single(u.ForDay(Day)); // запись осталась в журнале
    }

    [Fact]
    public void Duplicate_order_mark_is_detected_but_cancelled_one_is_not()
    {
        var u = new UsageStore(TempFile());
        var e = U("s1", 1, 0, "o1"); u.Add(e);
        Assert.True(u.HasActiveEntry("o1", "s1", UsageStore.KindIncluded));
        Assert.False(u.HasActiveEntry("o2", "s1", UsageStore.KindIncluded));
        u.Cancel(e.Id, "ошибка", "М", Day);
        Assert.False(u.HasActiveEntry("o1", "s1", UsageStore.KindIncluded));
    }

    [Fact]
    public void Usage_store_survives_broken_file()
    {
        var path = TempFile();
        File.WriteAllText(path, "{не json");
        var u = new UsageStore(path);
        Assert.Equal(0, u.UsedPortions("s1", Day));
        u.Add(U("s1", 1, 0, "o1"));
        Assert.Equal(1, new UsageStore(path).UsedPortions("s1", Day));
    }

    // --- реестр ---

    static string TempDir() { var d = Path.Combine(Path.GetTempPath(), "iikoexely-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(d); return d; }
    static readonly DateTime Morning = new DateTime(2026, 10, 13, 8, 30, 0);
    static readonly DateTime After = new DateTime(2026, 10, 13, 11, 30, 0);

    static BreakfastRegistry NewRegistry(PluginConfig cfg = null) => new BreakfastRegistry(cfg ?? new PluginConfig(), new RegistryStore(TempDir()));

    static InHouseGuest G(string id, string room, string names = "Иванов И.", bool breakfast = true, int adults = 2, int children = 0, string pout = "2026-10-15T12:00:00") =>
        new InHouseGuest { Stay = MakeStay(id, room, breakfast: breakfast, adults: adults, children: children, pout: pout), Names = names };

    [Fact]
    public void Registry_statuses_before_and_after_breakfast_window()
    {
        var cfg = new PluginConfig();
        var reg = NewRegistry(cfg);
        var u = new UsageStore(TempFile());
        u.Add(U("A", 2, 0, "1"));                 // всё выдано
        u.Add(U("B", 1, 0, "2"));                 // частично
        var guests = new[] { G("A", "101"), G("B", "102"), G("C", "103"), G("D", "104", breakfast: false) };
        var policy = new BreakfastPolicy(cfg);

        var before = reg.Sync(guests, policy, u, Morning).Rows.ToDictionary(r => r.StayId);
        Assert.Equal(RegistryStatus.Used, before["A"].Status);
        Assert.Equal(RegistryStatus.Partial, before["B"].Status);
        Assert.Equal(RegistryStatus.Expected, before["C"].Status);
        Assert.Equal(RegistryStatus.NotEntitled, before["D"].Status);

        var after = reg.Sync(guests, policy, u, After).Rows.ToDictionary(r => r.StayId);
        Assert.Equal(RegistryStatus.NotUsed, after["C"].Status);
        Assert.Equal(RegistryStatus.Partial, after["B"].Status);
    }

    [Fact]
    public void Registry_shows_adults_and_children_separately_and_totals()
    {
        var cfg = new PluginConfig();
        var reg = NewRegistry(cfg);
        var u = new UsageStore(TempFile());
        u.Add(U("A", 1, 1, "1"));
        var day = reg.Sync(new[] { G("A", "101", adults: 2, children: 2) }, new BreakfastPolicy(cfg), u, Morning);
        var r = day.Rows.Single();
        Assert.Equal((2, 2, 1, 1), (r.AdultPortions, r.ChildPortions, r.UsedAdults, r.UsedChildren));
        Assert.Equal(2, day.EntitledAdults); Assert.Equal(2, day.EntitledChildren);
        Assert.Equal(1, day.UsedAdults); Assert.Equal(1, day.UsedChildren);
    }

    [Fact]
    public void Registry_notices_booking_changes_and_records_source_and_time()
    {
        var cfg = new PluginConfig();
        var reg = NewRegistry(cfg);
        var u = new UsageStore(TempFile());
        var policy = new BreakfastPolicy(cfg);
        reg.Sync(new[] { G("A", "101", adults: 2) }, policy, u, Morning);

        // изменение числа гостей и продление проживания
        var r = reg.Sync(new[] { G("A", "101", adults: 3, pout: "2026-10-17T12:00:00") }, policy, u, Morning.AddMinutes(10)).Rows.Single();
        Assert.Equal(3, r.AdultPortions);
        Assert.Contains("гости 2+0→3+0", r.LastChangeNote);
        Assert.Contains("выезд 2026-10-15→2026-10-17", r.LastChangeNote);
        Assert.Equal("Exely/хаб", r.LastChangeSource);
        Assert.Equal(Morning.AddMinutes(10).ToString("s"), r.LastChangeAt);

        // пересинхронизация без изменений не трогает метку
        var again = reg.Sync(new[] { G("A", "101", adults: 3, pout: "2026-10-17T12:00:00") }, policy, u, Morning.AddMinutes(20)).Rows.Single();
        Assert.Equal(r.LastChangeAt, again.LastChangeAt);
    }

    [Fact]
    public void Registry_takes_last_change_from_cashier_action_and_cancellation()
    {
        var cfg = new PluginConfig();
        var reg = NewRegistry(cfg);
        var u = new UsageStore(TempFile());
        var policy = new BreakfastPolicy(cfg);
        reg.Sync(new[] { G("A", "101") }, policy, u, Morning);

        var e = U("A", 2, 0, "1", time: "2026-10-13T08:40:00"); e.Employee = "Айгуль"; e.OrderNumber = 77; u.Add(e);
        var r = reg.Sync(new[] { G("A", "101") }, policy, u, Morning.AddMinutes(15)).Rows.Single();
        Assert.Equal("iiko: Айгуль", r.LastChangeSource);
        Assert.Contains("заказ 77", r.LastChangeNote);
        Assert.Equal("2026-10-13T08:40:00", r.LastChangeAt);

        u.Cancel(e.Id, "не тот гость", "Менеджер", new DateTime(2026, 10, 13, 8, 50, 0));
        r = reg.Sync(new[] { G("A", "101") }, policy, u, Morning.AddMinutes(25)).Rows.Single();
        Assert.Equal(RegistryStatus.Expected, r.Status);
        Assert.Equal("iiko: Менеджер", r.LastChangeSource);
        Assert.Contains("не тот гость", r.LastChangeNote);
    }

    [Fact]
    public void Early_checkout_before_breakfast_cancels_row_but_after_eating_keeps_it()
    {
        var cfg = new PluginConfig();
        var reg = NewRegistry(cfg);
        var u = new UsageStore(TempFile());
        var policy = new BreakfastPolicy(cfg);
        u.Add(U("B", 2, 0, "1"));
        reg.Sync(new[] { G("A", "101"), G("B", "102") }, policy, u, Morning);

        var rows = reg.Sync(new InHouseGuest[0], policy, u, Morning.AddMinutes(30)).Rows.ToDictionary(r => r.StayId);
        Assert.Equal(RegistryStatus.Cancelled, rows["A"].Status);
        Assert.Contains("выезд", rows["A"].LastChangeNote);
        Assert.Equal(RegistryStatus.Used, rows["B"].Status);
    }

    [Fact]
    public void Registry_counts_extra_and_discount_persons_and_exports_csv()
    {
        var cfg = new PluginConfig();
        var reg = NewRegistry(cfg);
        var u = new UsageStore(TempFile());
        var x = U("A", 1, 0, "2"); x.Extra = true; x.ApprovedBy = "Менеджер";
        u.Add(U("A", 2, 0, "1")); u.Add(x); u.Add(U("A", 1, 0, "3", UsageStore.KindDiscount));
        var day = reg.Sync(new[] { G("A", "101", names: "=Иванов") }, new BreakfastPolicy(cfg), u, Morning);
        var r = day.Rows.Single();
        Assert.Equal(1, r.ExtraPersons);
        Assert.Equal(1, r.DiscountPersons);
        Assert.Equal(RegistryStatus.Used, r.Status);
        Assert.Contains("\"'=Иванов\"", day.ToCsv());
    }

    // --- прогноз ---

    [Fact]
    public void Forecast_uses_entitlements_and_history_attendance_and_slots()
    {
        var cfg = new PluginConfig { ForecastHistoryDays = 3, ForecastSlotMinutes = 30 };
        var store = new RegistryStore(TempDir());
        var u = new UsageStore(TempFile());
        var today = new DateTime(2026, 10, 13);
        // вчера: положено 4 взрослых + 2 ребёнка, пришли 2 взрослых + 2 ребёнка
        store.Save(new DayRegistry
        {
            Day = "2026-10-12",
            Rows =
            {
                new RegistryRow { StayId = "x", AdultPortions = 4, ChildPortions = 2, UsedAdults = 2, UsedChildren = 2, StatusName = "Used" },
            },
        }, today.AddDays(-1));
        u.Add(U("x", 1, 1, "h1", date: "2026-10-12", time: "2026-10-12T07:40:00"));
        u.Add(U("x", 1, 1, "h2", date: "2026-10-12", time: "2026-10-12T08:10:00"));

        var stays = new List<Stay> { MakeStay("A", adults: 2, children: 2), MakeStay("B", adults: 2), MakeStay("N", breakfast: false) };
        var f = BreakfastForecast.Build(stays, new BreakfastPolicy(cfg), cfg, store, u, today, today);
        Assert.Equal((4, 2, 2), (f.EntitledAdults, f.EntitledChildren, f.Rooms));
        Assert.Equal(1, f.HistoryDays);
        Assert.Equal(0.5, f.AdultAttendance);
        Assert.Equal(1.0, f.ChildAttendance);
        Assert.Equal((2, 2), (f.ExpectedAdults, f.ExpectedChildren));
        Assert.Equal(new[] { "07:30", "08:00" }, f.Slots.Select(s => s.Start).ToArray());
        Assert.Equal(2, f.Slots[0].ExpectedPeople);
        Assert.Contains("Прогноз завтраков", f.Text());
    }

    [Fact]
    public void Forecast_without_history_equals_entitlements_and_future_day_includes_booked()
    {
        var cfg = new PluginConfig();
        var today = new DateTime(2026, 10, 13);
        var booked = MakeStay("B", pin: "2026-10-13T14:00:00", pout: "2026-10-16T12:00:00", adults: 2); booked.Status = "booked";
        var stays = new List<Stay> { MakeStay("A", adults: 1), booked };
        var f = BreakfastForecast.Build(stays, new BreakfastPolicy(cfg), cfg, new RegistryStore(TempDir()), new UsageStore(TempFile()), today, today);
        Assert.Equal(1, f.EntitledAdults); // забронированный сегодня не завтракает
        Assert.Equal(0, f.HistoryDays);
        var tomorrow = BreakfastForecast.Build(stays, new BreakfastPolicy(cfg), cfg, new RegistryStore(TempDir()), new UsageStore(TempFile()), today, today.AddDays(1));
        Assert.Equal(3, tomorrow.EntitledAdults); // завтра: гость A (выезд 15-го) и бронь B (заедет сегодня)
        Assert.Equal(3, tomorrow.ExpectedAdults);
    }

    // --- рум-сервис ---

    [Theory]
    [InlineData(50000, 0, 5000)]
    [InlineData(40000, 0, 5000)]
    [InlineData(39999, 2500, 2500)]
    [InlineData(20000, 2500, 2500)]
    [InlineData(19999, 5000, 0)]
    [InlineData(3000, 5000, 0)]
    public void Room_service_ladder(int check, int guestFee, int hotelComp)
    {
        var q = RoomServicePolicy.Quote(check, new PluginConfig());
        Assert.Equal(guestFee, q.GuestFee);
        Assert.Equal(hotelComp, q.HotelCompensation);
        Assert.Equal(5000, q.DeliveryFee);
        Assert.Equal(check + guestFee, q.TotalToGuest);
    }

    [Fact]
    public void Room_service_fee_never_exceeds_delivery_and_defaults_to_full_without_tiers()
    {
        var cfg = new PluginConfig { RoomServiceTiers = { } };
        cfg.RoomServiceTiers.Clear();
        Assert.Equal(5000, RoomServicePolicy.Quote(100000, cfg).GuestFee);
        cfg.RoomServiceTiers.Add(new RoomServiceTier { MinCheck = 0, GuestFee = 9999 });
        Assert.Equal(5000, RoomServicePolicy.Quote(1, cfg).GuestFee);
    }

    [Fact]
    public void Room_charges_persist_dedupe_and_export()
    {
        var path = TempFile();
        var s = new RoomChargeStore(path);
        var c = new RoomCharge { Date = "2026-10-13", Time = "2026-10-13T20:00:00", Kind = RoomCharge.KindRoomService, Room = "101", Guest = "=Х", OrderId = "o1", OrderNumber = 5, Check = 30000, GuestDeliveryFee = 2500, HotelCompensation = 2500, DeliveryEmployee = "Курьер" };
        s.Add(c);
        Assert.True(new RoomChargeStore(path).HasActive("o1", RoomCharge.KindRoomService));
        Assert.Single(s.Pending());
        Assert.Equal(32500, c.TotalToGuest);
        s.SetStatus(c.Id, RoomCharge.StatusPosted, "Рецепция", Day);
        Assert.Empty(s.Pending());
        s.SetStatus(c.Id, RoomCharge.StatusCancelled, "Рецепция", Day);
        Assert.False(s.HasActive("o1", RoomCharge.KindRoomService));
        var csv = RoomChargeStore.ToCsv(s.ForDay(Day));
        Assert.Contains("32500.00", csv);
        Assert.Contains("\"'=Х\"", csv);
    }

    [Fact]
    public void Kitchen_summary()
    {
        var k = new KitchenStore(TempFile());
        Assert.Contains("не вводила", k.Summary(Day, 10));
        k.Set(new KitchenEntry { Date = "2026-10-13", Prepared = 50, Leftover = 4, Wasted = 6 });
        k.Set(new KitchenEntry { Date = "2026-10-13", Prepared = 40, Leftover = 2, Wasted = 2 });
        Assert.Contains("Приготовлено 40", k.Summary(Day, 36));
        Assert.Contains("10%", k.Summary(Day, 36));
    }

    [Fact]
    public void Config_roundtrip()
    {
        var path = TempFile();
        var c = new PluginConfig { HubUrl = "http://hub:8000", PropertyCode = "9305" };
        c.ResidentDiscountRules.Add(new DiscountRule { Field = "loyalty", Contains = "gold", Discount = "G" });
        c.Save(path);
        var back = PluginConfig.Load(path);
        Assert.Equal("9305", back.PropertyCode);
        Assert.Single(back.ResidentDiscountRules);
        Assert.Equal("G", back.ResidentDiscountRules[0].Discount);
        Assert.Equal(new PluginConfig().BreakfastKeywords.Count, back.BreakfastKeywords.Count);
    }

    // --- HubClient / StayDirectory ---

    class FakeHandler : HttpMessageHandler
    {
        public List<string> Calls = new List<string>();
        public Func<HttpRequestMessage, HttpResponseMessage> Respond;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Calls.Add(request.RequestUri.PathAndQuery);
            return Task.FromResult(Respond(request));
        }
    }

    static HttpResponseMessage Json(string body, HttpStatusCode code = HttpStatusCode.OK) =>
        new HttpResponseMessage(code) { Content = new StringContent(body, Encoding.UTF8, "application/json") };

    const string StaysJson = @"{""stays"":[
        {""id"":""7001"",""booking"":""B1"",""status"":""checked_in"",""room"":""101"",""rate_plan"":{""id"":""bb"",""name"":""Тариф BB""},
         ""loyalty"":""Gold"",""planned_in"":""2026-10-12T14:00:00"",""planned_out"":""2026-10-15T12:00:00"",""adults"":2,""children"":0,
         ""services"":[{""id"":""b"",""name"":""Завтрак"",""included_in_rate"":true,""meal_plan_code"":""BB""}],""updated_at"":""x"",""extra"":1},
        {""id"":""7002"",""status"":""booked"",""room"":""102"",""planned_in"":""2026-10-20T14:00:00"",""planned_out"":""2026-10-22T12:00:00"",""adults"":1,""children"":0,""services"":[]}]}";

    [Fact]
    public async Task Hub_client_sends_bearer_and_parses_stays()
    {
        string auth = null;
        var h = new FakeHandler { Respond = r => { auth = r.Headers.Authorization?.ToString(); return Json(StaysJson); } };
        var c = new HubClient(new PluginConfig { HubUrl = "http://hub/", PropertyCode = "9305", HubToken = "tok" }, h);
        var r = await c.GetActiveStaysAsync(CancellationToken.None);
        Assert.Equal("Bearer tok", auth);
        Assert.Equal("/v1/properties/9305/stays?active=1&limit=2000", h.Calls[0]);
        Assert.Equal(2, r.Stays.Count);
        Assert.Equal("BB", r.Stays[0].Services[0].MealPlanCode);
        Assert.Equal(new DateTime(2026, 10, 12, 14, 0, 0), r.Stays[0].PlannedInTime);
    }

    [Fact]
    public async Task Hub_client_raises_hub_exception_with_status()
    {
        var h = new FakeHandler { Respond = r => Json("{}", HttpStatusCode.Forbidden) };
        var c = new HubClient(new PluginConfig { HubUrl = "http://hub", PropertyCode = "1" }, h);
        var e = await Assert.ThrowsAsync<HubException>(() => c.GetCatalogAsync(CancellationToken.None));
        Assert.Equal(403, e.Status);
    }

    class FakeHub : IHubClient
    {
        public bool Down; public bool GuestsForbidden; public int GuestCalls;
        public Task<StaysResponse> GetActiveStaysAsync(CancellationToken ct)
        {
            if (Down) throw new HubException(503, "down");
            var r = new StaysResponse();
            r.Stays.Add(MakeStay("1", "102")); r.Stays.Add(MakeStay("2", "101"));
            var booked = MakeStay("3", "103"); booked.Status = "booked"; r.Stays.Add(booked);
            return Task.FromResult(r);
        }
        public Task<GuestsResponse> GetGuestsAsync(string stayId, CancellationToken ct)
        {
            GuestCalls++;
            if (GuestsForbidden) throw new HubException(403, "no scope");
            var g = new GuestsResponse(); g.Guests.Add(new HotelGuest { LastName = "Гость" + stayId, FirstName = "Имя" });
            return Task.FromResult(g);
        }
        public Task<CatalogResponse> GetCatalogAsync(CancellationToken ct) => Task.FromResult(new CatalogResponse());
    }

    [Fact]
    public async Task Directory_keeps_only_checked_in_sorted_by_room_with_names_cached()
    {
        var hub = new FakeHub();
        var dir = new StayDirectory(hub, new PluginConfig());
        var list = await dir.RefreshAsync(true, CancellationToken.None);
        Assert.Equal(new[] { "101", "102" }, list.Select(g => g.Room).ToArray());
        Assert.Equal("Гость2 И.", list[0].Names);
        await dir.RefreshAsync(true, CancellationToken.None);
        Assert.Equal(2, hub.GuestCalls); // имена из кэша
    }

    [Fact]
    public async Task Directory_serves_stale_data_when_hub_goes_down()
    {
        var hub = new FakeHub();
        var dir = new StayDirectory(hub, new PluginConfig());
        await dir.RefreshAsync(true, CancellationToken.None);
        hub.Down = true;
        var list = await dir.RefreshAsync(true, CancellationToken.None);
        Assert.Equal(2, list.Count);
        Assert.True(dir.IsStale);
        Assert.Equal("down", dir.LastError);
    }

    [Fact]
    public async Task Directory_throws_when_hub_down_and_nothing_cached()
    {
        var dir = new StayDirectory(new FakeHub { Down = true }, new PluginConfig());
        await Assert.ThrowsAsync<HubException>(() => dir.RefreshAsync(true, CancellationToken.None));
    }

    [Fact]
    public async Task Directory_works_without_guests_scope()
    {
        var hub = new FakeHub { GuestsForbidden = true };
        var dir = new StayDirectory(hub, new PluginConfig());
        var list = await dir.RefreshAsync(true, CancellationToken.None);
        Assert.Equal(2, list.Count);
        Assert.All(list, g => Assert.Equal("", g.Names));
        await dir.RefreshAsync(true, CancellationToken.None);
        Assert.Equal(1, hub.GuestCalls); // после 403 больше не стучимся
    }
}
