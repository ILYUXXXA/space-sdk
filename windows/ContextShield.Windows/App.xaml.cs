using Windows.ApplicationModel.Activation;
using Windows.ApplicationModel;
using Windows.ApplicationModel.Background;
using Windows.Storage;
using Windows.UI.Notifications;
using Windows.UI.Notifications.Management;
using Windows.UI.Xaml;
using System;
using System.Linq;
using System.Threading.Tasks;

namespace ContextShield.Windows
{
    sealed partial class App : Application
    {
        private const string NotificationTaskName = "ContextShieldNotificationChanges";
        public App() { InitializeComponent(); Suspending += OnSuspending; }
        protected override void OnLaunched(LaunchActivatedEventArgs e)
        {
            var frame = Window.Current.Content as Windows.UI.Xaml.Controls.Frame;
            if (frame == null) { frame = new Windows.UI.Xaml.Controls.Frame(); Window.Current.Content = frame; }
            if (frame.Content == null) frame.Navigate(typeof(MainPage), e.Arguments);
            Window.Current.Activate();
        }
        public static async Task<bool> RegisterNotificationBackgroundTaskAsync(UserNotificationListener listener)
        {
            // Snapshot existing notifications so enabling the listener does not warn about old messages.
            var current = await listener.GetNotificationsAsync(NotificationKinds.Toast);
            LocalRisk.SeedExistingNotifications(current.Select(n => n.Id));
            var access = await BackgroundExecutionManager.RequestAccessAsync();
            if (access == BackgroundAccessStatus.DeniedByUser || access == BackgroundAccessStatus.DeniedBySystemPolicy) return false;
            if (BackgroundTaskRegistration.AllTasks.Values.Any(t => t.Name == NotificationTaskName)) return true;
            var builder = new BackgroundTaskBuilder { Name = NotificationTaskName };
            builder.SetTrigger(new UserNotificationChangedTrigger(NotificationKinds.Toast));
            // In-process task: Windows activates App.OnBackgroundActivated while the app is suspended.
            BackgroundTaskRegistration task = builder.Register();
            return task != null;
        }

        protected override async void OnBackgroundActivated(BackgroundActivatedEventArgs args)
        {
            base.OnBackgroundActivated(args);
            var deferral = args.TaskInstance.GetDeferral();
            try
            {
                var listener = UserNotificationListener.Current;
                if (listener.GetAccessStatus() != UserNotificationListenerAccessStatus.Allowed) return;
                var current = await listener.GetNotificationsAsync(NotificationKinds.Toast);
                foreach (var notification in current)
                {
                    if (notification.AppInfo?.PackageFamilyName == Package.Current.Id.FamilyName) continue;
                    if (!LocalRisk.TryMarkNotification(notification.Id)) continue;
                    var binding = notification.Notification.Visual.GetBinding(KnownNotificationBindings.ToastGeneric);
                    var raw = binding == null ? "" : string.Join(" ", binding.GetTextElements().Select(x => x.Text));
                    if (raw.Length < 8) continue;
                    var result = LocalRisk.Assess(raw);
                    if (result.IsHigh) ShowRiskToast(result.Factors);
                }
            }
            catch { /* The OS may remove a notification before it can be read. */ }
            finally { deferral.Complete(); }
        }

        public static void ShowRiskToast(System.Collections.Generic.IReadOnlyList<string> factors)
        {
            var description = string.Join(" · ", factors.Take(2));
            var xml = new Windows.Data.Xml.Dom.XmlDocument();
            xml.LoadXml($"<toast><visual><binding template='ToastGeneric'><text>Проверьте сообщение перед переводом</text><text>{System.Security.SecurityElement.Escape(description)}</text></binding></visual></toast>");
            ToastNotificationManager.CreateToastNotifier().Show(new ToastNotification(xml));
        }
        private void OnSuspending(object sender, Windows.ApplicationModel.SuspendingEventArgs e) { }
    }
}
