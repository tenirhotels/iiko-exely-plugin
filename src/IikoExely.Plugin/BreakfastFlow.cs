using System;
using System.Linq;
using Resto.Front.Api;
using Resto.Front.Api.Data.Orders;
using Resto.Front.Api.UI;

namespace IikoExely.Plugin
{
    /// <summary>Отметка завтрака на экране заказа: номер -> проверка права -> скидка -> запись в журнал.</summary>
    internal sealed class BreakfastFlow
    {
        private readonly Services _s;
        private readonly IOperationService _ops;
        private readonly IViewManager _view;

        public BreakfastFlow(Services s, IOperationService ops, IViewManager view) { _s = s; _ops = ops; _view = view; }

        public void Run(IOrder order)
        {
            System.Collections.Generic.IReadOnlyList<InHouseGuest> guests;
            try { guests = _s.Refresh(false); }
            catch (Exception e)
            {
                _view.ShowErrorPopup("Нет связи с Hotel Hub, список проживающих недоступен:\n" + e.Message);
                return;
            }
            if (_s.Directory.IsStale)
                _view.ShowOkPopup("Внимание", "Hotel Hub недоступен, список проживающих может быть неактуальным (на " +
                    _s.Directory.LoadedAtUtc?.ToLocalTime().ToString("HH:mm") + ").");

            var guest = Ui.ChooseGuest(_view, guests);
            if (guest == null) return;

            var today = DateTime.Now;
            var used = _s.Usage.Used(guest.Stay.Id, today);
            var decision = _s.Policy.Evaluate(guest.Stay, today, used.adults, used.children);
            var title = $"Номер {guest.Room} {guest.Names}".Trim();
            var employee = _s.CurrentEmployee(_ops);

            if (_s.Usage.HasActiveEntry(order.Id.ToString(), guest.Stay.Id, UsageStore.KindIncluded))
            {
                _view.ShowErrorPopup($"{title}: в этом заказе завтрак уже отмечен. Повторная отметка не допускается.");
                return;
            }

            switch (decision.Right)
            {
                case BreakfastRight.Included:
                    ApplyIncluded(order, guest, decision, title, employee);
                    break;
                case BreakfastRight.Exhausted:
                    ApplyExhausted(order, guest, decision, title, employee);
                    break;
                case BreakfastRight.NotIncluded:
                    ApplyResident(order, guest, title, employee, decision.Note);
                    break;
                default:
                    _view.ShowErrorPopup($"{title}: {decision.Note}.");
                    break;
            }
        }

        private void ApplyIncluded(IOrder order, InHouseGuest guest, BreakfastDecision d, string title, string employee)
        {
            var head = $"{title}\nЗавтрак включён. Осталось: взрослых {d.RemainingAdults}, детей {d.RemainingChildren}.";
            var adults = Ui.ChooseCount(_view, head + "\nСколько ВЗРОСЛЫХ за столом?", d.RemainingAdults);
            if (adults < 0) return;
            var children = Ui.ChooseCount(_view, head + "\nСколько ДЕТЕЙ за столом?", d.RemainingChildren);
            if (children < 0) return;
            if (adults + children == 0) return;
            var extra = false; string approvedBy = null;

            // дополнительный гость сверх положенного — только с разрешением менеджера
            if (_view.ShowYesNoPopup(title, "Есть ещё гости сверх положенного количества?", "Да", "Нет"))
            {
                var ea = Ui.ChooseCount(_view, "Сверх нормы: ВЗРОСЛЫХ", 10);
                if (ea < 0) return;
                var ec = Ui.ChooseCount(_view, "Сверх нормы: ДЕТЕЙ", 10);
                if (ec < 0) return;
                if (ea + ec > 0)
                {
                    approvedBy = Ui.ManagerApproval(_view, _s.Cfg, "Завтрак сверх положенного");
                    if (approvedBy == null) return;
                    adults += ea; children += ec; extra = true;
                }
            }
            Complete(order, guest, title, employee, adults, children, extra, approvedBy);
        }

        private void ApplyExhausted(IOrder order, InHouseGuest guest, BreakfastDecision d, string title, string employee)
        {
            var text = $"Завтраки на сегодня уже выданы ({d.Used} из {d.Portions}).\nВыбор:";
            var idx = _view.ShowChooserPopup(title + "\n" + text, new System.Collections.Generic.List<string>
            {
                "Дополнительный гость (разрешение менеджера)", "Скидка проживающего", "Отмена",
            });
            if (idx == 0)
            {
                var a = Ui.ChooseCount(_view, "Дополнительных ВЗРОСЛЫХ", 10);
                if (a < 0) return;
                var c = Ui.ChooseCount(_view, "Дополнительных ДЕТЕЙ", 10);
                if (c < 0 || a + c == 0) return;
                var by = Ui.ManagerApproval(_view, _s.Cfg, "Завтрак сверх положенного");
                if (by == null) return;
                Complete(order, guest, title, employee, a, c, true, by);
            }
            else if (idx == 1) ApplyResident(order, guest, title, employee, null);
        }

        private void Complete(IOrder order, InHouseGuest guest, string title, string employee, int adults, int children, bool extra, string approvedBy)
        {
            var discount = FindDiscount(_s.Cfg.IncludedBreakfastDiscount);
            if (discount == null) return;
            var kind = $"завтрак, {adults}+{children}" + (extra ? ", сверх нормы" : "");
            if (!Submit(order, discount, guest, kind)) return;
            var now = DateTime.Now;
            _s.Usage.Add(new UsageEntry
            {
                Date = UsageStore.DayKey(now), Time = now.ToString("s"), StayId = guest.Stay.Id, Room = guest.Room, Guest = guest.Names,
                Kind = UsageStore.KindIncluded, Discount = discount.Name, Adults = adults, Children = children,
                Employee = employee, Extra = extra, ApprovedBy = approvedBy,
                OrderId = order.Id.ToString(), OrderNumber = order.Number,
            });
            _view.ShowOkPopup(title, $"Завтрак отмечен: взрослых {adults}, детей {children}" + (extra ? " (сверх нормы, одобрил " + approvedBy + ")" : "") +
                                     $".\nСкидка «{discount.Name}» применена.");
        }

        private void ApplyResident(IOrder order, InHouseGuest guest, string title, string employee, string reason)
        {
            var name = DiscountSelector.ResidentDiscountName(guest.Stay, _s.Cfg);
            var level = guest.Stay.Loyalty ?? guest.Stay.RatePlan?.Name ?? "—";
            var text = (reason != null ? reason + ".\n" : "") + $"Уровень: {level}.\nПрименить скидку «{name}»?";
            if (!_view.ShowYesNoPopup(title, text, "Применить", "Отмена")) return;
            var discount = FindDiscount(name);
            if (discount == null) return;
            if (!Submit(order, discount, guest, "скидка проживающего")) return;
            var now = DateTime.Now;
            _s.Usage.Add(new UsageEntry
            {
                Date = UsageStore.DayKey(now), Time = now.ToString("s"), StayId = guest.Stay.Id, Room = guest.Room, Guest = guest.Names,
                Kind = UsageStore.KindDiscount, Discount = discount.Name, Adults = 1, Employee = employee,
                OrderId = order.Id.ToString(), OrderNumber = order.Number,
            });
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
            _s.Cfg.OrderCommentTemplate
                .Replace("{room}", g.Room)
                .Replace("{guest}", string.IsNullOrEmpty(g.Names) ? "гость" : g.Names)
                .Replace("{kind}", kind);
    }
}
