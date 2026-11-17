using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
using Windows.Storage;

namespace ContextShield.Windows
{
    public sealed class RiskSignal
    {
        public int Score { get; set; }
        public IReadOnlyList<string> Factors { get; set; }
        public bool IsHigh => Score >= 60;
    }

    public static class LocalRisk
    {
        private const string SeenKey = "processedNotificationIds";
        private static readonly (string Label, int Points, string Pattern)[] Rules = {
            ("Давление срочностью", 22, @"срочно|немедленно|прямо сейчас|времени нет|быстро|последний шанс"),
            ("Просьба сохранить разговор в тайне", 25, @"никому не (говори|сообщай)|держи в секрете|не рассказывай|не клади трубку"),
            ("Просьба о переводе или коде", 20, @"перевед[иите]|на безопасн(ый|ого) сч[её]т|нов(ую|ый) карт|код из sms|оплат[иите]"),
            ("Выдача себя за службу", 26, @"служб[аы] безопасност|сотрудник банк|полици|следователь|техническая поддержк"),
            ("Угроза или обещание награды", 18, @"заблокир|арест|штраф|выиграл|компенсац|проблем[аы] с аккаунт")
        };

        public static RiskSignal Assess(string text)
        {
            var found = Rules.Where(r => Regex.IsMatch(text ?? "", r.Pattern, RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)).ToArray();
            return new RiskSignal { Score = Math.Min(98, found.Sum(r => r.Points)), Factors = found.Select(r => r.Label).ToArray() };
        }

        public static bool TryMarkNotification(uint id)
        {
            var settings = ApplicationData.Current.LocalSettings;
            var values = (settings.Values[SeenKey] as string ?? "").Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries).ToList();
            var value = id.ToString();
            if (values.Contains(value)) return false;
            values.Add(value);
            if (values.Count > 200) values = values.Skip(values.Count - 200).ToList();
            settings.Values[SeenKey] = string.Join(",", values);
            return true;
        }

        public static void SeedExistingNotifications(IEnumerable<uint> ids)
        {
            foreach (var id in ids) TryMarkNotification(id);
        }
    }
}
