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

    [Fact]
    public void Usage_store_sums_portions_per_day_and_persists()
    {
        var path = TempFile();
        var u = new UsageStore(path);
        u.Add(new UsageEntry { Date = "2026-10-13", StayId = "s1", Kind = UsageStore.KindIncluded, Persons = 1, OrderId = "o1" });
        u.Add(new UsageEntry { Date = "2026-10-13", StayId = "s1", Kind = UsageStore.KindIncluded, Persons = 1, OrderId = "o2" });
        u.Add(new UsageEntry { Date = "2026-10-13", StayId = "s1", Kind = UsageStore.KindDiscount, Persons = 5, OrderId = "o3" });
        u.Add(new UsageEntry { Date = "2026-10-14", StayId = "s1", Kind = UsageStore.KindIncluded, Persons = 2, OrderId = "o4" });
        Assert.Equal(2, u.UsedPortions("s1", Day));
        Assert.Equal(2, new UsageStore(path).UsedPortions("s1", Day));
        Assert.Equal(1, u.RemoveOrder("o1"));
        Assert.Equal(1, u.UsedPortions("s1", Day));
    }

    [Fact]
    public void Usage_store_survives_broken_file()
    {
        var path = TempFile();
        File.WriteAllText(path, "{не json");
        var u = new UsageStore(path);
        Assert.Equal(0, u.UsedPortions("s1", Day));
        u.Add(new UsageEntry { Date = "2026-10-13", StayId = "s1", Kind = UsageStore.KindIncluded, Persons = 1, OrderId = "o1" });
        Assert.Equal(1, new UsageStore(path).UsedPortions("s1", Day));
    }

    [Fact]
    public void Report_lists_not_came_partial_and_discounted()
    {
        var cfg = new PluginConfig();
        var u = new UsageStore(TempFile());
        u.Add(new UsageEntry { Date = "2026-10-13", StayId = "A", Kind = UsageStore.KindIncluded, Persons = 2, OrderId = "1" });
        u.Add(new UsageEntry { Date = "2026-10-13", StayId = "B", Kind = UsageStore.KindIncluded, Persons = 1, OrderId = "2" });
        u.Add(new UsageEntry { Date = "2026-10-13", StayId = "D", Kind = UsageStore.KindDiscount, Persons = 1, Discount = "Проживающий Gold", OrderId = "3" });
        var guests = new[]
        {
            new InHouseGuest { Stay = MakeStay("A", "101"), Names = "Иванов И." },
            new InHouseGuest { Stay = MakeStay("B", "102"), Names = "Петров П." },
            new InHouseGuest { Stay = MakeStay("C", "103"), Names = "Сидоров С." },
            new InHouseGuest { Stay = MakeStay("D", "104", breakfast: false), Names = "Орлов О." },
            new InHouseGuest { Stay = MakeStay("E", "105", pin: "2026-10-20T14:00:00", pout: "2026-10-22T12:00:00") }, // ещё не заехал
        };
        var rep = BreakfastReport.Build(guests, new BreakfastPolicy(cfg), u, Day);
        Assert.Equal(4, rep.Rows.Count);
        Assert.Equal(new[] { "103" }, rep.NotCame.Select(r => r.Room).ToArray());
        Assert.Equal(new[] { "102" }, rep.Partial.Select(r => r.Room).ToArray());
        Assert.Equal(new[] { "104" }, rep.PaidWithDiscount.Select(r => r.Room).ToArray());
        Assert.Equal(6, rep.EntitledPortions);
        Assert.Equal(3, rep.UsedPortions);
        Assert.Contains("Не пришли", rep.Summary());
        Assert.Contains("Сидоров", rep.Summary());
    }

    [Fact]
    public void Csv_escapes_quotes_and_formula_injection()
    {
        var rep = new BreakfastReport { Day = Day };
        rep.Rows.Add(new ReportRow { Room = "1", Names = "=HYPERLINK(\"x\")", Decision = new BreakfastDecision { Right = BreakfastRight.Included, Portions = 1 } });
        var csv = rep.ToCsv();
        Assert.Contains("\"'=HYPERLINK(\"\"x\"\")\"", csv);
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
