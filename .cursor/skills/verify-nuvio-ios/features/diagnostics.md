# Diagnostics

Diagnostics is the on-device log. The first time the root view appears, a sheet titled Diagnostics log offers a Save diagnostics log switch that starts on, then Done. Later, Settings shows a Diagnostics row that opens Export log and Clear log.

## Sub-features

- `diagnostics-intro` shows the sheet once, with the switch on, until Done.
- `diagnostics-intro-toggle` turns Save diagnostics log off and back on before Done.
- `diagnostics-intro-persist` stores that choice and the seen flag after Done.
- `diagnostics-export` shares the log from Settings once a line exists.
- `diagnostics-clear` deletes the log after confirmation.

## How to get to it (user POV)

- Fresh install or a container whose intro has not been dismissed: the sheet appears on its own about 1.5 seconds after the root view appears. It is not waiting for catalog rows. On a fast start it covers Home.
- After Done: open the Profile tab (the label is Settings until the tab titles load), scroll to the ADVANCED section, and choose Diagnostics. That row is on the settings root, not inside the Advanced page.
- Export log and Clear log are on that Diagnostics page. Both stay disabled until the log file exists.

## Driving it with verify-nuvio-ios

Preconditions:

- `launch.sh` has exited 0 and `doctor.sh` prints `doctor: ok`.
- `NuvioDiagnosticsIntroSeen` is unset. `launch.sh` removes it.
- No second verification session is using this simulator.

- **Show the sheet.** Run `.cursor/skills/verify-nuvio-ios/scripts/drive-diagnostics.sh`. The test waits for `diagnostics.intro.title` (label `Diagnostics log`) and `diagnostics.intro.saveToggle` (label `Save diagnostics log`). The switch value is `1`. `diagnostics-intro-default-on.png` shows the title and the on switch.
- **Turn the switch off.** The same command taps the switch. The value becomes `0`. `diagnostics-intro-toggled-off.png` shows the off switch on the same sheet.
- **Turn the switch on.** The same command taps it again. The value is `1`.
- **Dismiss.** The same command taps `diagnostics.intro.done` (label `Done`). The button disappears. `diagnostics-intro-dismissed.png` shows the tab bar (`Home`, or `Profile` / `Settings`).
- **Confirm the stored choice.** The same command terminates the app and reads the container plist. `side-effects.txt` contains `NuvioDiagnosticsIntroSeen=true` and `NuvioDiagnosticsEnabled=true`.
- **Open the Diagnostics page.** After the sheet is gone, tap the Profile tab, then the row `settings.diagnostics` (label `Diagnostics`, description `On-device crash and error log`). The navigation title is Diagnostics. `diagnostics.settings.saveToggle` is on.
- **Export.** This step fails closed until `Documents/diagnostics.jsonl` exists. When it does, tap `diagnostics.settings.export` (label `Export log`). A share sheet appears for that file. Copy the file out of the container and confirm it is the same bytes. Do not upload a copy that still contains a debrid host.
- **Clear.** Tap `diagnostics.settings.clear` (label `Clear log`), then the alert button `Clear`. The log size reads `0 KB` and `diagnostics.jsonl` is gone. `Cancel` leaves the file in place.

`drive-diagnostics.sh` performs the intro bullets and the plist read. It does not tap Export or Clear.

## Gotchas

- The sheet is scheduled from the root view's appearance, 1.5 seconds later, and again when the scene becomes active. It can cover the startup gate if Home is not up yet. Wait for the identifier, not a fixed sleep alone.
- The switch on the sheet starts on in the UI. `DiagnosticsManager.isEnabled` stays false until Done writes it. A screenshot of the sheet is not proof of the stored flag. Read the plist after Done.
- Done with the switch off deletes the log and stores the switch as off. The proof turns it back on before Done so the stored flag is true.
- Export log and Clear log are disabled when the log file is absent. A disabled Export button is not a pass. One way a line appears without a stream: enable the log, quit within 5 seconds (the crash marker is still set), and launch again. The next launch appends an unclean-termination line. If that file is missing, stop and report the unmet precondition.
- The share sheet is the system share UI. Dismiss it without sending the file anywhere. Do not attach debrid links or account tokens to the evidence directory.
- Tapping Diagnostics on the Advanced page is the wrong screen. The row is in the ADVANCED section of the settings root.
