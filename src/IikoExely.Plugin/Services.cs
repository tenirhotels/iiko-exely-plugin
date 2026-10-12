using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Resto.Front.Api;
using Resto.Front.Api.Data.View;
using Resto.Front.Api.UI;

namespace IikoExely.Plugin
{
    /// <summary>Общие объекты плагина: настройки, кэш хаба, журналы.</summary>
    internal sealed class Services
    {
        public PluginConfig Cfg { get; }
        public StayDirectory Directory { get; }
        public UsageStore Usage { get; }
        public BreakfastRegistry Registry { get; }
        public RoomChargeStore Charges { get; }
        public KitchenStore Kitchen { get; }
        public RegistryStore RegistryFiles { get; }
        public string DataDir { get; }
        public CancellationToken Cancel { get; }

        public Services(PluginConfig cfg, string dataDir, CancellationToken cancel)
        {
            Cfg = cfg; DataDir = dataDir; Cancel = cancel;
            Directory = new StayDirectory(new HubClient(cfg), cfg);
            Usage = new UsageStore(Path.Combine(dataDir, "breakfast-log.json"));
            RegistryFiles = new RegistryStore(dataDir);
            Registry = new BreakfastRegistry(cfg, RegistryFiles);
            Charges = new RoomChargeStore(Path.Combine(dataDir, "room-charges.json"));
            Kitchen = new KitchenStore(Path.Combine(dataDir, "kitchen-log.json"));
        }

        public BreakfastPolicy Policy => new BreakfastPolicy(Cfg, Directory.Catalog);

        public string CurrentEmployee(IOperationService ops)
        {
            try { return ops.GetCurrentUser()?.Name ?? "?"; } catch (Exception) { return "?"; }
        }

        /// <summary>Обновляет список проживающих (блокирует поток кассы — таймаут запроса ограничен конфигом).</summary>
        public IReadOnlyList<InHouseGuest> Refresh(bool force) =>
            Task.Run(() => Directory.RefreshAsync(force, Cancel)).GetAwaiter().GetResult();

        /// <summary>Обновляет список, пересобирает реестр дня и возвращает его.</summary>
        public DayRegistry SyncRegistry(bool force)
        {
            var guests = Refresh(force);
            return Registry.Sync(guests, Policy, Usage, DateTime.Now);
        }
    }

    internal static class Ui
    {
        /// <summary>Выбор проживающего: номер комнаты (0 — весь список) -> при нескольких вариантах список.</summary>
        public static InHouseGuest ChooseGuest(IViewManager view, IReadOnlyList<InHouseGuest> guests)
        {
            if (guests.Count == 0)
            {
                view.ShowErrorPopup("В отеле нет проживающих (по данным Hotel Hub).");
                return null;
            }
            var typed = view.ShowInputDialog("Номер комнаты (0 — весь список)", InputDialogTypes.Number) as NumberInputDialogResult;
            if (typed == null) return null; // отмена
            var room = typed.Number == 0 ? "" : typed.Number.ToString();
            var found = room.Length == 0
                ? guests.ToList()
                : guests.Where(g => string.Equals(g.Room, room, StringComparison.OrdinalIgnoreCase)).ToList();
            if (found.Count == 0)
            {
                view.ShowErrorPopup($"Комната {room} не найдена среди проживающих.");
                return null;
            }
            if (found.Count == 1) return found[0];
            var labels = found.Select(g => $"{g.Room}  {g.Names}  {(g.Stay.RatePlan?.Name ?? "")}".Trim()).ToList();
            var idx = view.ShowChooserPopup("Выберите проживающего", labels);
            return idx >= 0 && idx < found.Count ? found[idx] : null;
        }

        /// <summary>Выбор количества 0..max. Возвращает -1 при отмене.</summary>
        public static int ChooseCount(IViewManager view, string title, int max)
        {
            if (max <= 0) return 0;
            var options = Enumerable.Range(0, max + 1).Select(n => n.ToString()).ToList();
            var idx = view.ShowChooserPopup(title, options);
            return idx < 0 || idx >= options.Count ? -1 : idx;
        }

        /// <summary>Подтверждение менеджера. Возвращает имя менеджера или null (отказ / код права не задан).</summary>
        public static string ManagerApproval(IViewManager view, PluginConfig cfg, string why)
        {
            if (string.IsNullOrWhiteSpace(cfg.ManagerPermissionCode))
            {
                view.ShowErrorPopup(why + "\nтребует подтверждения менеджера, но в iiko-exely.json не задан managerPermissionCode.");
                return null;
            }
            try
            {
                var user = view.ShowCheckPermissionPopup(cfg.ManagerPermissionCode, true);
                return user?.Name;
            }
            catch (ArgumentException)
            {
                view.ShowErrorPopup($"В iiko нет права с кодом «{cfg.ManagerPermissionCode}». Проверьте managerPermissionCode.");
                return null;
            }
        }

        /// <summary>Запрашивает обязательную причину; null — отмена или пусто.</summary>
        public static string RequiredReason(IViewManager view, string title)
        {
            var text = view.ShowKeyboard(title, "", false, 200, true, false);
            return string.IsNullOrWhiteSpace(text) ? null : text.Trim();
        }
    }
}
