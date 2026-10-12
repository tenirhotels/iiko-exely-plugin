using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using Resto.Front.Api;
using Resto.Front.Api.UI;

namespace IikoExely.Plugin
{
    /// <summary>Меню плагинов: реестр завтраков, отмена отметки, прогноз, итоги кухни, начисления на номер.</summary>
    internal sealed class RegistryFlow
    {
        private readonly Services _s;
        private readonly IOperationService _ops;
        private readonly IViewManager _view;

        public RegistryFlow(Services s, IOperationService ops, IViewManager view) { _s = s; _ops = ops; _view = view; }

        public void ShowRegistry()
        {
            DayRegistry reg;
            try { reg = _s.SyncRegistry(true); }
            catch (Exception e) { _view.ShowErrorPopup("Реестр недоступен:\n" + e.Message); return; }

            var csv = Path.Combine(_s.DataDir, $"breakfast-registry-{reg.Day}.csv");
            File.WriteAllText(csv, reg.ToCsv(), new UTF8Encoding(false));

            while (true)
            {
                var rows = reg.Rows.Where(r => r.Status != RegistryStatus.NotEntitled || r.DiscountPersons > 0).ToList();
                var labels = new List<string> { "Итоги и файл CSV" };
                labels.AddRange(rows.Select(r => $"{r.Room}  {r.Names}  [{RegistryStatusText.Ru(r.Status)}]  {r.UsedAdults + r.UsedChildren}/{r.Portions}".Replace("  ", " ")));
                var idx = _view.ShowChooserPopup("Реестр завтраков " + reg.Day, labels);
                if (idx < 0) return;
                if (idx == 0)
                {
                    _view.ShowOkPopup("Реестр завтраков", reg.Summary() + "\n\n" + _s.Kitchen.Summary(DateTime.Now, reg.UsedAdults + reg.UsedChildren) + "\n\nФайл: " + csv);
                    continue;
                }
                RowMenu(rows[idx - 1]);
                reg = _s.SyncRegistry(false);
            }
        }

        private void RowMenu(RegistryRow r)
        {
            var info = new StringBuilder();
            info.AppendLine($"Комната {r.Room}, {r.Names}");
            info.AppendLine($"Бронь: {r.Booking}   Тариф: {r.RatePlan}");
            info.AppendLine($"Заезд {r.Arrival}, выезд {r.Departure}");
            info.AppendLine($"Гостей: взрослых {r.Adults}, детей {r.Children}");
            info.AppendLine($"Включено: взрослых {r.AdultPortions}, детей {r.ChildPortions}. Выдано: взрослых {r.UsedAdults}, детей {r.UsedChildren}" + (r.ExtraPersons > 0 ? $" (сверх нормы {r.ExtraPersons})" : ""));
            info.AppendLine($"Статус: {RegistryStatusText.Ru(r.Status)}");
            info.AppendLine($"Изменено {r.LastChangeAt}: {r.LastChangeSource} — {r.LastChangeNote}");

            var entries = _s.Usage.ForDay(DateTime.Now).Where(e => e.StayId == r.StayId).OrderBy(e => e.Time).ToList();
            foreach (var e in entries)
                info.AppendLine($" • {e.Time?.Substring(11, 5)} {(e.Kind == UsageStore.KindIncluded ? "завтрак" : "скидка")} {e.Adults}+{e.Children}, {e.Employee}, заказ {e.OrderNumber}" +
                                (e.Extra ? $", сверх нормы ({e.ApprovedBy})" : "") + (e.Cancelled ? $" — ОТМЕНЁН: {e.CancelReason}" : ""));

            var active = entries.Where(e => !e.Cancelled).ToList();
            if (active.Count == 0) { _view.ShowOkPopup("Гость", info.ToString()); return; }
            if (!_view.ShowYesNoPopup("Гость", info + "\nОтменить одну из отметок?", "Отменить отметку", "Закрыть")) return;
            CancelEntry(active);
        }

        private void CancelEntry(List<UsageEntry> active)
        {
            var labels = active.Select(e => $"{e.Time?.Substring(11, 5)} {e.Adults}+{e.Children} заказ {e.OrderNumber} ({e.Employee})").ToList();
            var idx = labels.Count == 1 ? 0 : _view.ShowChooserPopup("Какую отметку отменить?", labels);
            if (idx < 0 || idx >= active.Count) return;
            var entry = active[idx];

            var by = _s.CurrentEmployee(_ops);
            if (_s.Cfg.CancelRequiresManager)
            {
                by = Ui.ManagerApproval(_view, _s.Cfg, "Отмена отметки завтрака");
                if (by == null) return;
            }
            var reason = Ui.RequiredReason(_view, "Причина отмены (обязательно)");
            if (reason == null) { _view.ShowErrorPopup("Без причины отметку отменить нельзя."); return; }

            _s.Usage.Cancel(entry.Id, reason, by, DateTime.Now);
            _view.ShowOkPopup("Отметка отменена",
                "Порции возвращены в реестр.\nСкидку в самом заказе iiko снимите вручную, если заказ ещё открыт (заказ " + entry.OrderNumber + ").");
        }

