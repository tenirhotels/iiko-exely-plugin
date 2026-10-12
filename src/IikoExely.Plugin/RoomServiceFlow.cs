using System;
using System.Collections.Generic;
using System.Linq;
using Resto.Front.Api;
using Resto.Front.Api.Data.Orders;
using Resto.Front.Api.UI;

namespace IikoExely.Plugin
{
    /// <summary>
    /// Заказ по номеру комнаты. Два режима: рум-сервис (доставка по лесенке) и допы в ресторане «на счёт номера».
    /// Начисление в Exely вручную: плагин пишет строку в журнал для рецепции.
    /// </summary>
    internal sealed class RoomServiceFlow
    {
        private readonly Services _s;
        private readonly IOperationService _ops;
        private readonly IViewManager _view;

        public RoomServiceFlow(Services s, IOperationService ops, IViewManager view) { _s = s; _ops = ops; _view = view; }

        public void Run(IOrder order)
        {
            var mode = _view.ShowChooserPopup("Заказ на номер", new List<string> { "Рум-сервис (доставка в номер)", "Допы в ресторане — на счёт номера", "Отмена" });
            if (mode != 0 && mode != 1) return;
            var roomService = mode == 0;

            if (_s.Charges.HasActive(order.Id.ToString(), roomService ? RoomCharge.KindRoomService : RoomCharge.KindRestaurant))
            {
                _view.ShowErrorPopup("Этот заказ уже отнесён на номер. Повторное начисление не допускается.");
                return;
            }
            System.Collections.Generic.IReadOnlyList<InHouseGuest> guests;
            try { guests = _s.Refresh(false); }
            catch (Exception e) { _view.ShowErrorPopup("Нет связи с Hotel Hub:\n" + e.Message); return; }

            var guest = Ui.ChooseGuest(_view, guests);
            if (guest == null) return;
            var title = $"Номер {guest.Room} {guest.Names}".Trim();
            var employee = _s.CurrentEmployee(_ops);
            var check = order.ResultSum;

            if (check <= 0) { _view.ShowErrorPopup("В заказе нет позиций к оплате."); return; }

            RoomServiceQuote quote = null;
            Resto.Front.Api.Data.Assortment.IProduct deliveryProduct = null;
            if (roomService)
            {
                if (!string.IsNullOrWhiteSpace(_s.Cfg.RoomServicePriceCategory) &&
                    !string.Equals(order.PriceCategory?.Name, _s.Cfg.RoomServicePriceCategory, StringComparison.OrdinalIgnoreCase))
                {
                    if (!_view.ShowYesNoPopup(title, $"Заказ не в меню «{_s.Cfg.RoomServicePriceCategory}» (сейчас: «{order.PriceCategory?.Name ?? "—"}»).\nПродолжить?", "Продолжить", "Отмена"))
                        return;
                }
                quote = RoomServicePolicy.Quote(check, _s.Cfg);
                var text = $"Чек: {check:0} ₸\nДоставка {quote.DeliveryFee:0}: гость платит {quote.GuestFee:0}, компенсирует отель {quote.HotelCompensation:0}.\n" +
                           $"К начислению на номер: {quote.TotalToGuest:0} ₸.\nДоставка идёт сотруднику ({employee}), в бонус официантов не входит.";
                if (!_view.ShowYesNoPopup(title, text, "Оформить", "Отмена")) return;
                if (quote.GuestFee > 0)
                {
                    deliveryProduct = FindDeliveryProduct();
                    if (deliveryProduct == null) return;
                }
            }
            else
            {
                if (!_view.ShowYesNoPopup(title, $"Отнести на счёт номера {check:0} ₸?\nНачисление проведёт рецепция.", "Отнести", "Отмена")) return;
            }

            try
            {
                // доставка и пометки заказа — одним изменением, чтобы заказ не остался в промежуточном состоянии
                var session = _ops.CreateEditSession();
                if (deliveryProduct != null)
                    session.AddOrderProductItem(1m, deliveryProduct, order, order.Guests.First(), null, OrderItemCourse.Default, quote.GuestFee);
                session.AddOrderExternalData("hotel.stay", guest.Stay.Id, true, order);
                session.AddOrderExternalData("hotel.room", guest.Room, true, order);
                session.ChangeOrderComment(_s.Cfg.OrderCommentTemplate.Replace("{room}", guest.Room)
                    .Replace("{guest}", string.IsNullOrEmpty(guest.Names) ? "гость" : guest.Names)
                    .Replace("{kind}", roomService ? "рум-сервис, на номер" : "на счёт номера"), order);
                _ops.SubmitChanges(session, _ops.GetDefaultCredentials());
            }
            catch (Exception e)
            {
                PluginContext.Log.Error("[IikoExely] " + e);
                _view.ShowErrorPopup("Заказ не удалось оформить:\n" + e.Message);
                return;
            }

            var now = DateTime.Now;
            _s.Charges.Add(new RoomCharge
            {
                Date = UsageStore.DayKey(now), Time = now.ToString("s"),
                Kind = roomService ? RoomCharge.KindRoomService : RoomCharge.KindRestaurant,
                StayId = guest.Stay.Id, Room = guest.Room, Guest = guest.Names,
                OrderId = order.Id.ToString(), OrderNumber = order.Number, Check = check,
                GuestDeliveryFee = quote?.GuestFee ?? 0m, HotelCompensation = quote?.HotelCompensation ?? 0m,
                DeliveryEmployee = roomService ? employee : null, Employee = employee,
            });
            var total = check + (quote?.GuestFee ?? 0m);
            _view.ShowOkPopup(title, $"Оформлено: {total:0} ₸ на счёт номера. Рецепция проведёт начисление.\nОплату в iiko проведите способом оплаты «на номер»/по согласованному процессу.");
        }

        private Resto.Front.Api.Data.Assortment.IProduct FindDeliveryProduct()
        {
            var product = _ops.GetAllProducts().FirstOrDefault(p => p.IsActive &&
                string.Equals(p.Name, _s.Cfg.RoomServiceDeliveryProduct, StringComparison.OrdinalIgnoreCase));
            if (product == null)
                _view.ShowErrorPopup($"В меню нет позиции «{_s.Cfg.RoomServiceDeliveryProduct}». Создайте её (открытая цена) или поправьте iiko-exely.json.");
            return product;
        }
    }
}
