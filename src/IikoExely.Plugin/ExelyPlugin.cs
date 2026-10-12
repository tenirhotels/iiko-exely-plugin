using System;
using System.IO;
using System.Linq;
using System.Reactive.Disposables;
using System.Threading;
using System.Threading.Tasks;
using Resto.Front.Api;
using Resto.Front.Api.Attributes;
using Resto.Front.Api.Data.Orders;
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
        private readonly PluginConfig _cfg;
        private readonly StayDirectory _directory;
        private readonly UsageStore _usage;
        private readonly string _dataDir;

        public ExelyPlugin()
        {
            var integration = PluginContext.Integration;
            var configDir = integration.GetConfigsDirectoryPath();
            _dataDir = integration.GetDataStorageDirectoryPath();
            Directory.CreateDirectory(configDir);
            Directory.CreateDirectory(_dataDir);

            var cfgPath = Path.Combine(configDir, "iiko-exely.json");
            try
            {
                if (!File.Exists(cfgPath)) new PluginConfig().Save(cfgPath); // образец для заполнения
                _cfg = PluginConfig.Load(cfgPath);
            }
            catch (Exception e)
            {
                PluginContext.Log.Error($"[IikoExely] не удалось прочитать {cfgPath}: {e.Message}");
                _cfg = new PluginConfig();
            }

            _usage = new UsageStore(Path.Combine(_dataDir, "breakfast-log.json"));
            _directory = new StayDirectory(new HubClient(_cfg), _cfg);

            var ops = PluginContext.Operations;
            _subscriptions.Add(ops.AddButtonToOrderEditScreen("Проживающий\n(завтрак)", OnOrderButton));
            _subscriptions.Add(ops.AddButtonToPluginsMenu("Завтраки: сверка", OnReportButton));
            PluginContext.Log.Info("[IikoExely] запущен, хаб: " + _cfg.HubUrl);
        }

        public void Dispose()
        {
            _cts.Cancel();
            _subscriptions.Dispose();
        }

        private void OnOrderButton((IOrder order, IOperationService ops, IViewManager view) a)
        {
            try
            {
                new OrderFlow(_cfg, _directory, _usage, a.ops, a.view).Run(a.order);
            }
            catch (Exception e)
            {
                PluginContext.Log.Error("[IikoExely] " + e);
                a.view.ShowErrorPopup("Не удалось обработать проживающего:\n" + e.Message);
            }
        }

        private void OnReportButton((IViewManager view, IReceiptPrinter printer) a)
        {
            try
            {
                var guests = Task.Run(() => _directory.RefreshAsync(true, _cts.Token)).GetAwaiter().GetResult();
                var day = DateTime.Now;
                var report = BreakfastReport.Build(guests, new BreakfastPolicy(_cfg, _directory.Catalog), _usage, day);
                var csv = Path.Combine(_dataDir, $"breakfast-{day:yyyy-MM-dd}.csv");
                File.WriteAllText(csv, report.ToCsv(), new System.Text.UTF8Encoding(false));
                a.view.ShowOkPopup("Сверка завтраков", report.Summary() + "\n\nФайл: " + csv);
            }
            catch (Exception e)
            {
                PluginContext.Log.Error("[IikoExely] " + e);
                a.view.ShowErrorPopup("Сверка недоступна:\n" + e.Message);
            }
        }
    }
}