        public void ShowForecast()
        {
            try
            {
                var chosen = _view.ShowChooserPopup("Прогноз завтраков на", new List<string> { "Сегодня", "Завтра" });
                if (chosen < 0) return;
                _s.Refresh(true);
                var today = DateTime.Now.Date;
                var target = today.AddDays(chosen);
                var f = BreakfastForecast.Build(_s.Directory.AllActive, _s.Policy, _s.Cfg, _s.RegistryFiles, _s.Usage, today, target);
                _view.ShowOkPopup("Прогноз для кухни", f.Text() + (chosen > 0 ? "\n\nДанные по плану броней; заезды и выезды завтра могут изменить цифры." : ""));
            }
            catch (Exception e) { _view.ShowErrorPopup("Прогноз недоступен:\n" + e.Message); }
        }

        public void KitchenSummary()
        {
            var today = DateTime.Now;
            var prepared = _view.ShowInputDialog("Приготовлено порций завтрака", Resto.Front.Api.Data.View.InputDialogTypes.Number) as Resto.Front.Api.Data.View.NumberInputDialogResult;
            if (prepared == null) return;
            var leftover = _view.ShowInputDialog("Осталось нетронутыми (порций)", Resto.Front.Api.Data.View.InputDialogTypes.Number) as Resto.Front.Api.Data.View.NumberInputDialogResult;
            if (leftover == null) return;
            var wasted = _view.ShowInputDialog("Списано / выброшено (порций)", Resto.Front.Api.Data.View.InputDialogTypes.Number) as Resto.Front.Api.Data.View.NumberInputDialogResult;
            if (wasted == null) return;
            _s.Kitchen.Set(new KitchenEntry
            {
                Date = UsageStore.DayKey(today), Time = today.ToString("s"), Prepared = prepared.Number, Leftover = leftover.Number,
                Wasted = wasted.Number, Employee = _s.CurrentEmployee(_ops),
            });
            var u = _s.Usage.ForDay(today, false).Where(e => e.Kind == UsageStore.KindIncluded).Sum(e => e.Persons);
            _view.ShowOkPopup("Итоги кухни", _s.Kitchen.Summary(today, u));
        }

        public void RoomCharges()
        {
            var pending = _s.Charges.Pending().OrderBy(c => c.Time).ToList();
            var csv = Path.Combine(_s.DataDir, $"room-charges-{DateTime.Now:yyyy-MM-dd}.csv");
            File.WriteAllText(csv, RoomChargeStore.ToCsv(_s.Charges.ForDay(DateTime.Now)), new UTF8Encoding(false));
            if (pending.Count == 0) { _view.ShowOkPopup("Начисления на номер", "Непроведённых начислений нет.\nФайл за день: " + csv); return; }
            while (pending.Count > 0)
            {
                var labels = new List<string> { "Файл CSV за день" };
                labels.AddRange(pending.Select(c => $"{c.Room} {c.Guest} — {c.TotalToGuest:0} ₸ ({(c.Kind == RoomCharge.KindRoomService ? "рум-сервис" : "ресторан")}, заказ {c.OrderNumber})"));
                var idx = _view.ShowChooserPopup("Ожидают проведения на номер (" + pending.Count + ")", labels);
                if (idx < 0) return;
                if (idx == 0) { _view.ShowOkPopup("Файл", csv); continue; }
                var c = pending[idx - 1];
                var text = $"Номер {c.Room}, {c.Guest}\nЧек {c.Check:0}, доставка с гостя {c.GuestDeliveryFee:0}, к начислению {c.TotalToGuest:0} ₸.\nКомпенсация отеля курьеру {c.HotelCompensation:0}.\nНачислено в Exely?";
                if (_view.ShowYesNoPopup("Начисление на номер", text, "Проведено", "Не сейчас"))
                {
                    _s.Charges.SetStatus(c.Id, RoomCharge.StatusPosted, _s.CurrentEmployee(_ops), DateTime.Now);
                    pending.Remove(c);
                }
            }
        }
    }
}
