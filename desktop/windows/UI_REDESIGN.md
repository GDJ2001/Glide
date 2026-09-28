# Glide Windows UI specification

The Windows desktop application opens on **Send** and keeps one active transfer visible while the user visits another page. The shell has four destinations: Send, Receive, Activity, and Settings. It targets a 1040 by 720 DIP window and remains usable at 820 by 560 DIP.

## Design artifacts

- Magic Patterns prototype: https://www.magicpatterns.com/c/8dhgafdi15uxlfmnmlvki1
- Preview: https://project-glide-windows-sharing-shell-972.magicpatterns.app

The prototype uses synthetic people, devices, and filenames. It describes the interaction model; the production application remains WPF and uses Glide's actual transfer runtime.

## Screens and states

| Area | Primary state | Important alternate states |
| --- | --- | --- |
| Send | Drop zone, Add files, Add folder, selected-file list, nearby devices | Empty selection, skipped file explanation, discovery empty, device unavailable, failed connection, busy transfer |
| Device selection | Device name, type, availability and trust state | Technical endpoint details are secondary; discovery alone never signals authentication |
| Pairing | Short instructions, large QR, copyable/pasted pairing value | Expired invitation, regeneration, recipient confirmation, optional persistent trust decision |
| Receive | Ready/Off status, device name, receive folder, pairing affordance | Incoming request shows sender, count, size, destination, Accept and Decline |
| Activity | Current batch, file-level progress, speed, verification, cancellation | Searchable verified history, sent/received filtering, failed and cancelled current-session results |
| Settings | Appearance, receiving folder, trusted devices, advanced local information | Appearance follows System by default; revocation is explicit; host and port remain advanced |

## Component and behavior contract

- Navigation is a keyboard-accessible radio group with a persistent selected state.
- File queue, device list, and activity history use virtualizing lists for large collections.
- Shared controls use semantic surface, text, border, success, warning, error, focus, and primary-action resources.
- Focus, hover, pressed, selected, disabled, and loading states are visible. Dialogs provide an accessible name and restore focus to their invoker.
- Completion is shown only after runtime verification. Retry begins a fresh authenticated attempt and preserves the selected files where recovery is possible.
- The UI represents the existing one-batch runtime accurately: it exposes cancellation and busy states without promising pause, resume, or concurrent sends.

## Appearance tokens

Use Segoe UI Variable with Segoe UI and Arial fallbacks; body text is 14 DIP. The spacing scale is 4 and 8 DIP, standard page padding is 24 DIP, control heights are 36–40 DIP, and corners are 8–12 DIP. Glide blue is reserved for primary actions. System, Light, and Dark appearance selections use the same semantic resources, while Windows high contrast uses system colors.

Appearance is a Windows-only preference written separately from transfer settings and verified history. The default is System.
