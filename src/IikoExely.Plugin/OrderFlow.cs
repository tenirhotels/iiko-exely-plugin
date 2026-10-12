using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Resto.Front.Api;
using Resto.Front.Api.Data.Orders;
using Resto.Front.Api.Data.View;
using Resto.Front.Api.UI;

namespace IikoExely.Plugin
{
    /// <summary>Сценарий кассира на экране заказа: номер -> проверка права -> скидка -> запись в журнал.</summary>
    internal sealed class OrderFlow
    {
        private readonly PluginConfig _cfg;
        private readonly StayDirectory _directory;
        private readonly UsageStore _usage;
        private readonly IOperationService _ops;
        private readonly IViewManager _view;

        public OrderFlow(PluginConfig cfg, StayDirectory directory, UsageStore usage, IOperationService ops, IViewManager view)
        {
            _cfg = cfg; _directory = directory; _usage = usage; _ops = ops; _view = view;
        }

        public void Run(IOrder order)
        {
            IReadOnlyList<InHouseGuest> guests;
            try
            {
                guests = Task.Run(() => _directory.RefreshAsync(false, CancellationToken.None)).GetAwaiter().GetResult();
            }
            catch (Exception e)
            {
                _view.ShowErrorPopup("Нет связи с Hotel Hub, список проживающих недоступен:\n" + e.Message);
                return;
            }
            if (_directory.IsStale)
                _view.ShowOkPopup("Внимание", "Hotel Hub недоступен, список проживающих может быть неактуальным (на " +
                    _directory.LoadedAtUtc?.ToLocalTime().ToString("HH:mm") + ").");

            var guest = ChooseGuest(guests);
            if (guest == null) return;

            var today = DateTime.Now;
            var policy = new BreakfastPolicy(_cfg, _directory.Catalog);
            var used = _usage.UsedPortions(guest.Stay.Id, today);
            var decision = policy.Evaluate(guest.Stay, today, used);
            var title = $"Номер {guest.Room} {guest.Names}".Trim();

            switch (decision.Right)
            {
                case BreakfastRight.Included:
                    ApplyIncluded(order, guest, decision, title);
                    break;
                case BreakfastRight.Exhausted:
                    if (_view.ShowYesNoPopup(title, $"Завтраки на сегодня уже выданы ({decision.Used} из {decision.Portions}).\nПрименить скидку проживающего?", "Да", "Нет"))
                        ApplyResident(order, guest, title);
                    break;
                case BreakfastRight.NotIncluded:
                    ApplyResident(order, guest, title, decision.Note);
                    break;
                default:
                    _view.ShowErrorPopup($"{title}: {decision.Note}.");
                    break;
            }
        }

        private InHouseGuest ChooseGuest(IReadOnlyList<InHouseGuest> guests)
        {
            if (guests.Count == 0)
            {
                _view.ShowErrorPopup("В отеле нет проживающих (по данным Hotel Hub).");
                return null;
            }
            var typed = _view.ShowInputDialog("Номер комнаты (0 — весь список)", InputDialogTypes.Number) as NumberInputDialogResult;
            if (typed == null) return null; // отмена
            var room = typed.Number == 0 ? "" : typed.Number.ToString();
            var found = room.Length == 0
                ? guests.ToList()
                : guests.Where(g => string.Equals(g.Room, room, StringComparison.OrdinalIgnoreCase)).ToList();
            if (found.Count == 0)
            {
                _view.ShowErrorPopup($"Комната {room} не найдена среди проживающих.");
                return null;
            }
            if (found.Count == 1) return found[0];

            var labels = found.Select(g => $"{g.Room}  {g.Names}  {(g.Stay.RatePlan?.Name ?? "")}".Trim()).ToList();
            var idx = _view.ShowChooserPopup("Выберите проживающего", labels);
            return idx >= 0 && idx < found.Count ? found[idx] : null;
        }

        private void ApplyIncluded(IOrder order, InHouseGuest guest, BreakfastDecision decision, string title)
        {
            int persons = 1;
            if (decision.Remaining > 1)
            {
                var options = Enumerable.Range(1, decision.Remaining).Select(n => n.ToString()).ToList();
                var idx = _view.ShowChooserPopup($"{title}\nЗавтрак включён. Сколько человек за столом?\n(осталось {decision.Remaining} из {decision.Portions})", options);
                if (idx < 0 || idx >= options.Count) return;
                persons = idx + 1;
            }
            var discount = FindDiscount(_cfg.IncludedBreakfastDiscount);
            if (discount == null) return;
            if (!Submit(order, discount, guest, $"завтрак включён, {persons} чел.")) return;
            Record(order, guest, UsageStore.KindIncluded, discount.Name, persons);
            _view.ShowOkPopup(title, $"Завтрак включён в тариф. Скидка «{discount.Name}» применена ({persons} чел.).");
        }

        private void ApplyResident(IOrder order, InHouseGuest guest, string title, string reason = null)
        {
            var name = DiscountSelector.ResidentDiscountName(guest.Stay, _cfg);
            var level = guest.Stay.Loyalty ?? guest.Stay.RatePlan?.Name ?? "—";
            var text = (reason != null ? reason + ".\n" : "") + $"Уровень: {level}.\nПрименить скидку «{name}»?";
            if (!_view.ShowYesNoPopup(title, text, "Применить", "Отмена")) return;
            var discount = FindDiscount(name);
            if (discount == null) return;
            if (!Submit(order, discount, guest, "скидка проживающего")) return;
            Record(order, guest, UsageStore.KindDiscount, discount.Name, 1);
        }

        private IDiscountType FindDiscount(string name)
        {
            var d = _ops.GetDiscountTypes().FirstOrDefault(x => !x.Deleted && x.IsActive && x.CanApplyManually &&
                string.Equals(x.Name, name, StringComparison.OrdinalIgnoreCase));
            if (d == null)
                _view.ShowErrorPopup($"В iiko нет доступной для ручного применения скидки «{name}».\nСоздайте её в iikoOffice или поправьте iiko-exely.json.");
            return d;
        }

        private bool Submit(IOrder order, IDiscountType discount, InHouseGuest guest, string kind)
        {
            try
            {
                var session = _ops.CreateEditSession();
                session.AddDiscount(discount, order);
                session.AddOrderExternalData("hotel.stay", guest.Stay.Id, true, order);
                session.ChangeOrderComment(Comment(guest, kind), order);
                _ops.SubmitChanges(session, _ops.GetDefaultCredentials());
                return true;
            }
            catch (Exception e)
            {
                PluginContext.Log.Error("[IikoExely] " + e);
                _view.ShowErrorPopup("Скидку применить не удалось:\n" + e.Message);
                return false;
            }
        }

        private string Comment(InHouseGuest g, string kind) =>
            _cfg.OrderCommentTemplate
                .Replace("{room}", g.Room)
                .Replace("{guest}", string.IsNullOrEmpty(g.Names) ? "гость" : g.Names)
                .Replace("{kind}", kind);

        private void Record(IOrder order, InHouseGuest guest, string kind, string discount, int persons)
        {
            var now = DateTime.Now;
            _usage.Add(new UsageEntry
            {
                Date = UsageStore.DayKey(now),
                Time = now.ToString("s"),
                StayId = guest.Stay.Id,
                Room = guest.Room,
                Guest = guest.Names,
                Kind = kind,
                Discount = discount,
                Persons = persons,
                OrderId = order.Id.ToString(),
                OrderNumber = order.Number,
            });
        }
    }
}
