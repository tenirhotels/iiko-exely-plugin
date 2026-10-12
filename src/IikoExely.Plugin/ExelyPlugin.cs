using System;
using System.IO;
using System.Linq;
using System.Reactive.Disposables;
using System.Threading;
using System.Threading.Tasks;
using Resto.Front.Api;
using Resto.Front.Api.Attributes;
using Resto.Front.Api.UI;

namespace IikoExely.Plugin
{
    /// <summary>
    /// Проживающие Exely в iikoFront: на завтраке кассир выбирает номер, плагин проверяет право на завтрак,
    /// применяет скидку (включённый завтрак или скидка проживающего по уровню) и ведёт журнал для утренней сверки.
    /// </summary>
    [PluginLicenseModuleId(21016318)]
    public sealed class ExelyPlugin : IFrontPlugin
    {
        private readonly CompositeDisposable _subscriptions = new CompositeDisposable();
        private readonly CancellationTokenSource _cts = new CancellationTokenSource();
        private readonly Services _s;

        public ExelyPlugin()
        {
            var integration = PluginContext.Integration;
            var configDir = integration.GetConfigsDirectoryPath();
            var dataDir = integration.GetDataStorageDirectoryPath();
            Directory.CreateDirectory(configDir);
            Directory.CreateDirectory(dataDir);

            var cfgPath = Path.Combine(configDir, "iiko-exely.json");
            PluginConfig cfg;
            try
            {
                if (!File.Exists(cfgPath)) new PluginConfig().Save(cfgPath); // образец для заполнения
                cfg = PluginConfig.Load(cfgPath);
            }
            catch (Exception e)
            {
                PluginContext.Log.Error($"[IikoExely] не удалось прочитать {cfgPath}: {e.Message}");
                cfg = new PluginConfig();
            }
            _s = new Services(cfg, dataDir, _cts.Token);

            var ops = PluginContext.Operations;
            _subscriptions.Add(ops.AddButtonToOrderEditScreen("Проживающий\n(завтрак)", a => Safe(a.vm, () => new BreakfastFlow(_s, a.os, a.vm).Run(a.order))));
            _subscriptions.Add(ops.AddButtonToOrderEditScreen("На номер\n(рум-сервис)", a => Safe(a.vm, () => new RoomServiceFlow(_s, a.os, a.vm).Run(a.order))));
            _subscriptions.Add(ops.AddButtonToPluginsMenu("Реестр завтраков", a => Safe(a.vm, () => new RegistryFlow(_s, ops, a.vm).ShowRegistry())));
            _subscriptions.Add(ops.AddButtonToPluginsMenu("Прогноз завтраков", a => Safe(a.vm, () => new RegistryFlow(_s, ops, a.vm).ShowForecast())));
            _subscriptions.Add(ops.AddButtonToPluginsMenu("Кухня: итоги завтрака", a => Safe(a.vm, () => new RegistryFlow(_s, ops, a.vm).KitchenSummary())));
            _subscriptions.Add(ops.AddButtonToPluginsMenu("Начисления на номер", a => Safe(a.vm, () => new RegistryFlow(_s, ops, a.vm).RoomCharges())));
            PluginContext.Log.Info("[IikoExely] запущен, хаб: " + cfg.HubUrl);
        }

        public void Dispose()
        {
            _cts.Cancel();
            _subscriptions.Dispose();
        }

        private static void Safe(IViewManager view, Action action)
        {
            try { action(); }
            catch (Exception e)
            {
                PluginContext.Log.Error("[IikoExely] " + e);
                view.ShowErrorPopup("Ошибка плагина:\n" + e.Message);
            }
        }
    }
}
