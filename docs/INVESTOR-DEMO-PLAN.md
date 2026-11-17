# ContextShield — investor demo plan

## Product statement

ContextShield is an Android-first contextual anti-fraud assistant. It looks for combinations of social-engineering signals in recent notifications and checks that context again when a user is about to make a demo transfer.

The product promise for this prototype is **explainable warning before a risky action**, not a guarantee that every scam will be detected and not a bank-authorized payment block.

## Investor demo story (2–3 minutes)

1. Open the Android app and show the demo account dashboard.
2. Tap **Загрузить сценарий мошенничества**. The app adds three synthetic events:
   - an impersonation of a bank security employee;
   - urgency and a request to move money to a “safe account”;
   - a request to keep the conversation secret.
3. The dashboard refreshes the context score, recent signals and risk trend.
4. Open the transfer review. Enter a recipient and amount, leaving “new recipient” selected.
5. ContextShield combines communication context and transfer details, shows the risk level and explains the contributing factors.
6. In the high-risk case the demo transfer is held. In a clean context the user sees a demo-only completion state.

All seeded events are explicitly marked as a demo scenario. No real bank account, payment API or money movement is involved.

## What the current implementation demonstrates

- On-device Russian-language message heuristics in `LocalRisk`.
- A rolling 30-minute context window in `RiskHistory`.
- A transparent demo transaction assessment in `DemoTransferRisk`.
- A pre-confirmation demo gate with recipient, amount and new-recipient input.
- A repeatable synthetic scenario for a predictable live presentation.

The displayed 0–100 value is a heuristic risk index, not a calibrated probability of fraud.

## Scope boundaries

- Android is the primary product surface for this milestone.
- The Windows client and static web demo remain supporting prototypes; they are not presented as integrated clients.
- OpenRouter remains optional and manually invoked. It is not required for the main demo path.
- No real payment is initiated, intercepted or blocked.
- Notification access is explicit and controlled by Android system settings.
- Notification redaction is heuristic and cannot guarantee that all personal information is removed. Do not describe it as complete anonymisation.

## Two-day delivery checklist

### Day 1 — coherent product flow
- [x] Add an investor-ready seeded social-engineering scenario.
- [x] Add a separate transaction risk assessment model.
- [x] Replace the transfer placeholder with a recipient/amount review flow.
- [ ] Run a clean Android build and install on a physical device.
- [ ] Verify the high-risk and low-risk paths on-device.

### Day 2 — presentation quality
- [ ] Refine visual hierarchy and wording on the dashboard.
- [ ] Add a clear incident timeline with timestamps and signal explanations.
- [ ] Test notification permission denied/granted, empty history, reset and app restart.
- [ ] Record a short demo walkthrough and prepare a clean demo device.
- [ ] Update APK artifacts only after a successful build and smoke test.

## Validation checklist

- A clean session plus a normal transfer stays below the hold threshold.
- The seeded bank-impersonation scenario crosses the hold threshold.
- The hold screen lists understandable reasons, not just a number.
- Reset removes seeded context and restores the clean path.
- No screen claims a real payment was stopped or money was moved.
- The app remains usable when notification access is not granted.
