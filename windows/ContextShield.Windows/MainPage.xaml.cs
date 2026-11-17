using System;
using System.Linq;
using Windows.Data.Xml.Dom;
using Windows.UI.Notifications;
using Windows.UI.Notifications.Management;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;

namespace ContextShield.Windows
{
    public sealed partial class MainPage : Page
    {
        private UserNotificationListener _listener;
        private int _checked;

        public MainPage() { InitializeComponent(); }

        private async void EnableButton_Click(object sender, RoutedEventArgs e)
        {
            EnableButton.IsEnabled = false;
            try
            {
                _listener = UserNotificationListener.Current;
                var permission = await _listener.RequestAccessAsync();
                if (permission != UserNotificationListenerAccessStatus.Allowed)
                {
                    StatusText.Text = "Windows не выдал разрешение. Разрешите доступ в параметрах уведомлений.";
                    return;
                }
                _listener.NotificationChanged -= Listener_NotificationChanged;
                _listener.NotificationChanged += Listener_NotificationChanged;
                var backgroundReady = await App.RegisterNotificationBackgroundTaskAsync(_listener);
                StatusText.Text = backgroundReady ? "● Монитор включён · передний план + фоновая задача" : "● Монитор включён, пока открыто приложение";
                StatsText.Text = backgroundReady ? "Проверка локальная. Windows может ограничивать фон по энергосбережению." : "Фоновая работа не разрешена системой; монитор активен, пока приложение открыто.";
            }
            catch (Exception ex) { StatusText.Text = "Не удалось включить монитор: " + ex.Message; }
            finally { EnableButton.IsEnabled = true; }
        }

        private async void Listener_NotificationChanged(UserNotificationListener sender, UserNotificationChangedEventArgs args)
        {
            if (args.ChangeKind != UserNotificationChangedKind.Added) return;
            try
            {
                var notification = await sender.GetNotificationAsync(args.UserNotificationId);
                if (notification == null || notification.AppInfo == null) return;
                if (!LocalRisk.TryMarkNotification(notification.Id)) return;
                var ownFamily = Windows.ApplicationModel.Package.Current.Id.FamilyName;
                if (notification.AppInfo.PackageFamilyName == ownFamily) return;
                var binding = notification.Notification.Visual.GetBinding(KnownNotificationBindings.ToastGeneric);
                if (binding == null) return;
                var raw = string.Join(" ", binding.GetTextElements().Select(item => item.Text)).Trim();
                if (raw.Length < 8) return;
                var result = LocalRisk.Assess(raw);
                _checked++;
                var appName = notification.AppInfo.DisplayInfo.DisplayName;
                await Dispatcher.RunAsync(Windows.UI.Core.CoreDispatcherPriority.Normal, () =>
                {
                    StatsText.Text = $"Проверено новых уведомлений: {_checked}\nПоследняя оценка: {result.Score}/98";
                    EventsList.Items.Insert(0, $"{DateTime.Now:HH:mm:ss} · {appName} · риск {result.Score}/98" + (result.Factors.Count > 0 ? " · " + string.Join(", ", result.Factors) : ""));
                    while (EventsList.Items.Count > 20) EventsList.Items.RemoveAt(EventsList.Items.Count - 1);
                });
                if (result.IsHigh) ShowLocalWarning(result.Factors);
                // Keep raw content only in this callback; never log, persist, or transmit it.
            }
            catch { /* A notification may disappear before the listener retrieves it. */ }
        }

        private void ShowLocalWarning(System.Collections.Generic.IReadOnlyList<string> factors)
        {
            App.ShowRiskToast(factors);
        }
    }
}
